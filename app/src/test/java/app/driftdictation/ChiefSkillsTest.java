package app.driftdictation;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public class ChiefSkillsTest {
  static String template(String name) throws Exception {
    return new String(Files.readAllBytes(Path.of("src/main/assets/skills/" + name + "/SKILL.md")), java.nio.charset.StandardCharsets.UTF_8);
  }

  String router(String name, String topic, String link) throws Exception {
    return ChiefSkills.fill(ChiefSkills.ROUTER, template(ChiefSkills.ROUTER), name, "https://ntfy.sh", topic, link);
  }

  @Test
  public void theTemplatesAreFilledIn() throws Exception {
    String r = router("Alex", "talkrouter-abc", "https://someone.getbb.app");
    assertFalse(r, r.contains("{{"));
    assertTrue(r.contains("the voice app on Alex's phone"));
    assertTrue(r.contains("--data-binary @- https://ntfy.sh/talkrouter-abc <<'TEXT'"));
    assertTrue(r.contains("-H \"Click: https://someone.getbb.app/threads/$BB_THREAD_ID\""));
    // The paste flow: request ID, tags and the Copy broadcast.
    assertTrue(r.contains("[request: <id>]"));
    assertTrue(r.contains("Tags: talkrouter-paste,req-<id>"));
    assertTrue(r.contains("intent=" + TalkAccessibility.COPY_ACTION + ", extras.request=<id>"));
    assertTrue(r.startsWith("---\nname: dictate-and-delegate\n"));
    String c = ChiefSkills.fill(ChiefSkills.CHIEF, template(ChiefSkills.CHIEF), "Alex", "https://ntfy.sh", "t", "");
    assertTrue(c.contains("You're Alex's chief of staff."));
    assertTrue(c.contains("When a decision is Alex's, ask."));
    assertFalse(c.contains("{{"));
  }

  @Test
  public void withoutANameItSaysTheUser() throws Exception {
    String c = ChiefSkills.fill(ChiefSkills.CHIEF, template(ChiefSkills.CHIEF), "  ", "https://ntfy.sh", "t", "");
    assertTrue(c.contains("Act as the user's chief of staff"));
    assertTrue(c.contains("You manage the user's tasks"));
    String r = router("", "t", "");
    assertTrue(r.contains("when you need the user,"));
    // Without a link base there's no Click header.
    assertFalse(r.contains("Click:"));
    assertFalse(r.contains("{{"));
  }

  @Test
  public void theMarkerChangesWithTheTemplateAndThePlaceholdersOnly() throws Exception {
    String a = router("Alex", "talkrouter-abc", "https://x.getbb.app");
    String hash = ChiefSkills.marker(a);
    assertTrue(hash, hash.matches("[0-9a-f]{12}"));
    assertTrue(a.endsWith("\n<!-- dictate-and-delegate-skill: " + hash + " -->\n"));
    assertEquals(hash, ChiefSkills.marker(router("Alex", "talkrouter-abc", "https://x.getbb.app")));
    assertNotEquals(hash, ChiefSkills.marker(router("Alex", "talkrouter-new", "https://x.getbb.app")));
    assertNotEquals(hash, ChiefSkills.marker(router("Sam", "talkrouter-abc", "https://x.getbb.app")));
    assertNotEquals(hash, ChiefSkills.marker(router("Alex", "talkrouter-abc", "https://y.getbb.app")));
    String changed = template(ChiefSkills.ROUTER).replace("odd misheard word", "occasional misheard word");
    assertNotEquals(
        hash,
        ChiefSkills.marker(ChiefSkills.fill(ChiefSkills.ROUTER, changed, "Alex", "https://ntfy.sh", "talkrouter-abc", "https://x.getbb.app")));
    assertEquals("", ChiefSkills.marker("no marker here"));
  }

  @Test
  public void outdatedMeansMissingUnmarkedOrAnotherHash() throws Exception {
    Map<String, String> files = new LinkedHashMap<>();
    files.put(ChiefSkills.CHIEF, ChiefSkills.fill(ChiefSkills.CHIEF, template(ChiefSkills.CHIEF), "", "s", "t", ""));
    files.put(ChiefSkills.ROUTER, router("", "t", ""));
    String c = ChiefSkills.marker(files.get(ChiefSkills.CHIEF)), r = ChiefSkills.marker(files.get(ChiefSkills.ROUTER));
    assertEquals(List.of(), ChiefSkills.outdated(files, new JSONObject().put("chief-of-staff", c).put("dictate-and-delegate", r)));
    assertEquals(
        List.of("dictate-and-delegate"),
        ChiefSkills.outdated(files, new JSONObject().put("chief-of-staff", c).put("dictate-and-delegate", "old")));
    assertEquals(List.of("chief-of-staff", "dictate-and-delegate"), ChiefSkills.outdated(files, new JSONObject()));
    // After the rename: the old talk-router skill still there counts as outdated, so it's removed.
    assertEquals(
        List.of("talk-router"),
        ChiefSkills.outdated(
            files, new JSONObject().put("chief-of-staff", c).put("dictate-and-delegate", r).put("talk-router", "abc")));
    assertEquals("chief-of-staff:" + c + ",dictate-and-delegate:" + r, ChiefSkills.summary(files));
  }

  @Test
  public void theInstallThreadGetsBothFilesVerbatim() throws Exception {
    Map<String, String> files = new LinkedHashMap<>();
    files.put(ChiefSkills.CHIEF, "chief file\n");
    files.put(ChiefSkills.ROUTER, "router file\n");
    String p = ChiefSkills.installPrompt(files);
    assertTrue(p.contains("`chief-of-staff` describes the chief's role"));
    assertTrue(p.contains("shows `chief-of-staff` and `dictate-and-delegate` as user skills and no `talk-router`"));
    assertTrue(
        p.contains(
            "===== ~/.bb/skills/dictate-and-delegate/SKILL.md (begins on the next line) =====\nrouter file\n===== end of dictate-and-delegate ====="));
    assertTrue(p.contains("remove `~/.bb/skills/talk-router` if it's there"));
    assertTrue(p.contains("so keep it as it is"));
    assertTrue(p.contains("fix it the way the author obviously meant"));
    assertFalse(p.contains("byte for byte"));
    assertTrue(code(p).contains("\"--title\",\"Dictate & Delegate: install skills\""));

    assertTrue(ChiefSkills.checkSnippet().contains("x.scope === \"bb-user\""));
  }

  static String code(String prompt) {
    return ChiefSkills.spawnSnippet(prompt);
  }

  /** The front matter between the --- lines as key → value, unquoting a double-quoted value. */
  static Map<String, String> frontMatter(String file) {
    assertTrue(file.startsWith("---\n"));
    int end = file.indexOf("\n---\n", 4);
    assertTrue(end > 0);
    Map<String, String> out = new LinkedHashMap<>();
    for (String line : file.substring(4, end).split("\n")) {
      int colon = line.indexOf(": ");
      assertTrue(line, colon > 0 && line.substring(0, colon).matches("[a-z-]+"));
      String value = line.substring(colon + 2);
      if (value.startsWith("\"")) {
        // A closed double-quoted string: only \\ and \" escapes, nothing after the closing quote.
        StringBuilder v = new StringBuilder();
        int i = 1;
        for (; i < value.length() && value.charAt(i) != '"'; i++) {
          char ch = value.charAt(i);
          if (ch == '\\') {
            char next = value.charAt(++i);
            assertTrue(line, next == '\\' || next == '"');
            v.append(next);
          } else v.append(ch);
        }
        assertEquals(line, value.length() - 1, i);
        value = v.toString();
      } else assertFalse(line, value.contains(": ") || value.contains("\""));
      out.put(line.substring(0, colon), value);
    }
    return out;
  }

  @Test
  public void theFrontMatterStaysValidWithATrickyName() throws Exception {
    String name = "Ada \"A.\" O'Neil: Jr \\ x";
    for (String skill : ChiefSkills.NAMES) {
      String filled = ChiefSkills.fill(skill, template(skill), name, "https://ntfy.sh", "t", "https://x.getbb.app");
      Map<String, String> front = frontMatter(filled);
      assertEquals(skill, front.get("name"));
      assertTrue(front.get("description"), front.get("description").contains(name + "'s"));
      assertEquals(2, front.size());
      // In the body the name stays as typed.
      assertTrue(filled.substring(filled.indexOf("\n---\n", 4)).contains(name));
    }
    // Without a name too.
    for (String skill : ChiefSkills.NAMES)
      assertTrue(frontMatter(ChiefSkills.fill(skill, template(skill), "", "s", "t", "")).get("description").contains("the user"));
  }
}
