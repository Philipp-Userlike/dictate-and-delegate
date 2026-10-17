package app.driftdictation;

import android.content.Context;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * UI text stays readable English in the code: {@code tr("Start dictating")}. Each language lives in
 * {@code res/values-xx/strings_ui.xml} under a key derived from the English text; missing entries
 * fall back to English. Templates use {@code %1$s}; a literal percent sign in a template with
 * arguments is written {@code %%}.
 */
final class I18n {
  private static Context app;

  /**
   * Called by every entry point (activity, service, tile). Uses the caller's configuration, so an
   * activity recreated after a language change translates right away, without keeping the
   * activity itself alive.
   */
  static void init(Context context) {
    app =
        context
            .getApplicationContext()
            .createConfigurationContext(context.getResources().getConfiguration());
  }

  /** Resource name for an English text; the generator and the completeness test use the same. */
  static String key(String english) {
    CRC32 crc = new CRC32();
    crc.update(english.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return String.format(Locale.ROOT, "t_%08x", crc.getValue());
  }

  @android.annotation.SuppressLint("DiscouragedApi") // Keys are derived, not hand-written.
  static String tr(String english, Object... args) {
    String text = english;
    if (app != null) {
      int id = app.getResources().getIdentifier(key(english), "string", app.getPackageName());
      if (id != 0) text = app.getString(id);
    }
    return args.length == 0 ? text : String.format(Locale.getDefault(), text, args);
  }

  /**
   * A count in the current language's plural form: {@code plural(n, "%1$d word", "%1$d words")}.
   * Translations are stored per CLDR category (one, few, many, other) under the key of the English
   * plural plus "_category"; a missing category falls back to "other", then to English.
   */
  @android.annotation.SuppressLint("DiscouragedApi")
  static String plural(long n, String one, String other) {
    String text = n == 1 ? one : other;
    if (app != null) {
      Locale locale = app.getResources().getConfiguration().getLocales().get(0);
      String category = android.icu.text.PluralRules.forLocale(locale).select(n);
      for (String c : new String[] {category, "other"}) {
        int id =
            app.getResources().getIdentifier(key(other) + "_" + c, "string", app.getPackageName());
        if (id != 0) {
          text = app.getString(id);
          break;
        }
      }
    }
    return String.format(Locale.getDefault(), text, n);
  }
}
