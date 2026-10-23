package app.driftdictation;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.*;
import org.junit.Test;

/** Every tr("…") in the code has an entry in every language, with the same placeholders. */
public class I18nTest {
  static final String[] LANGS = {"de", "fr", "es", "it", "pt", "nl", "pl"};
  static final String LIT = "\"((?:[^\"\\\\]|\\\\.)*)\"";

  static File main() {
    File here = new File("src/main");
    return here.exists() ? here : new File("app/src/main");
  }

  static String unescape(String java) {
    return java.replace("\\\"", "\"").replace("\\'", "'").replace("\\n", "\n").replace("\\\\", "\\");
  }

  static Set<String> texts() throws Exception {
    Set<String> found = new TreeSet<>();
    Pattern call = Pattern.compile("\\b(?:tr|reason|ongoing)\\(\\s*(" + LIT + "(?:\\s*\\+\\s*" + LIT + ")*)");
    for (File f : new File(main(), "java/app/driftdictation").listFiles()) {
      if (!f.getName().endsWith(".java")) continue;
      String code = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
      Matcher m = call.matcher(code);
      while (m.find()) {
        StringBuilder text = new StringBuilder();
        Matcher lit = Pattern.compile(LIT).matcher(m.group(1));
        while (lit.find()) text.append(unescape(lit.group(1)));
        found.add(text.toString());
      }
    }
    return found;
  }

  /** English "other" forms of every plural(n, "one", "other") call. */
  static Set<String> plurals() throws Exception {
    Set<String> found = new TreeSet<>();
    Pattern call = Pattern.compile("\\bplural\\([^,]+,\\s*" + LIT + "\\s*,\\s*" + LIT + "\\s*\\)");
    for (File f : new File(main(), "java/app/driftdictation").listFiles()) {
      if (!f.getName().endsWith(".java")) continue;
      Matcher m = call.matcher(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
      while (m.find()) found.add(unescape(m.group(2)));
    }
    return found;
  }

  static Map<String, String> strings(String dir) throws Exception {
    File file = new File(main(), "res/" + dir + "/strings_ui.xml");
    Map<String, String> out = new HashMap<>();
    if (!file.exists()) return out;
    String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    Matcher m = Pattern.compile("<string name=\"(t_[0-9a-f]{8}(?:_[a-z]+)?)\"[^>]*>(.*?)</string>", Pattern.DOTALL).matcher(xml);
    while (m.find()) out.put(m.group(1), m.group(2));
    return out;
  }

  static List<String> placeholders(String text) {
    List<String> out = new ArrayList<>();
    Matcher m = Pattern.compile("%(?:\\d+\\$)?[sdf]|%%").matcher(text);
    while (m.find()) out.add(m.group());
    Collections.sort(out);
    return out;
  }

  @Test
  public void everyTextIsTranslatedWithTheSamePlaceholders() throws Exception {
    Set<String> texts = texts();
    assertTrue("found " + texts.size(), texts.size() > 300);
    Map<String, String> english = strings("values");
    List<String> problems = new ArrayList<>();
    for (String text : texts)
      if (!english.containsKey(I18n.key(text)))
        problems.add("not extracted (run scripts/i18n.py extract): " + text);
    for (String lang : LANGS) {
      Map<String, String> translated = strings("values-" + lang);
      for (String text : texts) {
        String value = translated.get(I18n.key(text));
        if (value == null) problems.add(lang + " missing: " + text);
        else if (!placeholders(value).equals(placeholders(text)))
          problems.add(lang + " placeholders differ: " + text + " → " + value);
      }
    }
    assertTrue(String.join("\n", problems), problems.isEmpty());
  }

  @Test
  public void everyPluralHasTheFormsItsLanguageNeeds() throws Exception {
    Map<String, String[]> categories = new HashMap<>();
    categories.put("values", new String[] {"one", "other"});
    for (String lang : new String[] {"de", "nl"}) categories.put("values-" + lang, new String[] {"one", "other"});
    for (String lang : new String[] {"fr", "es", "it", "pt"})
      categories.put("values-" + lang, new String[] {"one", "many", "other"});
    categories.put("values-pl", new String[] {"one", "few", "many", "other"});
    Set<String> plurals = plurals();
    assertTrue("found " + plurals.size(), plurals.size() >= 4);
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, String[]> lang : categories.entrySet()) {
      Map<String, String> strings = strings(lang.getKey());
      for (String other : plurals)
        for (String c : lang.getValue()) {
          String value = strings.get(I18n.key(other) + "_" + c);
          if (value == null) problems.add(lang.getKey() + " " + c + " missing: " + other);
          else if (!value.contains("%1$d")) problems.add(lang.getKey() + " " + c + " lost %1$d: " + value);
        }
    }
    assertTrue(String.join("\n", problems), problems.isEmpty());
  }
}
