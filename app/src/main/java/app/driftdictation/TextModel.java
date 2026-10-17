package app.driftdictation;

import android.content.Context;
import android.content.SharedPreferences;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Refine: one call to the chosen text model, through OpenRouter or, with an Anthropic key, Anthropic. */
final class TextModel {
  /** The default, Claude Sonnet 5.5: careful with the user's words and quick enough to wait for. */
  static final String MODEL = "anthropic/claude-sonnet-5.5", NAME = "Claude Sonnet 5.5";

  /** Settings: the OpenRouter model and its name, and the Claude model on the user's Anthropic key. */
  static final String PREF_MODEL = "refineModel", PREF_NAME = "refineModelName", PREF_CLAUDE = "refineClaude";

  private static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("settings", 0);
  }

  /** The OpenRouter model Refine uses without an Anthropic key, and when Anthropic fails. */
  static String model(Context c) {
    return prefs(c).getString(PREF_MODEL, MODEL);
  }

  static String modelName(Context c) {
    return prefs(c).getString(PREF_NAME, NAME);
  }

  /** The Claude model Refine uses with an Anthropic key; Sonnet 5.5 unless another was picked. */
  static Anthropic.Claude claude(Context c) {
    Anthropic.Claude chosen = Anthropic.claude(prefs(c).getString(PREF_CLAUDE, ""));
    return chosen != null ? chosen : Anthropic.MODELS.get(0);
  }

  /** The name of the model Refine calls first. */
  static String name(Context c) {
    return Anthropic.hasKey(c) ? claude(c).name() : modelName(c);
  }

  /**
   * The text models in OpenRouter's catalog (GET /models): text in, text only out, with a price.
   * The default first, then by name.
   */
  static List<JSONObject> catalog(JSONObject response) {
    List<JSONObject> out = new ArrayList<>();
    JSONArray data = response.optJSONArray("data");
    for (int i = 0; data != null && i < data.length(); i++) {
      JSONObject m = data.optJSONObject(i);
      if (m == null || m.optString("id").isEmpty()) continue;
      JSONObject arch = m.optJSONObject("architecture");
      JSONArray in = arch == null ? null : arch.optJSONArray("input_modalities");
      JSONArray outputs = arch == null ? null : arch.optJSONArray("output_modalities");
      if (in == null || outputs == null || outputs.length() != 1) continue;
      if (!"text".equals(outputs.optString(0)) || !in.toString().contains("\"text\"")) continue;
      if (m.optJSONObject("pricing") == null) continue;
      out.add(m);
    }
    out.sort(
        (a, b) -> {
          boolean da = MODEL.equals(a.optString("id")), db = MODEL.equals(b.optString("id"));
          if (da != db) return da ? -1 : 1;
          return a.optString("name", a.optString("id")).compareToIgnoreCase(b.optString("name", b.optString("id")));
        });
    return out;
  }

  /** "$2.00 / $10.00" per million tokens in and out, from the catalog's per-token prices; "" if unknown. */
  static String price(JSONObject model) {
    JSONObject p = model.optJSONObject("pricing");
    if (p == null) return "";
    try {
      BigDecimal million = new BigDecimal(1_000_000);
      BigDecimal in = new BigDecimal(p.getString("prompt")).multiply(million);
      BigDecimal out = new BigDecimal(p.getString("completion")).multiply(million);
      if (in.signum() < 0 || out.signum() < 0) return "";
      return dollars(in) + " / " + dollars(out);
    } catch (Exception ex) {
      return "";
    }
  }

  static String dollars(BigDecimal perMillion) {
    return "$" + perMillion.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  static final int MAX_TOKENS = 4096;

  /**
   * Refine's instructions: work out what the text is for and make it that, follow what the
   * dictation asks, keep the language and the facts. The named template comes after (Templates).
   */
  static final String DEFAULT_PROMPT =
      "You refine dictations inside a dictation app. The user spoke; a speech model wrote it down"
          + " (expect the odd misheard word). Turn it into the text the user needs.\n\n"
          + "1. Work out what the text is for: a chat message, an email, a reply, a note, a list, a"
          + " post, a prompt for an AI, a document… Then make it a good text of that kind: remove"
          + " filler words, false starts and repetitions, apply the user's own corrections (\"no,"
          + " I mean Thursday\"), and fix grammar, punctuation and structure, in the tone that fits.\n"
          + "2. Follow any instructions in the dictation about the text (\"make it formal\", \"turn"
          + " it into bullet points\", \"translate to English\", \"reply to this offer\", \"write"
          + " a draft that…\"), and leave those instructions out of the result.\n"
          + "3. Keep the user's language unless asked otherwise, and keep their voice: a casual"
          + " message stays casual.\n"
          + "4. Keep every fact (names, numbers, dates, places) as said. Invent nothing; where a"
          + " draft needs details you don't have, keep it general instead of adding placeholders.\n\n"
          + "Reply with the finished text only, ready to paste: no preface, no quotes, no comments.";

  static final String ATTACHED =
      "\n\nThe user attached material to this dictation (pasted text, images or files). Use it"
          + " where the dictation refers to it, for example the offer to reply to.";

  /** The system prompt: instructions, the template asked for, attachments, about the user. */
  static String system(String prompt, String template, boolean attached, String context) {
    return prompt
        + template
        + (attached ? ATTACHED : "")
        + (context.isBlank() ? "" : "\n\nAbout the user:\n" + context);
  }

  /**
   * What Refine returned: the text, its cost, and who answered. anthropic: Anthropic directly,
   * with the user's Anthropic key (its cost computed at list price, paid from Anthropic credits).
   * fallback: why the Anthropic call failed when OpenRouter answered instead; keyProblem: that
   * was the key or the credits, which the note warns about. model: the ID that answered.
   */
  record Refined(
      String text, BigDecimal cost, boolean anthropic, String fallback, boolean keyProblem, String model) {}

  /**
   * template: Templates.prompt(), empty when none was named. attachments: message parts (pasted
   * text, images, PDFs) from Attachments.parts(), sent along with the dictation. With an Anthropic
   * key, Anthropic answers; if that fails, the same request goes through OpenRouter.
   */
  static Refined process(
      Context app,
      String text,
      JSONArray attachments,
      String prompt,
      String template,
      String context)
      throws Exception {
    Object user = text;
    if (attachments.length() > 0) {
      JSONArray parts = new JSONArray().put(new JSONObject().put("type", "text").put("text", text));
      for (int i = 0; i < attachments.length(); i++) parts.put(attachments.get(i));
      user = parts;
    }
    String system = system(prompt, template, attachments.length() > 0, context);
    String fallback = null;
    boolean keyProblem = false;
    if (Anthropic.hasKey(app)) {
      try {
        String claude = claude(app).id();
        OpenRouter.Result r = new Anthropic(app).messages(Anthropic.body(claude, system, user, MAX_TOKENS));
        return new Refined(r.text(), r.cost(), true, null, false, claude);
      } catch (Anthropic.Failure f) {
        fallback = f.getMessage();
        keyProblem = f.problem == Anthropic.Problem.KEY || f.problem == Anthropic.Problem.CREDITS;
      } catch (Exception ex) {
        fallback = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
      }
    }
    JSONArray messages =
        new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", system))
            .put(new JSONObject().put("role", "user").put("content", user));
    String model = model(app);
    OpenRouter.Result r = new OpenRouter(app).chat(OpenRouter.chatBody(model, messages, MAX_TOKENS, false));
    return new Refined(r.text(), r.cost(), false, fallback, keyProblem, model);
  }
}
