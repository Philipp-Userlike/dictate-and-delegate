package app.driftdictation;

import android.content.Context;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/**
 * The fake OpenRouter (scripts/fakes/fake_openrouter.py; scripts/device-test.sh starts it and
 * forwards it with adb reverse). Tests queue the transcripts, Jev's answers and Refine's replies
 * they expect, and read back what the app sent.
 */
final class TestServer {
  /** What the fake reports per call as usage.cost; notes show exactly these. */
  static final BigDecimal SPEECH = new BigDecimal("0.001"),
      JEV = new BigDecimal("0.00002"),
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

  /** Queues one answer of Jev: the refine and chief probabilities and the template ("none"). */
  static void decide(double refine, double chief, String template) throws Exception {
    script("decide", new JSONObject().put("refine", refine).put("chief", chief).put("template", template));
  }

  /** Queues text-model replies; null queues a provider error. */
  static void replies(String... texts) throws Exception {
    post("/test/script", new JSONObject().put("text", answers(texts)));
  }

  /** Queues one error answer of the given kind ("transcribe", "decide", "text"): "credits" is a 402. */
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

  // ------------------------------------------------------------------ the fake bb MCP server

  /** The fake bb (scripts/fakes/fake_bb_mcp.py) listens on the OpenRouter fake's port + 1. */
  static String bbRoot() {
    java.net.URI u = java.net.URI.create(root());
    return u.getScheme() + "://" + u.getHost() + ":" + (u.getPort() + 1);
  }

  /** The custom MCP address the app uses for the fake. */
  static String bbUrl() {
    return bbRoot() + "/mcp";
  }

  /** The chief id the fake hands out when a thread is spawned. */
  static final String CHIEF = "thr_fakechief";

  static void bbReset() throws Exception {
    postTo(bbRoot(), "/test/reset", new JSONObject());
  }

  /** Queues one failure for the next "spawn", "tell", "show" (or "any") call: "tool", "http503"… */
  static void bbFail(String kind, String error) throws Exception {
    postTo(bbRoot(), "/test/fail", new JSONObject().put("kind", kind).put("error", error));
  }

  /** The fake requires this bearer token from now on ("" turns that off). */
  static void bbToken(String token) throws Exception {
    postTo(bbRoot(), "/test/config", new JSONObject().put("token", token));
  }

  /** MCP requests the fake received since the last reset: {"method", "tool", "code", "auth"}. */
  static JSONArray bbRequests() throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(bbRoot() + "/test/requests").openConnection();
    try (InputStream in = c.getInputStream()) {
      return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8))
          .getJSONArray("requests");
    } finally {
      c.disconnect();
    }
  }

  /** The snippets sent with the run tool, oldest first. */
  static java.util.List<String> bbSnippets() throws Exception {
    java.util.List<String> out = new java.util.ArrayList<>();
    JSONArray all = bbRequests();
    for (int i = 0; i < all.length(); i++)
      if (all.getJSONObject(i).optString("tool").equals("run"))
        out.add(all.getJSONObject(i).getString("code"));
    return out;
  }

  // ------------------------------------------------------------------ the fake ntfy

  /** The fake ntfy (scripts/fakes/fake_ntfy.py) listens on the OpenRouter fake's port + 2. */
  static String ntfyRoot() {
    java.net.URI u = java.net.URI.create(root());
    return u.getScheme() + "://" + u.getHost() + ":" + (u.getPort() + 2);
  }

  static void ntfyReset() throws Exception {
    postTo(ntfyRoot(), "/test/reset", new JSONObject());
  }

  /** Publishes as the chief would: text to paste, tagged with the request ID. */
  static void ntfyPaste(String topic, String request, String text) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(ntfyRoot() + "/" + topic).openConnection();
    try {
      byte[] payload = text.getBytes(StandardCharsets.UTF_8);
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setRequestProperty("Title", "Text to paste");
      c.setRequestProperty("Tags", BbBridge.PASTE_TAG + "," + BbBridge.requestTag(request));
      try (OutputStream out = c.getOutputStream()) {
        out.write(payload);
      }
      if (c.getResponseCode() >= 300) throw new IOException("ntfy publish → HTTP " + c.getResponseCode());
    } finally {
      c.disconnect();
    }
  }

  /** What the fake ntfy saw: publishes and subscriptions, oldest first. */
  static JSONArray ntfyRequests() throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(ntfyRoot() + "/test/requests").openConnection();
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
    postTo(root(), path, body);
  }

  private static void postTo(String root, String path, JSONObject body) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(root + path).openConnection();
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
