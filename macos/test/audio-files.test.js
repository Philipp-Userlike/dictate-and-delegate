'use strict';
// AudioFilesTest.java
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const AudioFiles = require('../src/core/audio-files');

function temp(data) {
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'drift-')), 'a.pcm');
  fs.writeFileSync(file, data);
  return file;
}

function cleanup(file) {
  fs.rmSync(path.dirname(file), { recursive: true, force: true });
}

test('wavHasValidHeaderAndExactSamples', () => {
  const samples = crypto.randomBytes(32000);
  const pcm = temp(samples);
  try {
    const chunk = AudioFiles.readChunk(pcm, 0);
    const b = chunk.wav;
    assert.equal(b.toString('latin1', 0, 4), 'RIFF');
    assert.equal(b.readInt32LE(4), 32036);
    assert.equal(b.readInt32LE(24), 16000);
    assert.equal(b.readInt16LE(22), 1);
    assert.equal(b.readInt16LE(34), 16);
    assert.equal(b.readInt32LE(40), 32000);
    assert.deepEqual(b.subarray(44), samples);
    assert.equal(chunk.nextOffset, samples.length);
    // wav() builds the same file.
    assert.deepEqual(AudioFiles.wav(samples), b);
  } finally {
    cleanup(pcm);
  }
});

test('longAudioChunksPreserveEverySampleExactlyOnce', () => {
  const input = crypto.randomBytes(AudioFiles.CHUNK_BYTES * 2 + 1234);
  const pcm = temp(input);
  try {
    const rebuilt = [];
    let offset = 0;
    let chunks = 0;
    while (offset < input.length) {
      const part = AudioFiles.readChunk(pcm, offset);
      assert.ok(part.wav.length <= AudioFiles.CHUNK_BYTES + 44);
      assert.ok(part.nextOffset > offset);
      rebuilt.push(part.wav.subarray(44));
      offset = part.nextOffset;
      chunks++;
    }
    assert.equal(chunks, 3);
    assert.ok(Buffer.concat(rebuilt).equals(input));
  } finally {
    cleanup(pcm);
  }
});

test('silenceIsPreserved', () => {
  const pcm = temp(Buffer.alloc(AudioFiles.CHUNK_BYTES + 32000));
  try {
    let offset = 0;
    let count = 0;
    const size = fs.statSync(pcm).size;
    while (offset < size) {
      const part = AudioFiles.readChunk(pcm, offset);
      count += part.wav.length - 44;
      offset = part.nextOffset;
    }
    assert.equal(count, size);
  } finally {
    cleanup(pcm);
  }
});

test('interruptedHalfSampleIsSafelyExcluded', () => {
  const pcm = temp(Buffer.from([1, 2, 3]));
  try {
    assert.equal(AudioFiles.readChunk(pcm, 0).nextOffset, 2);
  } finally {
    cleanup(pcm);
  }
});

test('emptyRecordingIsRejected', () => {
  const pcm = temp(Buffer.alloc(0));
  try {
    assert.throws(() => AudioFiles.readChunk(pcm, 0), /Recording contains no readable audio/);
  } finally {
    cleanup(pcm);
  }
});

test('elapsedSupportsLongRecordings', () => {
  assert.equal(AudioFiles.elapsed(-1), '00:00');
  assert.equal(AudioFiles.elapsed(65000), '01:05');
  assert.equal(AudioFiles.elapsed(7500000), '125:00');
});

test('transcriptsKeepOrderWithoutInventingWords', () => {
  assert.equal(AudioFiles.append('first', ' second '), 'first\nsecond');
  assert.equal(AudioFiles.append('first', '   '), 'first');
});
