package app.driftdictation;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import java.util.List;

/**
 * A hands-free call's audio is a call: the phone is in communication mode while it lasts, so the
 * replies (call audio) are taken out of the microphone by its echo cancellation. It plays on a
 * headset when one is connected (Bluetooth, else wired or USB), else on the speaker, never the
 * earpiece; a headset that connects or goes away during the call takes over or hands back.
 */
final class CallAudio {
  /** Communication devices in the order they're wanted; the earpiece isn't among them. */
  static final int[] PREFERRED = {
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
  };

  /** Which of the device types to use: the first preferred one there is, -1 if none. */
  static int pick(List<Integer> types) {
    for (int t : PREFERRED) if (types.contains(t)) return t;
    return -1;
  }

  static String name(int type) {
    return switch (type) {
      case AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset";
      case AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset (SCO: call quality)";
      case AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset";
      case AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset";
      case AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones";
      case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker";
      default -> "device type " + type;
    };
  }

  private final AudioManager audio;
  private final java.util.function.Consumer<String> log;
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private int mode = AudioManager.MODE_NORMAL;
  private boolean on;
  private String routed = "";

  private final AudioDeviceCallback devices =
      new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
          route();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
          route();
        }
      };

  CallAudio(Context context, java.util.function.Consumer<String> log) {
    audio = context.getSystemService(AudioManager.class);
    this.log = log;
  }

  /** The call starts: communication mode, and the best device. */
  void start() {
    if (on) return;
    on = true;
    mode = audio.getMode();
    audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
    route();
    audio.registerAudioDeviceCallback(devices, main);
  }

  /** Picks the device again (one connected or went away). */
  private synchronized void route() {
    if (!on) return;
    List<AudioDeviceInfo> available = audio.getAvailableCommunicationDevices();
    List<Integer> types = new java.util.ArrayList<>();
    for (AudioDeviceInfo d : available) types.add(d.getType());
    int type = pick(types);
    String now = type < 0 ? "the phone's choice" : name(type);
    if (type >= 0) {
      for (AudioDeviceInfo d : available) {
        if (d.getType() != type) continue;
        if (!audio.setCommunicationDevice(d)) now = name(type) + " refused: the phone's choice";
        break;
      }
    }
    if (!now.equals(routed)) log.accept("audio: call mode, on the " + now);
    routed = now;
  }

  /** The call ended: the device and mode the phone had before. */
  synchronized void stop() {
    if (!on) return;
    on = false;
    audio.unregisterAudioDeviceCallback(devices);
    audio.clearCommunicationDevice();
    audio.setMode(mode);
  }
}
