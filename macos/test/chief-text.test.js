'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const ChiefText = require('../src/core/chief-text');

test('requestIdsAreShortAndRandom', () => {
  const id = ChiefText.requestId();
  assert.match(id, /^[a-z2-9]{6}$/);
  assert.notEqual(id, ChiefText.requestId());
});

test('onlyThePasteMessageForThisRequestCounts', async () => {
  const m = { event: 'message', message: 'Hi Tom, see you at 10.', tags: ['talkrouter-paste', 'req-k3x9qa'] };
  assert.ok(ChiefText.matches(m, 'k3x9qa'));
  assert.equal(await ChiefText.text(m, 'https://ntfy.sh'), 'Hi Tom, see you at 10.');
  assert.ok(!ChiefText.matches(m, 'other1'));
  assert.ok(!ChiefText.matches({ event: 'message', tags: ['req-k3x9qa'] }, 'k3x9qa'));
  assert.ok(!ChiefText.matches({ event: 'keepalive', tags: ['talkrouter-paste', 'req-k3x9qa'] }, 'k3x9qa'));
  assert.ok(!ChiefText.matches({ event: 'open' }, 'k3x9qa'));
});

test('anAttachmentOnAnotherServerIsNotFetched', async () => {
  const m = {
    event: 'message',
    message: 'You received a file: a.txt',
    attachment: { type: 'text/plain', url: 'https://ntfy.sh.evil.example/a.txt' },
    tags: ['talkrouter-paste', 'req-k3x9qa'],
  };
  const fetch = () => assert.fail('fetched ' + m.attachment.url);
  assert.equal(await ChiefText.text(m, 'https://ntfy.sh', { fetch }), 'You received a file: a.txt');
});

test('theStreamStartsWhenTheRequestWasSent', () => {
  assert.equal(ChiefText.streamUrl('https://ntfy.sh', 'talkrouter-x', 1792248545, false), 'https://ntfy.sh/talkrouter-x/json?since=1792248545');
  assert.equal(ChiefText.streamUrl('http://127.0.0.1:8799', 't', 5, true), 'http://127.0.0.1:8799/t/json?since=5&poll=1');
});

// Mac: what a topic message is for the notifications.
test('messagesAreClassifiedForTheMac', () => {
  const paste = ChiefText.classify({ id: 'a1', event: 'message', title: 'Reply to Tom', message: ' Hi Tom ', tags: ['talkrouter-paste', 'req-k3x9qa'] });
  assert.deepEqual(
    { kind: paste.kind, request: paste.request, title: paste.title, text: paste.text },
    { kind: 'paste', request: 'k3x9qa', title: 'Reply to Tom', text: 'Hi Tom' },
  );
  const speak = ChiefText.classify({ event: 'message', message: 'Done.', tags: ['talkrouter-speak', 'req-ab12cd'] });
  assert.equal(speak.kind, 'speak');
  assert.equal(speak.request, 'ab12cd');
  const note = ChiefText.classify({ event: 'message', title: 'Flights', message: 'Found three.', click: 'https://x.getbb.app/threads/thr_a' });
  assert.equal(note.kind, 'notification');
  assert.equal(note.request, '');
  assert.equal(note.click, 'https://x.getbb.app/threads/thr_a');
});

test('spokenRepliesAreToldApart', () => {
  assert.equal(ChiefText.spokenRequest({ event: 'message', tags: ['talkrouter-speak', 'req-ab12'] }), 'ab12');
  assert.equal(ChiefText.spokenRequest({ event: 'message', tags: ['talkrouter-speak'] }), '');
  assert.ok(ChiefText.spoken({ event: 'message', tags: ['talkrouter-speak'] }));
  assert.equal(ChiefText.spokenRequest({ event: 'message', tags: ['talkrouter-paste', 'req-ab12'] }), '');
  assert.ok(!ChiefText.spoken({ event: 'keepalive', tags: ['talkrouter-speak'] }));
});
