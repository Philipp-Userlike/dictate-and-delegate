'use strict';
// One session is also its durable upload checkpoint. Costs are what OpenRouter reported, in USD,
// kept in the JSON as decimal strings exactly as the Android app writes them ("knownCost":
// "0.00060"); the getters return Numbers.

const decimal = require('./decimal');
const { append } = require('./audio-files');

const str = (v, fallback = '') => (v === undefined || v === null ? fallback : String(v));
const int = (v) => {
  const n = typeof v === 'number' ? v : typeof v === 'string' && v.trim() !== '' ? Number(v) : NaN;
  return Number.isFinite(n) ? Math.trunc(n) : 0;
};
const dec = (v) => decimal.parse(str(v, '0')) || decimal.of(0);

class HistoryEntry {
  /** json: the entry as stored (a plain object, edited in place). */
  constructor(json) {
    this.json = json;
  }

  static create(id, model, seconds, now = Date.now()) {
    return new HistoryEntry({
      id,
      created: now,
      model,
      duration: seconds,
      text: '',
      offset: 0,
      knownCost: '0',
      unpriced: 0,
      unconfirmed: 0,
      requests: 0,
      status: 'pending',
    });
  }

  id() {
    return str(this.json.id);
  }

  recordingId() {
    return str(this.json.recordingId, this.id());
  }

  created() {
    return int(this.json.created);
  }

  text() {
    return str(this.json.text);
  }

  /** What the model wrote, before instructions or your edits. */
  raw() {
    return str(this.json.raw, this.text());
  }

  model() {
    return str(this.json.model);
  }

  offset() {
    return int(this.json.offset);
  }

  duration() {
    const v = this.json.duration;
    const n = typeof v === 'number' ? v : typeof v === 'string' ? Number(v) : NaN;
    return Number.isFinite(n) ? n : 0;
  }

  cost() {
    return decimal.toNumber(dec(this.json.knownCost));
  }

  /** The part of cost() the text model charged; 0 for notes from before it was recorded. */
  textCost() {
    return decimal.toNumber(dec(this.json.textCost));
  }

  /** The part of cost the request check of versions 2.0 and 2.1 charged. */
  routeCost() {
    return decimal.toNumber(dec(this.json.routeCost));
  }

  /** The part of cost the voice charged for reading the chief's replies (hands-free). */
  voiceCost() {
    return decimal.toNumber(dec(this.json.voiceCost));
  }

  /** The part of cost Jev charged. */
  jevCost() {
    return decimal.toNumber(dec(this.json.jevCost));
  }

  /**
   * What Refine through the user's own Anthropic key cost, at list price. Paid from Anthropic
   * credits, so never part of cost().
   */
  anthropicCost() {
    return decimal.toNumber(dec(this.json.anthropicCost));
  }

  /** Adds to a cost kept apart ("textCost", "jevCost") for Usage; null adds nothing. */
  addCost(key, cost) {
    if (cost !== null && cost !== undefined) this.json[key] = decimal.plain(decimal.add(dec(this.json[key]), decimal.of(cost)));
  }

  /**
   * One step of the activity log: "speech", "jev", "refine" or "bb" (older notes: "check",
   * "text", "simple", "smart", agent steps), the model, how long it took and what it cost (null
   * when unknown); detail is an error. The speech chunks of one recording add up to one step.
   * Returns the step (the stored object), e.g. to add Jev's "answer".
   */
  step(kind, model, ms, cost, detail) {
    if (!Array.isArray(this.json.steps)) this.json.steps = [];
    const steps = this.json.steps;
    const last = steps.length === 0 ? null : steps[steps.length - 1];
    const known = cost !== null && cost !== undefined;
    if (
      kind === 'speech' &&
      last &&
      str(last.kind) === 'speech' &&
      str(last.model) === model &&
      !('detail' in last)
    ) {
      last.ms = int(last.ms) + ms;
      if (!known || !('cost' in last)) delete last.cost;
      else last.cost = decimal.plain(decimal.add(decimal.of(last.cost), decimal.of(cost)));
      if (detail !== null && detail !== undefined) last.detail = detail;
      return last;
    }
    const s = { kind, model, ms };
    if (known) s.cost = decimal.plain(decimal.of(cost));
    if (detail !== null && detail !== undefined) s.detail = detail;
    steps.push(s);
    return s;
  }

  /** Some call's cost is unknown ("estimated": charged at an estimate by the former server). */
  incompleteCost() {
    return int(this.json.unpriced) > 0 || int(this.json.estimated) > 0 || int(this.json.unconfirmed) > 0 || int(this.json.requests) === 0;
  }

  beginRequest() {
    this.json.unconfirmed = int(this.json.unconfirmed) + 1;
  }

  /** result: what OpenRouter.transcribe returned, { text, cost }. */
  accept(result, offset) {
    this.json.text = append(this.text(), result.text);
    this.json.offset = offset;
    this.json.requests = int(this.json.requests) + 1;
    this.json.unconfirmed = Math.max(0, int(this.json.unconfirmed) - 1);
    this.charge(result.cost);
  }

  /** Adds what OpenRouter reported for a call on this note; null: it reported nothing. */
  charge(cost) {
    if (cost === null || cost === undefined) this.json.unpriced = int(this.json.unpriced) + 1;
    else this.json.knownCost = decimal.plain(decimal.add(dec(this.json.knownCost), decimal.of(cost)));
  }

  costLabel() {
    return '$' + decimal.plain(decimal.strip(dec(this.json.knownCost))) + (this.incompleteCost() ? ' reported · incomplete' : ' actual');
  }
}

module.exports = { HistoryEntry };
