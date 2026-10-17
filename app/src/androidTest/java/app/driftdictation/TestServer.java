package app.driftdictation;

import android.content.Context;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/**
 * The fake OpenRouter (scripts/fakes/fake_openrouter.py; scripts/device-test.sh starts it and
 * forwards it with adb reverse). Tests queue the transcripts, request-check answers and text-model
 * replies they expect, and read back what the app sent.
 */
final class TestServer {
  /** What the fake reports per call as usage.cost; notes show exactly these. */
  static final BigDecimal SPEECH = new BigDecimal("0.001"),
      ROUTE = new BigDecimal("0.00005"),
      TEXT = new BigDecimal("0.0003");

  /** The fake accepts any key except "bad". */
  static final String KEY = "sk-or-v1-fake", BAD_KEY = "bad";

  /** The fake's control API lives next to /api/v1. */
  private static String root() {
    return BuildConfig.OPENROUTER_URL.replaceFirst("/api/v1/?$", "");
  }

  static void reset() throws Exception {
    post("/test/reset", new JSONObject());
  }

  /** Saves the fake key, as setup does after checking it. */
  static void useKey(Context context) throws Exception {
    new Vault(context).put(OpenRouter.KEY, KEY);
  }

  static void removeKey(Context context) {
    new Vault(context).remove(OpenRouter.KEY);
  }

  /** Queues transcripts; null queues a provider error. */
  static void transcripts(String... texts) throws Exception {
    post("/test/script", new JSONObject().put("transcribe", answers(texts)));
  }

  /** Queues answers of the request check: "transcript" or "text", with no template. */
  static void routes(String... kinds) throws Exception {
    JSONArray list = new JSONArray();
    for (String k : kinds)
      list.put(new JSONObject().put("text", "{\"route\": \"" + k + "\", \"template\": \"\"}"));
    post("/test/script", new JSONObject().put("route", list));
  }

  /** Queues text-model replies; null queues a provider error. */
  static void replies(String... texts) throws Exception {
    post("/test/script", new JSONObject().put("text", answers(texts)));
  }

  /** Queues one error answer of the given kind ("transcribe", "route", "text"): "credits" is a 402. */
  static void error(String kind, String error) throws Exception {
    script(kind, new JSONObject().put("error", error));
  }

  /** Queues one answer as the fake takes it, e.g. {"text": "…", "cost": null} for no usage.cost. */
  static void script(String kind, JSONObject item) throws Exception {
    post("/test/script", new JSONObject().put(kind, new JSONArray().put(item)));
  }

  /** The requests the fake received since the last reset, oldest first: {"kind", "body"}. */
  static JSONArray requests() throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(root() + "/test/requests").openConnection();
    try (InputStream in = c.getInputStream()) {
      return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8))
          .getJSONArray("requests");
    } finally {
      c.disconnect();
    }
  }

  private static JSONArray answers(String... texts) throws JSONException {
    JSONArray list = new JSONArray();
    for (String t : texts)
      list.put(t == null ? new JSONObject().put("error", "provider") : new JSONObject().put("text", t));
    return list;
  }

  private static void post(String path, JSONObject body) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(root() + path).openConnection();
    try {
      byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/json");
      try (OutputStream out = c.getOutputStream()) {
        out.write(payload);
      }
      if (c.getResponseCode() >= 300)
        throw new IOException(path + " → HTTP " + c.getResponseCode() + " (is the fake running?)");
    } finally {
      c.disconnect();
    }
  }
}
