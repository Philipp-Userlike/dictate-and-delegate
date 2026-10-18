package app.driftdictation;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.*;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

/**
 * Home and All notes with many notes. Opt-in (-e perf true), since it adds thousands of notes:
 * run {@link #seed} once, then {@link #measure} in a fresh process (so nothing is cached yet),
 * then {@link #clean}. Timings go to logcat under "NotesPerf". -e perfNotes sets the count.
 */
@RunWith(AndroidJUnit4.class)
public class NotesPerfTest {
  static final String TAG = "NotesPerf";
  static final String NEWEST = "Perf newest note about the harbour";
  static final String[] WORDS =
      ("call send invoice tomorrow meeting draft offer kitchen ferry plan budget review team"
              + " idea onboarding button explain later client email polite thursday friday notes"
              + " project deadline summary agenda question answer price travel lisbon berlin"
              + " report quarter numbers chart design screen voice model refine chief thread")
          .split(" ");
  Context context;
  UiDevice device;

  @Before
  public void setUp() {
    assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("perf")));
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    // Timings, not UI idleness: look at the screen right away.
    Configurator.getInstance().setWaitForIdleTimeout(0);
  }

  int count() {
    String n = InstrumentationRegistry.getArguments().getString("perfNotes");
    return n == null ? 2000 : Integer.parseInt(n);
  }

  @Test
  public void seed() throws Exception {
    clean();
    Random r = new Random(42);
    long now = System.currentTimeMillis();
    int n = count();
    long t0 = SystemClock.elapsedRealtime();
    try (HistoryStore h = new HistoryStore(context)) {
      var db = h.getWritableDatabase();
      db.beginTransaction();
      try {
        for (int i = 0; i < n; i++) {
          // Spread over about a year, newest first; a few notes a day.
          long created = now - 60_000L - (long) i * 365L * 86_400_000L / n;
          String id = "perf-" + i;
          HistoryEntry e = entry(r, id, created, i == 0 ? NEWEST + ". " : "");
          h.save(e);
          if (i % 7 == 3) {
            HistoryEntry redo = entry(r, id + "-v2", created + 30_000, "");
            redo.json.put("recordingId", id).put("version", "processed");
            h.save(redo);
          }
        }
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
    }
    context
        .getSharedPreferences("settings", 0)
        .edit()
        .putBoolean("onboarded", true)
        .putLong("historyRevision", System.nanoTime())
        .commit();
    Log.i(TAG, "seeded " + n + " notes in " + (SystemClock.elapsedRealtime() - t0) + " ms");
  }

  HistoryEntry entry(Random r, String id, long created, String prefix) throws Exception {
    StringBuilder text = new StringBuilder(prefix);
    int words = 30 + r.nextInt(220);
    for (int w = 0; w < words; w++) text.append(WORDS[r.nextInt(WORDS.length)]).append(' ');
    HistoryEntry e = HistoryEntry.create(id, "microsoft/mai-transcribe-2", 10 + r.nextInt(170));
    e.json
        .put("created", created)
        .put("text", text.toString().trim())
        .put("raw", text.toString().trim())
        .put("status", "complete")
        .put("requests", 1)
        .put("processingMs", 2400)
        .put("knownCost", "0.00" + (100 + r.nextInt(800)));
    e.step("speech", e.model(), 1700, new BigDecimal("0.0007"), null);
    e.step("jev", Jev.MODEL, 400, new BigDecimal("0.00002"), null).put("answer", "refine 0.06");
    if (r.nextInt(3) == 0) e.step("refine", TextModel.MODEL, 2100, new BigDecimal("0.0003"), null);
    return e;
  }

  @Test
  public void clean() throws Exception {
    try (HistoryStore h = new HistoryStore(context)) {
      h.getWritableDatabase().delete("history", "id LIKE 'perf-%'", null);
    }
  }

  /** Waits until the text is on screen; returns the ms since start. */
  long until(long start, BySelector what) {
    long deadline = start + 60_000;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (device.hasObject(what)) return SystemClock.elapsedRealtime() - start;
      SystemClock.sleep(10);
    }
    fail("Never appeared: " + what);
    return -1;
  }

  String shell(String command) throws Exception {
    var fd = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
    try (var in = new BufferedReader(new InputStreamReader(new FileInputStream(fd.getFileDescriptor())))) {
      StringBuilder out = new StringBuilder();
      for (String line; (line = in.readLine()) != null; ) out.append(line).append('\n');
      return out.toString();
    }
  }

  long launchHome() {
    long start = SystemClock.elapsedRealtime();
    context.startActivity(
        new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    return start;
  }

  @Test
  public void measure() throws Exception {
    // Cold: the first time this process shows home.
    long start = launchHome();
    long recent = until(start, By.textStartsWith(NEWEST));
    long month = until(start, By.text("Days without dictation cost nothing."));
    Log.i(TAG, "home cold: recent " + recent + " ms, month " + month + " ms");
    SystemClock.sleep(1500);

    // Back to home from another page.
    device.findObject(By.text("Settings")).click();
    SystemClock.sleep(1000);
    device.pressBack();
    start = SystemClock.elapsedRealtime();
    recent = until(start, By.textStartsWith(NEWEST));
    month = until(start, By.text("Days without dictation cost nothing."));
    Log.i(TAG, "home again: recent " + recent + " ms, month " + month + " ms");
    SystemClock.sleep(1500);

    UiObject2 all = device.findObject(By.text("All notes"));
    start = SystemClock.elapsedRealtime();
    all.click();
    long notes = until(start, By.textStartsWith(NEWEST));
    Log.i(TAG, "all notes: first row " + notes + " ms");
    SystemClock.sleep(1500);

    shell("dumpsys gfxinfo " + context.getPackageName() + " reset");
    int w = device.getDisplayWidth(), h = device.getDisplayHeight();
    for (int i = 0; i < 12; i++) {
      device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 8);
      SystemClock.sleep(400);
    }
    String gfx = shell("dumpsys gfxinfo " + context.getPackageName());
    for (String line : gfx.split("\n"))
      if (line.startsWith("Total frames")
          || line.startsWith("Janky frames")
          || line.contains("th percentile")) Log.i(TAG, "scroll: " + line.trim());

    // Back at the top, search for words only the newest note has.
    device.pressBack();
    SystemClock.sleep(1000);
    device.findObject(By.text("All notes")).click();
    until(SystemClock.elapsedRealtime(), By.textStartsWith(NEWEST));
    UiObject2 search = device.findObject(By.text("Search your notes"));
    start = SystemClock.elapsedRealtime();
    search.setText("no note says this");
    long found = until(start, By.text("Nothing matches."));
    Log.i(TAG, "search: " + found + " ms");
    SystemClock.sleep(500);

    search.setText("harbour");
    UiObject2 row = device.wait(Until.findObject(By.textStartsWith(NEWEST)), 60_000);
    start = SystemClock.elapsedRealtime();
    row.click();
    long note = until(start, By.textContains("recorded"));
    Log.i(TAG, "open note: " + note + " ms");
  }
}
