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

/** Runs scripts/fakes/<script> and waits until it answers; {url, get, post, stop}. */
async function startFake(script) {
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
