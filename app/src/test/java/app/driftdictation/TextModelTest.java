package app.driftdictation;

import static org.junit.Assert.*;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class TextModelTest {
  @Test
  public void refineWorksOutThePurposeFollowsInstructionsAndKeepsTheFacts() {
    String p = TextModel.DEFAULT_PROMPT;
    assertTrue(p.contains("Work out what the text is for"));
    assertTrue(p.contains("Follow any instructions in the dictation"));
    assertTrue(p.contains("Keep the user's language"));
    assertTrue(p.contains("Invent nothing"));
    assertTrue(p.contains("Reply with the finished text only"));
  }

  @Test
  public void theSystemPromptAddsTheTemplateAttachmentsAndTheUser() {
    String template = "\n\n" + Templates.INSTRUCTIONS + "\n\n<template name=\"Weekly update\">\nDone: …\n</template>";
    String s = TextModel.system("PROMPT", template, true, "Usually dictates: Emails.");
    assertTrue(s.startsWith("PROMPT\n\n" + Templates.INSTRUCTIONS));
    assertTrue(s.indexOf("<template") < s.indexOf(TextModel.ATTACHED.trim()));
    assertTrue(s.endsWith("\n\nAbout the user:\nUsually dictates: Emails."));
    assertEquals("PROMPT", TextModel.system("PROMPT", "", false, " "));
  }

  @Test
  public void thePickerOffersTextModelsTheDefaultFirst() throws Exception {
    JSONObject catalog =
        new JSONObject(
            "{\"data\": ["
                + "{\"id\": \"z/zeta\", \"name\": \"Zeta\", \"pricing\": {\"prompt\": \"0.000001\", \"completion\": \"0.000002\"},"
                + " \"architecture\": {\"input_modalities\": [\"text\"], \"output_modalities\": [\"text\"]}},"
                + "{\"id\": \"img/maker\", \"name\": \"Maker\", \"pricing\": {\"prompt\": \"0\", \"completion\": \"0\"},"
                + " \"architecture\": {\"input_modalities\": [\"text\"], \"output_modalities\": [\"image\"]}},"
                + "{\"id\": \"a/alpha\", \"name\": \"Alpha\", \"pricing\": {\"prompt\": \"0.0000005\", \"completion\": \"0.0000015\"},"
                + " \"architecture\": {\"input_modalities\": [\"text\", \"image\"], \"output_modalities\": [\"text\"]}},"
                + "{\"id\": \"anthropic/claude-sonnet-5.5\", \"name\": \"Claude Sonnet 5.5\", \"pricing\": {\"prompt\": \"0.000002\", \"completion\": \"0.00001\"},"
                + " \"architecture\": {\"input_modalities\": [\"text\"], \"output_modalities\": [\"text\"]}},"
                + "{\"id\": \"no/arch\", \"name\": \"No arch\"}]}");
    List<JSONObject> list = TextModel.catalog(catalog);
    assertEquals(3, list.size());
    assertEquals(TextModel.MODEL, list.get(0).getString("id"));
    assertEquals("a/alpha", list.get(1).getString("id"));
    assertEquals("z/zeta", list.get(2).getString("id"));
    assertEquals("$2.00 / $10.00", TextModel.price(list.get(0)));
    assertEquals("$0.50 / $1.50", TextModel.price(list.get(1)));
    assertEquals("", TextModel.price(new JSONObject()));
  }
}
