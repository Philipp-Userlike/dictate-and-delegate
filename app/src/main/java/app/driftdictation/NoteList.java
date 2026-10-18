package app.driftdictation;

import java.math.BigDecimal;
import java.util.*;

/** History rows are versions; a note is one recording, shown as the version you kept. */
final class NoteList {
  record Note(HistoryEntry shown, List<HistoryEntry> versions) {
    String id() {
      return shown.recordingId();
    }

    long created() {
      return versions.stream().mapToLong(HistoryEntry::created).min().orElse(shown.created());
    }

    BigDecimal cost() {
      BigDecimal total = BigDecimal.ZERO;
      for (HistoryEntry v : versions) total = total.add(v.cost());
      return total;
    }

    BigDecimal anthropicCost() {
      BigDecimal total = BigDecimal.ZERO;
      for (HistoryEntry v : versions) total = total.add(v.anthropicCost());
      return total;
    }

    boolean incompleteCost() {
      return versions.stream().anyMatch(HistoryEntry::incompleteCost);
    }
  }

  /** A hands-free call as one entry: its messages' notes, newest first. */
  record Call(String id, List<Note> turns) {
    /** When it started: its first message. */
    long created() {
      return turns.stream().mapToLong(Note::created).min().orElse(0);
    }

    /** Its newest message, where it sits in the list. */
    long latest() {
      return turns.get(0).created();
    }

    /** Transcription and voice, all its messages. */
    BigDecimal cost() {
      BigDecimal total = BigDecimal.ZERO;
      for (Note n : turns) total = total.add(n.cost());
      return total;
    }

    boolean incompleteCost() {
      return turns.stream().anyMatch(Note::incompleteCost);
    }

    /** Seconds you spoke. */
    double duration() {
      return turns.stream().mapToDouble(n -> n.shown().duration()).sum();
    }
  }

  /**
   * Notes and calls, newest first: the notes of one hands-free call (same "call") become one
   * Call, where its newest message is.
   */
  static List<Object> withCalls(List<Note> notes) {
    Map<String, Call> calls = new HashMap<>();
    List<Object> out = new ArrayList<>();
    for (Note n : notes) {
      String id = n.shown().json.optString("call");
      if (id.isEmpty()) {
        out.add(n);
        continue;
      }
      Call call = calls.get(id);
      if (call == null) {
        call = new Call(id, new ArrayList<>());
        calls.put(id, call);
        out.add(call);
      }
      call.turns().add(n);
    }
    return out;
  }

  static List<Note> group(List<HistoryEntry> entries) {
    Map<String, List<HistoryEntry>> byRecording = new LinkedHashMap<>();
    for (HistoryEntry e : entries)
      byRecording.computeIfAbsent(e.recordingId(), k -> new ArrayList<>()).add(e);
    List<Note> notes = new ArrayList<>();
    for (List<HistoryEntry> versions : byRecording.values()) {
      HistoryEntry shown = null;
      for (HistoryEntry v : versions) if (v.json.optBoolean("preferred")) shown = v;
      if (shown == null)
        for (HistoryEntry v : versions)
          if (v.json.optString("status").equals("complete")
              && (shown == null || v.created() > shown.created())) shown = v;
      if (shown == null)
        shown = Collections.max(versions, Comparator.comparingLong(HistoryEntry::created));
      notes.add(new Note(shown, versions));
    }
    notes.sort(Comparator.comparingLong(Note::created).reversed());
    return notes;
  }

  /**
   * The notes whose first version is among the rows read so far. Rows are read newest first and
   * redone versions are newer than the first, so these notes have all their versions.
   */
  static List<Note> complete(List<Note> notes) {
    List<Note> result = new ArrayList<>();
    for (Note n : notes)
      if (n.versions().stream().anyMatch(v -> v.id().equals(n.id()))) result.add(n);
    return result;
  }

  static List<Note> search(List<Note> notes, String query) {
    String q = query.trim().toLowerCase(Locale.ROOT);
    if (q.isEmpty()) return notes;
    List<Note> result = new ArrayList<>();
    for (Note n : notes) if (n.shown().text().toLowerCase(Locale.ROOT).contains(q)) result.add(n);
    return result;
  }
}
