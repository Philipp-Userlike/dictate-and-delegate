'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { Connection } = require('../src/core/mcp');

test('readsTheAnswerFromAnEventStream', () => {
  const stream =
    'event: message\n' +
    'data: {"jsonrpc":"2.0","method":"notifications/progress"}\n\n' +
    'event: message\r\n' +
    'data: {"jsonrpc":"2.0","id":2,"result":{"ok":true}}\r\n\r\n';
  const event = Connection.fromStream(stream, 2);
  assert.ok(event);
  assert.equal(event.result.ok, true);
  // As bb sends it: one event, ending in a blank line.
  assert.equal(Connection.fromStream('event: message\ndata: {"id":1,"result":{}}\n\n', 1).id, 1);
});

// Mac: the content of a tool result as text, as BbBridge reads it.
test('toolResultsReadAsText', () => {
  assert.equal(Connection.text({ content: [{ type: 'text', text: 'a' }, { type: 'image' }] }), 'a\n[image]');
  assert.equal(Connection.text({ content: [], structuredContent: { x: 1 } }), '{"x":1}');
  assert.equal(Connection.text({ content: [{ type: 'resource', resource: { uri: 'u' } }] }), 'u');
});
