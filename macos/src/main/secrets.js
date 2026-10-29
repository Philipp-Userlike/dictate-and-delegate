'use strict';
// The vault on the Mac: API keys, bb tokens and the ntfy topic, encrypted with Electron's
// safeStorage, whose key lives in the macOS Keychain ("Dictate & Delegate Safe Storage"). Because
// every build is signed with the same certificate, macOS lets each new version read that
// Keychain item without asking again. The encrypted values sit in vault.json in the app's data
// folder; nothing is stored in plain text on the Mac.

const fs = require('fs');
const path = require('path');
const { safeStorage } = require('electron');

class KeychainVault {
  constructor(file) {
    this.file = file;
    this.cache = new Map();
    try {
      this.sealed = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
      this.sealed = {};
    }
  }

  /** Off the Mac (development on Linux) there may be no keyring: values are then kept in memory only. */
  available() {
    try {
      return safeStorage.isEncryptionAvailable();
    } catch {
      return false;
    }
  }

  get(name) {
    if (this.cache.has(name)) return this.cache.get(name);
    const sealed = this.sealed[name];
    if (!sealed || !this.available()) return null;
    try {
      const value = safeStorage.decryptString(Buffer.from(sealed, 'base64'));
      this.cache.set(name, value);
      return value;
    } catch {
      return null;
    }
  }

  set(name, value) {
    if (value === null || value === undefined || value === '') return this.remove(name);
    this.cache.set(name, value);
    if (!this.available()) {
      if (process.platform === 'darwin') throw new Error('The macOS Keychain is not available, so the key could not be saved.');
      return;
    }
    this.sealed[name] = safeStorage.encryptString(String(value)).toString('base64');
    this.save();
  }

  remove(name) {
    this.cache.delete(name);
    delete this.sealed[name];
    this.save();
  }

  names() {
    return [...new Set([...Object.keys(this.sealed), ...this.cache.keys()])];
  }

  clear() {
    this.cache.clear();
    this.sealed = {};
    this.save();
  }

  save() {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = this.file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(this.sealed), { mode: 0o600 });
    fs.renameSync(tmp, this.file);
  }
}

module.exports = { KeychainVault };
