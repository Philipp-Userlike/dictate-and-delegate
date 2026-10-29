#!/usr/bin/env node
'use strict';
// Writes the designated requirement for the self-signed build in Apple's binary form (what
// `csreq -b` makes): identifier "<bundle id>" and certificate leaf = H"<SHA-1 of our certificate>".
// macOS ties Keychain items, microphone and Accessibility permissions to this requirement, so
// every build signed with the same certificate is the same app to it. Without it, a self-signed
// build's implicit requirement is its cdhash, which changes with each build (the old Drift app's
// re-prompts). Usage: requirement.js <bundle id> <certificate.pem> <out.bin>

const crypto = require('crypto');
const fs = require('fs');

const OP_IDENT = 2, OP_ANCHOR_HASH = 4, OP_AND = 6, LEAF = 0;

function u32(n) {
  const b = Buffer.alloc(4);
  b.writeInt32BE(n);
  return b;
}

function data(bytes) {
  const pad = (4 - (bytes.length % 4)) % 4;
  return Buffer.concat([u32(bytes.length), bytes, Buffer.alloc(pad)]);
}

function requirement(identifier, certDer) {
  const sha1 = crypto.createHash('sha1').update(certDer).digest();
  const expr = Buffer.concat([
    u32(OP_AND),
    u32(OP_IDENT),
    data(Buffer.from(identifier, 'utf8')),
    u32(OP_ANCHOR_HASH),
    u32(LEAF),
    data(sha1),
  ]);
  // Requirement blob: magic, total length, kind 1 (expression form), expression.
  return Buffer.concat([u32(0xfade0c00 | 0), u32(12 + expr.length), u32(1), expr]);
}

function derOf(pem) {
  const m = /-----BEGIN CERTIFICATE-----([\s\S]+?)-----END CERTIFICATE-----/.exec(pem);
  if (!m) throw new Error('no certificate in the PEM file');
  return Buffer.from(m[1].replace(/\s+/g, ''), 'base64');
}

if (require.main === module) {
  const [id, pemFile, out] = process.argv.slice(2);
  if (!out) {
    console.error('usage: requirement.js <bundle id> <certificate.pem> <out.bin>');
    process.exit(2);
  }
  fs.writeFileSync(out, requirement(id, derOf(fs.readFileSync(pemFile, 'utf8'))));
}

module.exports = { requirement, derOf };
