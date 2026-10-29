'use strict';
// ModelRankingTest.java
const test = require('node:test');
const assert = require('node:assert/strict');
const ModelRanking = require('../src/core/model-ranking');

function model(id, accuracy, speed, unit, price) {
  return { id, name: id, accuracyTier: accuracy, speedTier: speed, supportsDe: true, supportsEn: true, unit, pricing: { prompt: price } };
}

test('costsUseComparableUnitsAndDoNotGuessTokenCosts', () => {
  const second = model('seconds', 4, 4, 'second', '.0001');
  const hour = model('hours', 4, 4, 'hour', '.36');
  assert.ok(Math.abs(ModelRanking.minuteCost(second) - ModelRanking.minuteCost(hour)) < 0.0000001);
  assert.equal(ModelRanking.cost(second), 4);
  const tokens = model('tokens', 5, 0, 'token', '.000001');
  assert.ok(Number.isNaN(ModelRanking.minuteCost(tokens)));
  assert.equal(ModelRanking.cost(tokens), 0);
  assert.equal(ModelRanking.cost(model('bad', 3, 3, 'second', '-1')), 0);
});

test('listsEveryModelWhateverItsLanguagesAndSearchesByName', () => {
  const both = model('both', 4, 4, 'hour', '.1');
  const unknown = Object.assign(model('unknown', 4, 4, 'hour', '.1'), { supportsDe: false, supportsEn: false });
  assert.equal(ModelRanking.filter([both, unknown], '', 'name').length, 2);
  assert.equal(ModelRanking.filter([both, unknown], 'unk', 'name').length, 1);
  assert.equal(ModelRanking.languages(unknown), '—');
  both.languages = '🌐 60 languages';
  assert.equal(ModelRanking.languages(both), '🌐 60 languages');
});

test('sortsUnverifiedCostsAndSpeedLast', () => {
  const low = model('low', 3, 5, 'second', '.00001');
  const high = model('high', 5, 2, 'second', '.0001');
  const unknown = model('unknown', 0, 0, 'token', '0');
  const values = [unknown, high, low];
  assert.equal(ModelRanking.filter(values, '', 'cost')[0].id, 'low');
  assert.equal(ModelRanking.filter(values, '', 'cost')[2].id, 'unknown');
  assert.equal(ModelRanking.filter(values, '', 'accuracy')[0].id, 'high');
  assert.equal(ModelRanking.filter(values, '', 'speed')[0].id, 'low');
  // filter() doesn't reorder the caller's list.
  assert.deepEqual(values.map((m) => m.id), ['unknown', 'high', 'low']);
});

test('unratedModelsShowNoDots', () => {
  assert.equal(ModelRanking.dots(0), '—');
  assert.equal(ModelRanking.dots(3), '●●●○○');
});
