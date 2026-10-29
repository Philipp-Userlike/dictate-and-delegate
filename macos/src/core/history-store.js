'use strict';
// Note versions as JSON, by ID, recording ID and date. No automatic expiry. Android keeps them in
// SQLite; here each version is one JSON file (<dir>/<id>.json) holding exactly the entry's JSON, so
// an entry reads the same as on Android. Synchronous, like the Java (which runs on a worker).

const fs = require('fs');
const path = require('path');
const { tr } = require('./util');
const { HistoryEntry } = require('./history-entry');

class HistoryStore {
  /** dir: where the notes live (created when missing). */
  constructor(dir) {
    this.dir = dir;
    fs.mkdirSync(dir, { recursive: true });
  }

  file(id) {
    // IDs are UUIDs (or "legacy-v1"); anything else is encoded so it stays one file in dir.
    return path.join(this.dir, encodeURIComponent(String(id)).replace(/^\./, '%2E') + '.json');
  }

  save(entry) {
    const target = this.file(entry.id());
    const tmp = target + '.' + process.pid + '.tmp';
    try {
      fs.writeFileSync(tmp, JSON.stringify(entry.json), { mode: 0o600 });
      fs.renameSync(tmp, target);
    } catch (ex) {
      try {
        fs.unlinkSync(tmp);
      } catch {
        /* Nothing written. */
      }
      throw new Error(tr('Could not save transcript history.'), { cause: ex });
    }
  }

  get(id) {
    return this.read(this.file(id));
  }

  /** The entry's JSON (a plain object), or null: for code that keeps notes as plain objects. */
  load(id) {
    const e = this.get(id);
    return e ? e.json : null;
  }

  /** Saves a note kept as a plain object (HistoryEntry keys). */
  saveJson(json) {
    this.save(new HistoryEntry(json));
  }

  read(file) {
    let text;
    try {
      text = fs.readFileSync(file, 'utf8');
    } catch (ex) {
      if (ex.code === 'ENOENT') return null;
      throw ex;
    }
    try {
      return new HistoryEntry(JSON.parse(text));
    } catch {
      return null; // A damaged file (a crash mid-write is excluded by the rename) is skipped.
    }
  }

  /** Every version, newest first (created, then id, descending). */
  entries() {
    let names;
    try {
      names = fs.readdirSync(this.dir);
    } catch (ex) {
      if (ex.code === 'ENOENT') return [];
      throw ex;
    }
    const out = [];
    for (const n of names) {
      if (!n.endsWith('.json')) continue;
      const e = this.read(path.join(this.dir, n));
      if (e) out.push(e);
    }
    out.sort((a, b) => b.created() - a.created() || (a.id() < b.id() ? 1 : a.id() > b.id() ? -1 : 0));
    return out;
  }

  list(limit, offset) {
    const from = Math.max(0, offset);
    return this.entries().slice(from, from + Math.max(1, limit));
  }

  all() {
    return this.entries();
  }

  versions(recordingId) {
    return this.entries().filter((e) => e.recordingId() === recordingId);
  }

  /** Marks the version you kept: preferred on it, off on the recording's other versions. */
  prefer(selected) {
    for (const entry of this.versions(selected.recordingId())) {
      entry.json.preferred = entry.id() === selected.id();
      this.save(entry);
    }
  }

  delete(id) {
    try {
      fs.unlinkSync(this.file(id));
    } catch (ex) {
      if (ex.code !== 'ENOENT') throw ex;
    }
  }
}

module.exports = { HistoryStore };
