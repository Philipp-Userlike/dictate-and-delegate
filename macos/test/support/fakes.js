'use strict';
// Starts the repo's Python fakes (scripts/fakes) on free ports for integration tests.

const { spawn } = require('child_process');
const net = require('net');
const path = require('path');
const { sleep } = require('../../src/core/util');

const FAKES = path.join(__dirname, '..', '..', '..', 'scripts', 'fakes');

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.unref();
    s.on('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });
}

/**
 * The fake OpenRouter's voice reply (scripts/fakes/reply.mp3, git-ignored): when it's missing, two
 * seconds of valid silent MP3 frames like Kokoro's (MPEG-2 Layer III, 64 kbit/s, 24 kHz, mono;
 * all-zero frames decode to silence).
 */
function ensureReplyMp3() {
  const fs = require('fs');
  const file = path.join(FAKES, 'reply.mp3');
  if (fs.existsSync(file)) return;
  const frame = Buffer.alloc(192);
  Buffer.from([0xff, 0xf3, 0x84, 0xc4]).copy(frame);
  fs.writeFileSync(file, Buffer.concat(Array.from({ length: 84 }, () => frame)));
}

/** Runs scripts/fakes/<script> and waits until it answers; {url, get, post, stop}. */
async function startFake(script) {
  if (script === 'fake_openrouter.py') ensureReplyMp3();
  for (let tries = 0; ; tries++) {
    const port = await freePort();
    const child = spawn('python3', ['-I', path.join(FAKES, script), '--port', String(port)], { stdio: ['ignore', 'ignore', 'pipe'] });
    let stderr = '';
    child.stderr.on('data', (d) => {
      stderr = (stderr + d).slice(-4000);
    });
    let exited = false;
    child.on('exit', () => {
      exited = true;
    });
    const url = 'http://127.0.0.1:' + port;
    const until = Date.now() + 10_000;
    while (!exited && Date.now() < until) {
      try {
        const r = await fetch(url + '/test/requests');
        if (r.ok) {
          await r.arrayBuffer();
          const fake = {
            url,
            port,
            child,
            stderr: () => stderr,
            async get(p) {
              const res = await fetch(url + p);
              return res.json();
            },
            async post(p, body = {}) {
              const res = await fetch(url + p, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
              return res.json();
            },
            stop() {
              child.kill();
            },
          };
          return fake;
        }
      } catch {
        /* Not up yet. */
      }
      await sleep(50);
    }
    child.kill();
    if (tries >= 2) throw new Error(script + " didn't start: " + stderr);
  }
}

module.exports = { startFake, freePort };
