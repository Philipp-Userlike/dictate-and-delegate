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
  public void encryptedHistorySurvivesReopeningWithoutExpiry() throws Exception {
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
        assertFalse(cursor.getString(0).contains("Private regression transcript"));
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
}
