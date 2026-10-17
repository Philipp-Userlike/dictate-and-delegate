package app.driftdictation;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class NoteListTest {
  HistoryEntry version(String id, String recording, long created, String text) throws Exception {
    HistoryEntry e = HistoryEntry.create(id, "m", 5);
    e.json.put("recordingId", recording).put("created", created).put("text", text);
    e.json.put("status", "complete").put("knownCost", "0.001").put("requests", 1);
    return e;
  }

  @Test
  public void showsTheKeptVersionAndSumsAllVersionCosts() throws Exception {
    HistoryEntry first = version("a", "a", 100, "first take");
    HistoryEntry redo = version("b", "a", 200, "smarter take");
    HistoryEntry other = version("c", "c", 50, "older note");
    List<NoteList.Note> notes = NoteList.group(List.of(redo, first, other));
    assertEquals(2, notes.size());
    assertEquals("smarter take", notes.get(0).shown().text());
    assertEquals(0, notes.get(0).cost().compareTo(new java.math.BigDecimal("0.002")));
    first.json.put("preferred", true);
    assertEquals("first take", NoteList.group(List.of(redo, first)).get(0).shown().text());
    assertEquals(1, NoteList.search(notes, "OLDER").size());
  }
}
