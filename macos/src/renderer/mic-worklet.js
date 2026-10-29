// Turns the microphone into what the app's core expects: 16 kHz mono 16-bit PCM in 20 ms frames
// (320 samples, 640 bytes), like the Android app's AudioRecord. When the context doesn't run at
// 16 kHz, it low-pass filters and resamples here.
const OUT_RATE = 16000, FRAME = 320;

class MicFrames extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ratio = sampleRate / OUT_RATE;
    this.frame = new Int16Array(FRAME);
    this.filled = 0;
    this.pos = 0; // Position of the next output sample, in input samples.
    // A windowed-sinc low-pass at 7 kHz against aliasing, when downsampling.
    this.taps = [];
    if (this.ratio > 1) {
      const n = 33, fc = 7000 / sampleRate;
      for (let i = 0; i < n; i++) {
        const m = i - (n - 1) / 2;
        const sinc = m === 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * m) / (Math.PI * m);
        const w = 0.54 - 0.46 * Math.cos((2 * Math.PI * i) / (n - 1));
        this.taps.push(sinc * w);
      }
      const sum = this.taps.reduce((a, b) => a + b, 0);
      this.taps = this.taps.map((t) => t / sum);
    }
    this.history = new Float32Array(this.taps.length || 1);
    this.filtered = [];
  }

  emit(sample) {
    const s = Math.max(-1, Math.min(1, sample));
    this.frame[this.filled++] = s < 0 ? s * 0x8000 : s * 0x7fff;
    if (this.filled === FRAME) {
      this.port.postMessage(this.frame.buffer, [this.frame.buffer]);
      this.frame = new Int16Array(FRAME);
      this.filled = 0;
    }
  }

  process(inputs) {
    const input = inputs[0];
    if (!input || !input[0]) return true;
    const ch = input[0];
    if (this.ratio === 1) {
      for (let i = 0; i < ch.length; i++) this.emit(ch[i]);
      return true;
    }
    const taps = this.taps, h = this.history, n = taps.length;
    for (let i = 0; i < ch.length; i++) {
      h.copyWithin(0, 1);
      h[n - 1] = ch[i];
      let acc = 0;
      for (let k = 0; k < n; k++) acc += h[k] * taps[k];
      this.filtered.push(acc);
    }
    // Linear interpolation over the filtered input.
    while (this.pos + 1 < this.filtered.length) {
      const i = Math.floor(this.pos), f = this.pos - i;
      this.emit(this.filtered[i] * (1 - f) + this.filtered[i + 1] * f);
      this.pos += this.ratio;
    }
    const drop = Math.floor(this.pos);
    if (drop > 0) {
      this.filtered.splice(0, drop);
      this.pos -= drop;
    }
    return true;
  }
}

registerProcessor('mic-frames', MicFrames);
