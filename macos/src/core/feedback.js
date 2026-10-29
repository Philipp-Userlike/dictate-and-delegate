'use strict';
// "Redo with a smarter model": picks the next engine to try from editorial tiers and prices.

const ModelRanking = require('./model-ranking');

const ACCURACY = 'accuracy';
const SPEED = 'speed';
const COST = 'cost';
const ISSUES = [ACCURACY, SPEED, COST];
const LABELS = ['Wrong words', 'Too slow', 'Too expensive'];

function adjective(issue) {
  switch (issue) {
    case SPEED:
      return 'faster';
    case COST:
      return 'cheaper';
    default:
      return 'more accurate';
  }
}

/** Returns null when no assessed engine improves on the complaint. tried: a Set or array of IDs. */
function alternative(models, current, issue, tried) {
  const triedSet = tried instanceof Set ? tried : new Set(tried || []);
  const accuracy = current ? ModelRanking.accuracy(current) : 0;
  const speed = current ? ModelRanking.speed(current) : 0;
  const price = current ? ModelRanking.minuteCost(current) : NaN;
  // Speed and cost fixes may not trade away much accuracy.
  const floor = Math.max(3, accuracy - 1);
  let improves;
  switch (issue) {
    case SPEED:
      improves = (m) => ModelRanking.speed(m) > speed && ModelRanking.accuracy(m) >= floor;
      break;
    case COST:
      improves = (m) => {
        const p = ModelRanking.minuteCost(m);
        return Number.isFinite(p) && (!Number.isFinite(price) || p < price) && ModelRanking.accuracy(m) >= floor;
      };
      break;
    default:
      improves = (m) => ModelRanking.accuracy(m) > Math.max(0, accuracy);
  }
  const order = ModelRanking.order(issue === ACCURACY ? 'accuracy' : issue);
  let best = null;
  for (const m of models) {
    if (ModelRanking.accuracy(m) <= 0 || triedSet.has(String(m.id ?? '')) || !improves(m)) continue;
    if (best === null || order(m, best) < 0) best = m; // Stream.min keeps the first of equals.
  }
  return best;
}

module.exports = { ACCURACY, SPEED, COST, ISSUES, LABELS, adjective, alternative };
