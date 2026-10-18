package app.driftdictation;

/** 20 ms frames of a 220 Hz tone at a given amplitude: speech-loud or room-quiet. */
final class HandsFreeTestFrames {
  private HandsFreeTestFrames() {}

  static byte[] frame(int amplitude) {
    byte[] f = new byte[640];
    for (int i = 0; i < 320; i++) {
      int s = (int) (amplitude * Math.sin(i * 2 * Math.PI * 220 / 16000.0));
      f[2 * i] = (byte) s;
      f[2 * i + 1] = (byte) (s >> 8);
    }
    return f;
  }
}
