package app.driftdictation;

import java.math.BigDecimal;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One session is also its durable upload checkpoint. Costs are what OpenRouter reported, in USD
 * (notes from before version 2 hold what Drift's former server charged).
 */
final class HistoryEntry {
  final JSONObject json;

  HistoryEntry(JSONObject json) {
    this.json = json;
  }

  static HistoryEntry create(String id, String model, double seconds) throws Exception {
    return new HistoryEntry(
        new JSONObject()
            .put("id", id)
            .put("created", System.currentTimeMillis())
            .put("model", model)
            .put("duration", seconds)
            .put("text", "")
            .put("offset", 0)
            .put("knownCost", "0")
            .put("unpriced", 0)
            .put("unconfirmed", 0)
            .put("requests", 0)
            .put("status", "pending"));
  }

  String id() {
    return json.optString("id");
  }

  String recordingId() {
    return json.optString("recordingId", id());
  }

  long created() {
    return json.optLong("created");
  }

  String text() {
    return json.optString("text");
  }

  /** What the model wrote, before instructions or your edits. */
  String raw() {
    return json.optString("raw", text());
  }

  String model() {
    return json.optString("model");
  }

  long offset() {
    return json.optLong("offset");
  }

  double duration() {
    return json.optDouble("duration", 0);
  }

  BigDecimal cost() {
    return new BigDecimal(json.optString("knownCost", "0"));
  }

  /** The part of cost() the text model charged; 0 for notes from before it was recorded. */
  BigDecimal textCost() {
    return new BigDecimal(json.optString("textCost", "0"));
  }

  /** The part of cost the request check of versions 2.0 and 2.1 charged. */
  BigDecimal routeCost() {
    return new BigDecimal(json.optString("routeCost", "0"));
  }

  /** The part of cost Jev charged. */
  BigDecimal jevCost() {
    return new BigDecimal(json.optString("jevCost", "0"));
  }

  /**
   * What Refine through the user's own Anthropic key cost, at list price. Paid from Anthropic
   * credits, so never part of cost().
   */
  BigDecimal anthropicCost() {
    return new BigDecimal(json.optString("anthropicCost", "0"));
  }

  /** Adds to a cost kept apart ("textCost", "jevCost") for Usage; null adds nothing. */
  void addCost(String key, BigDecimal cost) throws Exception {
    if (cost != null)
      json.put(key, new BigDecimal(json.optString(key, "0")).add(cost).toPlainString());
  }

  /**
   * One step of the activity log: "speech", "jev", "refine" or "bb" (older notes: "check",
   * "text", "simple", "smart", agent steps), the model, how long it took and what it cost (null
   * when unknown); detail is an error (in older notes, also the check's answer). The speech chunks
   * of one recording add up to one step. Returns the step, e.g. to add Jev's "answer".
   */
  JSONObject step(String kind, String model, long ms, BigDecimal cost, String detail) throws Exception {
    JSONArray steps = json.optJSONArray("steps");
    if (steps == null) json.put("steps", steps = new JSONArray());
    JSONObject last = steps.length() == 0 ? null : steps.getJSONObject(steps.length() - 1);
    if (kind.equals("speech")
        && last != null
        && last.optString("kind").equals("speech")
        && last.optString("model").equals(model)
        && !last.has("detail")) {
      last.put("ms", last.optLong("ms") + ms);
      if (cost == null || !last.has("cost")) last.remove("cost");
      else last.put("cost", new BigDecimal(last.getString("cost")).add(cost).toPlainString());
      if (detail != null) last.put("detail", detail);
      return last;
    }
    JSONObject s = new JSONObject().put("kind", kind).put("model", model).put("ms", ms);
    if (cost != null) s.put("cost", cost.toPlainString());
    if (detail != null) s.put("detail", detail);
    steps.put(s);
    return s;
  }

  /** Some call's cost is unknown ("estimated": charged at an estimate by the former server). */
  boolean incompleteCost() {
    return json.optInt("unpriced") > 0
        || json.optInt("estimated") > 0
        || json.optInt("unconfirmed") > 0
        || json.optInt("requests") == 0;
  }

  void beginRequest() throws Exception {
    json.put("unconfirmed", json.optInt("unconfirmed") + 1);
  }

  void accept(OpenRouter.Result result, long offset) throws Exception {
    json.put("text", AudioFiles.append(text(), result.text()))
        .put("offset", offset)
        .put("requests", json.optInt("requests") + 1)
        .put("unconfirmed", Math.max(0, json.optInt("unconfirmed") - 1));
    charge(result.cost());
  }

  /** Adds what OpenRouter reported for a call on this note; null: it reported nothing. */
  void charge(BigDecimal cost) throws Exception {
    if (cost == null) json.put("unpriced", json.optInt("unpriced") + 1);
    else json.put("knownCost", cost().add(cost).toPlainString());
  }

  String costLabel() {
    return "$"
        + cost().stripTrailingZeros().toPlainString()
        + (incompleteCost() ? " reported · incomplete" : " actual");
  }
}
