package app.driftdictation;

import static org.junit.Assert.*;

import org.junit.Test;

public class TalkAccessibilityTest {
  @Test
  public void textGoesAtTheCursorOrReplacesTheSelection() {
    assertEquals("Dear Jana, see you thanks.", TalkAccessibility.spliced("Dear Jana, thanks.", 11, 11, "see you "));
    assertEquals("Dear Tom, thanks.", TalkAccessibility.spliced("Dear Jana, thanks.", 5, 9, "Tom"));
    assertEquals("hi", TalkAccessibility.spliced("", 0, 0, "hi"));
  }

  @Test
  public void insertedTextIsFoundWhereItWasOrWhereItMovedIfItIsUnique() {
    assertEquals(11, TalkAccessibility.locate("Dear Jana, um see you thanks.", "um see you", 11));
    // Typed before it: it moved, and it's the only one.
    assertEquals(17, TalkAccessibility.locate("Hello Dear Jana, um see you", "um see you", 11));
    // Changed, or there twice: not replaced.
    assertEquals(-1, TalkAccessibility.locate("Dear Jana, um see you soon", "um see you there", 11));
    assertEquals(-1, TalkAccessibility.locate("ab ab", "ab", 7));
    // Web editors turn edge spaces into non-breaking ones.
    assertEquals(9, TalkAccessibility.locate("Rich text PASTED", " PASTED", 9));
  }

  @Test
  public void aPlaceholderReportedAsTheFieldsTextCountsAsEmpty() {
    String bb = "Ask for a follow-up. @ to mention files, folders, sections, or threads";
    // The bb app's editor in Chrome: its placeholder comes as the text, and as its label.
    assertEquals("", TalkAccessibility.own(bb, false, "", bb));
    assertEquals("", TalkAccessibility.own(bb + "\u200b", false, bb, ""));
    assertEquals("", TalkAccessibility.own("Message…", false, "Message", ""));
    assertEquals("", TalkAccessibility.own("anything", true));
    // Real text stays, also with a placeholder around.
    assertEquals("I mean, I don't know", TalkAccessibility.own("I mean, I don't know", false, bb, bb));
    assertEquals("Dear Jana,", TalkAccessibility.own("Dear Jana,", false, "", ""));
    assertEquals("", TalkAccessibility.own("", false, bb));
  }
}
