'use strict';
// Mac: AudioArchive's week and 100 MB cap, in a folder passed in.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { AudioArchive, WEEK } = require('../src/core/audio-archive');
const { MemoryPrefs } = require('../src/core/store');

test('recordingsAreKeptForAWeekUnlessSwitchedOff', (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dd-archive-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const source = path.join(dir, 'source.pcm');
  fs.writeFileSync(source, Buffer.alloc(3200, 1));
  let now = Date.now();
  const prefs = new MemoryPrefs();
  const archive = new AudioArchive({ dir: path.join(dir, 'recordings'), prefs, clock: () => now });
  archive.retain('rec-1', source);
  const kept = archive.get('rec-1');
  assert.ok(kept);
  assert.deepEqual(fs.readFileSync(kept), fs.readFileSync(source));
  assert.equal(archive.get('rec-2'), null);
  assert.throws(() => archive.get('../etc'), /Invalid recording ID/);
  now += WEEK + 60000;
  assert.equal(archive.get('rec-1'), null);
  now = Date.now();
  archive.retain('rec-3', source);
  assert.ok(archive.get('rec-3'));
  prefs.set('retainAudio', false);
  assert.equal(archive.get('rec-3'), null);
  archive.retain('rec-4', source);
  assert.equal(archive.get('rec-4'), null);
});
