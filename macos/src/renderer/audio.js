'use strict';
// The hidden audio window: the microphone (as 16 kHz PCM frames for the core), reply playback
// (streamed MP3 through Media Source, streamed PCM through Web Audio), the Mac's own voice
// (speechSynthesis) and the hands-free sounds. Everything runs in one renderer, so Chromium's echo
// cancellation hears the replies it plays and keeps them out of the microphone during calls.

// window.dd comes from preload.js.

// ------------------------------------------------------------------ microphone

let mic = null;
/** Counts starts and stops: a stop that comes while a start still waits for the microphone wins. */
let micTurn = 0;

async function micStart({ call = false, deviceId = '' } = {}) {
  const stopping = micStop();
  const turn = ++micTurn; // Taken before waiting: a stop that comes meanwhile wins.
  await stopping;
  const constraints = {
    channelCount: 1,
    // A call is like Android's VOICE_COMMUNICATION: echo cancelling, noise suppression and gain
    // control. Dictation is like its MIC source: the raw signal, which speech models prefer.
    echoCancellation: call,
    noiseSuppression: call,
    autoGainControl: call,
  };
  if (deviceId) constraints.deviceId = { exact: deviceId };
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: constraints });
  } catch (e) {
    // The chosen microphone isn't there (AirPods away): the default one rather than nothing.
    if (!deviceId || !['OverconstrainedError', 'NotFoundError'].includes(e && e.name)) throw e;
    console.warn('microphone ' + deviceId + ' unavailable (' + e.name + '): using the default one');
    delete constraints.deviceId;
    stream = await navigator.mediaDevices.getUserMedia({ audio: constraints });
  }
  if (turn !== micTurn) {
    stream.getTracks().forEach((t) => t.stop());
    throw new Error('stopped');
  }
  let ctx;
  let source;
  try {
    ctx = new AudioContext({ sampleRate: 16000, latencyHint: 'interactive' });
    source = ctx.createMediaStreamSource(stream);
  } catch {
    if (ctx) ctx.close();
    ctx = new AudioContext({ latencyHint: 'interactive' });
    source = ctx.createMediaStreamSource(stream);
  }
  await ctx.audioWorklet.addModule('mic-worklet.js');
  const node = new AudioWorkletNode(ctx, 'mic-frames', { numberOfInputs: 1, numberOfOutputs: 0 });
  node.port.onmessage = (e) => dd.send('mic:frame', new Uint8Array(e.data));
  source.connect(node);
  const track = stream.getAudioTracks()[0];
  track.onended = () => dd.send('mic:error', 'The microphone stopped (it was unplugged or taken by another app).');
  if (turn !== micTurn) {
    stream.getTracks().forEach((t) => t.stop());
    ctx.close();
    throw new Error('stopped');
  }
  mic = { stream, ctx, node, source };
  return { rate: ctx.sampleRate, label: track.label, settings: track.getSettings() };
}

async function micStop() {
  micTurn++;
  const m = mic;
  mic = null;
  if (!m) return;
  try {
    m.source.disconnect();
    m.node.port.onmessage = null;
    m.stream.getTracks().forEach((t) => t.stop());
    await m.ctx.close();
  } catch {
    /* Already gone. */
  }
}

dd.on('mic:start', async (id, opts) => {
  try {
    dd.send('mic:started', id, await micStart(opts));
  } catch (e) {
    dd.send('mic:started', id, { error: (e && (e.name === 'NotAllowedError' ? 'microphone-denied' : e.message)) || String(e) });
  }
});
dd.on('mic:stop', () => micStop());
dd.on('mic:devices', async (id) => {
  try {
    const all = await navigator.mediaDevices.enumerateDevices();
    dd.send('mic:devices', id, all.filter((d) => d.kind === 'audioinput').map((d) => ({ id: d.deviceId, label: d.label })));
  } catch {
    dd.send('mic:devices', id, []);
  }
});

// ------------------------------------------------------------------ playback

let out = null; // The context replies and sounds play on.
let keepOpen = null; // A silent source that keeps the output awake during a call.

function output() {
  if (!out || out.state === 'closed') out = new AudioContext({ latencyHint: 'interactive' });
  if (out.state === 'suspended') out.resume();
  return out;
}

/** Keeps the audio output open (silent) for the whole call, so a reply's first sound comes out at once. */
/**
 * The headset's play/pause button during a call: AirPods and car buttons go to the app that's
 * "now playing", so a silent looping track makes the call that app, and its media session's
 * play/pause sends (Android: the call's media session).
 */
let nowPlaying = null;
function silentWav(seconds) {
  const rate = 8000, n = rate * seconds, buf = new ArrayBuffer(44 + n * 2), v = new DataView(buf);
  const str = (o, t) => [...t].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  str(0, 'RIFF'); v.setUint32(4, 36 + n * 2, true); str(8, 'WAVE'); str(12, 'fmt ');
  v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  str(36, 'data'); v.setUint32(40, n * 2, true);
  return URL.createObjectURL(new Blob([buf], { type: 'audio/wav' }));
}
function mediaButtonsOn() {
  if (nowPlaying || !('mediaSession' in navigator)) return;
  nowPlaying = new Audio(silentWav(5));
  nowPlaying.loop = true;
  nowPlaying.play().catch(() => {});
  navigator.mediaSession.metadata = new MediaMetadata({ title: 'Hands-free call', artist: 'Dictate & Delegate' });
  for (const action of ['play', 'pause', 'stop'])
    navigator.mediaSession.setActionHandler(action, () => {
      dd.send('media:button', action);
      if (nowPlaying && nowPlaying.paused) nowPlaying.play().catch(() => {}); // Stays "now playing".
    });
  navigator.mediaSession.playbackState = 'playing';
}
function mediaButtonsOff() {
  if (!nowPlaying) return;
  nowPlaying.pause();
  URL.revokeObjectURL(nowPlaying.src);
  nowPlaying = null;
  for (const action of ['play', 'pause', 'stop']) navigator.mediaSession.setActionHandler(action, null);
  navigator.mediaSession.playbackState = 'none';
}
dd.on('media:on', () => mediaButtonsOn());
dd.on('media:off', () => mediaButtonsOff());

dd.on('audio:open', () => {
  const ctx = output();
  if (keepOpen) return;
  const gain = ctx.createGain();
  gain.gain.value = 0;
  const osc = ctx.createOscillator();
  osc.connect(gain).connect(ctx.destination);
  osc.start();
  keepOpen = { osc, gain };
});
dd.on('audio:close', () => {
  if (!keepOpen) return;
  try {
    keepOpen.osc.stop();
  } catch {}
  keepOpen = null;
});

const players = new Map();

function report(id, what, ...args) {
  dd.send('play:event', id, what, ...args);
}

/** Silence first, so a route that wakes with the first sound loses silence instead of words. */
function warm(ms) {
  if (!ms || keepOpen) return Promise.resolve();
  const ctx = output();
  const buf = ctx.createBuffer(1, Math.max(1, Math.round((ctx.sampleRate * ms) / 1000)), ctx.sampleRate);
  const src = ctx.createBufferSource();
  src.buffer = buf;
  src.connect(ctx.destination);
  return new Promise((resolve) => {
    src.onended = resolve;
    src.start();
  });
}

/** A streamed MP3 (OpenRouter's voices): Media Source plays it while it still comes in. */
class Mp3Player {
  constructor(id, warmMs) {
    this.id = id;
    this.queue = [];
    this.ended = false;
    this.done = false;
    this.started = false;
    this.audio = new Audio();
    this.ms = new MediaSource();
    this.audio.src = URL.createObjectURL(this.ms);
    this.ready = new Promise((resolve) => this.ms.addEventListener('sourceopen', resolve, { once: true })).then(() => {
      this.sb = this.ms.addSourceBuffer('audio/mpeg');
      this.sb.mode = 'sequence';
      this.sb.addEventListener('updateend', () => this.pump());
      this.pump();
    });
    this.audio.addEventListener('playing', () => {
      if (this.started) return;
      this.started = true;
      report(id, 'started');
    });
    this.audio.addEventListener('ended', () => this.finish(true));
    this.audio.addEventListener('error', () => this.finish(false, 'The reply audio could not be played'));
    this.warmed = warm(warmMs);
    this.timer = setInterval(() => report(id, 'pos', Math.round(this.audio.currentTime * 1000), !this.audio.paused), 100);
  }

  add(bytes) {
    this.queue.push(bytes);
    this.pump();
  }

  end() {
    this.ended = true;
    this.pump();
  }

  pump() {
    if (!this.sb || this.sb.updating || this.done) return;
    if (this.queue.length) {
      try {
        this.sb.appendBuffer(this.queue.shift());
      } catch (e) {
        this.finish(false, e.message);
        return;
      }
      if (this.audio.paused && !this.playRequested) {
        this.playRequested = true;
        this.warmed.then(() => this.audio.play().catch((e) => this.finish(false, e.message)));
      }
    } else if (this.ended && this.ms.readyState === 'open') {
      try {
        this.ms.endOfStream();
      } catch {}
    }
  }

  stop() {
    this.finish(false, 'stopped');
  }

  finish(played, why) {
    if (this.done) return;
    this.done = true;
    clearInterval(this.timer);
    report(this.id, 'pos', Math.round(this.audio.currentTime * 1000), false);
    try {
      this.audio.pause();
    } catch {}
    URL.revokeObjectURL(this.audio.src);
    players.delete(this.id);
    report(this.id, 'ended', played, why || '');
  }
}

/** Streamed PCM (16-bit little-endian): each chunk is scheduled right after the one before. */
class PcmPlayer {
  constructor(id, rate, channels, warmMs) {
    this.id = id;
    this.rate = rate;
    this.channels = channels;
    this.ctx = output();
    this.rest = new Uint8Array(0);
    this.sources = new Set();
    this.at = 0;
    this.started = false;
    this.ended = false;
    this.done = false;
    this.warmed = warm(warmMs).then(() => {
      this.at = Math.max(this.at, this.ctx.currentTime + 0.02);
      this.begin = this.at;
      this.flushPending();
    });
    this.pending = [];
    this.timer = setInterval(() => report(id, 'pos', this.playedMs(), this.playing()), 100);
  }

  playedMs() {
    if (!this.started) return 0;
    return Math.max(0, Math.round((Math.min(this.ctx.currentTime, this.at) - this.begin) * 1000));
  }

  playing() {
    return this.started && !this.done && this.ctx.currentTime < this.at;
  }

  add(bytes) {
    this.pending.push(bytes);
    if (this.begin !== undefined) this.flushPending();
  }

  flushPending() {
    for (const b of this.pending.splice(0)) this.schedule(b);
    this.checkEnd();
  }

  schedule(bytes) {
    const all = new Uint8Array(this.rest.length + bytes.length);
    all.set(this.rest);
    all.set(bytes, this.rest.length);
    const frameBytes = 2 * this.channels;
    const usable = all.length - (all.length % frameBytes);
    this.rest = all.slice(usable);
    const frames = usable / frameBytes;
    if (!frames) return;
    const view = new DataView(all.buffer, all.byteOffset, usable);
    const buf = this.ctx.createBuffer(this.channels, frames, this.rate);
    for (let c = 0; c < this.channels; c++) {
      const data = buf.getChannelData(c);
      for (let i = 0; i < frames; i++) data[i] = view.getInt16((i * this.channels + c) * 2, true) / 32768;
    }
    const src = this.ctx.createBufferSource();
    src.buffer = buf;
    src.connect(this.ctx.destination);
    const when = Math.max(this.at, this.ctx.currentTime);
    src.start(when);
    this.at = when + buf.duration;
    this.sources.add(src);
    src.onended = () => {
      this.sources.delete(src);
      this.checkEnd();
    };
    if (!this.started) {
      this.started = true;
      setTimeout(() => !this.done && report(this.id, 'started'), Math.max(0, (when - this.ctx.currentTime) * 1000));
    }
  }

  end() {
    this.ended = true;
    this.checkEnd();
  }

  checkEnd() {
    if (this.ended && !this.pending.length && this.begin !== undefined && this.sources.size === 0) this.finish(this.started);
  }

  stop() {
    for (const s of this.sources) {
      try {
        s.stop();
      } catch {}
    }
    this.finish(false, 'stopped');
  }

  finish(played, why) {
    if (this.done) return;
    report(this.id, 'pos', this.playedMs(), false);
    this.done = true;
    clearInterval(this.timer);
    players.delete(this.id);
    report(this.id, 'ended', played, why || '');
  }
}

dd.on('play:start', (id, format) => {
  try {
    const p =
      format.type === 'mp3'
        ? new Mp3Player(id, format.warmMs)
        : new PcmPlayer(id, format.rate || 24000, format.channels || 1, format.warmMs);
    players.set(id, p);
  } catch (e) {
    report(id, 'ended', false, e.message);
  }
});
dd.on('play:chunk', (id, bytes) => {
  const p = players.get(id);
  if (p) p.add(bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes));
});
dd.on('play:end', (id) => {
  const p = players.get(id);
  if (p) p.end();
});
dd.on('play:stop', (id) => {
  const p = players.get(id);
  if (p) p.stop();
});

// ------------------------------------------------------------------ the Mac's voice

dd.on('speak:start', (id, text, voiceName) => {
  const u = new SpeechSynthesisUtterance(text);
  const voices = speechSynthesis.getVoices();
  const voice = (voiceName && voices.find((v) => v.name === voiceName)) || voices.find((v) => v.default && v.lang.startsWith('en')) || voices.find((v) => v.lang.startsWith('en'));
  if (voice) u.voice = voice;
  let started = false;
  u.onstart = () => {
    started = true;
    report(id, 'started');
  };
  u.onboundary = (e) => report(id, 'range', e.charIndex);
  u.onend = () => report(id, 'ended', started, '');
  u.onerror = (e) => report(id, 'ended', false, e.error || 'speech failed');
  players.set(id, { stop: () => speechSynthesis.cancel() });
  speechSynthesis.speak(u);
});
dd.on('speak:stop', () => speechSynthesis.cancel());
speechSynthesis.getVoices(); // Loads the list, which is empty on first use.
dd.on('speak:voices', (id) => dd.send('speak:voices', id, speechSynthesis.getVoices().map((v) => ({ name: v.name, lang: v.lang }))));

// ------------------------------------------------------------------ sounds

dd.on('tone', (pcm, rate) => {
  const ctx = output();
  const samples = new Int16Array(pcm.buffer, pcm.byteOffset, Math.floor(pcm.byteLength / 2));
  const buf = ctx.createBuffer(1, samples.length, rate);
  const data = buf.getChannelData(0);
  for (let i = 0; i < samples.length; i++) data[i] = samples[i] / 32768;
  const src = ctx.createBufferSource();
  src.buffer = buf;
  src.connect(ctx.destination);
  src.start();
});

dd.send('audio:ready');
