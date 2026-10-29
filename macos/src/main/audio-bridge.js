'use strict';
// The main process's side of the hidden audio window (src/renderer/audio.js): the microphone as
// a stream of 20 ms PCM frames, and playback the core's voice code can drive.

const path = require('path');
const { BrowserWindow, ipcMain, systemPreferences } = require('electron');
const { deferred, withTimeout } = require('../core/util');

class AudioBridge {
  constructor({ log = () => {} } = {}) {
    this.log = log;
    this.seq = 0;
    this.waiting = new Map();
    this.players = new Map();
    this.onFrame = null;
    this.onMicError = null;
    this.readyGate = deferred();
    this.win = new BrowserWindow({
      show: false,
      width: 200,
      height: 100,
      webPreferences: {
        preload: path.join(__dirname, '..', 'renderer', 'preload.js'),
        contextIsolation: true,
        sandbox: true,
        backgroundThrottling: false,
        autoplayPolicy: 'no-user-gesture-required',
      },
    });
    this.win.loadFile(path.join(__dirname, '..', 'renderer', 'audio.html'));
    this.win.webContents.on('console-message', (e) => {
      const level = e.level !== undefined ? e.level : 'info';
      if (level === 'warning' || level === 'error' || level === 2 || level === 3) log(`audio window: ${e.message}`);
    });
    this.win.webContents.on('render-process-gone', (_e, d) => {
      log(`audio window gone: ${d.reason}; reloading it`);
      this.readyGate = deferred();
      for (const [id, p] of this.players) {
        this.players.delete(id);
        if (p.callbacks.onEnded) p.callbacks.onEnded(false, 'the audio window stopped');
      }
      if (this.onMicError) this.onMicError('the audio window stopped');
      if (!this.win.isDestroyed()) this.win.webContents.reload();
    });
    const mine = (e) => e.sender === this.win.webContents;
    ipcMain.on('audio:ready', (e) => mine(e) && this.readyGate.resolve());
    ipcMain.on('mic:frame', (e, bytes) => {
      if (!mine(e) || !this.onFrame) return;
      this.onFrame(Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength));
    });
    ipcMain.on('mic:error', (e, why) => mine(e) && this.onMicError && this.onMicError(why));
    ipcMain.on('media:button', (e, action) => mine(e) && this.onMediaButton && this.onMediaButton(action));
    for (const ch of ['mic:started', 'mic:devices', 'speak:voices'])
      ipcMain.on(ch, (e, id, value) => {
        if (!mine(e)) return;
        const w = this.waiting.get(id);
        if (w) {
          this.waiting.delete(id);
          w.resolve(value);
        }
      });
    ipcMain.on('play:event', (e, id, what, a, b) => {
      if (!mine(e)) return;
      const p = this.players.get(id);
      if (!p) return;
      if (what === 'pos') {
        p.positionMs = a;
        p.isPlaying = b;
      } else if (what === 'started') {
        p.startedAt = Date.now();
        p.isPlaying = true;
        p.callbacks.onStarted && p.callbacks.onStarted(p.durationMs ? p.durationMs() : 0);
      } else if (what === 'range') {
        p.chars = a;
        p.callbacks.onRange && p.callbacks.onRange(a);
      } else if (what === 'ended') {
        p.isPlaying = false;
        this.players.delete(id);
        if (b && b !== 'stopped') this.log(`audio: playback ended: ${b}`);
        p.callbacks.onEnded && p.callbacks.onEnded(!!a, b);
      }
    });
  }

  ready() {
    return withTimeout(this.readyGate.promise, 15000, 'the audio window did not start');
  }

  async ask(channel, ...args) {
    await this.ready();
    const id = ++this.seq;
    const d = deferred();
    this.waiting.set(id, d);
    this.win.webContents.send(channel, id, ...args);
    return withTimeout(d.promise, 10000, channel + ' did not answer').finally(() => this.waiting.delete(id));
  }

  /**
   * Opens the microphone: frames (Buffer, 640 bytes = 20 ms of 16 kHz mono PCM16LE) go to onFrame.
   * call: echo cancelling, noise suppression and gain control on (hands-free); off for dictation.
   */
  async micStart({ call = false, deviceId = '' }, onFrame, onError) {
    // Asked first, so the first recording doesn't get silence while macOS's prompt is up.
    if (process.platform === 'darwin' && systemPreferences.getMediaAccessStatus('microphone') === 'not-determined')
      await systemPreferences.askForMediaAccess('microphone');
    this.onFrame = onFrame;
    this.onMicError = onError;
    let r;
    try {
      r = await this.ask('mic:start', { call, deviceId });
    } catch (e) {
      this.micStop(); // It may still open late: closed again.
      throw e;
    }
    if (r && r.error) {
      this.onFrame = null;
      const err = new Error(r.error === 'microphone-denied' ? 'Dictate & Delegate may not use the microphone. Allow it in System Settings → Privacy & Security → Microphone.' : r.error);
      err.code = r.error;
      throw err;
    }
    this.log(`audio: microphone on (${r.label || 'default'}, ${r.rate} Hz, ${call ? 'call' : 'dictation'} mode)`);
    return r;
  }

  micStop() {
    this.onFrame = null;
    this.onMicError = null;
    if (!this.win.isDestroyed()) this.win.webContents.send('mic:stop');
  }

  micDevices() {
    return this.ask('mic:devices').catch(() => []);
  }

  voices() {
    return this.ask('speak:voices').catch(() => []);
  }

  /** The headset's play/pause button goes to fn while on (during a call), through Now Playing. */
  mediaButtons(fn) {
    this.onMediaButton = fn;
    if (!this.win.isDestroyed()) this.win.webContents.send(fn ? 'media:on' : 'media:off');
  }

  /** Keeps the output awake, silently, for a call (Settings → Hands-free → Keep the call audio open). */
  openCall() {
    this.win.webContents.send('audio:open');
  }

  closeCall() {
    if (!this.win.isDestroyed()) this.win.webContents.send('audio:close');
  }

  /**
   * Starts playing a stream of audio. format: {type: 'mp3'|'pcm', rate, channels, warmMs}.
   * Returns a handle: push(bytes), end(), stop(), playedMs(), playing(). callbacks: onStarted(ms),
   * onEnded(played, why). durationMs(): how long it is, asked when it starts.
   */
  play(format, callbacks = {}, durationMs) {
    const id = 'p' + ++this.seq;
    const p = { callbacks, durationMs, positionMs: 0, isPlaying: false, chars: 0 };
    this.players.set(id, p);
    const send = (...a) => !this.win.isDestroyed() && this.win.webContents.send(...a);
    send('play:start', id, format);
    return {
      id,
      push: (bytes) => send('play:chunk', id, bytes),
      end: () => send('play:end', id),
      stop: () => send('play:stop', id),
      playedMs: () => p.positionMs,
      playing: () => p.isPlaying,
    };
  }

  /** The Mac's own voice. callbacks: onStarted(), onRange(charIndex), onEnded(played, why). */
  speak(text, callbacks = {}, voiceName = '') {
    const id = 's' + ++this.seq;
    const p = { callbacks, positionMs: 0, isPlaying: false, chars: 0 };
    this.players.set(id, p);
    this.win.webContents.send('speak:start', id, text, voiceName);
    return {
      id,
      stop: () => !this.win.isDestroyed() && this.win.webContents.send('speak:stop'),
      playing: () => p.isPlaying,
      chars: () => p.chars,
    };
  }

  /** A short sound: 16-bit mono PCM at this rate. */
  tone(pcm, rate) {
    if (!this.win.isDestroyed()) this.win.webContents.send('tone', pcm, rate);
  }
}

module.exports = { AudioBridge };
