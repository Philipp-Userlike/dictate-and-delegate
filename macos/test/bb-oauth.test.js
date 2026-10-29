'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const BbOAuth = require('../src/core/bb-oauth');

test('challengeFollowsRfc7636', () => {
  // RFC 7636, appendix B.
  assert.equal(BbOAuth.challenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'), 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
});

test('verifiersAreLongRandomAndUrlSafe', () => {
  const a = BbOAuth.verifier();
  const b = BbOAuth.verifier();
  assert.equal(a.length, 43);
  assert.match(a, /^[A-Za-z0-9_-]+$/);
  assert.notEqual(a, b);
});

test('authorizeUrlCarriesEverythingBbAsksFor', () => {
  const url = BbOAuth.authorizeUrl('https://getbb.app/oauth/authorize', BbOAuth.resource('someone'), 'CHALLENGE', 'st/ate');
  const uri = new URL(url);
  assert.equal(uri.hostname, 'getbb.app');
  assert.equal(uri.pathname, '/oauth/authorize');
  const q = {};
  for (const pair of uri.search.substring(1).split('&')) {
    const [k, v] = pair.split('=', 2);
    q[k] = decodeURIComponent(v);
  }
  assert.equal(q.response_type, 'code');
  assert.equal(q.client_id, BbOAuth.CLIENT_ID);
  assert.equal(q.redirect_uri, 'https://philipp258.github.io/dictate-and-delegate/oauth/callback.html');
  assert.equal(q.scope, 'bb');
  assert.equal(q.code_challenge, 'CHALLENGE');
  assert.equal(q.code_challenge_method, 'S256');
  assert.equal(q.state, 'st/ate');
  assert.equal(q.resource, 'https://someone.getbb.app/mcp');
  assert.equal(Object.keys(q).length, 8);
  assert.ok(!url.includes('+'));
});

test('handlesAreReadFromWhateverThePersonPastes', () => {
  assert.equal(BbOAuth.handle('someone'), 'someone');
  assert.equal(BbOAuth.handle(' Someone.getbb.app '), 'someone');
  assert.equal(BbOAuth.handle('https://someone.getbb.app/threads/thr_abc'), 'someone');
  assert.equal(BbOAuth.handle('my-bb2'), 'my-bb2');
  assert.equal(BbOAuth.handle(''), '');
  assert.equal(BbOAuth.handle('not a handle'), '');
  assert.equal(BbOAuth.handle('-dash'), '');
});

test('tokensAreRefreshedWithTheClientIdTheyWereIssuedTo', () => {
  // Saved before 3.1.0, when the app was Talk Router: no clientId field, so the old client ID.
  const legacy = { refresh: 'r1', resource: 'https://someone.getbb.app/mcp' };
  assert.equal(BbOAuth.clientIdOf(legacy), 'https://philipp258.github.io/talk-router/oauth/client.json');
  const body = BbOAuth.refreshBody(legacy);
  assert.equal(body.grant_type, 'refresh_token');
  assert.equal(body.refresh_token, 'r1');
  assert.equal(body.client_id, BbOAuth.LEGACY_CLIENT_ID);
  assert.equal(body.resource, 'https://someone.getbb.app/mcp');
  // Saved by a new sign-in: the new client ID.
  const current = { ...legacy, clientId: BbOAuth.CLIENT_ID };
  assert.equal(BbOAuth.refreshBody(current).client_id, 'https://philipp258.github.io/dictate-and-delegate/oauth/client.json');
  // The redirect sits next to the new client document.
  assert.equal(BbOAuth.REDIRECT, 'https://philipp258.github.io/dictate-and-delegate/oauth/callback.html');
});
