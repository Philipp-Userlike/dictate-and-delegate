package app.driftdictation;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class ChiefTextTest {
  @Test
  public void requestIdsAreShortAndRandom() {
    String id = ChiefText.requestId();
    assertTrue(id, id.matches("[a-z2-9]{6}"));
    assertNotEquals(id, ChiefText.requestId());
  }

  @Test
  public void onlyThePasteMessageForThisRequestCounts() throws Exception {
    JSONObject m =
        new JSONObject(
            "{\"event\": \"message\", \"message\": \"Hi Tom, see you at 10.\","
                + " \"tags\": [\"talkrouter-paste\", \"req-k3x9qa\"]}");
    assertTrue(ChiefText.matches(m, "k3x9qa"));
    assertEquals("Hi Tom, see you at 10.", ChiefText.text(m));
    assertFalse(ChiefText.matches(m, "other1"));
    assertFalse(
        ChiefText.matches(new JSONObject("{\"event\": \"message\", \"tags\": [\"req-k3x9qa\"]}"), "k3x9qa"));
    assertFalse(
        ChiefText.matches(
            new JSONObject("{\"event\": \"keepalive\", \"tags\": [\"talkrouter-paste\", \"req-k3x9qa\"]}"),
            "k3x9qa"));
    assertFalse(ChiefText.matches(new JSONObject("{\"event\": \"open\"}"), "k3x9qa"));
  }

  @Test
  public void theStreamStartsWhenTheRequestWasSent() {
    assertEquals(
        "https://ntfy.sh/talkrouter-x/json?since=1792248545",
        ChiefText.streamUrl("https://ntfy.sh", "talkrouter-x", 1792248545L, false));
    assertEquals(
        "http://127.0.0.1:8799/t/json?since=5&poll=1",
        ChiefText.streamUrl("http://127.0.0.1:8799", "t", 5, true));
  }
}
