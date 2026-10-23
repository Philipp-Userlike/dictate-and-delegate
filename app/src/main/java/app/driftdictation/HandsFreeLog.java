package app.driftdictation;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * The hands-free log (Settings → Hands-free → Recent events): each event is appended as a line,
 * so a line once written stays. The file is only rewritten to drop old lines, from what was read
 * in full, into a temporary file that then takes its place; a read that fails leaves it as it is.
 * One lock for the whole app: a late thread of an earlier call writes through it too.
 */
final class HandsFreeLog {
  private HandsFreeLog() {}

  /** The lines kept across calls. */
  static final int KEEP = 1000;

  /** Past this size the oldest lines are dropped: down to KEEP, and to three quarters of it. */
  static final long TRIM_BYTES = 512 * 1024;

  private static final Object LOCK = new Object();

  /** Lines appended since the app started: every hundred, the line count is checked. */
  private static int appended;

  /** Appends one line (a line break in it is kept on the same line). */
  static void append(File file, String line) {
    append(file, line, KEEP, TRIM_BYTES);
  }

  static void append(File file, String line, int keep, long trimBytes) {
    byte[] bytes = (line.replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8);
    synchronized (LOCK) {
      try {
        Files.write(file.toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (file.length() > trimBytes || ++appended % 100 == 0 && lineCount(file) > keep) trim(file, keep, trimBytes);
      } catch (IOException ignored) {
        /* The log is a help, not a must. */
      }
    }
  }

  /**
   * Keeps the last keep lines, in at most three quarters of trimBytes: written to a temporary file
   * that replaces the log.
   */
  private static void trim(File file, int keep, long trimBytes) throws IOException {
    List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8); // Throws: kept as it is.
    int from = Math.max(0, lines.size() - keep);
    long size = 0;
    for (int i = from; i < lines.size(); i++) size += lines.get(i).getBytes(StandardCharsets.UTF_8).length + 1;
    while (from < lines.size() - 1 && size > trimBytes * 3 / 4) size -= lines.get(from++).getBytes(StandardCharsets.UTF_8).length + 1;
    File temp = new File(file.getPath() + ".tmp");
    Files.write(temp.toPath(), (String.join("\n", lines.subList(from, lines.size())) + "\n").getBytes(StandardCharsets.UTF_8));
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /** The whole log, "" if there's none. */
  static String read(File file) {
    synchronized (LOCK) {
      try {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
      } catch (IOException ex) {
        return "";
      }
    }
  }

  /** The line that starts a call's events: "── call started 14:05 ──". */
  static String callStarted(String time) {
    return CALL_STARTED + time + " ──";
  }

  private static final String CALL_STARTED = "── call started ";

  /**
   * The last calls of a log, newest included: from the line that started the oldest of them to
   * the end, so no call is cut. The whole log if it holds fewer calls.
   */
  static String lastCalls(String log, int calls) {
    int from = log.length();
    for (int i = 0; i < calls; i++) {
      int at = from == 0 ? -1 : log.lastIndexOf(CALL_STARTED, from - 1);
      if (at < 0) return log;
      from = at;
    }
    return log.substring(from);
  }

  private static long lineCount(File file) throws IOException {
    try (java.util.stream.Stream<String> s = Files.lines(file.toPath(), StandardCharsets.UTF_8)) {
      return s.count();
    }
  }
}
