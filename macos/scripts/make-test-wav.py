#!/usr/bin/env python3
"""A speech-like test signal (a voiced buzz in syllables with short pauses), 16-bit 48 kHz mono, for
Chromium's fake microphone in the end-to-end tests (it loops the file).
Usage: make-test-wav.py <out.wav> [seconds] [pattern]: "dictation" (short pauses) or "call"
(2.4 s of speech, then silence with faint room noise until the end: a turn and a long pause)."""
import random
import math, struct, sys, wave
out = sys.argv[1]
seconds = float(sys.argv[2]) if len(sys.argv) > 2 else 6
pattern = sys.argv[3] if len(sys.argv) > 3 else 'dictation'
rate = 48000
w = wave.open(out, 'wb')
w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
frames = bytearray()
for i in range(int(rate * seconds)):
    t = i / rate
    syllable = (t * 4) % 1.0
    env = math.sin(math.pi * syllable / 0.7) if syllable < 0.7 else 0.0
    if pattern == 'call' and t > 2.4: env = 0.0
    elif pattern != 'call' and (t % 3) > 2.4: env = 0.0  # a pause between "sentences"
    f0 = 140 + 30 * math.sin(2 * math.pi * 0.7 * t)
    s = sum(math.sin(2 * math.pi * f0 * k * t) / k for k in range(1, 8))
    frames += struct.pack('<h', int(9000 * env * s / 2.6 + random.gauss(0, 30)))
w.writeframes(bytes(frames)); w.close()
