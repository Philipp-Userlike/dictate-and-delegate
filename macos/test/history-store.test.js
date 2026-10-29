'use strict';
// Mac: notes as JSON files (Android: SQLite), with versions and the kept one.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { HistoryStore } = require('../src/core/history-store');
const { HistoryEntry } = require('../src/core/history-entry');
const NoteList = require('../src/core/note-list');

function entry(id, recording, created) {
  const e = HistoryEntry.create(id, 'm', 3, created);
  if (recording !== id) e.json.recordingId = recording;
  return e;
}

test('versionsAreStoredAsTheirJsonAndListedNewestFirst', (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dd-history-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const store = new HistoryStore(path.join(dir, 'notes'));
  const a = entry('a', 'a', 100);
  a.accept({ text: 'first take', cost: 0.001 }, 32);
  store.save(a);
  store.save(entry('b', 'a', 200));
  store.save(entry('c', 'c', 150));
  store.save(entry('d', 'd', 150));
  assert.deepEqual(store.all().map((e) => e.id()), ['b', 'd', 'c', 'a']);
  assert.deepEqual(store.list(2, 1).map((e) => e.id()), ['d', 'c']);
  assert.equal(store.get('a').text(), 'first take');
  assert.equal(store.get('a').json.knownCost, '0.001');
  assert.equal(store.get('nothing'), null);
  assert.equal(store.load('nothing'), null);
  store.saveJson({ ...store.load('c'), text: 'edited' });
  assert.equal(store.get('c').text(), 'edited');
  // The file holds exactly the entry's JSON, as Android's payload column.
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(dir, 'notes', 'a.json'), 'utf8')), a.json);

  assert.deepEqual(store.versions('a').map((e) => e.id()), ['b', 'a']);
  store.prefer(store.get('a'));
  assert.equal(store.get('a').json.preferred, true);
  assert.equal(store.get('b').json.preferred, false);
  assert.equal(NoteList.group(store.all()).find((n) => n.id() === 'a').shown.id(), 'a');

  store.delete('b');
  store.delete('b');
  assert.deepEqual(store.versions('a').map((e) => e.id()), ['a']);
  // An odd ID stays one file inside the folder.
  store.save(entry('../x', '../x', 1));
  assert.equal(store.get('../x').id(), '../x');
  assert.ok(!fs.existsSync(path.join(dir, 'x.json')));
});
