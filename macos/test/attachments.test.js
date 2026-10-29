'use strict';
// Mac: Attachments with file paths instead of Uris, images as they are or through toJpeg.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { Attachments, MAX_ITEMS, MAX_FILE, sniff } = require('../src/core/attachments');

const PNG = Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), Buffer.alloc(20)]);
const PDF = Buffer.from('%PDF-1.4\n...');

function setup(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dd-attach-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const put = (name, data) => {
    const f = path.join(dir, 'picked', name);
    fs.mkdirSync(path.dirname(f), { recursive: true });
    fs.writeFileSync(f, data);
    return f;
  };
  return { dir, put };
}

test('filesAreSniffedAndBecomePartsForRefine', async (t) => {
  const { dir, put } = setup(t);
  const a = new Attachments({ dir: path.join(dir, 'attachments') });
  a.addText('  The offer:\nflat on Hauptstraße, 3 rooms, available from December, 1,200 € a month  ');
  await a.addFile(put('photo.dat', PNG)); // the bytes say PNG
  await a.addFile(put('offer.pdf', PDF));
  await a.addFile(put('notes.md', '# Notes\nbring "papers"'));
  const pending = a.pending();
  assert.deepEqual(pending.map((i) => i.kind), ['text', 'image', 'pdf', 'file']);
  assert.equal(pending[0].name, 'The offer: flat on Hauptstraße, 3 rooms,…');
  assert.equal(pending[1].mime, 'image/png'); // No toJpeg: kept as PNG.

  assert.equal(a.claimPending('rec-1'), 4);
  assert.deepEqual(a.pending(), []);
  const parts = a.parts('rec-1');
  assert.equal(parts[0].type, 'text');
  assert.ok(parts[0].text.startsWith('<attachment name="The offer: flat on Hauptstraße, 3 rooms,…">\nThe offer:\nflat'));
  assert.equal(parts[1].image_url.url, 'data:image/png;base64,' + PNG.toString('base64'));
  assert.equal(parts[2].file.filename, 'offer.pdf');
  assert.equal(parts[2].file.file_data, 'data:application/pdf;base64,' + PDF.toString('base64'));
  assert.equal(parts[3].text, '<attachment name="notes.md">\n# Notes\nbring "papers"\n</attachment>');

  const summary = a.summary('rec-1');
  assert.ok(summary.startsWith('The user attached:\n- pasted text ('));
  assert.ok(summary.endsWith('\n- an image (photo.dat)\n- a PDF (offer.pdf)\n- a file (notes.md)'));
  assert.deepEqual(a.texts('rec-1').map((x) => x[0]), ['pasted text', 'notes.md']);
  const bins = a.binaries('rec-1');
  assert.deepEqual(bins.map((b) => [b.kind, b.mimeType, b.fileName()]), [
    ['image', 'image/png', 'photo.png'],
    ['pdf', 'application/pdf', 'offer.pdf'],
  ]);
  assert.ok(bins[0].data.equals(PNG));
  a.deleteNote('rec-1');
  assert.deepEqual(a.of('rec-1'), []);
});

test('imagesBecomeJpegWithAConverterAndLimitsHold', async (t) => {
  const { dir, put } = setup(t);
  const a = new Attachments({ dir: path.join(dir, 'attachments'), toJpeg: async () => Buffer.from([0xff, 0xd8, 0xff, 1]) });
  await a.addFile(put('Screen Shot.png', PNG));
  const [image] = a.pending();
  assert.equal(image.kind, 'image');
  assert.ok(image.file.endsWith('.jpg'));
  assert.ok(!('mime' in image));
  a.claimPending('rec-2');
  assert.equal(a.binaries('rec-2')[0].fileName(), 'Screen_Shot.jpg');
  assert.ok(a.parts('rec-2')[0].image_url.url.startsWith('data:image/jpeg;base64,'));

  await assert.rejects(a.addFile(put('big.pdf', Buffer.alloc(MAX_FILE + 1))), /too large/);
  await assert.rejects(a.addFile(path.join(dir, 'missing.txt')), /Could not read this file/);
  assert.throws(() => a.addText('   '), /Nothing to paste/);
  for (let i = 0; i < MAX_ITEMS; i++) a.addText('t' + i);
  assert.throws(() => a.addText('one more'), /At most 6 attachments/);
  a.remove(a.pending()[0]);
  assert.equal(a.pending().length, MAX_ITEMS - 1);
  a.clearPending();
  assert.deepEqual(a.pending(), []);
  assert.throws(() => a.of('../x'), /Invalid note ID/);
});

test('attachmentsExpireAfterAWeekButPendingStays', (t) => {
  const { dir } = setup(t);
  let now = Date.now();
  const a = new Attachments({ dir: path.join(dir, 'attachments'), clock: () => now });
  a.addText('kept');
  a.claimPending('old');
  a.addText('waiting');
  now += 8 * 86_400_000;
  a.prune();
  assert.deepEqual(a.of('old'), []);
  assert.equal(a.pending().length, 1);
});

test('kindsAreSniffedFromBytesThenExtension', () => {
  assert.equal(sniff('x.txt', Buffer.from([0xff, 0xd8, 0xff, 0])).mime, 'image/jpeg');
  assert.equal(sniff('x', Buffer.from('....ftypheic....')).mime, 'image/heic');
  assert.equal(sniff('x.JPG', Buffer.from('?')).kind, 'image');
  assert.equal(sniff('x.pdf', Buffer.from('?')).kind, 'pdf');
  assert.equal(sniff('x.csv', Buffer.from('a,b')).kind, 'file');
});
