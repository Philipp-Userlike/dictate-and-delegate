package app.driftdictation;

import android.content.Context;
import java.math.BigDecimal;
import org.json.JSONArray;
import org.json.JSONObject;

/** Refine: one call to one fixed text model, through OpenRouter or, with an Anthropic key, Anthropic. */
final class TextModel {
  /** Claude Sonnet 5.5: careful with the user's words and quick enough to wait for. */
  static final String MODEL = "anthropic/claude-sonnet-5.5", NAME = "Claude Sonnet 5.5";

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
   * was the key or the credits, which the note warns about.
   */
  record Refined(String text, BigDecimal cost, boolean anthropic, String fallback, boolean keyProblem) {}

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
        OpenRouter.Result r =
            new Anthropic(app).messages(Anthropic.body(Anthropic.model(MODEL), system, user, MAX_TOKENS));
        return new Refined(r.text(), r.cost(), true, null, false);
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
    OpenRouter.Result r = new OpenRouter(app).chat(OpenRouter.chatBody(MODEL, messages, MAX_TOKENS, false));
    return new Refined(r.text(), r.cost(), false, fallback, keyProblem);
  }
}
