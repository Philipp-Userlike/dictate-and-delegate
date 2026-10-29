'use strict';
// Settings and secrets. Settings use the Android app's SharedPreferences keys and defaults, so the
// two apps read alike; secrets (API keys, bb tokens, the ntfy topic) live in a vault, which on the
// Mac is encrypted with a key kept in the macOS Keychain (src/main/secrets.js).

const fs = require('fs');
const path = require('path');

/** Settings in memory (tests), with the SharedPreferences-like API every core module uses. */
class MemoryPrefs {
  constructor(values = {}) {
    this.values = { ...values };
    this.listeners = new Set();
  }

  get(key, fallback) {
    return Object.prototype.hasOwnProperty.call(this.values, key) ? this.values[key] : fallback;
  }

  has(key) {
    return Object.prototype.hasOwnProperty.call(this.values, key);
  }

  set(key, value) {
    if (value === undefined || value === null) return this.remove(key);
    this.values[key] = value;
    this.save();
    for (const l of this.listeners) l(key, value);
  }

  remove(key) {
    delete this.values[key];
    this.save();
    for (const l of this.listeners) l(key, undefined);
  }

  all() {
    return { ...this.values };
  }

  /** Called with (key, value) after each change. Returns a function that unsubscribes. */
  onChange(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  save() {}
}

/** Settings in a JSON file, written atomically on each change. */
class FilePrefs extends MemoryPrefs {
  constructor(file) {
    let values = {};
    try {
      values = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
      /* First start, or a broken file: defaults. */
    }
    super(values);
    this.file = file;
  }

  save() {
    if (!this.file) return;
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = this.file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(this.values, null, 2), { mode: 0o600 });
    fs.renameSync(tmp, this.file);
  }
}

/** Secrets in memory (tests). get() returns null when there's none. */
class MemoryVault {
  constructor(values = {}) {
    this.values = { ...values };
  }

  get(name) {
    return Object.prototype.hasOwnProperty.call(this.values, name) ? this.values[name] : null;
  }

  set(name, value) {
    if (value === null || value === undefined || value === '') delete this.values[name];
    else this.values[name] = value;
  }

  remove(name) {
    delete this.values[name];
  }

  names() {
    return Object.keys(this.values);
  }
}

module.exports = { MemoryPrefs, FilePrefs, MemoryVault };
