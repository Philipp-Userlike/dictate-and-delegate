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

  @Test
  public void aNoteIsCompleteOnceItsFirstVersionIsRead() throws Exception {
    // Newest rows first: the redo of "a" is read before "a" itself.
    HistoryEntry redo = version("b", "a", 300, "smarter take");
    HistoryEntry newer = version("c", "c", 200, "newer note");
    HistoryEntry first = version("a", "a", 100, "first take");
    List<NoteList.Note> partial = NoteList.complete(NoteList.group(List.of(redo, newer)));
    assertEquals(1, partial.size());
    assertEquals("c", partial.get(0).id());
    List<NoteList.Note> all = NoteList.complete(NoteList.group(List.of(redo, newer, first)));
    assertEquals(List.of("c", "a"), all.stream().map(NoteList.Note::id).toList());
    assertEquals(2, all.get(1).versions().size());
  }

  @Test
  public void aCallsMessagesAreOneEntryWithTheirTotalCost() throws Exception {
    HistoryEntry before = version("a", "a", 100, "a dictation");
    HistoryEntry t1 = version("b", "b", 200, "first message");
    HistoryEntry t2 = version("c", "c", 300, "second message");
    HistoryEntry after = version("d", "d", 400, "another dictation");
    t1.json.put("call", "call1");
    t2.json.put("call", "call1").put("knownCost", "0.0021"); // Transcription and voice.
    List<Object> items = NoteList.withCalls(NoteList.group(List.of(after, t2, t1, before)));
    assertEquals(3, items.size());
    assertEquals("d", ((NoteList.Note) items.get(0)).id());
    NoteList.Call call = (NoteList.Call) items.get(1);
    assertEquals(List.of("c", "b"), call.turns().stream().map(NoteList.Note::id).toList());
    assertEquals(0, call.cost().compareTo(new java.math.BigDecimal("0.0031")));
    assertEquals(200, call.created());
    assertEquals(300, call.latest());
    assertEquals(10, call.duration(), 0.001);
    assertEquals("a", ((NoteList.Note) items.get(2)).id());
  }
}
