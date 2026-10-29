'use strict';
// Explicit editorial tiers + price bands; never represents a measured accuracy percentage.

const str = (v) => (v === undefined || v === null ? '' : String(v));

/** org.json's optInt: a number (truncated) or a numeric string; else 0. */
function optInt(m, key) {
  const v = m[key];
  const n = typeof v === 'number' ? v : typeof v === 'string' && v.trim() !== '' ? Number(v) : NaN;
  return Number.isFinite(n) ? Math.trunc(n) : 0;
}

function tier(m, key) {
  return Math.max(0, Math.min(5, optInt(m, key)));
}

function accuracy(m) {
  return tier(m, 'accuracyTier');
}

function speed(m) {
  return tier(m, 'speedTier');
}

function minuteCost(m) {
  const p = m.pricing;
  if (p === null || typeof p !== 'object' || p.prompt === undefined || p.prompt === null) return NaN;
  const s = String(p.prompt).trim();
  const input = s === '' ? NaN : Number(s);
  if (!Number.isFinite(input) || input < 0) return NaN;
  switch (str(m.unit)) {
    case 'second':
      return input * 60;
    case 'hour':
      return input / 60;
    default:
      return NaN; // Tokens cannot be honestly converted to minutes without workload data.
  }
}

function cost(m) {
  const price = minuteCost(m);
  if (!Number.isFinite(price)) return 0;
  return price <= 0.0005 ? 1 : price <= 0.002 ? 2 : price <= 0.004 ? 3 : price <= 0.008 ? 4 : 5;
}

/** Language coverage as the catalog states it, e.g. "🌐 60 languages"; never filtered by. */
function languages(m) {
  const value = str(m.languages).trim();
  return value === '' ? '—' : value;
}

function dots(value) {
  return value === 0 ? '—' : '●'.repeat(value) + '○'.repeat(5 - value);
}

function name(m) {
  const n = m.name === undefined || m.name === null ? str(m.id) : str(m.name);
  const colon = n.indexOf(': ');
  return colon < 0 ? n : n.substring(colon + 2);
}

function score(m) {
  // Missing components earn no points. New/unassessed models do not outrank assessed models.
  return accuracy(m) * 0.65 + (cost(m) === 0 ? 0 : 6 - cost(m)) * 0.2 + speed(m) * 0.15;
}

const cmp = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
const finiteCost = (m) => (Number.isFinite(minuteCost(m)) ? minuteCost(m) : Infinity);

/** A comparator for Array.sort: "accuracy", "cost", "speed", "name", or the score (default). */
function order(sort) {
  const tie = (a, b) => cmp(name(a), name(b)) || cmp(str(a.id), str(b.id));
  let primary;
  switch (sort) {
    case 'accuracy':
      primary = (a, b) => accuracy(b) - accuracy(a) || cmp(finiteCost(a), finiteCost(b));
      break;
    case 'cost':
      primary = (a, b) => cmp(finiteCost(a), finiteCost(b));
      break;
    case 'speed':
      primary = (a, b) => speed(b) - speed(a) || accuracy(b) - accuracy(a);
      break;
    case 'name':
      primary = tie;
      break;
    default:
      primary = (a, b) => cmp(score(b), score(a));
  }
  return (a, b) => primary(a, b) || tie(a, b);
}

function filter(models, query, sort) {
  const q = query.toLowerCase().trim();
  return models.filter((m) => (str(m.name) + ' ' + str(m.id)).toLowerCase().includes(q)).sort(order(sort));
}

module.exports = { accuracy, speed, minuteCost, cost, languages, dots, name, score, order, filter };
