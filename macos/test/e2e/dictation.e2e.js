'use strict';
// End to end in the real app (Electron, headless on Linux): Chromium's fake microphone plays a
// WAV, the app records it through the audio window, transcribes it at the fake OpenRouter, shows
// the panel, inserts (here: copies, as Linux has no Accessibility), refines and keeps the note.
// Run: scripts/e2e.sh dictation

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const { clipboard } = require('electron');
const { startFake } = require('../support/fakes');
const { waitFor, sleep, withTimeout } = require('../../src/core/util');

const step = (what) => process.env.DD_TRACE && fs.appendFileSync(process.env.DD_TRACE, `e2e: ${what} ${Date.now()}\n`);

module.exports = async function (dd) {
  step('begin');
  const fake = await startFake('fake_openrouter.py');
  process.env.DD_OPENROUTER_URL = fake.url + '/api/v1';
  const shots = process.env.DD_SHOTS || '/tmp/dd-e2e';
  fs.mkdirSync(shots, { recursive: true });
  try {
    dd.ctx.vault.set(require('../../src/core/openrouter').KEY, 'sk-or-fake');
    await fake.post('/test/script', {
      transcribe: [{ text: 'Book a table for Friday at the Italian place.', cost: 0.001 }],
      decide: [{ refine: 0.1, chief: 0, template: 'none' }],
      text: [{ text: 'Please book a table for Friday at the Italian restaurant.', cost: 0.0003 }],
    });
    const d = dd.dictation();

    // Record two seconds of the fake microphone.
    step('await d.start();');
    await d.start();
    await waitFor(() => d.snapshot().state === 'recording', 10000, 'recording').catch((e) => { throw new Error(e.message + ' ' + JSON.stringify(d.snapshot())); });
    const frames = [];
    const audio = dd.audio;
    const original = audio.onFrame;
    audio.onFrame = (f) => {
      frames.push(f.length);
      original(f);
    };
    await sleep(2000);
    await capture(dd.panel(), path.join(shots, 'panel-recording.png'));
    assert.ok(frames.length > 60, `microphone frames arrived (${frames.length})`);
    assert.ok(frames.every((n) => n === 640), 'each frame is 20 ms of 16 kHz PCM16');
    step('await d.stop();');
    await d.stop();
    await waitFor(() => d.snapshot().state === 'copied', 15000, 'transcript ready');
    const snap = d.snapshot();
    assert.equal(snap.session.transcript, 'Book a table for Friday at the Italian place.');
    const reqs = (await fake.get('/test/requests')).requests;
    const t = reqs.find((r) => r.kind === 'transcribe');
    assert.ok(t, 'a transcription request reached OpenRouter: ' + JSON.stringify(reqs).slice(0, 300));
    assert.ok(t.body.input_audio.bytes > 40000, 'it carried the recording (' + t.body.input_audio.bytes + ' base64 chars)');
    await sleep(300);
    await capture(dd.panel(), path.join(shots, 'panel-result.png'));
    assert.ok(dd.panel().isVisible(), 'the panel shows the result');

    // Insert: no Accessibility on Linux, so it's copied and the panel says so.
    await clipboard.writeText('something the user copied');
    step("dd.panelAction('insert');");
    dd.panelAction('insert');
    await waitFor(() => /Copied instead/.test(d.snapshot().session.message || ''), 5000, 'copied-instead message');
    assert.equal(await clipboard.readText(), 'Book a table for Friday at the Italian place.');

    // Refine, then Insert refined.
    step("dd.panelAction('refine');");
    dd.panelAction('refine');
    await waitFor(() => d.snapshot().session.refined, 10000, 'refined text');
    assert.equal(d.snapshot().session.refined, 'Please book a table for Friday at the Italian restaurant.');
    await sleep(400);
    await capture(dd.panel(), path.join(shots, 'panel-refined.png'));

    // The note is kept with both versions and the costs.
    await sleep(300);
    step('const notes = dd.ctx.history.all();');
    const notes = dd.ctx.history.all();
    assert.ok(notes.length >= 1, 'a note was saved');
    const json = JSON.stringify(notes.map((n) => n.json));
    assert.ok(json.includes('Book a table for Friday'), 'note has the transcript');
    assert.ok(json.includes('Italian restaurant'), 'note has the refined text');
    dd.panelAction('close');

  } finally {
    fake.stop();
  }
};

async function capture(win, file) {
  try {
    // capturePage can wait forever on a window that isn't drawn yet.
    const img = await withTimeout(win.webContents.capturePage(), 3000);
    fs.writeFileSync(file, img.toPNG());
  } catch (e) {
    console.log('screenshot failed', e.message);
  }
}
