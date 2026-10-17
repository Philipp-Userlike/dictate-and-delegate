package app.driftdictation;

import android.content.SharedPreferences;
import java.util.*;
import org.json.*;

/** What the user told Talk Router about themselves, and how Talk Router handles their dictations. */
final class Profile {
  static final String[] TOPICS = {
    "Messages",
    "Work notes",
    "Emails",
    "Ideas & journaling",
    "Code & tech",
    "Studying",
    "Medical",
    "Legal"
  };
  private final SharedPreferences prefs;

  Profile(SharedPreferences prefs) {
    this.prefs = prefs;
  }

  List<String> topics() {
    return list("profileTopics");
  }

  String about() {
    return prefs.getString("profileAbout", "");
  }

  List<String> words() {
    return list("profileWords");
  }

  /**
   * Removes settings from earlier versions: spoken languages, words collected from fixes, the
   * Simple/Advanced mode, the text model choices, the account at Drift's former server, and "Act on
   * requests" (off becomes both Refine switches off).
   */
  void forgetRetired() {
    if (prefs.contains("processing")) {
      boolean on = prefs.getBoolean("processing", true);
      SharedPreferences.Editor e = prefs.edit().remove("processing");
      if (!on) e.putBoolean(AUTO_REFINE, false).putBoolean(AUTO_CHIEF, false);
      e.apply();
    }
    prefs
        .edit()
        .remove("profileLanguages")
        .remove("profileSuggestions")
        .remove("profileDismissed")
        .remove("modelMode")
        .remove("mode")
        .remove("textModel")
        .remove("smartModel")
        .remove("account")
        .remove("ledgerCache")
        .remove("consent")
        .apply();
  }

  static final String DEFAULT_MODEL = "microsoft/mai-transcribe-2";

  /**
   * Speech models whose host demonstrably applies word hints through OpenRouter (tested October
   * 2026; assets/models.json lists the same). A model saved by an older version that is no longer
   * offered falls back to the default.
   */
  static final java.util.Set<String> OFFERED =
      java.util.Set.of(
          "openai/gpt-transcribe",
          "openai/gpt-4o-transcribe",
          "openai/gpt-4o-mini-transcribe",
          "openai/whisper-1",
          "assemblyai/universal-3-5-pro",
          "microsoft/mai-transcribe-2",
          "microsoft/mai-transcribe-1.5");

  String fixedModel() {
    String id = prefs.getString("model", DEFAULT_MODEL);
    return OFFERED.contains(id) ? id : DEFAULT_MODEL;
  }

  List<String> hintWords() {
    return words();
  }

  /** Settings → Refine: the two switches. */
  static final String AUTO_REFINE = "autoRefine", AUTO_CHIEF = "autoChief";

  /** Refine starts by itself when Jev says the dictation asks for it or names a template. */
  boolean autoRefine() {
    return prefs.getBoolean(AUTO_REFINE, true);
  }

  /** The dictation goes to the chief by itself when Jev is sure it was addressed. */
  boolean autoChief() {
    return prefs.getBoolean(AUTO_CHIEF, true);
  }

  static final String CHIEF_NAME = "chiefName";

  /** The chief's name, the word that sends a dictation to it ("Henry, …"); empty: only 🧑‍💼. */
  String chiefName() {
    return prefs.getString(CHIEF_NAME, "").trim();
  }

  void setChiefName(String name) {
    prefs.edit().putString(CHIEF_NAME, name.trim()).apply();
  }

  /** Refine's instructions: the user's own, or the default. */
  String refinePrompt() {
    String value = prefs.getString("processingPrompt", "").trim();
    return value.isEmpty() ? TextModel.DEFAULT_PROMPT : value;
  }

  void setTopics(List<String> value) {
    putList("profileTopics", value);
  }

  void setAbout(String value) {
    prefs.edit().putString("profileAbout", value.trim()).apply();
  }

  void addWord(String word) {
    String clean = word.trim();
    if (clean.isEmpty()) return;
    List<String> words = new ArrayList<>(words());
    if (!lower(words).contains(clean.toLowerCase(Locale.ROOT))) words.add(clean);
    putList("profileWords", words);
  }

  void removeWord(String word) {
    List<String> words = new ArrayList<>(words());
    words.removeIf(w -> w.equalsIgnoreCase(word));
    putList("profileWords", words);
  }

  /** Background for the text model; empty parts are left out. */
  String context() {
    StringBuilder s = new StringBuilder();
    if (!topics().isEmpty())
      s.append("Usually dictates: ").append(String.join(", ", topics())).append(".\n");
    if (!about().isEmpty()) s.append(about()).append("\n");
    if (!words().isEmpty())
      s.append("Spell these as written: ").append(String.join(", ", words())).append(".\n");
    return s.toString().trim();
  }

  private List<String> list(String key) {
    List<String> result = new ArrayList<>();
    try {
      JSONArray array = new JSONArray(prefs.getString(key, "[]"));
      for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
    } catch (JSONException ignored) {
      /* Treat a damaged list as empty. */
    }
    return result;
  }

  private void putList(String key, List<String> value) {
    prefs.edit().putString(key, new JSONArray(value).toString()).apply();
  }

  private static List<String> lower(List<String> values) {
    List<String> result = new ArrayList<>();
    for (String v : values) result.add(v.toLowerCase(Locale.ROOT));
    return result;
  }
}
