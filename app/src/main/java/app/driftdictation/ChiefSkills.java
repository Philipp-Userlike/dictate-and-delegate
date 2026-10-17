package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * The chief's instructions as two bb user skills, chief-of-staff and talk-router. Their templates
 * ship in assets/skills; Talk Router fills in the user's name, the ntfy server and topic and the bb
 * link base. Each filled file ends with a marker holding a hash of its content, e.g.
 * {@code <!-- talk-router-skill: 3f2a9c1b04de -->}: a changed template (an app update) or a changed
 * placeholder (topic, server, link, name) gives another hash, so one comparison says whether bb has
 * the current version.
 *
 * <p>Talk Router reads the installed markers through bb's MCP "read" tool. When one is missing or
 * different, a short-lived bb thread writes both files to ~/.bb/skills (MCP snippets can't write
 * files), Talk Router waits until bb lists the new versions, then archives that thread.
 */
final class ChiefSkills {
  private ChiefSkills() {}

  static final String CHIEF = "chief-of-staff", ROUTER = "talk-router";
  static final String[] NAMES = {CHIEF, ROUTER};

  static final String INSTALL_TITLE = "Talk Router: install skills";

  /** The line a chief gets when it's created or adopted, and an older chief once. */
  static final String START = "You're my chief of staff. Use the chief-of-staff and talk-router skills.";

  /** Prefs: the user's name; the hashes installed last time; the app version that checked. */
  static final String USER_NAME = "bbUserName", INSTALLED = "skillsInstalled", CHECKED_VERSION = "skillsCheckedVersion";

  /** Prefs: the chief was told to use the skills (new chiefs at once, older ones after install). */
  static final String CHIEF_TOLD = "chiefToldSkills";

  // ------------------------------------------------------------------ filling (unit-tested)

  /**
   * A template with its placeholders filled and its marker appended. Without a name, "{{NAME}}"
   * reads "the user"; without a link base, the notification has no Click header.
   */
  static String fill(String skill, String template, String name, String server, String topic, String linkBase) {
    String n = name == null ? "" : name.trim();
    String t = template;
    if (n.isEmpty()) t = t.replace("{{NAME}}'s", "the user's").replace("{{NAME}}", "the user");
    else t = t.replace("{{NAME}}", n);
    if (linkBase.isEmpty()) t = t.replace(" -H \"Click: {{LINK_BASE}}/threads/$BB_THREAD_ID\"", "");
    t = t.replace("{{LINK_BASE}}", linkBase).replace("{{NTFY_SERVER}}", server).replace("{{TOPIC}}", topic);
    if (!t.endsWith("\n")) t += "\n";
    return t + "\n<!-- " + skill + "-skill: " + hash(t) + " -->\n";
  }

  /** The first 12 hex digits of the SHA-256 of a filled file (without its marker). */
  static String hash(String filled) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(filled.getBytes(StandardCharsets.UTF_8));
      StringBuilder s = new StringBuilder();
      for (int i = 0; i < 6; i++) s.append(String.format("%02x", d[i]));
      return s.toString();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static final Pattern MARKER = Pattern.compile("<!-- ([a-z-]+)-skill: ([0-9a-f]+) -->");

  /** The hash in a filled file's marker; "" without one. */
  static String marker(String content) {
    Matcher m = MARKER.matcher(content);
    return m.find() ? m.group(2) : "";
  }

  /** The skills bb should have: name → filled file. */
  static Map<String, String> filled(Context context) throws Exception {
    BbBridge bb = new BbBridge(context);
    SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    Map<String, String> files = new LinkedHashMap<>();
    for (String name : NAMES)
      files.put(
          name,
          fill(name, template(context, name), prefs.getString(USER_NAME, ""), bb.ntfyServer(), bb.ntfyTopic(), bb.linkBase()));
    return files;
  }

  static String template(Context context, String name) throws IOException {
    try (InputStream in = context.getAssets().open("skills/" + name + "/SKILL.md")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** "chief-of-staff:<hash>,talk-router:<hash>": what's installed when all is current. */
  static String summary(Map<String, String> files) {
    StringBuilder s = new StringBuilder();
    for (Map.Entry<String, String> e : files.entrySet())
      s.append(s.length() == 0 ? "" : ",").append(e.getKey()).append(':').append(marker(e.getValue()));
    return s.toString();
  }

  // ------------------------------------------------------------------ bb snippets

  /** Reads the markers of the installed user skills: {"skills": {name: hash, "" missing, "old" unmarked}}. */
  static String checkSnippet() {
    return "const l = await bb.json([\"skill\",\"list\"]);\n"
        + "const out = {};\n"
        + "for (const name of [\"" + CHIEF + "\",\"" + ROUTER + "\"]) {\n"
        + "  const s = (l.skills || []).find(x => x.name === name && x.scope === \"bb-user\");\n"
        + "  if (!s) { out[name] = \"\"; continue; }\n"
        + "  const f = await bb.json([\"skill\",\"show\",s.id]);\n"
        + "  const m = /<!-- [a-z-]+-skill: ([0-9a-f]+) -->/.exec(f.content || \"\");\n"
        + "  out[name] = m ? m[1] : \"old\";\n"
        + "}\n"
        + "return {skills: out};";
  }

  /** The install thread's brief: both files verbatim, where they go, and nothing else. */
  static String installPrompt(Map<String, String> files) {
    StringBuilder s =
        new StringBuilder(
            "Install two bb user skills for Talk Router. Write each file below exactly as given,"
                + " byte for byte, creating the folders and overwriting what's there:\n\n");
    for (Map.Entry<String, String> e : files.entrySet())
      s.append("- `~/.bb/skills/").append(e.getKey()).append("/SKILL.md`\n");
    s.append(
        "\nThen check with `bb skill list` that both are listed, reply \"installed\" and end your"
            + " turn. Change nothing else, and send no notification.\n");
    for (Map.Entry<String, String> e : files.entrySet())
      s.append("\n===== ~/.bb/skills/")
          .append(e.getKey())
          .append("/SKILL.md (begins on the next line) =====\n")
          .append(e.getValue())
          .append("===== end of ")
          .append(e.getKey())
          .append(" =====\n");
    return s.toString();
  }

  static String spawnSnippet(String prompt) {
    return "const t = await bb.json([\"thread\",\"spawn\",\"--project\","
        + BbBridge.js(BbBridge.PROJECT)
        + ",\"--title\","
        + BbBridge.js(INSTALL_TITLE)
        + ",\"--prompt-file\",\"-\"], {stdin: "
        + BbBridge.js(prompt)
        + "});\nreturn {id: t.id, status: t.status};";
  }

  static String archiveSnippet(String id) {
    return "await bb.json([\"thread\",\"archive\"," + BbBridge.js(id) + "]);\nreturn {archived: true};";
  }

  /** Which skills differ from what bb has (missing, unmarked or another hash). */
  static List<String> outdated(Map<String, String> files, JSONObject installed) {
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, String> e : files.entrySet())
      if (!marker(e.getValue()).equals(installed.optString(e.getKey()))) out.add(e.getKey());
    return out;
  }

  // ------------------------------------------------------------------ keeping bb current

  /** Where the install stands, for setup and Settings → bb. */
  enum State {
    IDLE,
    CHECKING,
    INSTALLING,
    INSTALLED,
    FAILED
  }

  static volatile State state = State.IDLE;
  static volatile String problem = "";
  private static volatile boolean running;

  /** How long the app waits for the install thread, polling every few seconds. */
  static long installWaitMs = 4 * 60_000, pollMs = 3000;

  /**
   * Brings bb's skills up to date in the background. Without force it only asks bb when something
   * changed since the last install: the filled files (template, name, topic, server, link) or the
   * app version.
   */
  static synchronized void sync(Context context, boolean force) {
    Context app = context.getApplicationContext();
    if (running || !new BbBridge(app).connected()) return;
    SharedPreferences prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    Map<String, String> files;
    try {
      files = filled(app);
    } catch (Exception ex) {
      return;
    }
    boolean same =
        summary(files).equals(prefs.getString(INSTALLED, ""))
            && prefs.getInt(CHECKED_VERSION, 0) == BuildConfig.VERSION_CODE;
    if (same && !force) {
      state = State.INSTALLED;
      return;
    }
    running = true;
    new Thread(
            () -> {
              try {
                ensure(app, files);
                prefs.edit().putString(INSTALLED, summary(files)).putInt(CHECKED_VERSION, BuildConfig.VERSION_CODE).apply();
                state = State.INSTALLED;
                problem = "";
                tellOlderChief(app);
              } catch (Exception ex) {
                problem = DictationService.friendly(ex);
                state = State.FAILED;
              } finally {
                running = false;
              }
            },
            "chief-skills")
        .start();
  }

  /** Checks bb and, if needed, installs through a thread and waits for it. Blocks. */
  static void ensure(Context app, Map<String, String> files) throws Exception {
    BbBridge bb = new BbBridge(app);
    state = State.CHECKING;
    JSONObject installed = bb.read(checkSnippet()).optJSONObject("skills");
    List<String> outdated = outdated(files, installed == null ? new JSONObject() : installed);
    if (outdated.isEmpty()) return;
    state = State.INSTALLING;
    String id = bb.run(spawnSnippet(installPrompt(files))).optString("id");
    if (id.isEmpty()) throw new IOException(tr("bb didn't say which thread it created."));
    try {
      long until = SystemClock.elapsedRealtime() + installWaitMs;
      while (true) {
        Thread.sleep(pollMs);
        JSONObject skills = bb.read(checkSnippet()).optJSONObject("skills");
        if (skills != null && outdated(files, skills).isEmpty()) return;
        if (SystemClock.elapsedRealtime() > until)
          throw new IOException(tr("bb didn't install the chief's skills in time. Try again."));
      }
    } finally {
      try {
        bb.run(archiveSnippet(id));
      } catch (Exception ignored) {
        /* The thread stays; it did its job or failed. */
      }
    }
  }

  /** A chief from before the skills gets the one line once, now that they're there. */
  static void tellOlderChief(Context app) throws Exception {
    BbBridge bb = new BbBridge(app);
    SharedPreferences prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    if (bb.chiefId().isEmpty() || prefs.getBoolean(CHIEF_TOLD, false)) return;
    bb.send(bb.chiefId(), START);
    prefs.edit().putBoolean(CHIEF_TOLD, true).apply();
  }
}
