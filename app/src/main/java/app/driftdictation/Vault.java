package app.driftdictation;

import static app.driftdictation.I18n.tr;
import static java.nio.charset.StandardCharsets.UTF_8;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Secrets never leave app-private storage unencrypted; key material stays in Android Keystore,
 * except the history key, which is stored sealed by it.
 */
final class Vault {
  private static final String ALIAS = "drift.v1";
  private final SharedPreferences prefs;

  Vault(Context context) {
    prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE);
  }

  /** Marks a value sealed with the history key (see sealRow) rather than by Keystore itself. */
  private static final String ROW = "h1:";

  private static final Object ROW_LOCK = new Object();
  private static SecretKey key, rowKey;

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
    String data = prefs.getString(name, null);
    if (data == null) return "";
    return unseal(data);
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

  /**
   * History has a row per note version, thousands in time, and each Keystore operation takes
   * milliseconds. So rows are sealed with an AES key of their own, which is stored sealed by the
   * Keystore key: one Keystore operation per process instead of one per row.
   */
  String sealRow(String value) throws Exception {
    byte[] iv = new byte[12];
    new SecureRandom().nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, rowKey(), new GCMParameterSpec(128, iv));
    return ROW
        + Base64.encodeToString(iv, Base64.NO_WRAP)
        + ":"
        + Base64.encodeToString(cipher.doFinal(value.getBytes(UTF_8)), Base64.NO_WRAP);
  }

  /** Opens a row from sealRow, or one sealed by Keystore itself as rows were before. */
  String unsealRow(String data) throws Exception {
    if (!isRow(data)) return unseal(data);
    String[] parts = data.substring(ROW.length()).split(":", 2);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE, rowKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), UTF_8);
  }

  /** Sealed with sealRow, not by Keystore itself. */
  static boolean isRow(String data) {
    return data.startsWith(ROW);
  }

  private SecretKey rowKey() throws Exception {
    synchronized (ROW_LOCK) {
      if (rowKey == null) {
        String stored = get("historyKey");
        if (stored.isEmpty()) {
          KeyGenerator generator = KeyGenerator.getInstance("AES");
          generator.init(256);
          SecretKey made = generator.generateKey();
          // Saved before any row uses it.
          put("historyKey", Base64.encodeToString(made.getEncoded(), Base64.NO_WRAP));
          rowKey = made;
        } else rowKey = new SecretKeySpec(Base64.decode(stored, Base64.NO_WRAP), "AES");
      }
      return rowKey;
    }
  }

  void remove(String name) {
    prefs.edit().remove(name).commit();
  }
}
