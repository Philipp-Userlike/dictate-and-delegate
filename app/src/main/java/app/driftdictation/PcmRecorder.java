package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.annotation.SuppressLint;
import android.media.*;
import java.io.*;

final class PcmRecorder {
  private AudioRecord recorder;
  private volatile boolean running;
  private Thread thread;
  private volatile Exception failure;

  interface FailureListener {
    void failed();
  }

  @SuppressLint("MissingPermission") // Service checks permission before constructing this recorder.
  void start(File file, FailureListener listener) throws IOException {
    int minimum =
        AudioRecord.getMinBufferSize(
            AudioFiles.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    if (minimum <= 0) throw new IOException(tr("Microphone does not support 16 kHz audio."));
    recorder =
        new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AudioFiles.RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            Math.max(minimum * 2, 8192));
    if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
      recorder.release();
      recorder = null;
      throw new IOException(tr("Microphone is unavailable."));
    }
    FileOutputStream stream;
    try {
      stream = new FileOutputStream(file);
    } catch (IOException ex) {
      recorder.release();
      recorder = null;
      throw ex;
    }
    try {
      recorder.startRecording();
    } catch (Exception ex) {
      stream.close();
      recorder.release();
      recorder = null;
      throw new IOException(tr("Could not start microphone."), ex);
    }
    running = true;
    thread =
        new Thread(
            () -> {
              try (FileOutputStream out = stream) {
                byte[] buffer = new byte[8192];
                while (running) {
                  int count = recorder.read(buffer, 0, buffer.length);
                  if (count < 0 && running)
                    throw new IOException(
                        tr("Microphone was interrupted. Saved audio can be retried."));
                  if (count > 0) out.write(buffer, 0, count);
                  if (file.getParentFile().getUsableSpace() < 16 * 1024 * 1024)
                    throw new IOException(
                        tr(
                            "Storage is nearly full. Recording stopped; saved audio can be"
                                + " retried."));
                }
                out.getFD().sync();
              } catch (Exception ex) {
                failure = ex;
                running = false;
                listener.failed();
              }
            },
            "drift-recorder");
    thread.start();
  }

  Exception stop() {
    running = false;
    if (recorder != null) {
      try {
        recorder.stop();
      } catch (IllegalStateException ignored) {
      }
    }
    if (thread != null) {
      try {
        thread.join(3000);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    }
    if (recorder != null) {
      recorder.release();
      recorder = null;
    }
    return failure;
  }
}
