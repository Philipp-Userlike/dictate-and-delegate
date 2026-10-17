package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Device-private PCM archives. Expiry is enforced before every access and on app launch. */
final class AudioArchive {
  static final long MAX_BYTES = 100L * 1024 * 1024;
  static final long WEEK = 7L * 24 * 60 * 60 * 1000;
  private final Context context;
  private final File directory;

  AudioArchive(Context context) {
    this.context = context;
    directory = new File(context.getNoBackupFilesDir(), "recordings");
    directory.mkdirs();
  }

  private File path(String id) {
    if (!id.matches("[A-Za-z0-9-]{1,80}"))
      throw new IllegalArgumentException(tr("Invalid recording ID"));
    return new File(directory, id + ".pcm");
  }

  File get(String id) throws IOException {
    synchronized (AudioArchive.class) {
      prune();
      File file = path(id);
      return file.isFile() ? file : null;
    }
  }

  void retain(String id, File source) throws IOException {
    synchronized (AudioArchive.class) {
      prune();
      if (!context.getSharedPreferences("settings", 0).getBoolean("retainAudio", true)
          || source.length() > MAX_BYTES) return;
      File target = path(id);
      if (!target.exists()) {
        File temporary = new File(directory, id + ".tmp");
        Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }
      prune();
    }
  }

  void delete(String id) throws IOException {
    synchronized (AudioArchive.class) {
      Files.deleteIfExists(path(id).toPath());
    }
  }

  void deleteAll() throws IOException {
    synchronized (AudioArchive.class) {
      File[] files = directory.listFiles();
      if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
    }
  }

  void prune() throws IOException {
    synchronized (AudioArchive.class) {
      File[] files = directory.listFiles();
      if (files == null) return;
      Arrays.sort(files, Comparator.comparingLong(File::lastModified));
      long total = 0, cutoff = System.currentTimeMillis() - WEEK;
      boolean keep = context.getSharedPreferences("settings", 0).getBoolean("retainAudio", true);
      for (File file : files) {
        if (!keep || file.lastModified() < cutoff || file.getName().endsWith(".tmp"))
          Files.deleteIfExists(file.toPath());
        else total += file.length();
      }
      for (File file : files)
        if (total > MAX_BYTES && file.exists()) {
          long size = file.length();
          Files.delete(file.toPath());
          total -= size;
        }
    }
  }
}
