package app.driftdictation;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** The text model that does what a dictation asks for: one fixed model, through OpenRouter. */
final class TextModel {
  /** Claude Sonnet 5.5: careful with the user's words and quick enough to wait for. */
  static final String MODEL = "anthropic/claude-sonnet-5.5", NAME = "Claude Sonnet 5.5";

  static final int MAX_TOKENS = 4096;

  /**
   * For dictations that ask Drift for something; the request check sends nothing else here.
   * Tested on tidy-ups, tone changes, templates, drafts and plain-transcript requests.
   */
  static final String DEFAULT_PROMPT =
      "You are used inside a dictation app. The following is what the user dictated, written down"
          + " by a speech model, and it asks you, the dictation handler, for something: for example"
          + " to tidy it up, make it more formal, turn it into an email or bullet points, translate"
          + " it, fill in a template, or write, answer or plan something. Do what is asked and"
          + " reply with the resulting text only, ready to paste. Leave out the remarks addressed"
          + " to you. Change only what the request covers; if the user asks for a plain"
          + " transcript, keep their words exactly. Keep the user's language unless asked"
          + " otherwise. Don't invent facts about the user; where a draft needs details you don't"
          + " have, keep it general instead of adding placeholders.";

  static final String ATTACHED =
      "\n\nThe user attached material to this dictation (pasted text, images or files). Use it"
          + " where the request refers to it, for example the offer to reply to.";

  /**
   * templates: Templates.prompt(), empty when none was asked for. attachments: message parts
   * (pasted text, images, PDFs) from Attachments.parts(), sent along with the dictation.
   */
  static OpenRouter.Result process(
      Context app,
      String text,
      JSONArray attachments,
      String prompt,
      String templates,
      String context)
      throws Exception {
    Object user = text;
    String system = prompt + templates;
    if (attachments.length() > 0) {
      JSONArray parts = new JSONArray().put(new JSONObject().put("type", "text").put("text", text));
      for (int i = 0; i < attachments.length(); i++) parts.put(attachments.get(i));
      user = parts;
      system += ATTACHED;
    }
    JSONArray messages =
        new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", system + background(context)))
            .put(new JSONObject().put("role", "user").put("content", user));
    return new OpenRouter(app).chat(OpenRouter.chatBody(MODEL, messages, MAX_TOKENS, false));
  }

  private static String background(String context) {
    return context.isBlank() ? "" : "\n\nAbout the user:\n" + context;
  }
}
