'use strict';
// FeedbackTest.java
const test = require('node:test');
const assert = require('node:assert/strict');
const Feedback = require('../src/core/feedback');

function model(id, accuracy, speed, perSecond) {
  return { id, name: id, accuracyTier: accuracy, speedTier: speed, supportsDe: true, supportsEn: true, unit: 'second', pricing: { prompt: perSecond } };
}

test('eachComplaintPicksAnEngineThatAddressesIt', () => {
  const current = model('current', 3, 3, '.0001');
  const accurate = model('accurate', 5, 2, '.0002');
  const fast = model('fast', 4, 5, '.00005');
  const cheap = model('cheap', 3, 3, '.00001');
  const sloppy = model('sloppy', 1, 5, '.000001');
  const all = [current, accurate, fast, cheap, sloppy];
  const tried = new Set(['current']);
  assert.equal(Feedback.alternative(all, current, Feedback.ACCURACY, tried).id, 'accurate');
  assert.equal(Feedback.alternative(all, current, Feedback.SPEED, tried).id, 'fast');
  assert.equal(Feedback.alternative(all, current, Feedback.COST, tried).id, 'cheap');
});

test('skipsTriedAndUnassessedEngines', () => {
  const current = model('current', 4, 3, '.0001');
  const tried = model('tried', 5, 3, '.0001');
  const unassessed = model('unassessed', 0, 0, '.00001');
  const peer = model('peer', 4, 3, '.0001');
  const better = model('better', 5, 3, '.0002');
  const all = [current, tried, unassessed, peer, better];
  assert.equal(Feedback.alternative(all, current, Feedback.ACCURACY, new Set(['current', 'tried'])).id, 'better');
  assert.equal(Feedback.alternative(all, current, Feedback.COST, ['current', 'tried']), null);
});
