package app.driftdictation;

import static org.junit.Assert.*;

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
}
