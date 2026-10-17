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
    assertTrue(r.startsWith("---\nname: talk-router\n"));
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
    assertTrue(a.endsWith("\n<!-- talk-router-skill: " + hash + " -->\n"));
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
    assertEquals(List.of(), ChiefSkills.outdated(files, new JSONObject().put("chief-of-staff", c).put("talk-router", r)));
    assertEquals(List.of("talk-router"), ChiefSkills.outdated(files, new JSONObject().put("chief-of-staff", c).put("talk-router", "old")));
    assertEquals(List.of("chief-of-staff", "talk-router"), ChiefSkills.outdated(files, new JSONObject()));
    assertEquals("chief-of-staff:" + c + ",talk-router:" + r, ChiefSkills.summary(files));
  }

  @Test
  public void theInstallThreadGetsBothFilesVerbatim() throws Exception {
    Map<String, String> files = new LinkedHashMap<>();
    files.put(ChiefSkills.CHIEF, "chief file\n");
    files.put(ChiefSkills.ROUTER, "router file\n");
    String p = ChiefSkills.installPrompt(files);
    assertTrue(p.contains("- `~/.bb/skills/chief-of-staff/SKILL.md`"));
    assertTrue(p.contains("===== ~/.bb/skills/talk-router/SKILL.md (begins on the next line) =====\nrouter file\n===== end of talk-router ====="));
    assertTrue(p.contains("reply \"installed\""));
    String code = ChiefSkills.spawnSnippet(p);
    assertTrue(code.contains("\"--title\",\"Talk Router: install skills\""));
    assertTrue(ChiefSkills.checkSnippet().contains("x.scope === \"bb-user\""));
  }
}
