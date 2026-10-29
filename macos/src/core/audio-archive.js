'use strict';
// Private PCM archives. Expiry is enforced before every access and on app launch.

const fs = require('fs');
const path = require('path');
const { tr } = require('./util');

const MAX_BYTES = 100 * 1024 * 1024;
const WEEK = 7 * 24 * 60 * 60 * 1000;

class AudioArchive {
  /** dir: the recordings folder; prefs: settings ("retainAudio", default on); clock for tests. */
  constructor({ dir, prefs, clock = () => Date.now() }) {
    this.dir = dir;
    this.prefs = prefs;
    this.clock = clock;
    fs.mkdirSync(dir, { recursive: true });
  }

  keep() {
    return !this.prefs || this.prefs.get('retainAudio', true) !== false;
  }

  path(id) {
    if (!/^[A-Za-z0-9-]{1,80}$/.test(String(id))) throw new Error(tr('Invalid recording ID'));
    return path.join(this.dir, id + '.pcm');
  }

  /** The recording's PCM file, or null. */
  get(id) {
    this.prune();
    const file = this.path(id);
    return isFile(file) ? file : null;
  }

  /** Keeps a copy of a recording (unless switched off or over MAX_BYTES). */
  retain(id, source) {
    this.prune();
    if (!this.keep() || fs.statSync(source).size > MAX_BYTES) return;
    const target = this.path(id);
    if (!fs.existsSync(target)) {
      const temporary = path.join(this.dir, id + '.tmp');
      fs.copyFileSync(source, temporary);
      // macOS may clone the file with its old date; the week counts from now, as on Android.
      const now = new Date(this.clock());
      fs.utimesSync(temporary, now, now);
      fs.renameSync(temporary, target);
    }
    this.prune();
  }

  delete(id) {
    fs.rmSync(this.path(id), { force: true });
  }

  deleteAll() {
    for (const name of list(this.dir)) fs.rmSync(path.join(this.dir, name), { force: true, recursive: true });
  }

  prune() {
    const files = list(this.dir)
      .map((name) => {
        const file = path.join(this.dir, name);
        try {
          const st = fs.statSync(file);
          return { name, file, mtime: st.mtimeMs, size: st.size };
        } catch {
          return null;
        }
      })
      .filter(Boolean)
      .sort((a, b) => a.mtime - b.mtime);
    let total = 0;
    const cutoff = this.clock() - WEEK;
    const keep = this.keep();
    const kept = [];
    for (const f of files) {
      if (!keep || f.mtime < cutoff || f.name.endsWith('.tmp')) fs.rmSync(f.file, { force: true });
      else {
        total += f.size;
        kept.push(f);
      }
    }
    // Over the cap: the oldest go first.
    for (const f of kept)
      if (total > MAX_BYTES) {
        fs.rmSync(f.file, { force: true });
        total -= f.size;
      }
  }
}

function list(dir) {
  try {
    return fs.readdirSync(dir);
  } catch {
    return [];
  }
}

function isFile(file) {
  try {
    return fs.statSync(file).isFile();
  } catch {
    return false;
  }
}

module.exports = { AudioArchive, MAX_BYTES, WEEK };
