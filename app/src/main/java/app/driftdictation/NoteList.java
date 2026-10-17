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

  static List<Note> search(List<Note> notes, String query) {
    String q = query.trim().toLowerCase(Locale.ROOT);
    if (q.isEmpty()) return notes;
    List<Note> result = new ArrayList<>();
    for (Note n : notes) if (n.shown().text().toLowerCase(Locale.ROOT).contains(q)) result.add(n);
    return result;
  }
}
