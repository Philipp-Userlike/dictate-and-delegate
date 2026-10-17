package app.driftdictation;

import static org.junit.Assert.*;

import java.util.*;
import org.json.*;
import org.junit.Test;

public class FeedbackTest {
  JSONObject model(String id, int accuracy, int speed, String perSecond) throws Exception {
    return new JSONObject()
        .put("id", id)
        .put("name", id)
        .put("accuracyTier", accuracy)
        .put("speedTier", speed)
        .put("supportsDe", true)
        .put("supportsEn", true)
        .put("unit", "second")
        .put("pricing", new JSONObject().put("prompt", perSecond));
  }

  @Test
  public void eachComplaintPicksAnEngineThatAddressesIt() throws Exception {
    JSONObject current = model("current", 3, 3, ".0001");
    JSONObject accurate = model("accurate", 5, 2, ".0002");
    JSONObject fast = model("fast", 4, 5, ".00005");
    JSONObject cheap = model("cheap", 3, 3, ".00001");
    JSONObject sloppy = model("sloppy", 1, 5, ".000001");
    List<JSONObject> all = List.of(current, accurate, fast, cheap, sloppy);
    Set<String> tried = Set.of("current");
    assertEquals(
        "accurate",
        Feedback.alternative(all, current, Feedback.ACCURACY, tried).optString("id"));
    assertEquals(
        "fast", Feedback.alternative(all, current, Feedback.SPEED, tried).optString("id"));
    assertEquals(
        "cheap", Feedback.alternative(all, current, Feedback.COST, tried).optString("id"));
  }

  @Test
  public void skipsTriedAndUnassessedEngines() throws Exception {
    JSONObject current = model("current", 4, 3, ".0001");
    JSONObject tried = model("tried", 5, 3, ".0001");
    JSONObject unassessed = model("unassessed", 0, 0, ".00001");
    JSONObject peer = model("peer", 4, 3, ".0001");
    JSONObject better = model("better", 5, 3, ".0002");
    List<JSONObject> all = List.of(current, tried, unassessed, peer, better);
    assertEquals(
        "better",
        Feedback.alternative(all, current, Feedback.ACCURACY, Set.of("current", "tried"))
            .optString("id"));
    assertNull(Feedback.alternative(all, current, Feedback.COST, Set.of("current", "tried")));
  }
}
