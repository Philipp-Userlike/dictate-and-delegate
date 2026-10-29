'use strict';
// The Android app's assets (bb skills, the speech-model catalog), shared by both apps. In the
// packaged Mac app they're copied next to the code (resources/assets); in the repo they're read
// from app/src/main/assets.

const fs = require('fs');
const path = require('path');

const candidates = [
  process.env.DD_ASSETS,
  path.join(__dirname, '..', '..', 'assets'),
  path.join(__dirname, '..', '..', '..', 'app', 'src', 'main', 'assets'),
].filter(Boolean);

function dir() {
  for (const d of candidates) if (fs.existsSync(path.join(d, 'models.json'))) return d;
  throw new Error('assets not found in ' + candidates.join(', '));
}

function read(name) {
  return fs.readFileSync(path.join(dir(), name), 'utf8');
}

module.exports = { read, dir };
