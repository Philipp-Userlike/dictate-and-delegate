'use strict';
// A reply's audio as it comes in, read by the player at the same time, so a reply plays from its
// first fifth of a second instead of after its whole file. Its length is known once it's complete.
//
// Mac: Android decodes MP3 to PCM as it comes (MediaCodec); Node has no decoder, so an MP3 stays
// MP3 here, cut into whole frames (Mp3Frames), and the player decodes it. So a stream has an
// encoding: 'pcm' (16-bit little-endian, rate and channels), 'mp3' (whole frames; rate and
// channels from the first frame) or 'file' (a whole file of another type, mime: playable once
// it's all in).

/** Playing starts once this much audio is in (or all of it, if it's shorter). */
const START_MS = 200;

class VoiceStream {
  constructor() {
    this.encoding = 'pcm';
    this.mime = '';
    this._rate = 0;
    this._channels = 0;
    this.chunks = [];
    this.length = 0;
    this.joined = null;
    this._complete = false;
    this._failed = null;
    /** When it ended, ms after it was asked for (-1 while it's still coming). */
    this._allMs = -1;
    this.whenDoneListeners = [];
    this.waiters = new Set();
    this.dataListeners = new Set();
    /** MP3: the frames parser, the whole frames' ends (byte positions) and samples per channel in. */
    this.frames = null;
    this.frameEnds = [];
    this.samples = 0;
    /** Stops the download (a reply you talked over isn't fetched on). */
    this.cancel = () => {};
  }

  /**
   * The samples' rate and channels, before the first are added ('pcm'); for 'mp3' they come from
   * the first frame (pass 0, 0); for 'file' the mime type says what it is.
   */
  format(rate, channels, encoding = 'pcm', mime = '') {
    this._rate = rate;
    this._channels = channels;
    this.encoding = encoding;
    this.mime = mime;
    if (encoding === 'mp3' && !this.frames) this.frames = new Mp3Frames();
  }

  rate() {
    return this._rate;
  }

  channels() {
    return this._channels;
  }

  add(b, off = 0, n = b ? b.length - off : 0) {
    if (this._complete || n <= 0) return;
    const buf = Buffer.from(b.buffer, b.byteOffset + off, n);
    if (this.encoding === 'mp3') {
      this.frames.add(buf);
      this.takeFrames(false);
      return;
    }
    this.append(Buffer.from(buf)); // A copy: the caller may reuse its buffer.
  }

  append(buf) {
    this.chunks.push(buf);
    this.length += buf.length;
    this.joined = null;
    for (const l of this.dataListeners) l(buf);
    this.notify();
  }

  /** MP3: the whole frames that are in go into the stream (last: no more bytes come). */
  takeFrames(last) {
    for (let f; (f = this.frames.next(last)) !== null; ) {
      if (this._rate === 0) {
        this._rate = this.frames.rate;
        this._channels = this.frames.channels;
      }
      this.samples += this.frames.samples;
      this.frameEnds.push(this.length + f.length);
      this.append(f);
    }
  }

  notify() {
    const w = [...this.waiters];
    this.waiters.clear();
    for (const r of w) r();
  }

  /** All of it is in (allMs: when, after it was asked for). */
  end(allMs) {
    this.done(null, allMs);
  }

  /** It stopped coming: why, in a few words. What came before still plays. */
  fail(why, allMs) {
    this.done(why == null ? 'failed' : why, allMs);
  }

  done(why, ms) {
    if (this._complete) return;
    if (this.encoding === 'mp3' && why === null) this.takeFrames(true);
    this._complete = true;
    this._failed = why;
    this._allMs = ms;
    const then = this.whenDoneListeners;
    this.whenDoneListeners = [];
    this.notify();
    for (const t of then) t(this);
  }

  /** Hears once when it's complete (at once if it is); any number may listen. */
  whenDone(then) {
    if (!this._complete) {
      this.whenDoneListeners.push(then);
      return;
    }
    then(this);
  }

  /** Hears each piece of audio as it's added (whole samples or MP3 frames); returns how to stop. */
  onData(listener) {
    this.dataListeners.add(listener);
    return () => this.dataListeners.delete(listener);
  }

  complete() {
    return this._complete;
  }

  /** Why it stopped coming, null if it came whole (or is still coming). */
  failed() {
    return this._failed;
  }

  allMs() {
    return this._allMs;
  }

  /** Bytes in so far. */
  bytes() {
    return this.length;
  }

  /** All the bytes in so far. */
  data() {
    if (!this.joined) this.joined = Buffer.concat(this.chunks, this.length);
    if (this.chunks.length > 1) this.chunks = [this.joined];
    return this.joined;
  }

  /** Milliseconds of audio in so far (all of it, once complete; 0 for a file of another type). */
  audioMs() {
    if (this._rate <= 0) return 0;
    if (this.encoding === 'mp3') return Math.floor((this.samples * 1000) / this._rate);
    if (this.encoding !== 'pcm' || this._channels <= 0) return 0;
    return Math.floor((Math.floor(this.length / 2 / this._channels) * 1000) / this._rate);
  }

  /** Enough is in to start playing: START_MS, or all of it (when it's shorter and came whole). */
  playable() {
    const whole = this._complete && this._failed === null;
    if (this.encoding === 'file') return whole && this.length > 0;
    if (this.encoding === 'mp3') return this.audioMs() >= START_MS || (whole && this.length > 0);
    return this.audioMs() >= START_MS || (whole && this.length >= 2 * Math.max(1, this._channels));
  }

  /** Where whole units end at or before byte position limit (PCM sample frames, MP3 frames). */
  wholeUpTo(limit) {
    if (this.encoding === 'mp3') {
      let end = 0;
      for (const e of this.frameEnds) {
        if (e > limit) break;
        end = e;
      }
      return end;
    }
    if (this.encoding === 'file') return this._complete ? Math.min(limit, this.length) : 0;
    const frame = 2 * Math.max(1, this._channels);
    return Math.floor(limit / frame) * frame;
  }

  /**
   * Whole units (sample frames, MP3 frames, or a file once it's all in) from byte position at,
   * at most maxBytes (an MP3 frame at least), waiting at most waitMs for them to come: a Buffer
   * (empty if none came in time), null at the end.
   */
  async read(at, maxBytes, waitMs) {
    const until = Date.now() + waitMs;
    for (;;) {
      const whole = this.wholeUpTo(this.length);
      if (whole > at) {
        let end = this.wholeUpTo(Math.min(this.length, at + maxBytes));
        if (end <= at) end = this.encoding === 'mp3' ? this.frameEnds.find((e) => e > at) : at;
        if (end > at) return Buffer.from(this.data().subarray(at, end));
      }
      if (this._complete) return null;
      const left = until - Date.now();
      if (left <= 0) return Buffer.alloc(0);
      await new Promise((resolve) => {
        const timer = setTimeout(() => {
          this.waiters.delete(done);
          resolve();
        }, left);
        const done = () => {
          clearTimeout(timer);
          resolve();
        };
        this.waiters.add(done);
      });
    }
  }
}

/**
 * How many characters of a reply have been heard after playedMs of its audio: in proportion,
 * a third of a second less for the sound still on its way to the ear. While it's still coming
 * its whole length isn't known: then at most at charsPerSecond (a guess on the low side, so the
 * chief is never told you heard more than you did), and never more than its share of what's in.
 */
function heardChars(textLength, playedMs, audioMs, complete, charsPerSecond) {
  const at = Math.max(0, playedMs - 300);
  if (audioMs <= 0 || at === 0) return 0;
  const share = Math.min(textLength, Math.floor((textLength * at) / audioMs));
  if (complete) return share;
  return Math.min(share, Math.floor((at * charsPerSecond) / 1000));
}

const BITRATES_V1 = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320];
const BITRATES_V2 = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160];
const RATES = [[11025, 12000, 8000], [], [22050, 24000, 16000], [44100, 48000, 32000]];

/** MP3 frames, read from the bytes as they come; the frames go to the player one by one. */
class Mp3Frames {
  constructor() {
    this.in = Buffer.alloc(32 * 1024);
    this.length = 0;
    /** An ID3 tag's bytes still to skip. */
    this.skip = 0;
    this.started = false;
    /** The first frame's rate, channels and samples per channel (0 until one is found). */
    this.rate = 0;
    this.channels = 0;
    this.samples = 0;
  }

  /**
   * The frame's length in bytes if a Layer III frame header starts at b[i] (with its rate and
   * channels in format), else 0.
   */
  static frameLength(b, i, format) {
    if (i + 4 > b.length || (b[i] & 0xff) !== 0xff || (b[i + 1] & 0xe0) !== 0xe0) return 0;
    const version = (b[i + 1] >> 3) & 3,
      layer = (b[i + 1] >> 1) & 3;
    const bitrate = (b[i + 2] >> 4) & 15,
      rateIndex = (b[i + 2] >> 2) & 3,
      padding = (b[i + 2] >> 1) & 1;
    if (version === 1 || layer !== 1 || bitrate === 0 || bitrate === 15 || rateIndex === 3) return 0;
    const rate = RATES[version][rateIndex];
    const kbps = (version === 3 ? BITRATES_V1 : BITRATES_V2)[bitrate];
    if (format) {
      format[0] = rate;
      format[1] = ((b[i + 3] >> 6) & 3) === 3 ? 1 : 2;
    }
    return Math.floor(((version === 3 ? 144 : 72) * kbps * 1000) / rate) + padding;
  }

  /** Adds bytes as they came. */
  add(b, off = 0, n = b.length - off) {
    if (this.length + n > this.in.length) {
      const bigger = Buffer.alloc(Math.max(this.in.length * 2, this.length + n));
      this.in.copy(bigger, 0, 0, this.length);
      this.in = bigger;
    }
    Buffer.from(b.buffer, b.byteOffset + off, n).copy(this.in, this.length);
    this.length += n;
  }

  /**
   * The next whole frame, or null until more bytes come (last: no more will, so a last frame
   * cut short is dropped). Bytes that aren't a frame (an ID3 tag, junk) are skipped.
   */
  next(last) {
    const format = [0, 0];
    const b = this.in;
    let i = 0;
    try {
      for (;;) {
        if (this.skip > 0) {
          const n = Math.min(this.skip, this.length - i);
          i += n;
          this.skip -= n;
          if (this.skip > 0) return null;
        }
        if (!this.started && this.length - i >= 10 && b[i] === 0x49 && b[i + 1] === 0x44 && b[i + 2] === 0x33) {
          // ID3v2: its size in four 7-bit bytes, after the 10-byte header (and a footer if flagged).
          this.skip =
            10 +
            (((b[i + 6] & 0x7f) << 21) | ((b[i + 7] & 0x7f) << 14) | ((b[i + 8] & 0x7f) << 7) | (b[i + 9] & 0x7f)) +
            ((b[i + 5] & 0x10) !== 0 ? 10 : 0);
          continue;
        }
        if (this.length - i < 4) return null;
        if (!this.started && this.length - i < 10 && b[i] === 0x49) return null; // Maybe a tag: wait for its header.
        const frame = Mp3Frames.frameLength(b.subarray(i, i + 4), 0, format);
        if (frame === 0) {
          i++;
          continue;
        }
        if (this.length - i < frame) return null;
        this.started = true;
        if (this.rate === 0) {
          this.rate = format[0];
          this.channels = format[1];
          this.samples = ((b[i + 1] >> 3) & 3) === 3 ? 1152 : 576;
        }
        const out = Buffer.from(b.subarray(i, i + frame));
        i += frame;
        return out;
      }
    } finally {
      // What's been read (or skipped) is dropped.
      b.copy(b, 0, i, this.length);
      this.length -= i;
      if (last && this.length > 0 && this.skip === 0 && Mp3Frames.frameLength(b.subarray(0, Math.min(4, this.length)), 0, null) === 0)
        this.length = 0;
    }
  }
}

module.exports = { VoiceStream, Mp3Frames, heardChars, START_MS };
