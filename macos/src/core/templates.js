'use strict';
// Texts the user keeps for reuse, like an apartment application. A dictation can ask for one and
// say what to change ("use my apartment application for the flat on Hauptstraße, viewing on
// Saturday"): Jev sees only the names, and only the template asked for goes to the text model.
// Kept plain (not secret), like notes: on Android in the vault's PLAIN "templates" entry, here the
// same JSON string in the settings under "templates".

const crypto = require('crypto');

/** Each template's text, well inside the server's 64,000 characters with the prompt. */
const MAX_TEXT = 8000;

const INSTRUCTIONS =
  "The dictation asks for the user's template below. Reply with that template adapted as the" +
  ' dictation says: keep its wording and structure, change what the dictation asks to' +
  ' change or what must differ in this case (names, addresses, dates, amounts), and fill' +
  ' in the details it gives. Change nothing else, and keep the template\'s language' +
  ' unless the dictation asks otherwise.';

const KEY = 'templates';

class Templates {
  /** prefs: the settings store. */
  constructor({ prefs }) {
    this.prefs = prefs;
  }

  /** [{ id, name, text }] */
  all() {
    try {
      const saved = this.prefs.get(KEY, '');
      const array = Array.isArray(saved) ? saved : JSON.parse(!saved ? '[]' : saved);
      const out = [];
      for (const t of array) {
        if (t === null || typeof t !== 'object' || t.id === undefined || t.id === null) throw new Error('no id');
        out.push({ id: String(t.id), name: t.name == null ? '' : String(t.name), text: t.text == null ? '' : String(t.text) });
      }
      return out;
    } catch {
      return []; // Unreadable: no templates rather than a broken note.
    }
  }

  get(id) {
    return this.all().find((t) => t.id === id) || null;
  }

  /** Adds a template (id null) or replaces the one with this id; returns its id. */
  save(id, name, text) {
    const list = this.all();
    const key = id === null || id === undefined ? crypto.randomUUID() : id;
    const clean = text.trim();
    const t = { id: key, name: name.trim(), text: clean.length > MAX_TEXT ? clean.substring(0, MAX_TEXT) : clean };
    const i = list.findIndex((x) => x.id === key);
    if (i >= 0) list[i] = t;
    else list.push(t);
    this.write(list);
    return key;
  }

  delete(id) {
    const list = this.all();
    const i = list.findIndex((x) => x.id === id);
    if (i >= 0) list.splice(i, 1);
    this.write(list);
  }

  /** Names of the templates with text, for Jev. */
  names() {
    return this.all()
      .filter((t) => t.name.trim() !== '' && t.text.trim() !== '')
      .map((t) => t.name);
  }

  /** The text model's instructions for the template asked for; "" when none was. */
  prompt(name) {
    if (name === null || name === undefined || name.trim() === '') return '';
    for (const t of this.all())
      if (t.name.toLowerCase() === name.toLowerCase() && t.text.trim() !== '')
        return '\n\n' + INSTRUCTIONS + '\n\n<template name="' + t.name.replace(/"/g, "'") + '">\n' + t.text + '\n</template>';
    return '';
  }

  write(list) {
    this.prefs.set(KEY, JSON.stringify(list.map((t) => ({ id: t.id, name: t.name, text: t.text }))));
  }
}

module.exports = { Templates, MAX_TEXT, INSTRUCTIONS, KEY };
