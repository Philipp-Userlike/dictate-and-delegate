package app.driftdictation;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class HistoryEntryTest {
  @Test
  public void costsAndResumePositionStayTogether() throws Exception {
    HistoryEntry e = HistoryEntry.create("id", "model", 15);
    e.beginRequest();
    e.accept(new OpenRouter.Result("one", new BigDecimal("0.00025")), 100);
    e = new HistoryEntry(new JSONObject(e.json.toString()));
    e.beginRequest();
    e.accept(new OpenRouter.Result("two", new BigDecimal("0.00035")), 200);
    assertEquals("one\ntwo", e.text());
    assertEquals(200, e.offset());
    assertEquals(new BigDecimal("0.00060"), e.cost());
    assertFalse(e.incompleteCost());
  }

  @Test
  public void missingUsageIsNeverShownAsFree() throws Exception {
    HistoryEntry e = HistoryEntry.create("id", "model", 1);
    e.beginRequest();
    e.accept(new OpenRouter.Result("hi", null), 32);
    assertTrue(e.incompleteCost());
    assertTrue(e.costLabel().contains("incomplete"));
    assertFalse(e.costLabel().contains("actual"));
  }

  @Test
  public void lostResponseRemainsVisibleAfterSuccessfulRetry() throws Exception {
    HistoryEntry e = HistoryEntry.create("id", "model", 1);
    e.beginRequest();
    e.beginRequest();
    e.accept(new OpenRouter.Result("hi", new BigDecimal("0.001")), 32);
    assertEquals(1, e.json.getInt("unconfirmed"));
    assertTrue(e.incompleteCost());
    assertEquals(new BigDecimal("0.001"), e.cost());
  }

  @Test
  public void chargesTheFormerServerEstimatedStayMarked() throws Exception {
    HistoryEntry e = HistoryEntry.create("id", "model", 1);
    e.beginRequest();
    e.accept(new OpenRouter.Result("hi", new BigDecimal("0.002")), 32);
    assertFalse(e.incompleteCost());
    e.json.put("estimated", 1);
    assertTrue(e.incompleteCost());
    assertEquals(new BigDecimal("0.002"), e.cost());
  }

  @Test
  public void priceUnitsAreNotConfused() throws Exception {
    JSONObject m =
        new JSONObject("{\"unit\":\"hour\",\"pricing\":{\"prompt\":\"0.1\",\"completion\":\"0\"}}");
    assertEquals("From $0.001667 / min", ModelCatalog.price(m));
    m.put("unit", "second");
    assertEquals("From $6 / min", ModelCatalog.price(m));
    m.put("unit", "token");
    assertEquals("$100000 in / $0 out per 1M tokens", ModelCatalog.price(m));
    m.put("unit", "unknown");
    assertTrue(ModelCatalog.price(m).contains("unverified"));
  }

  @Test
  public void stepsMergeSpeechChunksAndKeepUnknownCostsUnknown() throws Exception {
    HistoryEntry e = HistoryEntry.create("id", "speech-model", 60);
    e.step("speech", "speech-model", 1200, new BigDecimal("0.001"), null);
    e = new HistoryEntry(new JSONObject(e.json.toString()));
    e.step("speech", "speech-model", 800, new BigDecimal("0.002"), null);
    e.step("jev", Jev.MODEL, 400, new BigDecimal("0.000009"), null).put("answer", "refine 0.97");
    e.step("refine", "text-model", 2500, null, null);
    JSONArray steps = e.json.getJSONArray("steps");
    assertEquals(3, steps.length());
    JSONObject speech = steps.getJSONObject(0);
    assertEquals("speech", speech.getString("kind"));
    assertEquals(2000, speech.getLong("ms"));
    assertEquals("0.003", speech.getString("cost"));
    assertFalse(speech.has("detail"));
    JSONObject check = steps.getJSONObject(1);
    assertEquals(Jev.MODEL, check.getString("model"));
    assertEquals("0.000009", check.getString("cost"));
    assertEquals("refine 0.97", check.getString("answer"));
    assertFalse(check.has("detail"));
    assertFalse(steps.getJSONObject(2).has("cost")); // Unknown, never shown as free.

    // One chunk without a price makes the whole step's cost unknown; a failed chunk ends it.
    HistoryEntry f = HistoryEntry.create("id2", "speech-model", 60);
    f.step("speech", "speech-model", 1000, new BigDecimal("0.001"), null);
    f.step("speech", "speech-model", 1000, null, null);
    f.step("speech", "speech-model", 1000, new BigDecimal("0.001"), null);
    f.step("speech", "speech-model", 300, null, "No connection");
    f.step("speech", "speech-model", 1000, new BigDecimal("0.001"), null);
    steps = f.json.getJSONArray("steps");
    assertEquals(2, steps.length());
    assertFalse(steps.getJSONObject(0).has("cost"));
    assertEquals(3300, steps.getJSONObject(0).getLong("ms"));
    assertEquals("No connection", steps.getJSONObject(0).getString("detail"));
    assertEquals("0.001", steps.getJSONObject(1).getString("cost"));
  }
}
