'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { VoiceStream, Mp3Frames, heardChars } = require('../src/core/voice-stream');
const { SLOWEST_CHARS_PER_SECOND } = require('../src/core/voice');

/**
 * The fake OpenRouter's reply: a real MP3 (MPEG-2 Layer III, 24 kHz mono, 64 kbps). It isn't in
 * git (*.mp3 is ignored): without it the tests use frames made up here (headers of the same kind,
 * silent payloads, some padded), which is all the frame splitter looks at.
 */
function replyMp3() {
  const p = path.join(__dirname, '..', '..', 'scripts', 'fakes', 'reply.mp3');
  if (fs.existsSync(p)) return fs.readFileSync(p);
  const frames = [];
  for (let i = 0; i < 90; i++) {
    const padded = i % 3 === 0;
    const f = Buffer.alloc(padded ? 193 : 192);
    f[0] = 0xff;
    f[1] = 0xf3;
    f[2] = padded ? 0x86 : 0x84;
    f[3] = 0xc4;
    frames.push(f);
  }
  return Buffer.concat(frames);
}

function frames(mp3, chunk) {
  const f = new Mp3Frames();
  const out = [];
  for (let i = 0; i < mp3.length; i += chunk) {
    f.add(mp3, i, Math.min(chunk, mp3.length - i));
    for (let frame; (frame = f.next(false)) !== null; ) out.push(frame);
  }
  for (let frame; (frame = f.next(true)) !== null; ) out.push(frame);
  return out;
}

test('anMp3ComingInPiecesIsCutIntoTheSameFramesAsWhole', () => {
  const mp3 = replyMp3();
  const whole = frames(mp3, mp3.length);
  // 64 kbps at 24 kHz: 192-byte frames of 24 ms; the file is about two seconds.
  assert.ok(whole.length > 60, String(whole.length));
  for (const chunk of [1, 7, 100, 1000, 4096]) {
    const pieces = frames(mp3, chunk);
    assert.equal(pieces.length, whole.length, 'chunks of ' + chunk);
    for (let i = 0; i < whole.length; i++) assert.deepEqual(pieces[i], whole[i]);
  }
  const f = new Mp3Frames();
  f.add(mp3, 0, mp3.length);
  f.next(false);
  assert.equal(f.rate, 24000);
  assert.equal(f.channels, 1);
  assert.equal(f.samples, 576);
});

test('anId3TagAndJunkBeforeTheFramesAreSkipped', () => {
  const mp3 = replyMp3();
  // ID3v2 with 20 bytes of tag data (one of them 0xFF, which mustn't count as a frame).
  const tag = Buffer.alloc(20);
  tag[3] = 0xff;
  tag[4] = 0xfb;
  const b = Buffer.concat([Buffer.from([0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 20]), tag, Buffer.from([1, 2, 3]), mp3]);
  const plain = frames(mp3, 4096);
  for (const chunk of [3, 11, 4096]) assert.equal(frames(b, chunk).length, plain.length);
});

test('aFrameHeaderGivesItsLength', () => {
  // MPEG-1 Layer III, 128 kbps, 44.1 kHz, no padding: 417 bytes; stereo.
  const format = [0, 0];
  assert.equal(Mp3Frames.frameLength(Buffer.from([0xff, 0xfb, 0x90, 0x00]), 0, format), 417);
  assert.equal(format[0], 44100);
  assert.equal(format[1], 2);
  // With padding: one more.
  assert.equal(Mp3Frames.frameLength(Buffer.from([0xff, 0xfb, 0x92, 0x00]), 0, null), 418);
  // MPEG-2, 64 kbps, 24 kHz, mono: 192.
  assert.equal(Mp3Frames.frameLength(Buffer.from([0xff, 0xf3, 0x84, 0xc0]), 0, format), 192);
  assert.equal(format[1], 1);
  // Not Layer III, a bad bitrate, a bad rate, no sync: none.
  assert.equal(Mp3Frames.frameLength(Buffer.from([0xff, 0xfd, 0x90, 0]), 0, null), 0);
  assert.equal(Mp3Frames.frameLength(Buffer.from([0xff, 0xfb, 0xf0, 0]), 0, null), 0);
  assert.equal(Mp3Frames.frameLength(Buffer.from([0xff, 0xfb, 0x9c, 0]), 0, null), 0);
  assert.equal(Mp3Frames.frameLength(Buffer.from([0xfe, 0xfb, 0x90, 0]), 0, null), 0);
});

test('itPlaysFromAFifthOfASecondAndIsReadWhileItComes', async () => {
  const s = new VoiceStream();
  s.format(24000, 1);
  s.add(Buffer.alloc((24000 * 2) / 10)); // 100 ms.
  assert.equal(s.playable(), false);
  assert.equal(s.audioMs(), 100);
  s.add(Buffer.alloc((24000 * 2) / 10 + 1)); // 200 ms and half a sample.
  assert.equal(s.playable(), true);
  assert.equal((await s.read(0, 100_000, 0)).length, 9600, 'whole samples only');
  assert.equal((await s.read(9600, 100_000, 10)).length, 0, 'nothing more yet');
  // More comes while the player waits for it.
  setTimeout(() => s.add(Buffer.alloc(99)), 50);
  assert.equal((await s.read(9600, 100_000, 2000)).length, 100);
  s.end(4000);
  assert.equal(await s.read(9700, 100_000, 10), null);
  assert.equal(s.complete(), true);
  assert.equal(s.failed(), null);
  assert.equal(s.allMs(), 4000);
});

test('aShortReplyPlaysOnceItsAllInAndAFailedOneSaysWhy', () => {
  const s = new VoiceStream();
  s.format(24000, 1);
  s.add(Buffer.alloc(480)); // 10 ms.
  assert.equal(s.playable(), false);
  const told = [];
  s.whenDone((d) => told.push(d));
  s.end(300);
  assert.equal(s.playable(), true);
  assert.equal(told.length, 1);
  const failed = new VoiceStream();
  failed.format(24000, 1);
  failed.add(Buffer.alloc(480));
  failed.fail('its audio stopped coming after 30 s', 31_000);
  assert.equal(failed.playable(), false);
  assert.equal(failed.failed(), 'its audio stopped coming after 30 s');
  failed.whenDone((d) => told.push(d)); // Already done: at once.
  assert.equal(told.length, 2);
});

test('severalHearWhenItsComplete', () => {
  const s = new VoiceStream();
  const told = [];
  s.whenDone(() => told.push('log'));
  s.whenDone(() => told.push('learn'));
  s.end(100);
  s.whenDone(() => told.push('late'));
  assert.deepEqual(told, ['log', 'learn', 'late']);
});

test('untilARateIsLearnedHowFarYouHeardNeverRunsAheadOfASlowVoice', () => {
  // A voice reading 11 characters a second, talked over 6 s in, its length not known yet:
  // about 63 characters were heard; the guess stays below.
  const heard = heardChars(400, 6000, 8000, false, SLOWEST_CHARS_PER_SECOND);
  assert.ok(heard <= 6 * 11 - Math.floor((300 * 11) / 1000), String(heard));
});

test('howFarYouHeardIsInProportionOnceItsAllInAndAtMostAGuessBefore', () => {
  const text = 'x'.repeat(400);
  // All in: 26 s of audio for 400 characters; 13.3 s played is half (less the 0.3 s on its way).
  assert.equal(heardChars(400, 13_300, 26_000, true, 15), 200);
  // Still coming, 5 s of it in after 1.3 s played: not 80 characters (its share of what's in), 15 a second at most.
  assert.equal(heardChars(text.length, 1300, 5000, false, 15), 15);
  // Most of it in: its share is the smaller.
  assert.equal(heardChars(400, 2300, 20_000, false, 30), 40);
  assert.equal(heardChars(400, 200, 5000, false, 15), 0);
  assert.equal(heardChars(400, 5000, 0, false, 15), 0);
});

// Mac only: an MP3 stays MP3 in the stream, in whole frames, its length counted from them.

test('anMp3StreamKeepsWholeFramesAndCountsTheirLength', async () => {
  const mp3 = replyMp3();
  const s = new VoiceStream();
  s.format(0, 0, 'mp3');
  const pieces = [];
  s.onData((b) => pieces.push(b.length));
  s.add(mp3.subarray(0, 100)); // Less than a frame.
  assert.equal(s.bytes(), 0);
  assert.equal(s.playable(), false);
  s.add(mp3.subarray(100, 2000));
  assert.equal(s.rate(), 24000);
  assert.equal(s.channels(), 1);
  assert.ok(s.audioMs() >= 200, String(s.audioMs()));
  assert.equal(s.playable(), true);
  // Read in whole frames, at least one even when asked for less.
  const first = await s.read(0, 10, 0);
  assert.equal(first.length, frames(mp3, mp3.length)[0].length);
  s.add(mp3.subarray(2000));
  s.end(500);
  const all = frames(mp3, mp3.length);
  assert.equal(s.bytes(), all.reduce((n, f) => n + f.length, 0));
  assert.equal(s.audioMs(), Math.floor((all.length * 576 * 1000) / 24000));
  assert.equal(pieces.length, all.length);
  let at = 0;
  for (let b; (b = await s.read(at, 4096, 0)) !== null; ) at += b.length;
  assert.equal(at, s.bytes());
});

test('aFileOfAnotherTypePlaysOnceItsAllIn', async () => {
  const s = new VoiceStream();
  s.format(0, 0, 'file', 'audio/ogg');
  s.add(Buffer.alloc(5000, 1));
  assert.equal(s.playable(), false);
  assert.equal((await s.read(0, 100_000, 0)).length, 0);
  s.end(100);
  assert.equal(s.playable(), true);
  assert.equal((await s.read(0, 100_000, 0)).length, 5000);
});
