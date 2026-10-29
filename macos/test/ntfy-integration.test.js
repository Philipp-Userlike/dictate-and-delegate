'use strict';
// The ntfy side against scripts/fakes/fake_ntfy.py: the test message, waiting for a request's
// text, fetching it later, hands-free replies, and the Mac's general subscription. No real ntfy.

const test = require('node:test');
const assert = require('node:assert/strict');
const { MemoryPrefs, MemoryVault } = require('../src/core/store');
const { waitFor, sleep } = require('../src/core/util');
const BbBridge = require('../src/core/bb-bridge');
const ChiefText = require('../src/core/chief-text');
const { startFake, freePort } = require('./support/fakes');

let ntfy;

test.before(async () => {
  ntfy = await startFake('fake_ntfy.py');
});

test.after(() => ntfy && ntfy.stop());

test.beforeEach(async () => {
  await ntfy.post('/test/reset');
});

function bridge() {
  const b = new BbBridge.BbBridge({ prefs: new MemoryPrefs(), vault: new MemoryVault() });
  b.setNtfyServer(ntfy.url + '/');
  return b;
}

/** Publishes as the chief's curl does. */
async function publish(topic, message, { title, tags } = {}) {
  const headers = {};
  if (title) headers.Title = title;
  if (tags) headers.Tags = tags.join(',');
  const res = await fetch(ntfy.url + '/' + topic, { method: 'POST', headers, body: message });
  return res.json();
}

const now = () => Math.floor(Date.now() / 1000);

test('theTestMessageGoesToTheTopic', async () => {
  const b = bridge();
  assert.equal(b.ntfyServer(), ntfy.url);
  await b.ntfyTest();
  const log = (await ntfy.get('/test/requests')).requests;
  assert.deepEqual(log, [
    {
      method: 'publish',
      topic: b.ntfyTopic(),
      title: 'Dictate & Delegate',
      tags: [],
      actions: '',
      message: "Test from Dictate & Delegate. Your chief of staff's notifications arrive like this.",
    },
  ]);
  // The topic is made once.
  assert.equal(b.ntfyTopic(), log[0].topic);
  const closed = await freePort();
  b.setNtfyServer('http://127.0.0.1:' + closed);
  await assert.rejects(b.ntfyTest(), /Could not reach http:\/\/127\.0\.0\.1:\d+\. Check the address/);
});

test('theListenerWaitsForThisRequestsText', async () => {
  const topic = 'talkrouter-listen';
  const found = [];
  const l = new ChiefText.Listener(ntfy.url, topic, 'k3x9qa', now(), (t) => found.push(t)).start();
  await sleep(100);
  await publish(topic, 'not for us', { tags: ['talkrouter-paste', 'req-other1'] });
  await publish(topic, 'a notification', { title: 'Done' });
  await publish(topic, '  Hi Tom, see you at 10.  ', { tags: ['talkrouter-paste', 'req-k3x9qa'] });
  await waitFor(() => found.length > 0, 5000, 'the text');
  await l.done;
  assert.deepEqual(found, ['Hi Tom, see you at 10.']);
  const subs = (await ntfy.get('/test/requests')).requests.filter((r) => r.method === 'subscribe');
  assert.equal(subs[0].topic, topic);
  assert.equal(subs[0].poll, false);
});

test('aListenerStoppedEarlyFindsNothing', async () => {
  const found = [];
  const l = new ChiefText.Listener(ntfy.url, 'talkrouter-stop', 'abc123', now(), (t) => found.push(t)).start();
  await sleep(100);
  l.finish();
  await l.done;
  await publish('talkrouter-stop', 'late', { tags: ['talkrouter-paste', 'req-abc123'] });
  await sleep(100);
  assert.deepEqual(found, []);
});

test('theTextIsFetchedLaterFromWhatTheTopicHolds', async () => {
  const b = bridge();
  const topic = b.ntfyTopic();
  await publish(topic, 'first draft', { tags: ['talkrouter-paste', 'req-ab12cd'] });
  await publish(topic, 'second draft', { tags: ['talkrouter-paste', 'req-ab12cd'] });
  await publish(topic, 'other', { tags: ['talkrouter-paste', 'req-zz99zz'] });
  assert.equal(await ChiefText.fetchText(b, 'ab12cd'), 'second draft');
  assert.equal(await ChiefText.fetchText(b, 'nothere'), '');
  const sub = (await ntfy.get('/test/requests')).requests.find((r) => r.method === 'subscribe');
  assert.equal(sub.poll, true);
});

test('handsFreeRepliesComeOnceEachAndOnlyOurs', async () => {
  const topic = 'talkrouter-replies';
  const got = [];
  const r = new ChiefText.Replies({
    server: ntfy.url,
    topic,
    sinceSeconds: now(),
    ours: (req) => req === 'ab12' || req === 'cd34',
    untagged: () => 'cd34',
    onReply: (request, text, published, untagged) => got.push({ request, text, untagged, published }),
  }).start();
  await sleep(100);
  await publish(topic, 'Booked.', { tags: ['talkrouter-speak', 'req-ab12'] });
  await publish(topic, 'Not ours.', { tags: ['talkrouter-speak', 'req-zz99'] });
  await publish(topic, 'A paste.', { tags: ['talkrouter-paste', 'req-ab12'] });
  await publish(topic, 'From the chief.', { tags: ['talkrouter-speak'] });
  await waitFor(() => got.length >= 2, 5000, 'two replies');
  await sleep(100);
  r.finish();
  await r.done;
  assert.deepEqual(got.map((g) => [g.request, g.text, g.untagged]), [
    ['ab12', 'Booked.', false],
    ['cd34', 'From the chief.', true],
  ]);
  assert.ok(got[0].published > 0 && got[0].published % 1000 === 0);
});

test('theMacSubscriptionShowsEveryMessageOnceAcrossReconnects', async () => {
  const topic = 'talkrouter-mac';
  const seen = [];
  const states = [];
  let connections = 0;
  // The first connection drops right after its first message, as a flaky network would.
  const flaky = async (url, opts) => {
    const res = await fetch(url, opts);
    if (connections++ > 0) return res;
    const reader = res.body.getReader();
    const body = new ReadableStream({
      async pull(c) {
        const { done, value } = await reader.read();
        if (done) return c.close();
        c.enqueue(value);
        if (Buffer.from(value).toString().includes('"event": "message"')) {
          reader.cancel().catch(() => {});
          c.error(new Error('dropped'));
        }
      },
    });
    return new Response(body, { status: res.status, headers: res.headers });
  };
  const sub = ChiefText.subscribe({
    server: ntfy.url,
    topic,
    sinceSeconds: now(),
    fetch: flaky,
    minBackoffMs: 50,
    onState: (s) => states.push(s),
    onMessage: (m) => seen.push(ChiefText.classify(m)),
  });
  await waitFor(() => states.includes('open'), 5000, 'the stream');
  await publish(topic, 'Found three flights.', { title: 'Flights' });
  await waitFor(() => connections >= 2 && states.filter((s) => s === 'open').length >= 2, 5000, 'the reconnect');
  await publish(topic, 'Hi Tom', { title: 'Reply to Tom', tags: ['talkrouter-paste', 'req-k3x9qa'] });
  await waitFor(() => seen.length >= 2, 5000, 'both messages');
  await sleep(150);
  await sub.stop();
  assert.deepEqual(seen.map((s) => [s.kind, s.title, s.text, s.request]), [
    ['notification', 'Flights', 'Found three flights.', ''],
    ['paste', 'Reply to Tom', 'Hi Tom', 'k3x9qa'],
  ]);
  assert.ok(states.includes('retrying'));
  // The reconnect resumed after the message it had, not from the start.
  const subs = (await ntfy.get('/test/requests')).requests.filter((r) => r.method === 'subscribe');
  assert.equal(subs[1].since, seen[0].id);
});
