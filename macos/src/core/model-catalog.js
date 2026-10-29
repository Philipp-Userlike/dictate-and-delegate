'use strict';
// The speech models Dictate & Delegate offers, with prices and editorial notes, bundled with the
// app (assets/models.json, shared with Android): only models whose word hints work
// (Profile.OFFERED). No network call.

const { tr } = require('./util');
const assets = require('./assets');
const decimal = require('./decimal');
const { OFFERED } = require('./profile');

const str = (v) => (v === undefined || v === null ? '' : String(v));

class ModelCatalog {
  /** read: reads an asset by name (tests can pass their own). */
  constructor({ read = assets.read } = {}) {
    const root = JSON.parse(read('models.json'));
    if (typeof root.verified !== 'string' || !Array.isArray(root.models)) throw new Error('models.json is damaged');
    this.priceDate = root.verified;
    this.models = root.models.filter((row) => OFFERED.has(str(row.id)));
    this.models.sort((a, b) => (str(a.name) < str(b.name) ? -1 : str(a.name) > str(b.name) ? 1 : 0));
  }

  find(id) {
    return this.models.find((m) => str(m.id) === id) || null;
  }

  name(id) {
    const m = this.find(id);
    return m === null ? id : str(m.name);
  }
}

/** Six decimals at most, no trailing zeros. value: a decimal (decimal.js), string or number. */
function number(value) {
  const d = typeof value === 'object' ? value : decimal.of(value);
  return decimal.plain(decimal.strip(decimal.setScale(d, 6)));
}

function price(model) {
  try {
    const p = model.pricing;
    if (p === null || typeof p !== 'object') throw new Error('no pricing');
    const input = decimal.of(p.prompt);
    switch (str(model.unit)) {
      case 'second':
        return tr('From $%1$s / min', number(decimal.multiply(input, decimal.of(60))));
      case 'hour':
        return tr('From $%1$s / min', number(decimal.divide(input, 60, 9)));
      case 'token':
        return tr(
          '$%1$s in / $%2$s out per 1M tokens',
          number(decimal.shift(input, 6)),
          number(decimal.shift(decimal.of(p.completion), 6)),
        );
      default:
        return tr('Price unit unverified · see source');
    }
  } catch {
    return tr('Price unavailable · see source');
  }
}

module.exports = { ModelCatalog, number, price };
