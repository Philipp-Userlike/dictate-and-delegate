package app.driftdictation;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class BbBridgeTest {
  @Test
  public void threadIdsComeFromLinksOrIds() {
    assertEquals("thr_abc123", BbBridge.threadId("thr_abc123"));
    assertEquals("thr_abc123", BbBridge.threadId("https://someone.getbb.app/threads/thr_abc123"));
    assertEquals("thr_abc123", BbBridge.threadId("https://someone.getbb.app/threads/thr_abc123?x=1"));
    assertEquals("", BbBridge.threadId("https://someone.getbb.app/"));
    assertEquals("", BbBridge.threadId(null));
  }

  @Test
  public void threadLinksPointAtTheWebApp() {
    assertEquals(
        "https://someone.getbb.app/threads/thr_x", BbBridge.threadLink("https://someone.getbb.app", "thr_x"));
    assertEquals("", BbBridge.threadLink("", "thr_x"));
    assertEquals("https://someone.getbb.app", BbBridge.linkBaseFor("https://someone.getbb.app/mcp"));
    assertEquals("", BbBridge.linkBaseFor("http://127.0.0.1:38886/api/v1/plugins/bb--mcp/http/mcp"));
  }

  @Test
  public void theMessageGoesAsDataNotCode() {
    String said = "Say \"hi\" `now` $(rm -rf ~) and\nback\\slash";
    String code = BbBridge.tellSnippet("thr_x", said);
    assertTrue(code.contains("\"thread\",\"tell\",\"thr_x\",\"--mode\",\"queue\",\"--message-file\",\"-\""));
    // The message is one JSON string literal handed over as stdin.
    assertTrue(code.contains("{stdin: " + org.json.JSONObject.quote(said) + "}"));
    assertFalse(code.contains("\nback"));
  }

  @Test
  public void pastedTextFollowsTheDictation() {
    String m =
        BbBridge.message(
            " research flights to Lisbon ",
            List.<String[]>of(new String[] {"pasted text", "Dates: 3 to 7 May"}),
            List.of("an image (map.jpg)"),
            "k3x9qa");
    assertTrue(m.endsWith("\n\n[request: k3x9qa]"));
    assertTrue(m.startsWith("research flights to Lisbon\n\n<pasted name=\"pasted text\">\nDates: 3 to 7 May\n</pasted>"));
    assertTrue(m.contains("an image (map.jpg)"));
    assertEquals("just this", BbBridge.message("just this", List.of(), List.of(), ""));
  }

  @Test
  public void aNewChiefGetsOneLine() {
    String code = BbBridge.spawnSnippet(ChiefSkills.START);
    assertTrue(code.contains("\"--project\",\"proj_personal\",\"--title\",\"Chief of staff\""));
    assertTrue(code.contains("Use the chief-of-staff and talk-router skills."));
  }

  @Test
  public void ntfyTopicsAndLinks() {
    String t = BbBridge.newTopic();
    assertTrue(t, t.matches("talkrouter-[A-Za-z0-9_-]{24}"));
    assertNotEquals(t, BbBridge.newTopic());
    assertEquals("ntfy://ntfy.sh/talkrouter-a", BbBridge.ntfyLink("https://ntfy.sh", "talkrouter-a"));
    assertEquals(
        "ntfy://example.org:8080/t?secure=false", BbBridge.ntfyLink("http://example.org:8080/", "t"));
  }
}
