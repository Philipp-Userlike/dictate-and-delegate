'use strict';
// Hands-free calls on the Mac: the call controller (src/core/handsfree-call.js, Android's
// HandsFreeService without Android) wired to the Mac's pieces: the microphone in call mode (echo
// cancelling on), the voice played by the audio window, live transcription, Jev, bb, the ntfy
// replies, the sounds, the notes, and a small call bar floating over other apps.

const path = require('path');
const { BrowserWindow, ipcMain, screen, powerSaveBlocker } = require('electron');
const HandsFree = require('../core/handsfree');
const { HandsFreeCall } = require('../core/handsfree-call');
const { Voice, choice: voiceChoice, createSpeaker, DEFAULT: DEFAULT_VOICE } = require('../core/voice');
const streaming = require('../core/streaming-speech');
const jev = require('../core/jev');
const cues = require('../core/cues');
const chiefText = require('../core/chief-text');

const RENDERER = path.join(__dirname, '..', 'renderer');

/** The voice's player on top of the audio window (what voice.js asks of a player). */
function makePlayer(audio, prefs) {
  return {
    stream(vs, { onStarted, onEnded }) {
      let format;
      if (vs.encoding === 'pcm') format = { type: 'pcm', rate: vs.rate(), channels: vs.channels() };
      else if (vs.encoding === 'mp3' || /mpeg|mp3/i.test(vs.mime || '')) format = { type: 'mp3' };
      else format = { type: 'mp3' };
      let stopData = null;
      const handle = audio.play(format, {
        onStarted: () => onStarted && onStarted(vs.audioMs ? vs.audioMs() : 0),
        onEnded: (played) => {
          if (stopData) stopData();
          onEnded && onEnded(!!played && !vs.failed());
        },
      });
      const isWav = vs.encoding === 'file' && /wav/i.test(vs.mime || '');
      if (vs.encoding === 'file') {
        // A whole file of another type: played once it's all there.
        vs.whenDone((s) => {
          let bytes = s.data();
          if (isWav && bytes.length > 44) bytes = bytes.subarray(44);
          if (!s.failed()) handle.push(bytes);
          handle.end();
        });
      } else {
        const first = vs.data();
        if (first.length) handle.push(first);
        stopData = vs.onData((chunk) => handle.push(chunk));
        vs.whenDone(() => {
          if (stopData) stopData();
          stopData = null;
          handle.end();
        });
      }
      return { stop: () => handle.stop(), playedMs: () => handle.playedMs(), playing: () => handle.playing() };
    },

    speak(text, { onStarted, onRange, onEnded, onError }) {
      const h = audio.speak(
        text,
        {
          onStarted,
          onRange,
          onEnded: (played, why) => {
            if (!played && why && why !== 'stopped' && why !== 'interrupted' && why !== 'canceled' && onError) onError(why);
            else onEnded && onEnded(!!played);
          },
        },
        prefs.get('macVoice', ''),
      );
      return { stop: () => h.stop(), playing: () => h.playing() };
    },

    warm(ms) {
      return new Promise((resolve) => {
        const h = audio.play({ type: 'pcm', rate: 16000, channels: 1 }, { onEnded: () => resolve() });
        h.push(Buffer.alloc(Math.round((16000 * 2 * ms) / 1000)));
        h.end();
        setTimeout(resolve, ms + 1500); // Never wait on a stuck output.
      });
    },

    open: () => audio.openCall(),
    close: () => audio.closeCall(),
  };
}

function createCall({ ctx, audio, shell }) {
  const listeners = new Set();
  const player = makePlayer(audio, ctx.prefs);
  let controller = null;
  let voice = null;
  let bar = null;
  let blocker = -1;

  const changed = () => {
    const snap = api.snapshot();
    for (const fn of listeners) fn(snap);
    if (bar && !bar.isDestroyed()) bar.webContents.send('call', snap);
  };

  function newController() {
    const { prefs, vault, bb, openRouter, profile, history } = ctx;
    voice = new Voice({ vault, player, log: (l) => ctx.log('voice: ' + l) });
    const choice = voiceChoice(prefs.get(HandsFree.VOICE, DEFAULT_VOICE.id));
    return new HandsFreeCall({
      prefs,
      bb,
      transcribe: (wav, { model, hints }) => openRouter.transcribe(wav, model, hints),
      speechModel: profile.fixedModel(),
      hintWords: profile.hintWords(),
      judge: (said, { sendWords, endWords }) => jev.turn(openRouter, said, sendWords, endWords),
      createLive: (keyterms, { onText, onFailure, log }) => {
        const c = streaming.choice(prefs.get(HandsFree.LIVE_MODEL, streaming.DEFAULT.id));
        const key = vault.get(streaming.keyName(c));
        if (!key) return { missingKey: c.provider === 'elevenlabs' ? 'ElevenLabs' : 'AssemblyAI' };
        return streaming.create(c, key, keyterms, { changed: onText, onFailure, events: log });
      },
      createSpeaker: (o) => createSpeaker({ voice, prefs, ...o }),
      voice,
      voiceChoice: choice,
      playCue: (name) => {
        const { pcm, rate } = cues.pcm(name);
        audio.tone(pcm, rate);
        return true;
      },
      microphone: {
        start: (onFrame) =>
          audio.micStart({ call: true, deviceId: prefs.get('micDevice', '') }, onFrame, (why) => {
            ctx.log('hands-free: ' + why);
            api.end();
          }),
        stop: () => audio.micStop(),
      },
      replies: ({ sinceSeconds, ours, untagged, onReply }) => {
        const r = new chiefText.Replies({ server: bb.ntfyServer(), topic: bb.ntfyTopic(), sinceSeconds, ours, untagged, onReply });
        r.start();
        return { close: () => r.finish() };
      },
      logFile: ctx.files.handsFreeLog,
      saveNote: async (entry) => {
        history.save(entry);
        prefs.set('historyRevision', Number(prefs.get('historyRevision', 0)) + 1);
        ctx.emit('notes', entry.json.id);
      },
      updateNote: async (request, fn) => {
        const e = history.all().find((n) => n.json.chiefRequest === request);
        if (!e) return;
        fn(e);
        history.save(e);
        ctx.emit('notes', e.json.id);
      },
      tellTalkerSkill: () => ctx.skills.safelyTellTalker(),
      onChange: changed,
      onEnded: () => {
        changed();
        setTimeout(() => {
          if (!api.active() && bar && !bar.isDestroyed()) bar.hide();
        }, 4000);
      },
    });
  }

  // ---------------------------------------------------------------- the call bar

  function createBar() {
    const win = new BrowserWindow({
      type: 'panel',
      width: 300,
      height: 52,
      frame: false,
      transparent: true,
      resizable: false,
      hasShadow: false,
      focusable: false,
      acceptFirstMouse: true,
      alwaysOnTop: true,
      skipTaskbar: true,
      fullscreenable: false,
      show: false,
      webPreferences: { preload: path.join(RENDERER, 'preload.js'), contextIsolation: true, sandbox: true },
    });
    win.setAlwaysOnTop(true, 'floating');
    win.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
    win.loadFile(path.join(RENDERER, 'callbar.html'));
    win.on('moved', () => {
      const [x, y] = win.getPosition();
      ctx.prefs.set('hfPanelX', x);
      ctx.prefs.set('hfPanelY', y);
    });
    const mine = (e) => e.sender === win.webContents;
    ipcMain.on('callbar:ready', (e) => mine(e) && win.webContents.send('call', api.snapshot()));
    ipcMain.on('callbar:action', (e, what) => {
      if (!mine(e)) return;
      if (what === 'send') api.sendNow();
      else if (what === 'end') api.end();
      else if (what === 'open') shell.openMain('handsfree');
    });
    ipcMain.on('callbar:size', (e, w, h) => {
      if (!mine(e)) return;
      const [x, y] = win.getPosition();
      win.setBounds({ x, y, width: Math.max(120, w), height: Math.max(36, h) });
    });
    return win;
  }

  function showBar(at) {
    if (!bar || bar.isDestroyed()) bar = createBar();
    const area = screen.getDisplayNearestPoint(screen.getCursorScreenPoint()).workArea;
    let x = ctx.prefs.get('hfPanelX', null);
    let y = ctx.prefs.get('hfPanelY', null);
    // Switched over with 📞: where the dictation panel was.
    if (at) [x, y] = at;
    if (x === null || y === null) {
      x = area.x + area.width - 340;
      y = area.y + 12;
    }
    bar.setPosition(Math.round(x), Math.round(y));
    bar.showInactive();
  }

  // ---------------------------------------------------------------- what main calls

  const api = {
    active: () => !!(controller && controller.current()),

    start({ seed = null } = {}) {
      if (api.active()) return;
      const at = shell.panelPosition ? shell.panelPosition() : null;
      controller = newController();
      blocker = powerSaveBlocker.start('prevent-app-suspension'); // Android's wake lock.
      controller.start({ seed });
      showBar(seed ? at : null);
      changed();
    },

    async end() {
      if (!controller) return;
      const c = controller;
      const done = c.finish();
      changed();
      await done;
      if (blocker >= 0 && powerSaveBlocker.isStarted(blocker)) powerSaveBlocker.stop(blocker);
      blocker = -1;
    },

    sendNow() {
      if (controller) controller.sendNow();
    },

    snapshot() {
      return controller ? controller.snapshot() : { active: false, liveLabel: 'Ended', shortLabel: 'Ended', reasons: [], canSend: false, canEnd: false, cost: 0, pending: '', reply: '' };
    },

    shortState() {
      return controller && api.active() ? controller.snapshot().shortLabel : '';
    },

    onChange(fn) {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },

    /** For tests: frames instead of the microphone. */
    controller: () => controller,
  };
  return api;
}

module.exports = { createCall, makePlayer };
