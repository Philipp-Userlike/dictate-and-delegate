package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * The bridge to bb (getbb.app), through bb's built-in MCP server (plugin bb--mcp). Its "run" tool
 * takes a JavaScript snippet that calls the bb command line ({@code await bb.json([...])}), at most
 * 25 seconds each; snippets here are one or two bb calls and never wait.
 *
 * <p>Two ways to reach bb: bb Connect (https://<handle>.getbb.app/mcp, signed in with OAuth, see
 * BbOAuth), or a custom address with an optional bearer token (a tailnet, or adb reverse to bb's
 * local address http://127.0.0.1:38886/api/v1/plugins/bb--mcp/http/mcp).
 *
 * <p>The chief of staff is one bb thread in Personal that receives every agent request. It
 * notifies the user through ntfy itself; Talk Router sends no notifications. Each request carries a
 * short ID; text meant for pasting comes back as an ntfy message tagged with it (ChiefText).
 */
final class BbBridge {
  static final String CONNECT = "connect", CUSTOM = "custom";

  /** bb's project for threads outside any repository ("no project"). */
  static final String PROJECT = "proj_personal";

  static final String CHIEF_TITLE = "Chief of staff";

  /** Settings (not secret) in the "settings" preferences; tokens and the topic in Vault. */
  static final String MODE = "bbMode",
      HANDLE = "bbHandle",
      URL = "bbUrl",
      LINK_BASE = "bbLinkBase",
      CHIEF_ID = "chiefId",
      CHIEF_NAME = "chiefTitle",
      NTFY_SERVER = "ntfyServer",
      TOKEN = "bbToken",
      NTFY_TOPIC = "ntfyTopic",
      /** The chief knows the paste command (its instructions are from version 2.2 or later). */
      CHIEF_KNOWS_PASTE = "chiefKnowsPaste";

  static final String NTFY_DEFAULT = "https://ntfy.sh";

  /** A bb thread: its id, title and status. */
  record ThreadInfo(String id, String title, String status) {}

  private final Context context;
  private final SharedPreferences prefs;
  private final Vault vault;

  BbBridge(Context context) {
    this.context = context.getApplicationContext();
    prefs = this.context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    vault = new Vault(this.context);
  }

  // ------------------------------------------------------------------ settings

  String mode() {
    return prefs.getString(MODE, CONNECT);
  }

  String handle() {
    return prefs.getString(HANDLE, "");
  }

  /** The MCP address in use: bb Connect's for the handle, or the custom one. */
  String url() {
    return mode().equals(CUSTOM) ? prefs.getString(URL, "") : handle().isEmpty() ? "" : BbOAuth.resource(handle());
  }

  /** Where thread links point: the bb web app (PWA), e.g. https://<handle>.getbb.app. */
  String linkBase() {
    String custom = prefs.getString(LINK_BASE, "");
    if (mode().equals(CUSTOM) && !custom.isEmpty()) return custom;
    if (!handle().isEmpty()) return BbOAuth.origin(handle());
    return mode().equals(CUSTOM) ? linkBaseFor(prefs.getString(URL, "")) : "";
  }

  /** bb Connect signed in, or a custom address saved. */
  boolean connected() {
    return mode().equals(CUSTOM)
        ? !prefs.getString(URL, "").isEmpty()
        : !handle().isEmpty() && BbOAuth.signedIn(context);
  }

  String chiefId() {
    return prefs.getString(CHIEF_ID, "");
  }

  String chiefTitle() {
    return prefs.getString(CHIEF_NAME, "");
  }

  /** The chief can get requests: bb is connected and a chief is set. */
  static boolean ready(Context context) {
    BbBridge b = new BbBridge(context);
    return b.connected() && !b.chiefId().isEmpty();
  }

  void useConnect(String handle) {
    prefs.edit().putString(MODE, CONNECT).putString(HANDLE, handle).apply();
  }

  void useCustom(String url, String token, String linkBase) throws Exception {
    prefs
        .edit()
        .putString(MODE, CUSTOM)
        .putString(URL, url.trim())
        .putString(LINK_BASE, linkBase.trim().replaceAll("/+$", ""))
        .apply();
    if (token.isBlank()) vault.remove(TOKEN);
    else vault.put(TOKEN, token.trim());
  }

  String customToken() {
    try {
      return vault.get(TOKEN);
    } catch (Exception ex) {
      return "";
    }
  }

  void setChief(ThreadInfo t) {
    prefs
        .edit()
        .putString(CHIEF_ID, t.id())
        .putString(CHIEF_NAME, t.title())
        .putBoolean(CHIEF_KNOWS_PASTE, true)
        .apply();
  }

  void forgetChief() {
    prefs.edit().remove(CHIEF_ID).remove(CHIEF_NAME).remove(CHIEF_KNOWS_PASTE).apply();
  }

  /** Signs out of bb Connect (revoking the tokens) or forgets the custom address. */
  void disconnect() {
    if (mode().equals(CUSTOM)) {
      prefs.edit().remove(URL).remove(LINK_BASE).apply();
      vault.remove(TOKEN);
    } else BbOAuth.signOut(context);
  }

  /** Forgets everything about bb on this phone (Delete all my data); revokes in the background. */
  static void forgetAll(Context context) {
    String tokens = BbOAuth.forget(context);
    new java.lang.Thread(() -> BbOAuth.revoke(tokens)).start();
    Vault v = new Vault(context);
    v.remove(TOKEN);
    v.remove(NTFY_TOPIC);
  }

  String ntfyServer() {
    return prefs.getString(NTFY_SERVER, NTFY_DEFAULT);
  }

  void setNtfyServer(String server) {
    String s = server.trim().replaceAll("/+$", "");
    prefs.edit().putString(NTFY_SERVER, s.isEmpty() ? NTFY_DEFAULT : s).apply();
  }

  /** The ntfy topic, made once: whoever knows it can read the notifications. */
  synchronized String ntfyTopic() throws Exception {
    String topic = vault.get(NTFY_TOPIC);
    if (topic.isEmpty()) {
      topic = newTopic();
      vault.put(NTFY_TOPIC, topic);
    }
    return topic;
  }

  /** "talkrouter-" and 24 random URL-safe characters (144 bits). */
  static String newTopic() {
    return "talkrouter-" + BbOAuth.random(18);
  }

  // ------------------------------------------------------------------ pure helpers (unit-tested)

  private static final Pattern THREAD = Pattern.compile("\\bthr_[A-Za-z0-9]+");

  /** A thread id from "thr_…" or a link like https://<handle>.getbb.app/threads/thr_…; "" if none. */
  static String threadId(String idOrLink) {
    Matcher m = THREAD.matcher(idOrLink == null ? "" : idOrLink.trim());
    return m.find() ? m.group() : "";
  }

  static String threadLink(String linkBase, String id) {
    return linkBase.isEmpty() || id.isEmpty() ? "" : linkBase + "/threads/" + id;
  }

  /** https://<handle>.getbb.app/mcp → https://<handle>.getbb.app; other addresses: "". */
  static String linkBaseFor(String mcpUrl) {
    Matcher m = Pattern.compile("^(https://[a-z0-9-]+\\.getbb\\.app)(/.*)?$").matcher(mcpUrl.trim());
    return m.matches() ? m.group(1) : "";
  }

  /** A JavaScript string literal (JSON is valid JavaScript). */
  static String js(String s) {
    return JSONObject.quote(s);
  }

  /** The snippet that sends a message to a thread, queued behind a running turn, else started. */
  static String tellSnippet(String id, String message) {
    return "const r = await bb.json([\"thread\",\"tell\","
        + js(id)
        + ",\"--mode\",\"queue\",\"--message-file\",\"-\"], {stdin: "
        + js(message)
        + "});\nreturn {threadId: r.threadId, delivery: r.delivery};";
  }

  static String spawnSnippet(String prompt) {
    return "const t = await bb.json([\"thread\",\"spawn\",\"--project\","
        + js(PROJECT)
        + ",\"--title\","
        + js(CHIEF_TITLE)
        + ",\"--prompt-file\",\"-\"], {stdin: "
        + js(prompt)
        + "});\nreturn {id: t.id, title: t.title, status: t.status};";
  }

  static String showSnippet(String id) {
    return "const s = await bb.json([\"thread\",\"show\","
        + js(id)
        + "]);\nreturn {id: s.thread.id, title: s.thread.title || s.thread.titleFallback || \"\","
        + " status: s.thread.status, archived: !!s.thread.archivedAt};";
  }

  /** The curl line the chief uses to notify the user; $BB_THREAD_ID is its own thread. */
  static String notifyCommand(String server, String topic, String linkBase) {
    String click = linkBase.isEmpty() ? "" : " -H \"Click: " + linkBase + "/threads/$BB_THREAD_ID\"";
    return "curl -s -H \"Title: <short title>\""
        + click
        + " --data-binary @- "
        + server
        + "/"
        + topic
        + " <<'EOF'\n<one or two sentences: what's done, or what you need from me>\nEOF";
  }

  /** The ntfy tag that marks text meant for pasting, and the one that names its request. */
  static final String PASTE_TAG = "talkrouter-paste";

  static String requestTag(String request) {
    return "req-" + request;
  }

  /**
   * The curl line for text to paste: tagged for Talk Router, with the request ID, and a Copy
   * button whose broadcast Talk Router receives (ntfy's "broadcast" action).
   */
  static String pasteCommand(String server, String topic) {
    return "curl -s -H \"Title: Text to paste\" -H \"Tags: "
        + PASTE_TAG
        + ","
        + requestTag("<request ID>")
        + "\" -H \"Actions: broadcast, Copy, intent="
        + TalkAccessibility.COPY_ACTION
        + ", extras.request=<request ID>, clear=true\" --data-binary @- "
        + server
        + "/"
        + topic
        + " <<'EOF'\n<only the text to paste>\nEOF";
  }

  /** How to send text back for pasting; part of the standing instructions. */
  static String pasteInstructions(String server, String topic) {
    return "Each message from Talk Router ends with a request ID, like [request: k3x9qa]. When a"
        + " request asks for a text I'll paste somewhere (a message, an email, a reply, a"
        + " command), send that text, and nothing else, as soon as it's ready, with:\n\n"
        + pasteCommand(server, topic)
        + "\n\n  Talk Router shows it with an Insert button. Then finish as usual, including the"
        + " notification.";
  }

  /** The chief's standing instructions, its first message. */
  static String chiefPrompt(String server, String topic, String linkBase) {
    return "You are my chief of staff. I talk to you by voice from Talk Router, an app on my phone:"
        + " every message in this thread is a dictation, written down by a speech model (expect the"
        + " odd misheard word), sometimes followed by text I pasted as context.\n\n"
        + "For each request:\n"
        + "- Do small things yourself. Hand bigger or parallel work to child threads with"
        + " `bb thread spawn --parent-self …` (see `bb guide`), give them a clear brief, and follow"
        + " up until the whole request is done. You'll hear when a child finishes.\n"
        + "- If a request is unclear or needs my decision, ask me instead of guessing.\n"
        + "- Reply in the language I used. Lead your final reply with the result; I read it on my"
        + " phone.\n"
        + "- Put text meant for me to paste (a message, an email, a command) in its own fenced"
        + " code block, with nothing else in the block.\n"
        + "- When a request is done, or when you need me, send exactly one notification, then"
        + " end your turn:\n\n"
        + notifyCommand(server, topic, linkBase)
        + "\n\n"
        + "  Plain text, no secrets. No notification for intermediate steps.\n"
        + "- "
        + pasteInstructions(server, topic)
        + "\n\n"
        + "Now reply with one short line saying you're ready. Don't run anything and don't send a"
        + " notification for this message.";
  }

  /**
   * What the chief is told when the notification settings change, and what an existing thread is
   * told when it becomes the chief.
   */
  static String settingsMessage(String server, String topic, String linkBase) {
    return "Talk Router, the voice app on my phone, sends my spoken requests to this thread: you"
        + " are my chief of staff. When a request is done, or when you need me, send exactly one"
        + " notification, from now on like this:\n\n"
        + notifyCommand(server, topic, linkBase)
        + "\n\n"
        + pasteInstructions(server, topic)
        + "\n\nReply with one short line. Don't send a notification for this message.";
  }

  /** The ntfy app's subscribe link: ntfy://<host>/<topic> (secure=false for plain HTTP). */
  static String ntfyLink(String server, String topic) {
    String host = server.replaceFirst("^[a-zA-Z]+://", "").replaceAll("/+$", "");
    return "ntfy://" + host + "/" + topic + (server.startsWith("http://") ? "?secure=false" : "");
  }

  /**
   * The message for the chief: the dictation as said, then pasted text as context. Images and
   * PDFs aren't sent in this version; the chief hears that they existed.
   */
  static String message(String said, List<String[]> pasted, List<String> notSent, String request) {
    StringBuilder s = new StringBuilder(said.trim());
    for (String[] p : pasted)
      s.append("\n\n<pasted name=\"")
          .append(p[0].replace("\"", "'"))
          .append("\">\n")
          .append(p[1].trim())
          .append("\n</pasted>");
    if (!notSent.isEmpty())
      s.append("\n\n(I also attached ")
          .append(String.join(", ", notSent))
          .append(", which Talk Router can't pass on yet.)");
    if (!request.isEmpty()) s.append("\n\n[request: ").append(request).append("]");
    return s.toString();
  }

  // ------------------------------------------------------------------ operations

  /** Checks that bb answers and offers the run tool. */
  void test() throws Exception {
    call(
        c -> {
          for (Mcp.Tool t : c.tools()) if (t.name().equals("run")) return null;
          throw new IOException(tr("bb answers, but it doesn't offer the run tool. Update bb and check that the MCP plugin is on."));
        });
  }

  /** Creates the chief of staff in Personal, with its standing instructions, and keeps it. */
  ThreadInfo createChief() throws Exception {
    JSONObject r = run(spawnSnippet(chiefPrompt(ntfyServer(), ntfyTopic(), linkBase())));
    ThreadInfo t = new ThreadInfo(r.optString("id"), r.optString("title", CHIEF_TITLE), r.optString("status"));
    if (t.id().isEmpty()) throw new IOException(tr("bb didn't say which thread it created."));
    setChief(t);
    return t;
  }

  /** Posts a test notification to the topic, as the chief would. */
  void ntfyTest() throws Exception {
    String url = ntfyServer() + "/" + ntfyTopic();
    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
    try {
      c.setConnectTimeout(15_000);
      c.setReadTimeout(20_000);
      c.setRequestMethod("POST");
      c.setRequestProperty("Title", "Talk Router");
      byte[] body =
          tr("Test from Talk Router. Your chief of staff's notifications arrive like this.")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      c.setDoOutput(true);
      c.setFixedLengthStreamingMode(body.length);
      try (java.io.OutputStream out = c.getOutputStream()) {
        out.write(body);
      }
      int status = c.getResponseCode();
      if (status >= 300) throw new IOException(tr("The ntfy server answered with HTTP %1$d.", status));
    } catch (java.net.UnknownHostException | java.net.ConnectException ex) {
      throw new IOException(tr("Could not reach %1$s. Check the address and your connection.", ntfyServer()), ex);
    } catch (java.net.SocketTimeoutException ex) {
      throw new IOException(tr("%1$s didn't answer in time. Try again.", ntfyServer()), ex);
    } finally {
      c.disconnect();
    }
  }

  /**
   * Uses an existing bb thread as the chief of staff, after checking it exists, and tells it where
   * notifications go (a thread from an earlier install knows an old topic).
   */
  ThreadInfo useExistingChief(String idOrLink) throws Exception {
    String id = threadId(idOrLink);
    if (id.isEmpty())
      throw new IOException(tr("That isn't a bb thread link or id (it starts with thr_)."));
    JSONObject r = run(showSnippet(id));
    if (r.optBoolean("archived"))
      throw new IOException(tr("That thread is archived in bb. Unarchive it or pick another."));
    ThreadInfo t = new ThreadInfo(r.optString("id", id), r.optString("title"), r.optString("status"));
    send(t.id(), settingsMessage(ntfyServer(), ntfyTopic(), linkBase()));
    setChief(t);
    return t;
  }

  /** Sends a message to the chief: queued behind a turn that is running, else it starts one. */
  void send(String chiefId, String message) throws Exception {
    run(tellSnippet(chiefId, message));
  }

  /**
   * Sends a request: a chief from before version 2.2 is first told (once) how to send text back
   * for pasting, then gets the request.
   */
  void sendRequest(String chiefId, String message) throws Exception {
    if (!prefs.getBoolean(CHIEF_KNOWS_PASTE, false)) {
      send(chiefId, settingsMessage(ntfyServer(), ntfyTopic(), linkBase()));
      prefs.edit().putBoolean(CHIEF_KNOWS_PASTE, true).apply();
    }
    send(chiefId, message);
  }

  /** Tells the chief where notifications go now (after the ntfy server or links changed). */
  void updateChief() throws Exception {
    if (chiefId().isEmpty()) return;
    send(chiefId(), settingsMessage(ntfyServer(), ntfyTopic(), linkBase()));
    prefs.edit().putBoolean(CHIEF_KNOWS_PASTE, true).apply();
  }

  String threadLink(String id) {
    return threadLink(linkBase(), id);
  }

  // ------------------------------------------------------------------ transport

  private interface Call<T> {
    T with(Mcp.Connection c) throws Exception;
  }

  /** Runs a snippet with the run tool; its returned value as JSON. */
  JSONObject run(String code) throws Exception {
    return call(
        c -> {
          JSONObject result = c.callResult("run", new JSONObject().put("code", code));
          String text = Mcp.Connection.text(result).trim();
          if (result.optBoolean("isError")) {
            if (text.length() > 400) text = text.substring(0, 400) + "…";
            throw new IOException(tr("bb couldn't do it: %1$s", text.isEmpty() ? tr("no details") : text));
          }
          try {
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
          } catch (Exception ex) {
            return new JSONObject().put("text", text);
          }
        });
  }

  /**
   * One MCP session. With bb Connect, an expired or refused token is refreshed once and the call
   * tried again. Every failure comes back as a plain IOException with a readable message.
   */
  private <T> T call(Call<T> work) throws Exception {
    String url = url();
    if (!connected() || url.isEmpty()) throw new IOException(tr("bb isn't connected. Connect it in Settings → bb."));
    boolean connect = mode().equals(CONNECT);
    try {
      for (int attempt = 0; ; attempt++) {
        String token = connect ? BbOAuth.accessToken(context, attempt > 0) : customToken();
        Mcp.Connection c = new Mcp.Connection(new Mcp.Server("bb", url, token));
        try {
          c.open();
          return work.with(c);
        } catch (Mcp.McpException ex) {
          if (ex.status == 401 && connect && attempt == 0) continue;
          throw ex;
        }
      }
    } catch (Mcp.McpException ex) {
      throw new IOException(explain(ex, connect), ex);
    } catch (BbOAuth.SignedOut ex) {
      throw new IOException(ex.getMessage(), ex);
    } catch (BbOAuth.HttpError ex) {
      throw new IOException(ex.getMessage(), ex);
    } catch (IOException ex) {
      if (ex.getClass() == IOException.class && ex.getMessage() != null) throw ex;
      throw new IOException(tr("The connection to bb broke off. Try again."), ex);
    } catch (org.json.JSONException ex) {
      throw new IOException(tr("bb sent something unexpected. Update bb, then try again."), ex);
    }
  }

  private static String explain(Mcp.McpException ex, boolean connect) {
    return switch (ex.status) {
      case 0 -> ex.getMessage();
      case 401 ->
          connect
              ? tr("bb no longer accepts this sign-in. Sign in again in Settings → bb.")
              : tr("bb refused the token (HTTP 401). Check the token in Settings → bb.");
      case 403 -> tr("bb refused the request (HTTP 403). Is this address allowed to use bb's MCP server?");
      case 404 -> tr("No MCP server at this address (HTTP 404). Check the address, and that bb's MCP plugin is on.");
      case 502, 503, 504 ->
          tr("bb isn't reachable right now (HTTP %1$d). Is your computer on and bb running?", ex.status);
      default -> ex.getMessage();
    };
  }
}
