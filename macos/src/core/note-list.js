'use strict';
// History rows are versions; a note is one recording, shown as the version you kept.

const decimal = require('./decimal');

const str = (v) => (v === undefined || v === null ? '' : String(v));

/** Sums HistoryEntry cost strings exactly (0.001 + 0.0021 is 0.0031, not 0.0031000000000000003). */
function total(entries, key) {
  let t = decimal.of(0);
  for (const e of entries) t = decimal.add(t, decimal.parse(str(e.json[key]) || '0') || decimal.of(0));
  return decimal.toNumber(t);
}

class Note {
  constructor(shown, versions) {
    this.shown = shown;
    this.versions = versions;
  }

  id() {
    return this.shown.recordingId();
  }

  created() {
    return this.versions.length ? Math.min(...this.versions.map((v) => v.created())) : this.shown.created();
  }

  cost() {
    return total(this.versions, 'knownCost');
  }

  anthropicCost() {
    return total(this.versions, 'anthropicCost');
  }

  incompleteCost() {
    return this.versions.some((v) => v.incompleteCost());
  }
}

/** A hands-free call as one entry: its messages' notes, newest first. */
class Call {
  constructor(id, turns) {
    this.id = id;
    this.turns = turns;
  }

  /** When it started: its first message. */
  created() {
    return this.turns.length ? Math.min(...this.turns.map((n) => n.created())) : 0;
  }

  /** Its newest message, where it sits in the list. */
  latest() {
    return this.turns[0].created();
  }

  /** Transcription and voice, all its messages. */
  cost() {
    return total(
      this.turns.flatMap((n) => n.versions),
      'knownCost',
    );
  }

  incompleteCost() {
    return this.turns.some((n) => n.incompleteCost());
  }

  /** Seconds you spoke. */
  duration() {
    return this.turns.reduce((s, n) => s + n.shown.duration(), 0);
  }
}

/**
 * Notes and calls, newest first: the notes of one hands-free call (same "call") become one Call,
 * where its newest message is.
 */
function withCalls(notes) {
  const calls = new Map();
  const out = [];
  for (const n of notes) {
    const id = str(n.shown.json.call);
    if (id === '') {
      out.push(n);
      continue;
    }
    let call = calls.get(id);
    if (!call) {
      call = new Call(id, []);
      calls.set(id, call);
      out.push(call);
    }
    call.turns.push(n);
  }
  return out;
}

function group(entries) {
  const byRecording = new Map();
  for (const e of entries) {
    if (!byRecording.has(e.recordingId())) byRecording.set(e.recordingId(), []);
    byRecording.get(e.recordingId()).push(e);
  }
  const notes = [];
  for (const versions of byRecording.values()) {
    let shown = null;
    for (const v of versions) if (v.json.preferred === true) shown = v;
    if (!shown)
      for (const v of versions)
        if (str(v.json.status) === 'complete' && (!shown || v.created() > shown.created())) shown = v;
    if (!shown) for (const v of versions) if (!shown || v.created() > shown.created()) shown = v; // the first newest
    notes.push(new Note(shown, versions));
  }
  notes.sort((a, b) => b.created() - a.created());
  return notes;
}

/**
 * The notes whose first version is among the rows read so far. Rows are read newest first and
 * redone versions are newer than the first, so these notes have all their versions.
 */
function complete(notes) {
  return notes.filter((n) => n.versions.some((v) => v.id() === n.id()));
}

function search(notes, query) {
  const q = query.trim().toLowerCase();
  if (q === '') return notes;
  return notes.filter((n) => n.shown.text().toLowerCase().includes(q));
}

module.exports = { Note, Call, group, withCalls, complete, search };
