'use strict';
// 16 kHz mono PCM, with no silence-triggered termination. Chunking happens only after stop.

const fs = require('fs');
const { tr, format } = require('./util');

const RATE = 16000;
const BYTES_PER_SECOND = RATE * 2;
const CHUNK_BYTES = BYTES_PER_SECOND * 120;

/** The 44-byte header of a 16 kHz 16-bit mono WAV file with count bytes of samples. */
function header(count) {
  const h = Buffer.alloc(44);
  h.write('RIFF', 0, 'latin1');
  h.writeInt32LE(36 + count, 4);
  h.write('WAVEfmt ', 8, 'latin1');
  h.writeInt32LE(16, 16);
  h.writeInt16LE(1, 20); // PCM
  h.writeInt16LE(1, 22); // mono
  h.writeInt32LE(RATE, 24);
  h.writeInt32LE(BYTES_PER_SECOND, 28);
  h.writeInt16LE(2, 32);
  h.writeInt16LE(16, 34);
  h.write('data', 36, 'latin1');
  h.writeInt32LE(count, 40);
  return h;
}

/**
 * The next chunk of a PCM file from offset, as a WAV: { wav: Buffer, nextOffset }. At most
 * CHUNK_BYTES, cut at the quietest 20 ms frame of its last 2 seconds.
 */
function readChunk(pcmPath, offset) {
  const fd = fs.openSync(pcmPath, 'r');
  try {
    const end = fs.fstatSync(fd).size & ~1;
    if (offset < 0 || offset % 2 !== 0 || offset >= end) throw new Error(tr('Recording contains no readable audio.'));
    let boundary = Math.min(end, offset + CHUNK_BYTES);
    // Prefer a quiet 20 ms frame in the final 2 seconds, without dropping any samples.
    if (boundary < end) {
      let best = Infinity;
      let bestAt = boundary;
      const frame = Buffer.alloc(640);
      for (let at = boundary - BYTES_PER_SECOND * 2; at < boundary; at += frame.length) {
        readFully(fd, frame, 0, frame.length, at);
        let energy = 0;
        for (let i = 0; i < frame.length; i += 2) energy += Math.abs(frame.readInt16LE(i));
        if (energy < best) {
          best = energy;
          bestAt = at + frame.length / 2;
        }
      }
      boundary = bestAt;
    }
    const count = boundary - offset;
    const wav = Buffer.alloc(44 + count);
    header(count).copy(wav, 0);
    readFully(fd, wav, 44, count, offset);
    return { wav, nextOffset: boundary };
  } finally {
    fs.closeSync(fd);
  }
}

function readFully(fd, buffer, at, length, position) {
  let done = 0;
  while (done < length) {
    const n = fs.readSync(fd, buffer, at + done, length - done, position + done);
    if (n <= 0) throw new Error(tr('Recording contains no readable audio.'));
    done += n;
  }
}

/** 16 kHz 16-bit mono PCM as a WAV file (a Buffer). */
function wav(pcm) {
  return Buffer.concat([header(pcm.length), Buffer.from(pcm)]);
}

function append(previous, next) {
  if (next.trim() === '') return previous;
  return previous === '' ? next.trim() : previous + '\n' + next.trim();
}

function elapsed(millis) {
  const seconds = Math.max(0, Math.trunc(millis / 1000));
  return format('%02d:%02d', Math.trunc(seconds / 60), seconds % 60);
}

module.exports = { RATE, BYTES_PER_SECOND, CHUNK_BYTES, header, readChunk, wav, append, elapsed };
