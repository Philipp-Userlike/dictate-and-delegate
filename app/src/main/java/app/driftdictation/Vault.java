package app.driftdictation;

import static app.driftdictation.I18n.tr;
import static java.nio.charset.StandardCharsets.UTF_8;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Secrets (API keys, bb tokens, the ntfy topic) never leave app-private storage unencrypted; key
 * material stays in Android Keystore. Values that aren't secrets (PLAIN) are kept plain in
 * app-private storage, like notes; earlier versions sealed them too, and they move on first read.
 */
final class Vault {
  private static final String ALIAS = "drift.v1";

  /** The templates, the last transcript and a version 1.0 dictation checkpoint. */
  static final Set<String> PLAIN = Set.of("templates", "transcript", "progress");

  private final SharedPreferences prefs, plain;

  Vault(Context context) {
    prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE);
    plain = context.getSharedPreferences("data", Context.MODE_PRIVATE);
  }

  private static SecretKey key;

  /** Looking the key up costs a Keystore call. */
  private static synchronized SecretKey key() throws Exception {
    if (key == null) key = load();
    return key;
  }

  private static SecretKey load() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias(ALIAS)) {
      KeyGenerator generator =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(
          new KeyGenParameterSpec.Builder(
                  ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      generator.generateKey();
    }
    return (SecretKey) ks.getKey(ALIAS, null);
  }

  synchronized void put(String name, String value) throws Exception {
    if (PLAIN.contains(name)) {
      if (!plain.edit().putString(name, value).commit())
        throw new java.io.IOException(tr("Could not save your changes"));
      prefs.edit().remove(name).commit();
      return;
    }
    String data = seal(value);
    if (!prefs.edit().putString(name, data).commit())
      throw new java.io.IOException(tr("Could not save encrypted data."));
  }

  synchronized String seal(String value) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
        + ":"
        + Base64.encodeToString(
            cipher.doFinal(value.getBytes(UTF_8)),
            Base64.NO_WRAP);
  }

  synchronized String get(String name) throws Exception {
    if (PLAIN.contains(name)) {
      String value = plain.getString(name, null);
      if (value != null) return value;
    }
    String data = prefs.getString(name, null);
    if (data == null) return "";
    String value = unseal(data);
    if (PLAIN.contains(name)) put(name, value);
    return value;
  }

  synchronized String unseal(String data) throws Exception {
    String[] parts = data.split(":", 2);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    return new String(
        cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
        UTF_8);
  }

  void remove(String name) {
    prefs.edit().remove(name).commit();
    if (PLAIN.contains(name)) plain.edit().remove(name).commit();
  }
}
