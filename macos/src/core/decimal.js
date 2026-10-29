'use strict';
// Exact decimals for the few places the Android app uses BigDecimal on stored strings: a note's
// costs ("knownCost": "0.00060") and catalog prices. Numbers would write 0.30000000000000004 into
// a note; these keep the strings exactly as the Android app writes them. Values are
// { u: BigInt unscaled, s: scale }, so 0.00060 is { u: 60n, s: 5 }.

const DECIMAL = /^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/;

/** A string or number as a decimal; null when it isn't one (BigDecimal's NumberFormatException). */
function parse(value) {
  if (typeof value === 'bigint') return { u: value, s: 0 };
  if (typeof value === 'number' && !Number.isFinite(value)) return null;
  if (typeof value !== 'number' && typeof value !== 'string') return null;
  const m = DECIMAL.exec(String(value).trim());
  if (!m || (!m[2] && !m[3])) return null;
  const frac = m[3] || '';
  let u = BigInt((m[2] || '') + frac || '0');
  let s = frac.length - Number(m[4] || 0);
  if (m[1] === '-') u = -u;
  if (s < 0) {
    u *= 10n ** BigInt(-s);
    s = 0;
  }
  return { u, s };
}

/** parse(), throwing for a value that isn't a decimal. */
function of(value) {
  const d = parse(value);
  if (!d) throw new Error('not a decimal: ' + value);
  return d;
}

function rescale(d, s) {
  return s === d.s ? d : { u: d.u * 10n ** BigInt(s - d.s), s };
}

function add(a, b) {
  const s = Math.max(a.s, b.s);
  return { u: rescale(a, s).u + rescale(b, s).u, s };
}

function multiply(a, b) {
  return { u: a.u * b.u, s: a.s + b.s };
}

/** a × 10^n (n may be negative), exact. */
function shift(d, n) {
  return n >= 0 ? (d.s >= n ? { u: d.u, s: d.s - n } : { u: d.u * 10n ** BigInt(n - d.s), s: 0 }) : { u: d.u, s: d.s - n };
}

function signum(d) {
  return d.u > 0n ? 1 : d.u < 0n ? -1 : 0;
}

/** Rounded half up to s digits after the point (setScale(s, HALF_UP)). */
function setScale(d, s) {
  if (s >= d.s) return rescale(d, s);
  const f = 10n ** BigInt(d.s - s);
  const neg = d.u < 0n;
  const abs = neg ? -d.u : d.u;
  let q = abs / f;
  if ((abs % f) * 2n >= f) q += 1n;
  return { u: neg ? -q : q, s };
}

/** d ÷ an integer, rounded half up to s digits (divide(n, s, HALF_UP)). */
function divide(d, n, s) {
  // d.u / 10^d.s / n at scale s: (d.u × 10^s) / (n × 10^d.s), rounded half up.
  const num = d.u * 10n ** BigInt(s);
  const den = BigInt(n) * 10n ** BigInt(d.s);
  const neg = (num < 0n) !== (den < 0n);
  const a = num < 0n ? -num : num;
  const b = den < 0n ? -den : den;
  let q = a / b;
  if ((a % b) * 2n >= b) q += 1n;
  return { u: neg ? -q : q, s };
}

/** Without trailing zeros (stripTrailingZeros); zero is 0. */
function strip(d) {
  if (d.u === 0n) return { u: 0n, s: 0 };
  let { u, s } = d;
  while (s > 0 && u % 10n === 0n) {
    u /= 10n;
    s--;
  }
  return { u, s };
}

/** BigDecimal.toPlainString(): no exponent, the scale's digits kept. */
function plain(d) {
  const neg = d.u < 0n;
  let digits = (neg ? -d.u : d.u).toString();
  if (d.s > 0) {
    digits = digits.padStart(d.s + 1, '0');
    digits = digits.slice(0, digits.length - d.s) + '.' + digits.slice(digits.length - d.s);
  }
  return (neg ? '-' : '') + digits;
}

function toNumber(d) {
  return Number(plain(d));
}

/** The sum of decimal strings or numbers as a plain string (nulls skipped). */
function sum(...values) {
  let total = { u: 0n, s: 0 };
  for (const v of values) if (v !== null && v !== undefined) total = add(total, of(v));
  return plain(total);
}

module.exports = { parse, of, add, multiply, shift, signum, setScale, divide, strip, plain, toNumber, sum };
