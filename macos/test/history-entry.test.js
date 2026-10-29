'use strict';
// HistoryEntryTest.java
const test = require('node:test');
const assert = require('node:assert/strict');
const { HistoryEntry } = require('../src/core/history-entry');
const ModelCatalog = require('../src/core/model-catalog');
const Jev = require('../src/core/jev');

const reload = (e) => new HistoryEntry(JSON.parse(JSON.stringify(e.json)));

test('costsAndResumePositionStayTogether', () => {
  let e = HistoryEntry.create('id', 'model', 15);
  e.beginRequest();
  e.accept({ text: 'one', cost: 0.00025 }, 100);
  e = reload(e);
  e.beginRequest();
  e.accept({ text: 'two', cost: 0.00035 }, 200);
  assert.equal(e.text(), 'one\ntwo');
  assert.equal(e.offset(), 200);
  assert.equal(e.cost(), 0.0006);
  assert.equal(e.json.knownCost, '0.00060'); // As BigDecimal writes it on Android.
  assert.ok(!e.incompleteCost());
});

test('missingUsageIsNeverShownAsFree', () => {
  const e = HistoryEntry.create('id', 'model', 1);
  e.beginRequest();
  e.accept({ text: 'hi', cost: null }, 32);
  assert.ok(e.incompleteCost());
  assert.ok(e.costLabel().includes('incomplete'));
  assert.ok(!e.costLabel().includes('actual'));
});

test('lostResponseRemainsVisibleAfterSuccessfulRetry', () => {
  const e = HistoryEntry.create('id', 'model', 1);
  e.beginRequest();
  e.beginRequest();
  e.accept({ text: 'hi', cost: 0.001 }, 32);
  assert.equal(e.json.unconfirmed, 1);
  assert.ok(e.incompleteCost());
  assert.equal(e.cost(), 0.001);
});

test('chargesTheFormerServerEstimatedStayMarked', () => {
  const e = HistoryEntry.create('id', 'model', 1);
  e.beginRequest();
  e.accept({ text: 'hi', cost: 0.002 }, 32);
  assert.ok(!e.incompleteCost());
  assert.equal(e.costLabel(), '$0.002 actual');
  e.json.estimated = 1;
  assert.ok(e.incompleteCost());
  assert.equal(e.cost(), 0.002);
});

test('priceUnitsAreNotConfused', () => {
  const m = { unit: 'hour', pricing: { prompt: '0.1', completion: '0' } };
  assert.equal(ModelCatalog.price(m), 'From $0.001667 / min');
  m.unit = 'second';
  assert.equal(ModelCatalog.price(m), 'From $6 / min');
  m.unit = 'token';
  assert.equal(ModelCatalog.price(m), '$100000 in / $0 out per 1M tokens');
  m.unit = 'unknown';
  assert.ok(ModelCatalog.price(m).includes('unverified'));
});

test('stepsMergeSpeechChunksAndKeepUnknownCostsUnknown', () => {
  let e = HistoryEntry.create('id', 'speech-model', 60);
  e.step('speech', 'speech-model', 1200, 0.001, null);
  e = reload(e);
  e.step('speech', 'speech-model', 800, 0.002, null);
  e.step('jev', Jev.MODEL, 400, 0.000009, null).answer = 'refine 0.97';
  e.step('refine', 'text-model', 2500, null, null);
  let steps = e.json.steps;
  assert.equal(steps.length, 3);
  const speech = steps[0];
  assert.equal(speech.kind, 'speech');
  assert.equal(speech.ms, 2000);
  assert.equal(speech.cost, '0.003');
  assert.ok(!('detail' in speech));
  const check = steps[1];
  assert.equal(check.model, Jev.MODEL);
  assert.equal(check.cost, '0.000009');
  assert.equal(check.answer, 'refine 0.97');
  assert.ok(!('detail' in check));
  assert.ok(!('cost' in steps[2])); // Unknown, never shown as free.

  // One chunk without a price makes the whole step's cost unknown; a failed chunk ends it.
  const f = HistoryEntry.create('id2', 'speech-model', 60);
  f.step('speech', 'speech-model', 1000, 0.001, null);
  f.step('speech', 'speech-model', 1000, null, null);
  f.step('speech', 'speech-model', 1000, 0.001, null);
  f.step('speech', 'speech-model', 300, null, 'No connection');
  f.step('speech', 'speech-model', 1000, 0.001, null);
  steps = f.json.steps;
  assert.equal(steps.length, 2);
  assert.ok(!('cost' in steps[0]));
  assert.equal(steps[0].ms, 3300);
  assert.equal(steps[0].detail, 'No connection');
  assert.equal(steps[1].cost, '0.001');
});

test('separateCostsAddUpExactly', () => {
  const e = HistoryEntry.create('id', 'm', 1);
  e.addCost('jevCost', 0.00002);
  e.addCost('jevCost', 0.00001);
  e.addCost('jevCost', null);
  e.addCost('anthropicCost', 1e-7);
  assert.equal(e.json.jevCost, '0.00003');
  assert.equal(e.jevCost(), 0.00003);
  assert.equal(e.json.anthropicCost, '0.0000001');
  assert.equal(e.textCost(), 0);
  e.charge(0.1);
  e.charge(0.2);
  assert.equal(e.json.knownCost, '0.3');
});
