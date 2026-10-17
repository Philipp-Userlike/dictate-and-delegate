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
}
