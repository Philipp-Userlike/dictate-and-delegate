'use strict';
// Quiet, short sounds for the key moments of a hands-free call, made here (no files): heard you
// (one soft high tick), sent (two rising notes), reply coming (two falling notes) and something
// failed (two low notes). HandsFreeSession keeps them from counting as your speech.
// Mac: only the samples are made here; the renderer plays them (Android played them on an
// AudioTrack with the replies' attributes).

const RATE = 22_050;

/** How loud, of full scale: well below a reply's voice. */
const VOLUME = 0.12;

/** HandsFreeSession's Cue names. */
const CUES = Object.freeze(['HEARD', 'SENT', 'REPLY', 'FAILED']);

/** The notes of each sound: [frequency Hz, length ms], one after the other. */
function notes(cue) {
  switch (cue) {
    case 'HEARD':
      return [[1320, 45]];
    case 'SENT':
      return [
        [660, 70],
        [990, 90],
      ];
    case 'REPLY':
      return [
        [880, 70],
        [587, 90],
      ];
    case 'FAILED':
      return [
        [294, 110],
        [220, 150],
      ];
    default:
      throw new Error('no such cue: ' + cue);
  }
}

/** The sound as 16-bit samples: sine notes, each faded in and out so nothing clicks. */
function samples(cue) {
  const ns = notes(cue);
  let total = 0;
  for (const n of ns) total += Math.trunc((RATE * n[1]) / 1000);
  const out = new Int16Array(total);
  let at = 0;
  for (const [hz, ms] of ns) {
    const len = Math.trunc((RATE * ms) / 1000),
      fade = Math.min(Math.trunc(len / 3), Math.trunc((RATE * 12) / 1000));
    for (let i = 0; i < len; i++) {
      const env = Math.min(1, Math.min(i, len - 1 - i) / fade);
      out[at + i] = Math.round(Math.sin((2 * Math.PI * hz * i) / RATE) * env * VOLUME * 32767);
    }
    at += len;
  }
  return out;
}

/** A cue to play: { rate, channels: 1, pcm: Buffer of 16-bit little-endian samples }. */
function pcm(cue) {
  const s = samples(cue);
  const b = Buffer.alloc(s.length * 2);
  for (let i = 0; i < s.length; i++) b.writeInt16LE(s[i], i * 2);
  return { rate: RATE, channels: 1, pcm: b };
}

module.exports = { pcm, samples, notes, CUES, RATE, VOLUME };
