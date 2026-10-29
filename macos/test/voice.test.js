'use strict';
// Voice has no Java test of its own; these check the port: choices, request bodies, error texts,
// cost lookups, the second request and the fallback (fake fetch and clock), and the speaker on a
// fake player.
const test = require('node:test');
const assert = require('node:assert/strict');
const net = require('net');
const path = require('path');
const { spawn } = require('child_process');
const { sleep, waitFor, deferred } = require('../src/core/util');
const { MemoryVault, MemoryPrefs } = require('../src/core/store');
const V = require('../src/core/voice');
const { Voice, Prepared, createSpeaker } = V;
const { VoiceStream } = require('../src/core/voice-stream');
const cues = require('../src/core/cues');

test('theChoicesKeepTheirIdsAndAnUnknownOneIsKokoro', () => {
  assert.deepEqual(
    V.CHOICES.map((c) => c.id),
    [
      'kokoro', 'fish-drama', 'fish-friendly', 'fish-adrian', 'fish-ethan', 'gemini-kore', 'gemini-puck', 'gemini-charon', 'gemini-aoede',
      'heygen-bram', 'heygen-nadine', 'heygen-lucian', 'heygen-tabitha', 'voxtral-paul', 'voxtral-paul-cheerful', 'voxtral-jane',
      'voxtral-oliver', 'mai-harper', 'mai-iris', 'mai-jasper', 'mai-ethan', 'eleven', 'android',
    ],
  );
  assert.equal(V.choice('fish-adrian').voice, 'bf322df2096a46f18c579d0baa36f41d');
  assert.equal(V.choice('nope'), V.KOKORO);
  assert.equal(V.DEFAULT, V.KOKORO);
  const mac = V.choice('android');
  assert.equal(mac.model, '');
  assert.equal(mac.label(), "The Mac's voice · offline, free");
  assert.equal(mac.tag(), "the Mac's voice");
  assert.equal(V.choice('fish-drama').tag(), 'fish-audio/drama-3 (933563129e564b19a115bedd57b7406a)');
  assert.equal(V.choice('gemini-puck').format, 'pcm');
  assert.equal(V.choice('heygen-bram').format, 'pcm');
  assert.equal(V.choice('mai-iris').format, 'mp3');
  assert.equal(V.ELEVEN.perCharacter(), 0.00004);
});

test('theRequestBodyAsksForTheChoicesFormatAndVoice', () => {
  assert.deepEqual(V.body(V.KOKORO, 'Hello.'), { model: 'hexgrad/kokoro-82m', input: 'Hello.', response_format: 'mp3', voice: 'af_heart' });
  assert.deepEqual(V.body(V.choice('gemini-kore'), 'Hi'), { model: V.GEMINI, input: 'Hi', response_format: 'pcm', voice: 'Kore' });
  assert.equal('voice' in V.body(V.SYSTEM, 'Hi'), false);
});

test('contentTypesAndTheWavHeader', () => {
  assert.deepEqual(V.pcmFormat('audio/pcm;rate=44100;channels=1'), [44100, 1]);
  assert.deepEqual(V.pcmFormat('Audio/PCM; rate=24000; channels=2'), [24000, 2]);
  assert.deepEqual(V.pcmFormat('audio/pcm;rate=24000'), [24000, 1]);
  assert.equal(V.pcmFormat('audio/pcm'), null);
  assert.equal(V.pcmFormat('audio/pcm;rate=fast'), null);
  assert.equal(V.pcmFormat('audio/mpeg'), null);
  assert.equal(V.pcmFormat(null), null);
  assert.ok(V.isMp3('audio/mpeg'));
  assert.ok(V.isMp3('audio/MP3; charset=x'));
  assert.ok(!V.isMp3('audio/wav'));
  const h = V.wavHeader(24000, 1, 4800);
  assert.equal(h.length, 44);
  assert.equal(h.toString('ascii', 0, 4), 'RIFF');
  assert.equal(h.readInt32LE(4), 36 + 4800);
  assert.equal(h.toString('ascii', 8, 16), 'WAVEfmt ');
  assert.equal(h.readInt16LE(22), 1);
  assert.equal(h.readInt32LE(24), 24000);
  assert.equal(h.readInt32LE(28), 48000);
  assert.equal(h.readInt16LE(32), 2);
  assert.equal(h.readInt16LE(34), 16);
  assert.equal(h.readInt32LE(40), 4800);
});

test('anHttpErrorSaysTheStatusAndWhatTheModelSaid', () => {
  assert.equal(V.httpError(400, '{"error":{"code":400,"message":"An explicit voice is required for this TTS provider."}}'), 'HTTP 400: An explicit voice is required for this TTS provider.');
  assert.equal(V.httpError(402, '{"message":"Insufficient credits"}'), 'HTTP 402: Insufficient credits');
  assert.equal(V.httpError(502, '<html>\n  Bad   gateway\n</html>'), 'HTTP 502: <html> Bad gateway </html>');
  assert.equal(V.httpError(500, ''), 'HTTP 500');
  assert.equal(V.httpError(500, null), 'HTTP 500');
  assert.equal(V.httpError(503, '{"error":{"message":""}}'), 'HTTP 503: {"error":{"message":""}}');
  const long = V.httpError(500, 'x'.repeat(300));
  assert.equal(long, 'HTTP 500: ' + 'x'.repeat(160) + '…');
});

// ------------------------------------------------------------------ fetching with a fake fetch

/** A response whose body the test writes: push(bytes), end(), or never. */
function streamed(headers, status = 200) {
  let controller;
  const body = new ReadableStream({ start: (c) => (controller = c) });
  return {
    response: new Response(body, { status, headers }),
    push: (b) => controller.enqueue(new Uint8Array(b)),
    end: () => controller.close(),
  };
}

/** A fake fetch: each call is recorded and answered by answer(call) (a Response or a promise of one). */
function fakeFetch(answer) {
  const calls = [];
  const fetch = (url, init = {}) => {
    const call = { url, init, body: init.body ? JSON.parse(init.body) : null, signal: init.signal };
    calls.push(call);
    return new Promise((resolve, reject) => {
      if (call.signal) call.signal.addEventListener('abort', () => reject(new Error('aborted')));
      Promise.resolve(answer(call, calls.length)).then(resolve, reject);
    });
  };
  return { fetch, calls };
}

const never = () => new Promise(() => {});
const pcmHeaders = { 'Content-Type': 'audio/pcm;rate=24000;channels=1', 'X-Generation-Id': 'gen-1' };

function voiceWith(fetch, clock) {
  return new Voice({ vault: new MemoryVault({ openrouterKey: 'sk-or-1' }), fetch, baseUrl: 'https://or.test/api/v1', clock, sleep: async () => {} });
}

test('aReplysAudioIsAskedForAndPlayableFromAFifthOfASecond', async () => {
  const r = streamed(pcmHeaders);
  const f = fakeFetch(() => r.response);
  const v = voiceWith(f.fetch);
  const preparing = v.prepare(V.choice('gemini-kore'), 'Hello there.', 3000);
  await waitFor(() => f.calls.length === 1);
  r.push(Buffer.alloc(4800)); // 100 ms.
  await sleep(60);
  r.push(Buffer.alloc(4800)); // 200 ms: enough to start.
  const p = await preparing;
  assert.ok(p instanceof Prepared && p.ready());
  assert.equal(p.stream.encoding, 'pcm');
  assert.equal(p.stream.rate(), 24000);
  assert.equal(p.generation, 'gen-1');
  assert.ok(p.firstByteMs >= 0 && p.playableMs >= p.firstByteMs);
  assert.equal(p.fallback, '');
  assert.equal(p.how, '');
  const call = f.calls[0];
  assert.equal(call.url, 'https://or.test/api/v1/audio/speech');
  assert.equal(call.init.method, 'POST');
  assert.equal(call.init.headers.Authorization, 'Bearer sk-or-1');
  assert.equal(call.init.headers['X-Title'], 'Dictate & Delegate');
  assert.deepEqual(call.body, { model: V.GEMINI, input: 'Hello there.', response_format: 'pcm', voice: 'Kore' });
  // The rest comes in while it plays.
  r.push(Buffer.alloc(48000 * 2));
  r.end();
  await waitFor(() => p.stream.complete());
  assert.equal(p.stream.failed(), null);
  assert.equal(p.stream.audioMs(), 2200);
  assert.ok(v.charsPerSecond > 5 && v.charsPerSecond < 6, 'learned from the whole reply: ' + v.charsPerSecond);
});

test('theMacsVoiceChosenFetchesNothing', async () => {
  const f = fakeFetch(never);
  const p = await voiceWith(f.fetch).prepare(V.SYSTEM, 'Hi');
  assert.equal(p.ready(), false);
  assert.equal(p.fallback, '');
  assert.equal(f.calls.length, 0);
});

test('aSlowRequestGetsASecondAfterASecondAndTheFirstToDeliverPlays', async () => {
  const now = { t: 0 };
  const second = streamed(pcmHeaders);
  const f = fakeFetch((call, n) => (n === 1 ? never() : second.response));
  const v = voiceWith(f.fetch, () => now.t);
  const preparing = v.prepare(V.choice('heygen-bram'), 'Hello.', 3000);
  await sleep(60);
  assert.equal(f.calls.length, 1);
  now.t = 999;
  await sleep(60);
  assert.equal(f.calls.length, 1, 'not before a second');
  now.t = 1000;
  await waitFor(() => f.calls.length === 2);
  second.push(Buffer.alloc(9600));
  second.end();
  const p = await preparing;
  assert.ok(p.ready());
  assert.equal(p.how, 'second request won (sent after 1000 ms)');
  assert.equal(f.calls[0].signal.aborted, true, 'the first is dropped');
  assert.equal(f.calls[1].signal.aborted, false);
});

test('noAnswerWithinTheWaitAndTheMacsVoiceReadsIt', async () => {
  const now = { t: 0 };
  const f = fakeFetch(never);
  const v = voiceWith(f.fetch, () => now.t);
  const preparing = v.prepare(V.KOKORO, 'Hello.', 3000);
  now.t = 1000;
  await waitFor(() => f.calls.length === 2);
  now.t = 3000;
  const p = await preparing;
  assert.equal(p.ready(), false);
  assert.equal(p.fallback, 'Kokoro: no answer in 3.0 s');
  assert.equal(p.how, 'two requests');
  assert.equal(p.playableMs, -1);
  assert.ok(f.calls.every((c) => c.signal.aborted), 'neither is downloaded on');
});

test('anAnswerWhoseAudioDoesntStartInTimeIsGivenUpToo', async () => {
  const now = { t: 0 };
  const f = fakeFetch(() => streamed({ 'Content-Type': 'audio/mpeg' }).response);
  const v = voiceWith(f.fetch, () => now.t);
  const preparing = v.prepare(V.KOKORO, 'Hello.', 2500);
  await waitFor(() => f.calls.length === 1);
  await sleep(50);
  now.t = 2500;
  const p = await preparing;
  assert.equal(p.fallback, "Kokoro: its audio didn't start coming in 2.5 s");
  assert.equal(p.firstByteMs, 0);
});

test('aFailingVoiceModelIsTriedThreeTimesThenSaysWhy', async () => {
  const f = fakeFetch(() => new Response('{"error":{"code":503,"message":"No provider available"}}', { status: 503 }));
  const p = await voiceWith(f.fetch).prepare(V.choice('mai-harper'), 'Hello.', 3000);
  assert.equal(f.calls.length, 3);
  assert.equal(p.ready(), false);
  assert.equal(p.fallback, 'MAI-Voice 2.1 Flash · Harper: HTTP 503: No provider available');
});

test('anAnswerWithoutAudioCountsAsAFailure', async () => {
  const f = fakeFetch(() => new Response(new Uint8Array(0), { status: 200, headers: { 'Content-Type': 'audio/mpeg' } }));
  const p = await voiceWith(f.fetch).prepare(V.KOKORO, 'Hello.', 3000);
  assert.equal(p.fallback, 'Kokoro: it sent no audio');
  // Bytes, but not one MP3 frame in them.
  const g = fakeFetch(() => new Response(Buffer.from('not audio at all'), { status: 200, headers: { 'Content-Type': 'audio/mpeg' } }));
  assert.equal((await voiceWith(g.fetch).prepare(V.KOKORO, 'Hello.', 3000)).fallback, 'Kokoro: it sent no audio');
});

test('anotherTypeIsPlayedWholeOnceItsIn', async () => {
  const f = fakeFetch(() => new Response(Buffer.alloc(3000, 7), { status: 200, headers: { 'Content-Type': 'audio/wav' } }));
  const p = await voiceWith(f.fetch).prepare(V.KOKORO, 'Hello.', 3000);
  assert.ok(p.ready());
  assert.equal(p.stream.encoding, 'file');
  assert.equal(p.stream.mime, 'audio/wav');
  assert.equal(p.stream.data().length, 3000);
});

test('whatAReplyCostIsLookedUpWithItsGenerationId', async () => {
  const waits = [];
  let n = 0;
  const f = fakeFetch(() =>
    ++n === 1
      ? new Response('{}', { status: 404 })
      : new Response(JSON.stringify({ data: { id: 'gen-1', total_cost: 0.0000124, provider_name: 'DeepInfra', latency: 420 } }), { status: 200 }),
  );
  const v = new Voice({ vault: new MemoryVault({ openrouterKey: 'sk-or-1' }), fetch: f.fetch, baseUrl: 'https://or.test/api/v1', sleep: async (ms) => waits.push(ms) });
  const g = await v.generation('gen/1');
  assert.deepEqual(g, { cost: 0.0000124, costText: '0.0000124', provider: 'DeepInfra', latencyMs: 420 });
  assert.equal(f.calls[1].url, 'https://or.test/api/v1/generation?id=gen%2F1');
  assert.equal(f.calls[1].init.headers.Authorization, 'Bearer sk-or-1');
  assert.deepEqual(waits, [1500, 3000], 'it shows up a moment after the call');
  // Not there after four tries, or no ID: unknown.
  const none = fakeFetch(() => new Response('{"data":{"total_cost":null}}', { status: 200 }));
  const w = new Voice({ fetch: none.fetch, baseUrl: 'https://or.test/api/v1', sleep: async () => {} });
  assert.equal(await w.cost('gen-2'), null);
  assert.equal(none.calls.length, 4);
  assert.equal(await w.cost(''), null);
  assert.deepEqual(V.listCost(V.KOKORO, 'x'.repeat(1000)), { cost: 0.00062, costText: '0.00062' });
});

// ------------------------------------------------------------------ playing, on a fake player

/** A player that records what it's asked; the test drives its callbacks. */
function fakePlayer() {
  const p = { streams: [], speeches: [], warmed: [] };
  p.warm = async (ms) => p.warmed.push(ms);
  p.stream = (s, cb) => {
    const h = { s, cb, played: 0, stopped: false, stop: () => (h.stopped = true), playedMs: () => h.played, playing: () => !h.stopped };
    p.streams.push(h);
    return h;
  };
  p.speak = (text, cb) => {
    const h = { text, cb, stopped: false, stop: () => (h.stopped = true), playing: () => !h.stopped };
    p.speeches.push(h);
    return h;
  };
  return p;
}

function recorder() {
  const r = { started: [], ended: [], done: deferred() };
  r.playback = { started: (ms) => r.started.push(ms), ended: (played) => (r.ended.push(played), r.done.resolve(played)) };
  return r;
}

function pcmStream(ms, complete = true) {
  const s = new VoiceStream();
  s.format(24000, 1);
  s.add(Buffer.alloc(48 * ms));
  if (complete) s.end(100);
  return s;
}

test('aStreamedReplyPlaysOnThePlayerAfterAWarmUpAndSaysHowFarYouHeard', async () => {
  const player = fakePlayer();
  const v = new Voice({ player });
  const text = 'x'.repeat(100);
  const r = recorder();
  v.play(new Prepared(pcmStream(10_000)), text, r.playback);
  await waitFor(() => player.streams.length === 1);
  assert.deepEqual(player.warmed, [V.WARM_MS]);
  const h = player.streams[0];
  h.cb.onStarted();
  await waitFor(() => r.started.length === 1);
  assert.deepEqual(r.started, [10_000]);
  assert.equal(v.playing(), true);
  h.played = 5300;
  assert.equal(v.heardChars(), 50);
  h.cb.onEnded(true);
  assert.equal(await r.done.promise, true);
  assert.equal(v.playing(), false);
  // With the call's audio kept open there's no warm-up.
  v.callOpen = true;
  v.play(new Prepared(pcmStream(1000)), text, recorder().playback);
  await waitFor(() => player.streams.length === 2);
  assert.deepEqual(player.warmed, [V.WARM_MS]);
});

test('stoppingIsSilentAtOnceKeepsHowFarYouHeardAndEndsUnplayed', async () => {
  const player = fakePlayer();
  const v = new Voice({ player });
  const r = recorder();
  const s = pcmStream(2000, false);
  let cancelled = 0;
  s.cancel = () => cancelled++;
  v.play(new Prepared(s), 'x'.repeat(400), r.playback);
  await waitFor(() => player.streams.length === 1);
  const h = player.streams[0];
  h.cb.onStarted();
  h.played = 1300;
  v.stop();
  assert.equal(h.stopped, true);
  assert.ok(cancelled > 0, 'not fetched on');
  assert.equal(await r.done.promise, false);
  // Still coming, so at most 10 characters a second: 1 s heard → 10.
  assert.equal(v.heardChars(), 10);
  h.cb.onEnded(false); // Late: changes nothing.
  assert.deepEqual(r.ended, [false]);
});

test('withoutAudioTheMacsVoiceReadsAndSaysWhereItIs', async () => {
  const player = fakePlayer();
  const v = new Voice({ player });
  const r = recorder();
  v.play(new Prepared(null), 'Book the table for Friday.', r.playback);
  await waitFor(() => player.speeches.length === 1);
  const sp = player.speeches[0];
  assert.equal(sp.text, 'Book the table for Friday.');
  sp.cb.onStarted();
  await waitFor(() => r.started.length === 1);
  assert.deepEqual(r.started, [Math.floor((26 * 1000) / 15)]);
  sp.cb.onRange(9);
  assert.equal(v.heardChars(), 9);
  assert.equal(v.playing(), true);
  sp.cb.onEnded(true);
  assert.equal(await r.done.promise, true);
  // A voice that fails: from now on the voice model is waited for.
  const statuses = [];
  v.systemStatus = (s) => statuses.push(s);
  const r2 = recorder();
  v.play(new Prepared(null), 'Hi', r2.playback);
  await waitFor(() => player.speeches.length === 2);
  player.speeches[1].cb.onError('no voices');
  assert.equal(await r2.done.promise, false);
  assert.equal(v.systemUnavailable, true);
  assert.deepEqual(statuses, ["couldn't speak (no voices): the voice model is waited for from now on"]);
});

test('aStreamThePlayerCantPlayIsReadByTheMacsVoice', async () => {
  const player = fakePlayer();
  player.stream = () => {
    throw new Error('no output');
  };
  const v = new Voice({ player });
  const r = recorder();
  v.play(new Prepared(pcmStream(1000)), 'Hello.', r.playback);
  await waitFor(() => player.speeches.length === 1);
  // And one that ends before a sound came out (a file that doesn't decode).
  const p2 = fakePlayer();
  const w = new Voice({ player: p2 });
  w.play(new Prepared(pcmStream(1000)), 'Hello.', recorder().playback);
  await waitFor(() => p2.streams.length === 1);
  p2.streams[0].cb.onEnded(false);
  await waitFor(() => p2.speeches.length === 1);
});

test('aStreamThatStopsComingWhilePlayingIsGivenUp', async () => {
  const now = { t: 0 };
  const player = fakePlayer();
  const v = new Voice({ player, clock: () => now.t });
  const s = pcmStream(500, false);
  v.play(new Prepared(s), 'Hello there.', recorder().playback);
  await waitFor(() => player.streams.length === 1);
  now.t = 4000;
  s.add(Buffer.alloc(480)); // Still coming.
  await sleep(150);
  now.t = 8900;
  await sleep(150);
  assert.equal(s.complete(), false);
  now.t = 9100;
  await waitFor(() => s.complete());
  assert.equal(s.failed(), 'its audio stopped coming for 5 s');
});

test('theSpeakerPreparesWithTheChosenVoiceLogsAndCharges', async () => {
  const r = streamed(pcmHeaders);
  const f = fakeFetch((call) => (call.url.includes('/generation') ? new Response('{"data":{"total_cost":0.0001,"provider_name":"Google"}}') : r.response));
  const v = voiceWith(f.fetch);
  v.player = fakePlayer();
  const events = [],
    costs = [],
    failures = [];
  const speaker = createSpeaker({
    voice: v,
    prefs: new MemoryPrefs({ hfVoice: 'gemini-kore', hfVoiceWaitMs: 3000 }),
    event: (e) => events.push(e),
    onCost: (c) => costs.push(c),
    onVoiceFailed: (why, system) => failures.push([why, system]),
  });
  const preparing = speaker.prepare('r-1', 'Hi there.');
  await waitFor(() => f.calls.length === 1);
  r.push(Buffer.alloc(9600));
  r.end();
  const p = await preparing;
  assert.ok(p.ready());
  await waitFor(() => costs.length === 1);
  assert.match(events[0], /^voice google\/gemini-3\.8-flash-lite-tts \(Kore\) for r-1: first byte after \d+ ms, playable after \d+ ms$/);
  assert.ok(events.includes('voice for r-1: all of it after 0 ms (0.2 s of audio)') || events.some((e) => /^voice for r-1: all of it after \d+ ms \(0\.2 s of audio\)$/.test(e)), events.join('\n'));
  assert.ok(events.includes('voice google/gemini-3.8-flash-lite-tts (Kore): 9 characters, $0.0001, served by Google'), events.join('\n'));
  assert.equal(costs[0].cost, 0.0001);
  assert.equal(costs[0].exact, true);
  assert.equal(costs[0].request, 'r-1');
  // Play, then drop and stop pass through.
  const rec = recorder();
  speaker.play('r-1', 'Hi there.', p, rec.playback);
  await waitFor(() => v.player.streams.length === 1);
  assert.equal(speaker.playing(), true);
  speaker.stop();
  assert.equal(await rec.done.promise, false);
  // A voice model that fails: the session hears why, and that the Mac's voice reads it.
  const bad = fakeFetch(() => new Response('{"error":{"message":"busy"}}', { status: 429 }));
  const w = voiceWith(bad.fetch);
  const s2 = createSpeaker({ voice: w, prefs: new MemoryPrefs(), event: (e) => events.push(e), onVoiceFailed: (why, system) => failures.push([why, system]) });
  const q = await s2.prepare('r-2', 'Hi');
  assert.equal(q.ready(), false);
  assert.deepEqual(failures, [['Kokoro: HTTP 429: busy', true]]);
  assert.equal(events.at(-1), "voice hexgrad/kokoro-82m (af_heart) for r-2 failed: Kokoro: HTTP 429: busy (first byte -1 ms): the Mac's voice reads it");
});

// ------------------------------------------------------------------ against scripts/fakes/fake_openrouter.py

async function freePort() {
  const s = net.createServer();
  await new Promise((r) => s.listen(0, '127.0.0.1', r));
  const port = s.address().port;
  await new Promise((r) => s.close(r));
  return port;
}

test('theFakeOpenRoutersVoiceStreamsPcmAndSaysItsCost', async (t) => {
  const port = await freePort();
  const fake = spawn('python3', [path.join(__dirname, '..', '..', 'scripts', 'fakes', 'fake_openrouter.py'), '--port', String(port)], { stdio: 'ignore' });
  const base = `http://127.0.0.1:${port}`;
  try {
    let up = false;
    for (let i = 0; i < 100 && !up; i++) {
      try {
        up = (await fetch(base + '/api/v1/models')).ok;
      } catch {
        await sleep(50);
      }
    }
    if (!up) return t.skip('python3 or the fake is missing');
    const v = new Voice({ vault: new MemoryVault({ openrouterKey: 'sk-or-fake' }), baseUrl: base + '/api/v1', sleep: async () => {} });
    const p = await v.prepare(V.choice('gemini-aoede'), 'A reply read aloud.', 3000);
    assert.ok(p.ready(), p.fallback);
    assert.equal(p.generation, 'gen-tts-fake');
    await waitFor(() => p.stream.complete(), 3000);
    assert.equal(p.stream.failed(), null);
    assert.equal(p.stream.audioMs(), 1500);
    assert.equal(await v.cost(p.generation), 0.0001);
    // It fails: why, from what it said.
    await fetch(base + '/test/script', { method: 'POST', body: JSON.stringify({ speech: [{ status: 503, message: 'Busy' }, { status: 503, message: 'Busy' }, { status: 503, message: 'Busy' }] }) });
    const q = await v.prepare(V.choice('gemini-aoede'), 'Again.', 3000);
    assert.equal(q.fallback, 'Gemini Flash Lite TTS · Aoede: HTTP 503: Busy');
  } finally {
    fake.kill();
  }
});

// ------------------------------------------------------------------ cues

test('theCuesAreTheirNotesAsQuietPcm', () => {
  assert.deepEqual(cues.CUES, ['HEARD', 'SENT', 'REPLY', 'FAILED']);
  const heard = cues.pcm('HEARD');
  assert.equal(heard.rate, 22050);
  assert.equal(heard.channels, 1);
  assert.equal(heard.pcm.length, Math.trunc((22050 * 45) / 1000) * 2);
  const sent = cues.samples('SENT');
  assert.equal(sent.length, Math.trunc((22050 * 70) / 1000) + Math.trunc((22050 * 90) / 1000));
  let peak = 0;
  for (const s of sent) peak = Math.max(peak, Math.abs(s));
  assert.ok(peak <= Math.round(0.12 * 32767) && peak > 3000, String(peak));
  assert.equal(sent[0], 0, 'faded in');
  assert.equal(cues.samples('FAILED').at(-1), 0, 'faded out');
  assert.throws(() => cues.pcm('NOPE'));
});
