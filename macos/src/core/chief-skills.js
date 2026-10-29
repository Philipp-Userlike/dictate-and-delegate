'use strict';
// The instructions as three bb user skills: chief-of-staff and dictate-and-delegate for the chief,
// dictate-and-delegate-talker for the talker of hands-free calls. Their templates ship in the
// Android app's assets/skills (shared); the app fills in the user's name, the ntfy server and topic
// and the bb link base. Each filled file ends with a marker holding a hash of its content, e.g.
// <!-- dictate-and-delegate-skill: 3f2a9c1b04de -->: a changed template (an app update) or a
// changed placeholder (topic, server, link, name) gives another hash, so one comparison says
// whether bb has the current version.
//
// The app reads the installed markers through bb's MCP "read" tool. When one is missing or
// different, a short-lived bb thread writes the files to ~/.bb/skills (MCP snippets can't write
// files), the app waits until bb lists the new versions, then archives that thread.
//
// Mac: the phone and the Mac install the same skills into the same bb. Filled differently (another
// topic, say), each would keep reinstalling over the other, so the Mac takes the phone's values
// from the installed skill (adoptFromBb) before it fills its own.

const crypto = require('crypto');
const { tr, sleep: defaultSleep, SerialQueue } = require('./util');
const assets = require('./assets');
const bridgeLib = require('./bb-bridge');

const CHIEF = 'chief-of-staff';
const ROUTER = 'dictate-and-delegate';
const TALKER = 'dictate-and-delegate-talker';

/** The second skill's name before the rename (3.1.0): the install removes it. */
const OLD_ROUTER = 'talk-router';
const NAMES = [CHIEF, ROUTER, TALKER];

const INSTALL_TITLE = 'Dictate & Delegate: install skills';

/** The line a chief gets when it's created or adopted, and an older chief once. */
const START = "You're my chief of staff. Use the chief-of-staff and dictate-and-delegate skills.";

/** The line a talker gets once, when it's set and whenever its skill changed. */
const TALKER_START =
  "You're the talker in my hands-free calls from Dictate & Delegate: you do the talking while my chief of staff" +
  ' does the work. Use the ' +
  TALKER +
  ' skill for every call.';

/** Prefs: which talker was told, with which version of its skill: "<thread id>:<hash>". */
const TALKER_TOLD = 'talkerToldSkill';

/** Prefs: the user's name; the hashes installed last time; the app version that checked. */
const USER_NAME = 'bbUserName';
const INSTALLED = 'skillsInstalled';
const CHECKED_VERSION = 'skillsCheckedVersion';

/** Prefs: the chief was told to use the skills (new chiefs at once, older ones after install). */
const CHIEF_TOLD = 'chiefToldSkills2'; // 2: after the rename, chiefs hear the new names once.

/** Where the install stands, for setup and Settings → bb. */
const State = Object.freeze({
  IDLE: 'IDLE',
  CHECKING: 'CHECKING',
  INSTALLING: 'INSTALLING',
  INSTALLED: 'INSTALLED',
  FAILED: 'FAILED',
});

/** The Mac app's version: where Android compares BuildConfig.VERSION_CODE (skillsCheckedVersion). */
const APP_VERSION = (() => {
  try {
    return require('../../package.json').version;
  } catch {
    return '0';
  }
})();

// ------------------------------------------------------------------ filling (unit-tested)

/** String.replace of every occurrence, taking the replacement literally (no $& patterns). */
function replaceAll(s, search, value) {
  return s.split(search).join(value);
}

/**
 * A template with its placeholders filled and its marker appended. Without a name, "{{NAME}}"
 * reads "the user"; without a link base, the notification has no Click header.
 */
function fill(skill, template, name, server, topic, linkBase) {
  const n = name == null ? '' : String(name).trim();
  let t = template;
  if (!n) t = replaceAll(replaceAll(t, "{{NAME}}'s", "the user's"), '{{NAME}}', 'the user');
  else {
    // In the front matter the name sits in a double-quoted YAML string: escape \ and ".
    const end = t.startsWith('---\n') ? t.indexOf('\n---', 4) : -1;
    const front = end < 0 ? '' : t.substring(0, end);
    const body = end < 0 ? t : t.substring(end);
    t = replaceAll(front, '{{NAME}}', n.replace(/\\/g, '\\\\').replace(/"/g, '\\"')) + replaceAll(body, '{{NAME}}', n);
  }
  const link = linkBase || '';
  if (!link) t = replaceAll(t, ' -H "Click: {{LINK_BASE}}/threads/$BB_THREAD_ID"', '');
  t = replaceAll(replaceAll(replaceAll(t, '{{LINK_BASE}}', link), '{{NTFY_SERVER}}', server), '{{TOPIC}}', topic);
  if (!t.endsWith('\n')) t += '\n';
  return t + '\n<!-- ' + skill + '-skill: ' + hash(t) + ' -->\n';
}

/** The first 12 hex digits of the SHA-256 of a filled file (without its marker). */
function hash(filled) {
  return crypto.createHash('sha256').update(filled, 'utf8').digest('hex').substring(0, 12);
}

/** The hash in a filled file's marker; "" without one. */
function marker(content) {
  const m = /<!-- ([a-z-]+)-skill: ([0-9a-f]+) -->/.exec(content || '');
  return m ? m[2] : '';
}

function template(name) {
  return assets.read('skills/' + name + '/SKILL.md');
}

/** "chief-of-staff:<hash>,dictate-and-delegate:<hash>": what's installed when all is current. */
function summary(files) {
  return Object.entries(files)
    .map(([k, v]) => k + ':' + marker(v))
    .join(',');
}

// ------------------------------------------------------------------ bb snippets

/** Reads the markers of the installed user skills: {"skills": {name: hash, "" missing, "old" unmarked}}. */
function checkSnippet() {
  return (
    'const l = await bb.json(["skill","list"]);\n' +
    'const out = {};\n' +
    'for (const name of ["' + CHIEF + '","' + ROUTER + '","' + TALKER + '","' + OLD_ROUTER + '"]) {\n' +
    '  const s = (l.skills || []).find(x => x.name === name && x.scope === "bb-user");\n' +
    '  if (!s) { out[name] = ""; continue; }\n' +
    '  const f = await bb.json(["skill","show",s.id]);\n' +
    '  const m = /<!-- [a-z-]+-skill: ([0-9a-f]+) -->/.exec(f.content || "");\n' +
    '  out[name] = m ? m[1] : "old";\n' +
    '}\n' +
    'return {skills: out};'
  );
}

/** Mac: reads the installed user skills' whole files: {"contents": {name: content, "" missing}}. */
function contentsSnippet() {
  return (
    'const l = await bb.json(["skill","list"]);\n' +
    'const contents = {};\n' +
    'for (const name of ["' + ROUTER + '","' + TALKER + '","' + CHIEF + '"]) {\n' +
    '  const s = (l.skills || []).find(x => x.name === name && x.scope === "bb-user");\n' +
    '  if (!s) { contents[name] = ""; continue; }\n' +
    '  const f = await bb.json(["skill","show",s.id]);\n' +
    '  contents[name] = f.content || "";\n' +
    '}\n' +
    'return {contents};'
  );
}

/**
 * The install thread's brief, for a capable colleague: what the skills are for, where bb reads
 * them, what done looks like, and the files; it may fix what obviously wouldn't load. The app never
 * needs the files byte for byte: it only reads each file's marker line.
 */
function installPrompt(files) {
  let s =
    'Please install or update three bb user skills. They come from Dictate & Delegate, the voice' +
    " app on the user's phone: `chief-of-staff` describes the role of the user's chief of" +
    ' staff, `dictate-and-delegate` explains the messages the app sends and how to notify the' +
    ' user or send text back through ntfy, and `dictate-and-delegate-talker` is for the' +
    " talker, the thread that does the talking in the user's hands-free calls.\n\n" +
    'bb reads user skills from `~/.bb/skills/<name>/SKILL.md`. Put each file below there,' +
    " replacing what's there now. Each file ends with a line like" +
    ' `<!-- dictate-and-delegate-skill: 3f2a9c1b04de -->`: the app reads that line to tell' +
    ' whether the installed skill is current, so keep it as it is. If something in a file' +
    " wouldn't load (bb skips a skill whose front matter isn't valid YAML, for example), fix" +
    ' it the way the author obviously meant, and keep the meaning.\n\n' +
    'The second skill used to be called `' +
    OLD_ROUTER +
    '`; remove `~/.bb/skills/' +
    OLD_ROUTER +
    "` if it's there.\n\n" +
    "You're done when `bb skill list` shows `chief-of-staff`, `dictate-and-delegate` and" +
    ' `dictate-and-delegate-talker` as user skills and no `' +
    OLD_ROUTER +
    '`. Then reply with one short line saying so. This is housekeeping, so no' +
    ' notification.\n';
  for (const [name, content] of Object.entries(files)) {
    s += '\n===== ~/.bb/skills/' + name + '/SKILL.md (begins on the next line) =====\n' + content + '===== end of ' + name + ' =====\n';
  }
  return s;
}

function spawnSnippet(prompt) {
  return (
    'const t = await bb.json(["thread","spawn","--project",' +
    bridgeLib.js(bridgeLib.PROJECT) +
    ',"--title",' +
    bridgeLib.js(INSTALL_TITLE) +
    ',"--prompt-file","-"], {stdin: ' +
    bridgeLib.js(prompt) +
    '});\nreturn {id: t.id, status: t.status};'
  );
}

function archiveSnippet(id) {
  return 'await bb.json(["thread","archive",' + bridgeLib.js(id) + ']);\nreturn {archived: true};';
}

/** Which skills differ from what bb has (missing, unmarked or another hash), and the old one if it's still there. */
function outdated(files, installed) {
  const inst = installed || {};
  const out = [];
  for (const [name, content] of Object.entries(files)) if (marker(content) !== String(inst[name] || '')) out.push(name);
  if (inst[OLD_ROUTER]) out.push(OLD_ROUTER);
  return out;
}

/** What TALKER_TOLD holds once this talker was told about this version of its skill; "" without a talker. */
function talkerToldValue(talker, files) {
  return !talker ? '' : talker + ':' + marker(files[TALKER] || '');
}

// ------------------------------------------------------------------ reading an installed skill (Mac)

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * The user's name an installed skill was filled with, found by matching the template's body lines
 * that hold {{NAME}}; "" when it was filled without one ("the user"), null when no line matches
 * (another template version, or a file the install thread changed).
 */
function nameIn(content, tmpl) {
  const end = tmpl.startsWith('---\n') ? tmpl.indexOf('\n---', 4) : -1;
  const body = end < 0 ? tmpl : tmpl.substring(end);
  for (const line of body.split('\n')) {
    if (!line.includes('{{NAME}}') || /\{\{(?!NAME\}\})/.test(line)) continue;
    let first = true;
    const pattern = line
      .split('{{NAME}}')
      .map(escapeRegExp)
      .reduce((acc, part) => {
        const group = first ? '(.+?)' : '\\1';
        first = false;
        return acc + group + part;
      });
    const m = new RegExp('^' + pattern + '$', 'm').exec(content);
    if (m) return m[1] === 'the user' ? '' : m[1];
  }
  return null;
}

/**
 * What an installed dictate-and-delegate (or talker) skill was filled with: {server, topic,
 * linkBase, name}; a field is null when the file doesn't show it.
 */
function parseInstalled(content, tmpl) {
  const out = { server: null, topic: null, linkBase: null, name: null, marker: marker(content) };
  const ntfy = /--data-binary @- (https?:\/\/\S+)\/([A-Za-z0-9_-]{1,64}) <<'TEXT'/.exec(content);
  if (ntfy) {
    out.server = ntfy[1];
    out.topic = ntfy[2];
  }
  if (tmpl && tmpl.includes('{{LINK_BASE}}')) {
    const click = /-H "Click: (\S*?)\/threads\/\$BB_THREAD_ID"/.exec(content);
    out.linkBase = click ? click[1] : ntfy ? '' : null;
  }
  if (tmpl) out.name = nameIn(content, tmpl);
  return out;
}

// ------------------------------------------------------------------ keeping bb current

class ChiefSkills {
  /**
   * bridge: the BbBridge; prefs: the settings. The rest is for tests: template(name), clock, sleep,
   * the waits (Android's installWaitMs, pollMs, talkerGraceMs), appVersion, onChange(state).
   */
  constructor({
    bridge,
    prefs,
    log = () => {},
    template: templateFn = template,
    clock = () => Date.now(),
    sleep = defaultSleep,
    installWaitMs = 4 * 60_000,
    pollMs = 3000,
    talkerGraceMs = 20_000,
    appVersion = APP_VERSION,
    onChange = () => {},
  } = {}) {
    this.bridge = bridge;
    this.prefs = prefs || bridge.prefs;
    this.log = log;
    this.template = templateFn;
    this.clock = clock;
    this.sleep = sleep;
    this.installWaitMs = installWaitMs;
    this.pollMs = pollMs;
    this.talkerGraceMs = talkerGraceMs;
    this.appVersion = appVersion;
    this.onChange = onChange;
    this.state = State.IDLE;
    this.problem = '';
    /** Why the talker's skill isn't in bb, and why the talker couldn't be told; "" when all is well. */
    this.talkerSkillProblem = '';
    this.talkerTellProblem = '';
    this.running = false;
    this.talkerQueue = new SerialQueue();
  }

  setState(state) {
    this.state = state;
    try {
      this.onChange(state);
    } catch {
      /* A listener's problem. */
    }
  }

  /** What the talker setting says is wrong, "" when nothing is. */
  talkerProblem() {
    return this.talkerSkillProblem || this.talkerTellProblem;
  }

  /**
   * The skills bb should have: name → filled file. The talker's only with a talker set (and on):
   * a user without one doesn't wait for it, and setting one installs it.
   */
  filled() {
    const bb = this.bridge;
    const files = {};
    for (const name of NAMES) {
      if (name !== TALKER || bb.talkerId()) {
        files[name] = fill(name, this.template(name), this.prefs.get(USER_NAME, ''), bb.ntfyServer(), bb.ntfyTopic(), bb.linkBase());
      }
    }
    return files;
  }

  /**
   * Brings bb's skills up to date. Without force it only asks bb when something changed since the
   * last install: the filled files (template, name, topic, server, link) or the app version.
   * Resolves when done (the app needn't wait); never rejects: see state and problem.
   */
  async sync(force = false) {
    if (this.running || !this.bridge.connected()) return;
    this.running = true;
    let tellTalker = false;
    try {
      await this.adoptIfNew();
      let files;
      try {
        files = this.filled();
      } catch {
        return;
      }
      const same = summary(files) === this.prefs.get(INSTALLED, '') && this.prefs.get(CHECKED_VERSION, 0) === this.appVersion;
      if (same && !force) {
        this.setState(State.INSTALLED);
        tellTalker = !this.talkerTold(files);
        return;
      }
      try {
        const missing = await this.ensure(files);
        // The talker's skill if bb didn't take it: tried again at the next sync, and said on its
        // own, never as the chief's skills failing.
        const got = { ...files };
        for (const name of missing) delete got[name];
        this.prefs.set(INSTALLED, summary(got));
        this.prefs.set(CHECKED_VERSION, this.appVersion);
        this.talkerSkillProblem = missing.includes(TALKER) ? tr("bb didn't install the talker's skill. It's tried again next time.") : '';
        this.problem = '';
        this.setState(State.INSTALLED);
        await this.tellOlderChief();
      } catch (ex) {
        this.problem = (ex && ex.message) || String(ex);
        this.setState(State.FAILED);
      }
      tellTalker = true;
    } finally {
      this.running = false;
    }
    if (tellTalker) await this.safelyTellTalker(); // Its own try: a talker that can't be told is said apart.
  }

  /**
   * Checks bb and, if needed, installs through a thread and waits for it. Only the chief's skills
   * must be there for it to succeed; returns the optional ones (the talker's) still missing.
   */
  async ensure(files) {
    const bb = this.bridge;
    this.setState(State.CHECKING);
    const installed = (await bb.read(checkSnippet())).skills;
    if (outdated(files, installed || {}).length === 0) return [];
    this.setState(State.INSTALLING);
    const id = (await bb.run(spawnSnippet(installPrompt(files)))).id || '';
    if (!id) throw new Error(tr("bb didn't say which thread it created."));
    try {
      const until = this.clock() + this.installWaitMs;
      let talkerSince = 0;
      for (;;) {
        await this.sleep(this.pollMs);
        const skills = (await bb.read(checkSnippet())).skills;
        const left = skills && typeof skills === 'object' ? outdated(files, skills) : [CHIEF];
        if (left.length === 0) return [];
        if (!left.includes(CHIEF) && !left.includes(ROUTER) && !left.includes(OLD_ROUTER)) {
          // The chief's are in: the talker's gets a little longer (written last), then it's said apart.
          if (talkerSince === 0) talkerSince = this.clock();
          if (this.clock() - talkerSince > this.talkerGraceMs) return left;
        }
        if (this.clock() > until) throw new Error(tr("bb didn't install the chief's skills in time. Try again."));
      }
    } finally {
      try {
        await bb.run(archiveSnippet(id));
      } catch {
        /* The thread stays; it did its job or failed. */
      }
    }
  }

  talkerTold(files) {
    const value = talkerToldValue(this.bridge.talkerId(), files);
    return !value || value === this.prefs.get(TALKER_TOLD, '');
  }

  /**
   * The talker (set and on) gets TALKER_START once: when it's set or changed, and whenever its
   * skill changed. Only once bb has the current skills, so it finds the one it's told to use.
   */
  tellTalker() {
    return this.talkerQueue.run(async () => {
      const files = this.filled();
      if (this.talkerTold(files) || summary(files) !== this.prefs.get(INSTALLED, '')) return;
      const talker = this.bridge.talkerId();
      await this.bridge.send(talker, TALKER_START);
      this.prefs.set(TALKER_TOLD, talkerToldValue(talker, files));
    });
  }

  /** The same, never throwing: a failure is said apart, and tried again at the next call or sync. */
  async safelyTellTalker() {
    try {
      await this.tellTalker();
      this.talkerTellProblem = '';
    } catch (ex) {
      this.talkerTellProblem = tr("Couldn't tell the talker to use its skill: %1$s", (ex && ex.message) || String(ex));
      this.log('talker not told: ' + ((ex && ex.message) || ex));
    }
  }

  /** A chief from before the skills gets the one line once, now that they're there. */
  async tellOlderChief() {
    const bb = this.bridge;
    if (!bb.chiefId() || this.prefs.get(CHIEF_TOLD, false)) return;
    await bb.send(bb.chiefId(), START);
    this.prefs.set(CHIEF_TOLD, true);
  }

  // ---------------------------------------------------------------- Mac: the phone's values

  /**
   * Reads the installed skills from bb and takes what the phone filled them with: the ntfy topic
   * and server, the user's name and the link base. Without force only what this Mac hasn't set yet
   * (no topic in the vault, no server or name saved, no custom link base); with force (the user
   * asks) all of it. Returns {found, values, adopted: [field names], exact, linkBaseDiffers}:
   * exact says the Mac now fills dictate-and-delegate exactly as bb has it (same template too).
   */
  async adoptFromBb({ force = false } = {}) {
    const bb = this.bridge;
    const contents = (await bb.read(contentsSnippet())).contents || {};
    const router = contents[ROUTER] || '';
    const talker = contents[TALKER] || '';
    const chief = contents[CHIEF] || '';
    if (!router && !talker && !chief) return { found: false, values: null, adopted: [], exact: false, linkBaseDiffers: false };
    const tmpl = (name) => {
      try {
        return this.template(name);
      } catch {
        return '';
      }
    };
    const r = router ? parseInstalled(router, tmpl(ROUTER)) : {};
    const t = talker ? parseInstalled(talker, tmpl(TALKER)) : {};
    const c = chief ? parseInstalled(chief, tmpl(CHIEF)) : {};
    const pick = (...vals) => vals.find((v) => v != null) ?? null;
    const values = {
      topic: pick(r.topic, t.topic),
      server: pick(r.server, t.server),
      name: pick(r.name, t.name, c.name),
      linkBase: router ? (r.linkBase ?? null) : null,
    };
    const adopted = [];
    if (values.topic && (force || !bb.hasNtfyTopic()) && (!bb.hasNtfyTopic() || bb.ntfyTopic() !== values.topic)) {
      bb.setNtfyTopic(values.topic);
      adopted.push('topic');
    }
    if (values.server && (force || this.prefs.get(bridgeLib.NTFY_SERVER, undefined) === undefined) && bb.ntfyServer() !== values.server) {
      bb.setNtfyServer(values.server);
      adopted.push('server');
    }
    if (values.name != null && (force || !this.prefs.get(USER_NAME, '')) && this.prefs.get(USER_NAME, '') !== values.name) {
      if (values.name) this.prefs.set(USER_NAME, values.name);
      else this.prefs.remove(USER_NAME);
      adopted.push('name');
    }
    let linkBaseDiffers = false;
    if (values.linkBase != null && values.linkBase !== bb.linkBase()) {
      // Only a custom address has a link base of its own; bb Connect's follows the handle.
      if (bb.mode() === bridgeLib.CUSTOM && (force || !this.prefs.get(bridgeLib.LINK_BASE, ''))) {
        this.prefs.set(bridgeLib.LINK_BASE, values.linkBase);
        adopted.push('linkBase');
      } else linkBaseDiffers = true;
    }
    let exact = false;
    if (router && bb.hasNtfyTopic()) {
      const mine = fill(ROUTER, tmpl(ROUTER), this.prefs.get(USER_NAME, ''), bb.ntfyServer(), bb.ntfyTopic(), bb.linkBase());
      exact = marker(mine) === marker(router);
    }
    if (adopted.length) this.log('took ' + adopted.join(', ') + ' from the skills in bb');
    return { found: true, values, adopted, exact, linkBaseDiffers };
  }

  /**
   * Before this Mac makes its own topic: takes the phone's from bb, if the phone set bb up. Best
   * effort; returns adoptFromBb's answer, or null when there was nothing to do or it failed.
   */
  async adoptIfNew() {
    if (this.bridge.hasNtfyTopic() || !this.bridge.connected()) return null;
    try {
      return await this.adoptFromBb();
    } catch (ex) {
      this.log("couldn't read the skills in bb: " + ((ex && ex.message) || ex));
      return null;
    }
  }
}

module.exports = {
  ChiefSkills,
  State,
  CHIEF,
  ROUTER,
  TALKER,
  OLD_ROUTER,
  NAMES,
  INSTALL_TITLE,
  START,
  TALKER_START,
  TALKER_TOLD,
  USER_NAME,
  INSTALLED,
  CHECKED_VERSION,
  CHIEF_TOLD,
  APP_VERSION,
  fill,
  hash,
  marker,
  template,
  summary,
  checkSnippet,
  contentsSnippet,
  installPrompt,
  spawnSnippet,
  archiveSnippet,
  outdated,
  talkerToldValue,
  nameIn,
  parseInstalled,
};
