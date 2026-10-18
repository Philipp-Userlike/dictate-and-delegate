package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;
import org.json.JSONObject;

/**
 * SQLite rows have encrypted payloads; only IDs (the row's and its recording's) and dates remain
 * indexable. No automatic expiry.
 */
final class HistoryStore extends SQLiteOpenHelper {
  private final Vault vault;

  HistoryStore(Context context) {
    super(context, "history.db", null, 2);
    vault = new Vault(context);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE history (id TEXT PRIMARY KEY, created INTEGER NOT NULL, payload TEXT NOT"
            + " NULL, recording TEXT)");
    db.execSQL("CREATE INDEX history_recording ON history (recording)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 2) {
      // Earlier rows get their recording when they are next read.
      db.execSQL("ALTER TABLE history ADD COLUMN recording TEXT");
      db.execSQL("CREATE INDEX history_recording ON history (recording)");
    }
  }

  private record Outdated(String json, String sealed, HistoryEntry entry) {}

  /** Rows of this query, read with COLUMNS. */
  private List<HistoryEntry> read(Cursor c) throws Exception {
    List<HistoryEntry> entries = new ArrayList<>();
    List<Outdated> outdated = new ArrayList<>();
    try (c) {
      while (c.moveToNext()) {
        String sealed = c.getString(1), json = vault.unsealRow(sealed);
        HistoryEntry e = new HistoryEntry(new JSONObject(json));
        entries.add(e);
        if (c.isNull(2) || !Vault.isRow(sealed)) outdated.add(new Outdated(json, sealed, e));
      }
    }
    // Rows saved by earlier versions get their recording and the history key, unless saved meanwhile.
    if (!outdated.isEmpty()) {
      SQLiteDatabase db = getWritableDatabase();
      db.beginTransaction();
      try {
        for (Outdated o : outdated) {
          ContentValues values = new ContentValues();
          values.put("recording", o.entry().recordingId());
          values.put("payload", vault.sealRow(o.json()));
          db.update("history", values, "id=? AND payload=?", new String[] {o.entry().id(), o.sealed()});
        }
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
    }
    return entries;
  }

  void save(HistoryEntry entry) throws Exception {
    ContentValues values = new ContentValues();
    values.put("id", entry.id());
    values.put("created", entry.created());
    values.put("recording", entry.recordingId());
    values.put("payload", vault.sealRow(entry.json.toString()));
    if (getWritableDatabase()
            .insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        < 0) throw new java.io.IOException(tr("Could not save transcript history."));
  }

  HistoryEntry get(String id) throws Exception {
    List<HistoryEntry> found =
        read(
            getReadableDatabase()
                .query("history", COLUMNS, "id=?", new String[] {id}, null, null, null));
    return found.isEmpty() ? null : found.get(0);
  }

  private static final String[] COLUMNS = {"id", "payload", "recording"};

  List<HistoryEntry> list(int limit, int offset) throws Exception {
    return read(
        getReadableDatabase()
            .query(
                "history",
                COLUMNS,
                null,
                null,
                null,
                null,
                "created DESC, id DESC",
                Math.max(0, offset) + "," + Math.max(1, limit)));
  }

  List<HistoryEntry> all() throws Exception {
    return list(Integer.MAX_VALUE, 0);
  }

  List<HistoryEntry> versions(String recordingId) throws Exception {
    List<HistoryEntry> result = new ArrayList<>();
    for (HistoryEntry entry :
        read(
            getReadableDatabase()
                .query(
                    "history",
                    COLUMNS,
                    "recording=? OR recording IS NULL",
                    new String[] {recordingId},
                    null,
                    null,
                    "created DESC, id DESC")))
      if (entry.recordingId().equals(recordingId)) result.add(entry);
    return result;
  }

  void prefer(HistoryEntry selected) throws Exception {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      for (HistoryEntry entry : versions(selected.recordingId())) {
        entry.json.put("preferred", entry.id().equals(selected.id()));
        save(entry);
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  void delete(String id) {
    getWritableDatabase().delete("history", "id=?", new String[] {id});
  }

  void migrate(Context context) throws Exception {
    var prefs = context.getSharedPreferences("settings", 0);
    if (prefs.getBoolean("historyMigrated", false)) return;
    String old = vault.get("transcript");
    if (!old.isBlank() && get("legacy-v1") == null) {
      HistoryEntry legacy =
          HistoryEntry.create("legacy-v1", tr("Previous version · model unknown"), 0);
      legacy.json.put("text", old).put("status", "imported").put("unpriced", 1);
      save(legacy);
    }
    prefs.edit().putBoolean("historyMigrated", true).commit();
  }
}
