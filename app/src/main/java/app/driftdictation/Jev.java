package app.driftdictation;

import android.content.Context;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Jev, TypeSafe's decision model through OpenRouter (POST /api/alpha/decisions): after the
 * transcript, it answers up to three questions about the dictation, each with a probability. Talk
 * Router acts on its own only when the answer is clear; otherwise the panel's buttons are one tap
 * away. Jev never sees question names, so the instructions and criteria carry all the meaning;
 * scripts/qa/jev-check.py checks them on labelled dictations and reads them from this file.
 */
final class Jev {
  static final String MODEL = "~typesafe/jev-latest", NAME = "Jev";

  /** Slower than this and nothing happens by itself; the buttons still work. */
  static final int TIMEOUT_MS = 3000;

  /** Refine starts by itself at this probability (or when a template is named). */
  static final double REFINE_AT = 0.5;

  /** Sent to the chief by itself only this sure that it was addressed; never on a guess. */
  static final double CHIEF_AT = 0.9;

  /** Between this and CHIEF_AT the 🧑‍💼 button is highlighted as a hint, nothing is sent. */
  static final double HINT_AT = 0.5;

  static final String NONE = "none";

  static final String REFINE =
      "Does the speaker want this dictation turned into a finished text by an assistant, or"
          + " does it clearly need that before anyone can read it?";
  static final String REFINE_YES =
      "The speaker asks for a text to be written or changed (tidy this up, make it more formal,"
          + " turn it into an email, a list or a reply, translate it, shorten it, fill in a"
          + " template, draft something), or the dictation is a message, email or note so full of"
          + " filler words, false starts and self-corrections (\"no wait\", \"I mean\") that it"
          + " can't be sent as it is.";
  static final String REFINE_NO =
      "The speaker dictated something that reads fine as said: a message, note or to-do in"
          + " plain sentences. Asking the reader to write something (\"can you send me the"
          + " report\") is part of the message, and a task for someone else is not a request to"
          + " write a text.";

  static final String TEMPLATE =
      "Which of the speaker's saved templates does the dictation ask to use? The speaker names"
          + " it, for example \"use my apartment application\".";
  static final String TEMPLATE_NONE = "The dictation doesn't ask for any of the saved templates.";
  static final String TEMPLATE_OPTION = "The speaker asks to use their saved template \"%s\".";

  static final String CHIEF =
      "Does the speaker talk to their own chief of staff or AI agent and give it a task or a"
          + " question, for example \"chief of staff, …\", \"hey chief, …\", \"tell my chief of"
          + " staff to …\", \"ask my agent to …\", \"agent, …\" or \"let the agent handle"
          + " this\", in any language (German: \"Chief of Staff, …\", \"frag meinen Agenten …\")?";
  static final String CHIEF_YES =
      "The speaker addresses the chief of staff or their agent directly (\"chief, …\", \"hey"
          + " agent, …\") or tells someone to pass the task to it (\"tell my chief of staff\","
          + " \"send my agent …\"), and says what it should do or answer.";
  static final String CHIEF_NO =
      "Nobody is addressed as the chief of staff or agent: a plain instruction or to-do (\"look"
          + " into why the build failed\") is not an address, nor is a message to another person,"
          + " nor a mention of a chief of staff or agent who is not being asked to do something"
          + " (\"my chief of staff will send you the contract\", \"ask your agent\").";

  /** What Jev answered: a probability per noul (null when not asked), the template, the cost. */
  record Answers(Double refine, String template, Double chief, BigDecimal cost) {}

  /** What happens by itself (refine, send to the chief), the template for Refine, the hint. */
  record Decision(boolean refine, String template, boolean chief, boolean hint) {}

  /**
   * The request: the dictation as state, and only the questions that matter now: refine when
   * Refine may start by itself, template when there are templates (a tapped Refine uses it too),
   * chief when there is a chief to send to.
   */
  static JSONObject body(String dictation, List<String> templates, boolean refine, boolean chief)
      throws Exception {
    JSONObject questions = new JSONObject();
    if (refine) questions.put("refine", noul(REFINE, REFINE_YES, REFINE_NO));
    JSONObject options = new JSONObject().put(NONE, TEMPLATE_NONE);
    for (String name : templates)
      if (!name.equalsIgnoreCase(NONE)) options.put(name, String.format(Locale.ROOT, TEMPLATE_OPTION, name));
    if (options.length() > 1)
      questions.put(
          "template",
          new JSONObject().put("type", "choice").put("instructions", TEMPLATE).put("criteria", options));
    if (chief) questions.put("chief", noul(CHIEF, CHIEF_YES, CHIEF_NO));
    return new JSONObject()
        .put("model", MODEL)
        .put("state", new JSONObject().put("dictation", dictation))
        .put("questions", questions);
  }

  private static JSONObject noul(String instructions, String yes, String no) throws Exception {
    return new JSONObject()
        .put("type", "noul")
        .put("instructions", instructions)
        .put("criteria", new JSONObject().put("true", yes).put("false", no));
  }

  /** Reads the answers; anything missing or unreadable counts as not asked. */
  static Answers parse(JSONObject response, List<String> templates) {
    JSONObject answers = response.optJSONObject("answers");
    if (answers == null) answers = new JSONObject();
    String template = "";
    JSONObject t = answers.optJSONObject("template");
    if (t != null)
      for (String name : templates)
        if (name.equals(t.optString("choice"))) template = name;
    return new Answers(
        probability(answers.optJSONObject("refine")),
        template,
        probability(answers.optJSONObject("chief")),
        OpenRouter.cost(response));
  }

  private static Double probability(JSONObject answer) {
    if (answer == null || !(answer.opt("noul") instanceof Number n)) return null;
    double p = n.doubleValue();
    return Double.isFinite(p) ? Math.max(0, Math.min(1, p)) : null;
  }

  /**
   * autoRefine and autoChief are the two switches in Settings → Refine; chiefReady: bb is
   * connected and a chief is set. The chief is never sent to below CHIEF_AT.
   */
  static Decision decide(Answers a, boolean autoRefine, boolean autoChief, boolean chiefReady) {
    if (a == null) return new Decision(false, "", false, false);
    boolean template = !a.template().isEmpty();
    boolean refine = autoRefine && (template || (a.refine() != null && a.refine() >= REFINE_AT));
    double c = a.chief() == null ? 0 : a.chief();
    boolean chief = chiefReady && autoChief && c >= CHIEF_AT;
    boolean hint = chiefReady && !chief && c >= HINT_AT;
    return new Decision(refine, a.template(), chief, hint);
  }

  /** For Activity: "refine 0.82 · chief 0.03 · Apartment application". */
  static String detail(Answers a) {
    StringBuilder s = new StringBuilder();
    if (a.refine() != null) s.append(String.format(Locale.ROOT, "refine %.2f", a.refine()));
    if (a.chief() != null)
      s.append(s.length() > 0 ? " · " : "").append(String.format(Locale.ROOT, "chief %.2f", a.chief()));
    if (!a.template().isEmpty()) s.append(s.length() > 0 ? " · " : "").append(a.template());
    return s.toString();
  }

  /** Asks Jev; throws when it fails or takes longer than TIMEOUT_MS. */
  static Answers ask(Context context, String dictation, List<String> templates, boolean refine, boolean chief)
      throws Exception {
    JSONObject r = new OpenRouter(context).decide(body(dictation, templates, refine, chief), TIMEOUT_MS);
    return parse(r, templates);
  }
}
