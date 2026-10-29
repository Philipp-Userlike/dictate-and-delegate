'use strict';
// Dictate & Delegate for macOS: a menu-bar app. A global shortcut starts and stops a dictation
// (like the Android volume key), a floating panel offers Insert / Refine / Chief, a second
// shortcut (or 📞) starts a hands-free call with the chief, and the chief's ntfy messages show
// as notifications. The logic is the Android app's, ported to src/core; this file wires it to
// macOS.

const fs = require('fs');
const path = require('path');
const {
  app,
  BrowserWindow,
  Tray,
  Menu,
  globalShortcut,
  ipcMain,
  screen,
  shell,
  clipboard,
  dialog,
  nativeImage,
  systemPreferences,
} = require('electron');

const { createContext } = require('./context');
const { AudioBridge } = require('./audio-bridge');
const insertion = require('./insert');
const { Notifier } = require('./notifier');
const { Dictation, FilePendingAudio } = require('../core/dictation');
const { panelModel } = require('../core/panel-model');
const chiefText = require('../core/chief-text');
const openrouter = require('../core/openrouter');

const RENDERER = path.join(__dirname, '..', 'renderer');
const PRELOAD = path.join(RENDERER, 'preload.js');

/** Settings of the Mac app (Android keeps these keys for its own start options). */
const HOTKEY = 'hotkey', CALL_HOTKEY = 'callHotkey', INSERT_METHOD = 'insertMethod', MIC = 'micDevice';
const DEFAULT_HOTKEY = 'Alt+Space', DEFAULT_CALL_HOTKEY = 'Alt+Shift+Space';

// End-to-end tests on Linux (scripts/e2e.js): their own data folder, a WAV file as the microphone.
if (process.env.DD_E2E) {
  if (process.env.DD_TRACE) fs.appendFileSync(process.env.DD_TRACE, `main: loaded ${Date.now()}\n`);
  app.whenReady().then(() => process.env.DD_TRACE && fs.appendFileSync(process.env.DD_TRACE, `main: ready ${Date.now()}\n`));
  if (process.env.DD_USER_DATA) app.setPath('userData', process.env.DD_USER_DATA);
  app.commandLine.appendSwitch('use-fake-ui-for-media-stream');
  app.commandLine.appendSwitch('use-fake-device-for-media-stream');
  if (process.env.DD_FAKE_MIC) app.commandLine.appendSwitch('use-file-for-fake-audio-capture', process.env.DD_FAKE_MIC);
  app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.setAsDefaultProtocolClient('talkrouter');
  start();
}

function start() {
  let ctx, audio, dictation, tray, panel, mainWindow, notifier, call;
  let pendingUrls = [];
  const callListeners = new Set();

  app.on('open-url', (event, url) => {
    event.preventDefault();
    if (ctx) handleUrl(url);
    else pendingUrls.push(url);
  });
  app.on('second-instance', () => openMain());
  app.on('window-all-closed', () => {
    /* A menu-bar app keeps running without windows. */
  });
  app.on('will-quit', () => globalShortcut.unregisterAll());

  app.whenReady().then(async () => {
    if (app.dock) app.dock.hide();
    ctx = await createContext({ log: (line) => process.env.DD_TRACE && fs.appendFileSync(process.env.DD_TRACE, line + '\n') });
    ctx.log(`app: Dictate & Delegate ${app.getVersion()} started (Electron ${process.versions.electron}, macOS ${process.getSystemVersion ? process.getSystemVersion() : ''})`);
    audio = new AudioBridge({ log: ctx.log });
    dictation = createDictation();
    panel = createPanel();
    tray = createTray();
    notifier = new Notifier(ctx, {
      openUrl: (url) => openExternal(url),
      insert: (text) => insertFromNotification(text),
      copy: (text) => clipboard.writeText(text),
      pasteArrived: () => false,
    });
    try {
      call = require('./call').createCall({ ctx, audio, shell: callShell() });
      call.onChange((snap) => {
        for (const fn of callListeners) fn(snap);
        updateTray();
      });
    } catch (e) {
      ctx.log(`app: hands-free unavailable (${e.message})`);
      call = null;
    }
    try {
      require('./api').registerApi(ctx, appShell());
    } catch (e) {
      ctx.log(`app: settings API not loaded (${e.message})`);
    }
    registerHotkeys();
    ctx.on('settings', (key) => {
      if (key === HOTKEY || key === CALL_HOTKEY) registerHotkeys();
      if (['ntfyServer', 'bbMode', 'bbHandle', 'bbUrl', 'chiefId', 'macNotifications'].includes(key)) notifier.refresh();
      updateTray();
    });
    ctx.on('skills', () => notifier.refresh());
    notifier.refresh();
    // Keep the chief's skills current (they carry the topic, name and links), as Android does on start.
    if (ctx.bb.connected()) ctx.skills.sync(false);
    for (const url of pendingUrls.splice(0)) handleUrl(url);
    if (process.env.DD_E2E) {
      global.__dd = { ctx, audio, dictation: () => dictation, panel: () => panel, call: () => call, openMain, panelAction, startCall, toggleDictation, notifier: () => notifier };
      const done = (code, message) => {
        const line = message + '\n';
        if (process.env.DD_TRACE) fs.appendFileSync(process.env.DD_TRACE, line);
        // A normal quit can wait on a renderer (a microphone, a capture); a test run mustn't.
        try {
          fs.writeSync(1, line);
        } catch {}
        process.reallyExit(code);
      };
      require(process.env.DD_E2E)(global.__dd).then(
        () => done(0, `E2E ${path.basename(process.env.DD_E2E)}: OK`),
        (e) => done(1, 'E2E FAILED: ' + (e && e.stack ? e.stack : e)),
      );
      return;
    }
    if (!ctx.prefs.get('onboarded', false) || !openrouter.hasKey(ctx.vault)) openMain('welcome');
    setInterval(() => {
      try {
        ctx.attachments.prune();
        ctx.archive.prune();
      } catch {}
    }, 6 * 3600_000);
  });

  // ---------------------------------------------------------------- dictation

  function createDictation() {
    const d = new Dictation({
      prefs: ctx.prefs,
      vault: ctx.vault,
      log: ctx.log,
      pending: new FilePendingAudio(ctx.files.recording),
      microphone: {
        start: (onFrame, onFail) =>
          audio.micStart({ call: false, deviceId: ctx.prefs.get(MIC, '') }, onFrame, (why) => onFail(new Error(why))),
        stop: () => audio.micStop(),
      },
      hasKey: () => openrouter.hasKey(ctx.vault),
      transcribe: (wav, words, model) => ctx.openRouter.transcribe(wav, model || ctx.profile.fixedModel(), words),
      decide: (t, o) => ctx.decide(t, o),
      templates: () => ctx.templates.names(),
      refine: (text, o) => ctx.refine(text, o),
      refineModel: () => require('../core/text-model').model(ctx.prefs),
      chiefReady: () => ctx.bb.ready(),
      chiefThread: () => {
        const id = ctx.bb.chiefId();
        return id ? { id, link: ctx.bb.threadLink(id) } : null;
      },
      sendToChief: async (text, { request, recordingId, chiefId }) =>
        ctx.bb.sendRequest(chiefId || ctx.bb.chiefId(), text, ctx.attachments.texts(recordingId), ctx.attachments.binaries(recordingId), request),
      waitForChiefText: (request, since, onText) => {
        const l = new chiefText.Listener(ctx.bb.ntfyServer(), ctx.bb.ntfyTopic(), request, since, onText);
        l.start();
        return { stop: () => l.finish() };
      },
      openLink: (url) => openExternal(url),
      insert: async (text) => {
        const r = await insertion.insert(text, { method: ctx.prefs.get(INSERT_METHOD, 'paste'), log: ctx.log });
        if (r.how === 'copied') return { how: 'copied', reason: r.why === 'no-field' ? undefined : 'permission' };
        return { how: r.how, clipboard: false };
      },
      replaceInPlace: async (oldText, newText) => {
        const r = await insertion.replace(oldText, newText, { method: ctx.prefs.get(INSERT_METHOD, 'paste'), log: ctx.log });
        return r ? { how: r.how, clipboard: false } : null;
      },
      copy: (text) => clipboard.writeText(text),
      loadNote: (id) => ctx.history.load(id),
      saveNote: (note) => {
        ctx.history.saveJson(note);
      },
      attachments: {
        pendingCount: () => ctx.attachments.pending().length,
        claimPending: (id) => ctx.attachments.claimPending(id),
        clearPending: () => ctx.attachments.clearPending(),
      },
      openAttach: () => attachMenu(),
      archiveAudio: (id, pending) => {
        const file = pending && (pending.file || pending.path);
        if (file && fs.existsSync(file)) ctx.archive.retain(id, file);
      },
      startCall: ({ pcm }) => startCall({ seed: pcm }),
    });
    d.on('change', (snap) => {
      showPanel(panelModel(snap));
      updateTray();
    });
    d.on('notice', (text) => ctx.log(`dictation: ${text}`));
    d.on('notes', (id) => ctx.emit('notes', id));
    return d;
  }

  function toggleDictation() {
    if (call && call.active()) return call.sendNow();
    dictation.toggle();
  }

  /** 📎 while recording: paste what you copied, or attach a file. */
  async function attachMenu() {
    const copied = await clipboard.readText().catch(() => '');
    const menu = Menu.buildFromTemplate([
      {
        label: 'Paste what you copied',
        enabled: !!copied.trim(),
        click: () => {
          try {
            ctx.attachments.addText(copied);
            dictation.touch && dictation.touch();
          } catch (e) {
            ctx.log(`attach: ${e.message}`);
          }
        },
      },
      {
        label: 'Attach a file…',
        click: async () => {
          const r = await dialog.showOpenDialog({
            properties: ['openFile', 'multiSelections'],
            filters: [{ name: 'Images, PDFs, text', extensions: ['png', 'jpg', 'jpeg', 'heic', 'gif', 'webp', 'pdf', 'txt', 'md', 'csv', 'json'] }, { name: 'All files', extensions: ['*'] }],
          });
          for (const f of r.filePaths || []) {
            try {
              await ctx.attachments.addFile(f);
            } catch (e) {
              dialog.showMessageBox({ type: 'warning', message: e.message });
            }
          }
          dictation.touch && dictation.touch();
        },
      },
    ]);
    menu.popup();
  }

  // ---------------------------------------------------------------- the floating panel

  function createPanel() {
    const win = new BrowserWindow({
      type: 'panel',
      width: 320,
      height: 60,
      frame: false,
      transparent: true,
      resizable: false,
      movable: true,
      hasShadow: false,
      focusable: false,
      acceptFirstMouse: true,
      alwaysOnTop: true,
      skipTaskbar: true,
      fullscreenable: false,
      show: false,
      webPreferences: { preload: PRELOAD, contextIsolation: true, sandbox: true },
    });
    win.setAlwaysOnTop(true, 'floating');
    win.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
    win.loadFile(path.join(RENDERER, 'panel.html'));
    win.on('moved', () => {
      const [x, y] = win.getPosition();
      ctx.prefs.set('panelX', x);
      ctx.prefs.set('panelY', y);
    });
    const mine = (e) => e.sender === win.webContents;
    ipcMain.on('panel:ready', (e) => mine(e) && showPanel(panelModel(dictation.snapshot())));
    ipcMain.on('panel:action', (e, id) => mine(e) && panelAction(id));
    ipcMain.on('panel:size', (e, w, h, visible) => {
      if (!mine(e)) return;
      if (!visible) return win.isVisible() && win.hide();
      placePanel(win, w, h);
      if (!win.isVisible()) win.showInactive();
    });
    return win;
  }

  function placePanel(win, w, h) {
    const x0 = ctx.prefs.get('panelX', null);
    const y0 = ctx.prefs.get('panelY', null);
    let x, y;
    if (x0 === null || y0 === null) {
      const area = screen.getDisplayNearestPoint(screen.getCursorScreenPoint()).workArea;
      x = Math.round(area.x + (area.width - w) / 2);
      y = Math.round(area.y + area.height - h - 28);
    } else {
      const area = screen.getDisplayNearestPoint({ x: x0, y: y0 }).workArea;
      x = Math.min(Math.max(x0, area.x), area.x + area.width - w);
      y = Math.min(Math.max(y0, area.y), area.y + area.height - h);
    }
    win.setBounds({ x, y, width: Math.max(60, w), height: Math.max(30, h) });
  }

  function showPanel(model) {
    if (panel && !panel.isDestroyed()) panel.webContents.send('panel', model);
  }

  function panelAction(id) {
    const d = dictation;
    switch (id) {
      case 'stop':
        return d.stop();
      case 'refine':
        return d.tapRefine();
      case 'chief':
        return d.tapChief();
      case 'call':
        return d.call();
      case 'attach':
        return d.attach();
      case 'insert':
        return d.insert();
      case 'insertFromChief':
        return d.insertFromChief();
      case 'close':
        return d.close();
      case 'touch':
        return d.touch && d.touch();
      case 'icon':
        if (d.hasSavedRecording && d.hasSavedRecording()) return openMain('home');
        return d.toggle();
    }
  }

  // ---------------------------------------------------------------- hands-free

  function startCall({ seed = null } = {}) {
    if (!call) return dialog.showMessageBox({ type: 'warning', message: 'Hands-free is not available in this build.' });
    if (!ctx.bb.ready()) {
      openMain('chief');
      return;
    }
    call.start({ seed });
  }

  /** What the call controller gets from the Mac. */
  function callShell() {
    return {
      openMain: (page) => openMain(page),
      panelPosition: () => (panel && panel.isVisible() ? panel.getPosition() : null),
      log: ctx && ctx.log,
    };
  }

  // ---------------------------------------------------------------- hotkeys

  function registerHotkeys() {
    globalShortcut.unregisterAll();
    const problems = {};
    const add = (key, fallback, fn) => {
      const accel = ctx.prefs.get(key, fallback);
      if (!accel) return;
      try {
        if (!globalShortcut.register(accel, fn)) problems[key] = `${accel} is taken by another app`;
      } catch (e) {
        problems[key] = `${accel}: ${e.message}`;
      }
    };
    add(HOTKEY, DEFAULT_HOTKEY, () => toggleDictation());
    add(CALL_HOTKEY, DEFAULT_CALL_HOTKEY, () => (call && call.active() ? call.end() : startCall()));
    for (const [k, v] of Object.entries(problems)) ctx.log(`hotkey: ${v}`);
    hotkeyProblems = problems;
    return problems;
  }
  let hotkeyProblems = {};

  function setHotkey(key, fallback, accel) {
    const before = ctx.prefs.get(key, fallback);
    ctx.prefs.set(key, accel || '');
    const problems = registerHotkeys();
    if (problems[key]) {
      ctx.prefs.set(key, before);
      registerHotkeys();
      return { ok: false, error: problems[key] };
    }
    return { ok: true };
  }

  // ---------------------------------------------------------------- the menu bar

  function trayIcon() {
    for (const dir of [RENDERER, path.join(__dirname, '..', '..', 'build')]) {
      const f = path.join(dir, 'trayTemplate.png');
      if (fs.existsSync(f)) {
        const img = nativeImage.createFromPath(f);
        img.setTemplateImage(true);
        return img;
      }
    }
    return nativeImage.createEmpty();
  }

  function createTray() {
    const t = new Tray(trayIcon());
    t.setToolTip('Dictate & Delegate');
    t.on('click', () => t.popUpContextMenu(trayMenu()));
    t.on('right-click', () => t.popUpContextMenu(trayMenu()));
    return t;
  }

  function accelLabel(a) {
    return a ? a.replace(/Alt\+?/g, '⌥').replace(/Shift\+?/g, '⇧').replace(/(Command|Cmd|CommandOrControl)\+?/g, '⌘').replace(/(Control|Ctrl)\+?/g, '⌃') : '';
  }

  function trayMenu() {
    const recording = dictation.busy();
    const inCall = call && call.active();
    return Menu.buildFromTemplate([
      {
        label: recording ? 'Stop dictating' : 'Start dictating',
        accelerator: undefined,
        sublabel: accelLabel(ctx.prefs.get(HOTKEY, DEFAULT_HOTKEY)),
        click: () => toggleDictation(),
        enabled: !inCall,
      },
      inCall
        ? { label: 'End hands-free call', click: () => call.end() }
        : { label: 'Hands-free with your chief', sublabel: accelLabel(ctx.prefs.get(CALL_HOTKEY, DEFAULT_CALL_HOTKEY)), click: () => startCall(), enabled: !!call },
      ...(inCall ? [{ label: 'Send now', click: () => call.sendNow() }] : []),
      { type: 'separator' },
      { label: 'Open Dictate & Delegate', click: () => openMain('home') },
      { label: 'Notes', click: () => openMain('notes') },
      { label: 'Settings…', click: () => openMain('settings') },
      { type: 'separator' },
      { label: 'Quit Dictate & Delegate', click: () => app.quit() },
    ]);
  }

  function updateTray() {
    if (!tray || tray.isDestroyed()) return;
    const snap = dictation.snapshot();
    let title = '';
    if (snap.state === 'recording') title = '● ' + require('../core/dictation').elapsed(snap.elapsedMs || 0);
    else if (snap.busy) title = '…';
    else if (call && call.active()) title = '📞 ' + (call.shortState ? call.shortState() : '');
    tray.setTitle(title, { fontType: 'monospacedDigit' });
  }
  setInterval(() => updateTray(), 1000);

  // ---------------------------------------------------------------- the main window

  function openMain(page = 'home') {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('navigate', page);
      mainWindow.show();
      mainWindow.focus();
      app.focus({ steal: true });
      return mainWindow;
    }
    mainWindow = new BrowserWindow({
      width: 980,
      height: 720,
      minWidth: 720,
      minHeight: 520,
      title: 'Dictate & Delegate',
      titleBarStyle: 'hiddenInset',
      show: false,
      webPreferences: { preload: PRELOAD, contextIsolation: true, sandbox: true },
    });
    mainWindow.loadFile(path.join(RENDERER, 'app.html'), { query: { page } });
    mainWindow.once('ready-to-show', () => {
      mainWindow.show();
      app.focus({ steal: true });
    });
    // Links open in the browser, never inside the app.
    mainWindow.webContents.setWindowOpenHandler(({ url }) => {
      openExternal(url);
      return { action: 'deny' };
    });
    mainWindow.webContents.on('will-navigate', (e, url) => {
      if (!url.startsWith('file:')) {
        e.preventDefault();
        openExternal(url);
      }
    });
    mainWindow.on('closed', () => (mainWindow = null));
    return mainWindow;
  }

  function openExternal(url) {
    if (/^(https?:|x-apple\.systempreferences:|mailto:)/.test(String(url))) shell.openExternal(url);
  }

  /** Insert from a notification: give the focus back to the app before, then paste. */
  async function insertFromNotification(text) {
    app.hide();
    await new Promise((r) => setTimeout(r, 350));
    const r = await insertion.insert(text, { method: ctx.prefs.get(INSERT_METHOD, 'paste'), log: ctx.log });
    if (r.how === 'copied') ctx.log('notification: text copied instead of inserted');
  }

  // ---------------------------------------------------------------- bb sign-in

  async function handleUrl(url) {
    let u;
    try {
      u = new URL(url);
    } catch {
      return;
    }
    if (u.protocol !== 'talkrouter:' || !/oauth\/callback/.test(u.host + u.pathname)) return;
    const query = Object.fromEntries(u.searchParams.entries());
    let message, ok = false;
    try {
      const handle = await ctx.oauth.complete(query);
      ctx.bb.useConnect(handle);
      ctx.skills.sync(true);
      message = `Signed in to ${handle}.getbb.app`;
      ok = true;
    } catch (e) {
      message = e && e.message ? (/^Sign-in/.test(e.message) ? e.message : `Sign-in failed: ${e.message}`) : 'Sign-in failed';
    }
    ctx.log(`bb: ${message}`);
    ctx.emit('settings', 'bbAuth');
    const w = openMain('bb');
    const send = () => w.webContents.send('event', 'signin', { ok, message });
    if (w.webContents.isLoading()) w.webContents.once('did-finish-load', send);
    else send();
    notifier.refresh();
  }

  // ---------------------------------------------------------------- what the settings window may ask

  function appShell() {
    return {
      startDictation: () => dictation.start(),
      toggleDictation: () => toggleDictation(),
      startCall: () => startCall(),
      endCall: () => call && call.end(),
      sendNow: () => call && call.sendNow(),
      callSnapshot: () => (call ? call.snapshot() : null),
      onCallChange: (fn) => {
        callListeners.add(fn);
        return () => callListeners.delete(fn);
      },
      hotkey: () => ctx.prefs.get(HOTKEY, DEFAULT_HOTKEY),
      setHotkey: (a) => setHotkey(HOTKEY, DEFAULT_HOTKEY, a),
      callHotkey: () => ctx.prefs.get(CALL_HOTKEY, DEFAULT_CALL_HOTKEY),
      setCallHotkey: (a) => setHotkey(CALL_HOTKEY, DEFAULT_CALL_HOTKEY, a),
      hotkeyProblems: () => hotkeyProblems,
      permissions: () => ({
        microphone: process.platform === 'darwin' ? systemPreferences.getMediaAccessStatus('microphone') : 'granted',
        accessibility: insertion.accessibility(false),
        notifications: require('electron').Notification.isSupported(),
      }),
      requestMicrophone: async () => (process.platform === 'darwin' ? systemPreferences.askForMediaAccess('microphone') : true),
      requestAccessibility: () => insertion.accessibility(true),
      openPrivacySettings: (kind) => {
        const pane = { microphone: 'Privacy_Microphone', accessibility: 'Privacy_Accessibility' }[kind];
        openExternal(pane ? `x-apple.systempreferences:com.apple.preference.security?${pane}` : 'x-apple.systempreferences:com.apple.preference.notifications');
      },
      signIn: async (handle) => {
        const url = await ctx.oauth.begin(handle);
        shell.openExternal(url);
        return true;
      },
      micDevices: () => audio.micDevices(),
      systemVoices: () => audio.voices(),
      openExternal: (url) => openExternal(url),
      copy: (text) => clipboard.writeText(String(text)),
      chooseFile: async (opts = {}) => {
        const r = await dialog.showOpenDialog(mainWindow || undefined, { properties: ['openFile'], ...opts });
        return r.canceled ? null : r.filePaths[0];
      },
      saveFile: async (opts = {}) => {
        const r = await dialog.showSaveDialog(mainWindow || undefined, opts);
        return r.canceled ? null : r.filePath;
      },
      testInsert: async (text) => {
        app.hide();
        await new Promise((r) => setTimeout(r, 400));
        return insertion.insert(text, { method: ctx.prefs.get(INSERT_METHOD, 'paste'), log: ctx.log });
      },
      notifierState: () => (notifier ? notifier.state : 'off'),
    };
  }
}
