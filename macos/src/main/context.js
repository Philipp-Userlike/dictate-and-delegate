'use strict';
// Builds the app's services once: settings, the Keychain vault, the model clients, bb, ntfy,
// notes, attachments and audio archive. Main and the settings window's API both use this.

const fs = require('fs');
const path = require('path');
const { app, nativeImage } = require('electron');

const { FilePrefs } = require('../core/store');
const { KeychainVault } = require('./secrets');
const openrouter = require('../core/openrouter');
const anthropic = require('../core/anthropic');
const textModel = require('../core/text-model');
const jev = require('../core/jev');
const { Profile } = require('../core/profile');
const { Templates } = require('../core/templates');
const { HistoryStore } = require('../core/history-store');
const { Attachments, IMAGE_MAX_WIDTH, IMAGE_MAX_HEIGHT, JPEG_QUALITY } = require('../core/attachments');
const { AudioArchive } = require('../core/audio-archive');
const { BbOAuth } = require('../core/bb-oauth');
const { BbBridge } = require('../core/bb-bridge');
const { ChiefSkills } = require('../core/chief-skills');
const handsFreeLog = require('../core/handsfree-log');

/** Images as JPEG (what Android stores), at most IMAGE_MAX_WIDTH × IMAGE_MAX_HEIGHT, through Electron. */
function toJpeg(buffer) {
  let img = nativeImage.createFromBuffer(buffer);
  if (img.isEmpty()) return null;
  const { width, height } = img.getSize();
  const scale = Math.min(1, IMAGE_MAX_WIDTH / width, IMAGE_MAX_HEIGHT / height);
  if (scale < 1) img = img.resize({ width: Math.round(width * scale), height: Math.round(height * scale), quality: 'best' });
  return img.toJPEG(JPEG_QUALITY);
}

async function createContext({ log: baseLog } = {}) {
  const data = app.getPath('userData');
  fs.mkdirSync(data, { recursive: true });
  const dir = (name) => {
    const d = path.join(data, name);
    fs.mkdirSync(d, { recursive: true });
    return d;
  };
  const files = {
    data,
    prefs: path.join(data, 'prefs.json'),
    vault: path.join(data, 'vault.json'),
    notes: dir('notes'),
    audio: dir('audio'),
    attachments: dir('attachments'),
    recording: path.join(data, 'recording.pcm'),
    log: path.join(data, 'app.log'),
    handsFreeLog: path.join(data, 'handsfree.log'),
  };

  /** The app log (Help → Copy log): one line each, trimmed to the last thousand lines. */
  const log = (line) => {
    const text = `${new Date().toISOString()} ${line}`;
    if (baseLog) baseLog(text);
    try {
      handsFreeLog.append(files.log, text, 2000, 400_000);
    } catch {
      /* Logging must never break anything. */
    }
  };

  const prefs = new FilePrefs(files.prefs);
  const vault = new KeychainVault(files.vault, { log });
  await vault.load();
  const openRouter = new openrouter.OpenRouter({ vault });
  const anthropicClient = new anthropic.Anthropic({ vault });
  const profile = new Profile(prefs);
  const templates = new Templates({ prefs });
  const history = new HistoryStore(files.notes);
  const attachments = new Attachments({ dir: files.attachments, toJpeg });
  const archive = new AudioArchive({ dir: files.audio, prefs });
  const oauth = new BbOAuth({ vault });
  const bb = new BbBridge({ prefs, vault, oauth, log });
  const skills = new ChiefSkills({ bridge: bb, prefs, log, onChange: () => ctx.emit('skills') });
  bb.skills = skills;

  const listeners = new Map();
  const ctx = {
    files,
    log,
    prefs,
    vault,
    openRouter,
    anthropic: anthropicClient,
    profile,
    templates,
    history,
    attachments,
    archive,
    oauth,
    bb,
    skills,
    version: app.getVersion(),

    /** Refine: the text model with the user's prompt, the named template and the note's attachments. */
    refine(text, { template = '', recordingId = '' } = {}) {
      let parts = [];
      try {
        parts = recordingId ? attachments.parts(recordingId) : [];
      } catch (e) {
        log(`refine: attachments not read (${e.message})`);
      }
      return textModel.refine(
        { prefs, vault, openRouter, anthropic: anthropicClient },
        text,
        parts,
        profile.refinePrompt(),
        templates.prompt(template),
        profile.context(),
      );
    },

    /** Jev on a dictation. */
    decide(transcript, { templates: names, refine, chiefName }) {
      return jev.ask(openRouter, transcript, names, refine, chiefName);
    },

    /** A tiny event bus between main's parts and the windows ("skills", "notes", "settings"…). */
    on(event, fn) {
      if (!listeners.has(event)) listeners.set(event, new Set());
      listeners.get(event).add(fn);
      return () => listeners.get(event).delete(fn);
    },
    emit(event, ...args) {
      for (const fn of listeners.get(event) || []) {
        try {
          fn(...args);
        } catch (e) {
          log(`event ${event}: ${e.message}`);
        }
      }
    },
  };
  prefs.onChange((key) => ctx.emit('settings', key));
  return ctx;
}

module.exports = { createContext, toJpeg };
