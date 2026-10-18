package app.driftdictation;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class HistoryStoreTest {
  @Test
  public void historySurvivesReopeningWithoutExpiry() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    String id = UUID.randomUUID().toString();
    HistoryEntry e = HistoryEntry.create(id, "openai/whisper-1", 60);
    e.json.put("created", System.currentTimeMillis() - 400L * 24 * 60 * 60 * 1000);
    e.beginRequest();
    e.accept(
        new OpenRouter.Result("Private regression transcript", new BigDecimal("0.006")),
        1920000);
    try (HistoryStore h = new HistoryStore(context)) {
      h.save(e);
      h.save(e);
      try (var cursor =
          h.getReadableDatabase()
              .rawQuery("SELECT payload FROM history WHERE id=?", new String[] {id})) {
        assertTrue(cursor.moveToFirst());
        // Notes are kept plain in app-private storage; only secrets are encrypted.
        assertEquals(e.text(), new org.json.JSONObject(cursor.getString(0)).getString("text"));
        assertFalse(cursor.moveToNext());
      }
    }
    try (HistoryStore h = new HistoryStore(context)) {
      var read = h.get(id);
      assertEquals(e.text(), read.text());
      assertEquals(e.cost(), read.cost());
      assertEquals(e.created(), read.created());
      h.delete(id);
      assertNull(h.get(id));
    }
  }

  @Test
  public void legacyTranscriptMigrationIsIdempotentAndCostIsUnknown() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var prefs = context.getSharedPreferences("settings", 0);
    var vault = new Vault(context);
    String previous = vault.get("transcript");
    boolean migrated = prefs.getBoolean("historyMigrated", false);
    try (HistoryStore h = new HistoryStore(context)) {
      h.delete("legacy-v1");
      prefs.edit().putBoolean("historyMigrated", false).commit();
      vault.put("transcript", "Preserve my old prompt.");
      h.migrate(context);
      h.migrate(context);
      HistoryEntry imported = h.get("legacy-v1");
      assertEquals("Preserve my old prompt.", imported.text());
      assertTrue(imported.incompleteCost());
      h.delete("legacy-v1");
    } finally {
      vault.put("transcript", previous);
      prefs.edit().putBoolean("historyMigrated", migrated).commit();
    }
  }

  @Test
  public void encryptedRowsFromEarlierVersionsAreRewrittenPlain() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    String id = UUID.randomUUID().toString(), redo = UUID.randomUUID().toString();
    HistoryEntry first = HistoryEntry.create(id, "openai/whisper-1", 30);
    first.json.put("text", "Sealed the old way");
    HistoryEntry second = HistoryEntry.create(redo, "openai/whisper-1", 30);
    second.json.put("recordingId", id).put("text", "Redone the old way");
    HistoryEntry other = HistoryEntry.create(UUID.randomUUID().toString(), "openai/whisper-1", 30);
    other.json.put("text", "Another old note");
    try (HistoryStore h = new HistoryStore(context)) {
      // As earlier versions wrote them: sealed by Keystore, no recording column.
      for (HistoryEntry e : new HistoryEntry[] {first, second, other}) {
        var values = new android.content.ContentValues();
        values.put("id", e.id());
        values.put("created", e.created());
        values.put("payload", new Vault(context).seal(e.json.toString()));
        h.getWritableDatabase().insert("history", null, values);
      }
      // Read as they are, before the rewrite.
      assertEquals("Another old note", h.get(other.id()).text());
      h.unseal();
      try (var cursor =
          h.getReadableDatabase()
              .rawQuery(
                  "SELECT payload, recording FROM history WHERE id IN (?, ?, ?)",
                  new String[] {id, redo, other.id()})) {
        int rows = 0;
        while (cursor.moveToNext()) {
          rows++;
          assertTrue(cursor.getString(0).startsWith("{"));
          assertNotNull(cursor.getString(1));
        }
        assertEquals(3, rows);
      }
      assertEquals(2, h.versions(id).size());
      assertEquals("Sealed the old way", h.get(id).text());
      assertEquals("Redone the old way", h.get(redo).text());
      assertEquals(0, h.unseal());
      h.delete(id);
      h.delete(redo);
      h.delete(other.id());
    }
  }
}
