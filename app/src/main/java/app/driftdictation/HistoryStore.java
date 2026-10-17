package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;
import org.json.JSONObject;

/**
 * SQLite rows have encrypted payloads; only IDs and dates remain indexable. No automatic expiry.
 */
final class HistoryStore extends SQLiteOpenHelper {
  private final Vault vault;

  HistoryStore(Context context) {
    super(context, "history.db", null, 1);
    vault = new Vault(context);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE history (id TEXT PRIMARY KEY, created INTEGER NOT NULL, payload TEXT NOT"
            + " NULL)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

  void save(HistoryEntry entry) throws Exception {
    ContentValues values = new ContentValues();
    values.put("id", entry.id());
    values.put("created", entry.created());
    values.put("payload", vault.seal(entry.json.toString()));
    if (getWritableDatabase()
            .insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        < 0) throw new java.io.IOException(tr("Could not save transcript history."));
  }

  HistoryEntry get(String id) throws Exception {
    try (Cursor c =
        getReadableDatabase()
            .query(
                "history", new String[] {"payload"}, "id=?", new String[] {id}, null, null, null)) {
      return c.moveToFirst()
          ? new HistoryEntry(new JSONObject(vault.unseal(c.getString(0))))
          : null;
    }
  }

  List<HistoryEntry> list(int limit, int offset) throws Exception {
    List<HistoryEntry> entries = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .query(
                "history",
                new String[] {"payload"},
                null,
                null,
                null,
                null,
                "created DESC, id DESC",
                Math.max(0, offset) + "," + Math.max(1, limit))) {
      while (c.moveToNext())
        entries.add(new HistoryEntry(new JSONObject(vault.unseal(c.getString(0)))));
    }
    return entries;
  }

  List<HistoryEntry> all() throws Exception {
    return list(Integer.MAX_VALUE, 0);
  }

  List<HistoryEntry> versions(String recordingId) throws Exception {
    List<HistoryEntry> result = new ArrayList<>();
    for (HistoryEntry entry : all()) if (entry.recordingId().equals(recordingId)) result.add(entry);
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
