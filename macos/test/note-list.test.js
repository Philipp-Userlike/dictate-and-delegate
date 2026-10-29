'use strict';
// NoteListTest.java
const test = require('node:test');
const assert = require('node:assert/strict');
const NoteList = require('../src/core/note-list');
const { HistoryEntry } = require('../src/core/history-entry');

function version(id, recording, created, text) {
  const e = HistoryEntry.create(id, 'm', 5);
  Object.assign(e.json, { recordingId: recording, created, text, status: 'complete', knownCost: '0.001', requests: 1 });
  return e;
}

test('showsTheKeptVersionAndSumsAllVersionCosts', () => {
  const first = version('a', 'a', 100, 'first take');
  const redo = version('b', 'a', 200, 'smarter take');
  const other = version('c', 'c', 50, 'older note');
  const notes = NoteList.group([redo, first, other]);
  assert.equal(notes.length, 2);
  assert.equal(notes[0].shown.text(), 'smarter take');
  assert.equal(notes[0].cost(), 0.002);
  first.json.preferred = true;
  assert.equal(NoteList.group([redo, first])[0].shown.text(), 'first take');
  assert.equal(NoteList.search(notes, 'OLDER').length, 1);
});

test('aNoteIsCompleteOnceItsFirstVersionIsRead', () => {
  // Newest rows first: the redo of "a" is read before "a" itself.
  const redo = version('b', 'a', 300, 'smarter take');
  const newer = version('c', 'c', 200, 'newer note');
  const first = version('a', 'a', 100, 'first take');
  const partial = NoteList.complete(NoteList.group([redo, newer]));
  assert.equal(partial.length, 1);
  assert.equal(partial[0].id(), 'c');
  const all = NoteList.complete(NoteList.group([redo, newer, first]));
  assert.deepEqual(all.map((n) => n.id()), ['c', 'a']);
  assert.equal(all[1].versions.length, 2);
});

test('aCallsMessagesAreOneEntryWithTheirTotalCost', () => {
  const before = version('a', 'a', 100, 'a dictation');
  const t1 = version('b', 'b', 200, 'first message');
  const t2 = version('c', 'c', 300, 'second message');
  const after = version('d', 'd', 400, 'another dictation');
  t1.json.call = 'call1';
  t2.json.call = 'call1';
  t2.json.knownCost = '0.0021'; // Transcription and voice.
  const items = NoteList.withCalls(NoteList.group([after, t2, t1, before]));
  assert.equal(items.length, 3);
  assert.equal(items[0].id(), 'd');
  const call = items[1];
  assert.ok(call instanceof NoteList.Call);
  assert.deepEqual(call.turns.map((n) => n.id()), ['c', 'b']);
  assert.equal(call.cost(), 0.0031);
  assert.equal(call.created(), 200);
  assert.equal(call.latest(), 300);
  assert.ok(Math.abs(call.duration() - 10) < 0.001);
  assert.equal(items[2].id(), 'a');
});
