package app.driftdictation;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Quiet, short sounds for the key moments of a hands-free call, made on the phone (no files):
 * heard you (one soft high tick), sent (two rising notes), reply coming (two falling notes) and
 * something failed (two low notes). HandsFreeSession keeps them from counting as your speech.
 */
final class Cues {
  static final int RATE = 22_050;

  /** How loud, of full scale: well below a reply's voice. */
  static final double VOLUME = 0.12;

  private final AudioTrack[] tracks = new AudioTrack[HandsFreeSession.Cue.values().length];
  /** The call ended: no more sounds (a message still delivered then stays quiet). */
  private boolean released;

  Cues(Context context) {}

  /** Plays a sound; true if it did (false only when the phone refused). */
  synchronized boolean play(HandsFreeSession.Cue cue) {
    if (released) return false;
    try {
      AudioTrack t = tracks[cue.ordinal()];
      if (t == null) {
        short[] pcm = pcm(cue);
        t =
            new AudioTrack.Builder()
                .setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(
                    new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
        t.write(pcm, 0, pcm.length);
        tracks[cue.ordinal()] = t;
      }
      if (t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) t.stop();
      t.reloadStaticData();
      t.play();
      return true;
    } catch (RuntimeException ex) {
      return false;
    }
  }

  synchronized void release() {
    released = true;
    for (int i = 0; i < tracks.length; i++) {
      if (tracks[i] != null) tracks[i].release();
      tracks[i] = null;
    }
  }

  /** The notes of each sound: {frequency Hz, length ms}, one after the other. */
  static int[][] notes(HandsFreeSession.Cue cue) {
    return switch (cue) {
      case HEARD -> new int[][] {{1320, 45}};
      case SENT -> new int[][] {{660, 70}, {990, 90}};
      case REPLY -> new int[][] {{880, 70}, {587, 90}};
      case FAILED -> new int[][] {{294, 110}, {220, 150}};
    };
  }

  /** The sound as 16-bit samples: sine notes, each faded in and out so nothing clicks. */
  static short[] pcm(HandsFreeSession.Cue cue) {
    int[][] notes = notes(cue);
    int total = 0;
    for (int[] n : notes) total += RATE * n[1] / 1000;
    short[] out = new short[total];
    int at = 0;
    for (int[] n : notes) {
      int len = RATE * n[1] / 1000, fade = Math.min(len / 3, RATE * 12 / 1000);
      for (int i = 0; i < len; i++) {
        double env = Math.min(1, Math.min(i, len - 1 - i) / (double) fade);
        out[at + i] = (short) Math.round(Math.sin(2 * Math.PI * n[0] * i / RATE) * env * VOLUME * Short.MAX_VALUE);
      }
      at += len;
    }
    return out;
  }
}
