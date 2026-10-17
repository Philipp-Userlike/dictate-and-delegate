package app.driftdictation;

import android.content.Context;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The request check: after transcribing, a fast model decides whether the dictation asks Drift for
 * anything. Nothing asked: the transcript is the note, unchanged. A request (tidy up, more formal,
 * translate, a template, a reply, a draft) goes to the text model. Saying so decides: "just a
 * transcript", "clean this up", "use my template".
 */
final class Router {
  /** DeepSeek V4.1 Flash: 20 of 20 right on Drift's routing examples, about 0.7 s (2026-10-14). */
  static final String MODEL = "deepseek/deepseek-v4.1-flash", NAME = "DeepSeek V4.1 Flash";

  static final int MAX_TOKENS = 200;

  /**
   * Where a dictation goes. Another destination (an agent, say) is one more constant here, a line
   * in the prompt and in parse(), and a branch in DictationService.process().
   */
  static final String TRANSCRIPT = "transcript", TEXT = "text";

  record Route(String kind, String template, OpenRouter.Result result) {}

  static final String PROMPT =
      "You route dictations inside Drift, a dictation app. The user spoke; a speech model wrote it"
          + " down. Decide whether the dictation asks Drift for anything and reply with JSON only:"
          + " {\"route\": \"...\", \"template\": \"...\"}.\n\n"
          + "Routes:\n"
          + "- \"transcript\": the user just dictated text to paste (a message, a note, an email"
          + " body) and says nothing to Drift. The transcript is used exactly as written. This is"
          + " the default.\n"
          + "- \"text\": the user says something to Drift: tidy up or remove filler, fix their own"
          + " corrections, change tone or format (more formal, bullet points, shorter), translate,"
          + " fill in one of their templates, reply to something, write or draft something from a"
          + " brief, answer a question, summarise, keep it word for word (\"just a transcript\"),"
          + " or other remarks meant for Drift (\"leave out the ums\", \"make that an email\").\n\n"
          + "Text that only talks about something (\"please call me back\", \"write me a poem\""
          + " said to a friend) is part of the message, not a request to Drift. If in doubt,"
          + " choose \"transcript\".\n\n"
          + "\"template\": the name of the user's template the dictation asks to use, exactly as"
          + " listed below, otherwise \"\". Asking for a template means \"text\".\n"
          + "Attachments (pasted text, images, files) are context for a request; they don't make"
          + " a request by themselves.";

  static Route route(Context context, String said, List<String> templateNames, String attached)
      throws Exception {
    String system =
        PROMPT
            + "\n\nThe user's templates: "
            + (templateNames.isEmpty() ? "none" : String.join(", ", templateNames))
            + (attached.isBlank() ? "" : "\n\n" + attached);
    JSONArray messages =
        new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", system))
            .put(new JSONObject().put("role", "user").put("content", said));
    OpenRouter.Result r =
        new OpenRouter(context).chat(OpenRouter.chatBody(MODEL, messages, MAX_TOKENS, true));
    return parse(r.text(), templateNames, r);
  }

  /**
   * Anything unreadable counts as "nothing asked": the transcript stays as it is. "simple" and
   * "smart" are the routes of earlier versions; both meant the text model.
   */
  static Route parse(String answer, List<String> templateNames, OpenRouter.Result result) {
    Matcher m = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(answer == null ? "" : answer);
    String kind = TRANSCRIPT, template = "";
    if (m.find()) {
      try {
        JSONObject o = new JSONObject(m.group());
        String r = o.optString("route");
        if (r.equals(TEXT) || r.equals("simple") || r.equals("smart")) kind = TEXT;
        String t = o.optString("template").trim();
        for (String name : templateNames) if (name.equalsIgnoreCase(t)) template = name;
      } catch (Exception ignored) {
        /* Treated as no request. */
      }
    }
    if (!template.isEmpty()) kind = TEXT;
    return new Route(kind, template, result);
  }
}
