'use strict';
// Refine: one call to the chosen text model, through OpenRouter or, with an Anthropic key, Anthropic.

const decimal = require('./decimal');
const openrouter = require('./openrouter');
const anthropic = require('./anthropic');

/** The default, Claude Sonnet 5.5: careful with the user's words and quick enough to wait for. */
const MODEL = 'anthropic/claude-sonnet-5.5';
const NAME = 'Claude Sonnet 5.5';

/** Settings: the OpenRouter model and its name, and the Claude model on the user's Anthropic key. */
const PREF_MODEL = 'refineModel';
const PREF_NAME = 'refineModelName';
const PREF_CLAUDE = 'refineClaude';

/** The OpenRouter model Refine uses without an Anthropic key, and when Anthropic fails. */
function model(prefs) {
  return prefs.get(PREF_MODEL, MODEL);
}

function modelName(prefs) {
  return prefs.get(PREF_NAME, NAME);
}

/** The Claude model Refine uses with an Anthropic key; Sonnet 5.5 unless another was picked. */
function claude(prefs) {
  return anthropic.claude(prefs.get(PREF_CLAUDE, '')) || anthropic.MODELS[0];
}

/** The name of the model Refine calls first. */
function name(prefs, vault) {
  return anthropic.hasKey(vault) ? claude(prefs).name : modelName(prefs);
}

const obj = (v) => (v !== null && typeof v === 'object' && !Array.isArray(v) ? v : null);
const str = (v) => (v === undefined || v === null ? '' : String(v));

/**
 * The text models in OpenRouter's catalog (GET /models): text in, text only out, with a price.
 * The default first, then by name.
 */
function catalog(response) {
  const out = [];
  const data = Array.isArray(response && response.data) ? response.data : [];
  for (const m of data) {
    if (!obj(m) || str(m.id) === '') continue;
    const arch = obj(m.architecture);
    const inputs = arch && Array.isArray(arch.input_modalities) ? arch.input_modalities : null;
    const outputs = arch && Array.isArray(arch.output_modalities) ? arch.output_modalities : null;
    if (!inputs || !outputs || outputs.length !== 1) continue;
    if (outputs[0] !== 'text' || !inputs.includes('text')) continue;
    if (!obj(m.pricing)) continue;
    out.push(m);
  }
  const label = (m) => (m.name === undefined || m.name === null ? str(m.id) : str(m.name));
  out.sort((a, b) => {
    const da = MODEL === a.id;
    const db = MODEL === b.id;
    if (da !== db) return da ? -1 : 1;
    const x = label(a).toLowerCase();
    const y = label(b).toLowerCase();
    return x < y ? -1 : x > y ? 1 : 0;
  });
  return out;
}

/** "$2.00 / $10.00" per million tokens in and out, from the catalog's per-token prices; "" if unknown. */
function price(m) {
  const p = obj(m && m.pricing);
  if (!p) return '';
  const tokensIn = decimal.parse(p.prompt);
  const tokensOut = decimal.parse(p.completion);
  if (!tokensIn || !tokensOut) return '';
  const perIn = decimal.shift(tokensIn, 6);
  const perOut = decimal.shift(tokensOut, 6);
  if (decimal.signum(perIn) < 0 || decimal.signum(perOut) < 0) return '';
  return dollars(perIn) + ' / ' + dollars(perOut);
}

/** "$2.00" from a per-million price (a decimal, string or number). */
function dollars(perMillion) {
  const d = typeof perMillion === 'object' ? perMillion : decimal.of(perMillion);
  return '$' + decimal.plain(decimal.setScale(d, 2));
}

const MAX_TOKENS = 4096;

/**
 * Refine's instructions: work out what the text is for and make it that, follow what the
 * dictation asks, keep the language and the facts. The named template comes after (Templates).
 */
const DEFAULT_PROMPT =
  'You refine dictations inside a dictation app. The user spoke; a speech model wrote it down' +
  ' (expect the odd misheard word). Turn it into the text the user needs.\n\n' +
  '1. Work out what the text is for: a chat message, an email, a reply, a note, a list, a' +
  ' post, a prompt for an AI, a document… Then make it a good text of that kind: remove' +
  ' filler words, false starts and repetitions, apply the user\'s own corrections ("no,' +
  ' I mean Thursday"), and fix grammar, punctuation and structure, in the tone that fits.\n' +
  '2. Follow any instructions in the dictation about the text ("make it formal", "turn' +
  ' it into bullet points", "translate to English", "reply to this offer", "write' +
  ' a draft that…"), and leave those instructions out of the result.\n' +
  "3. Keep the user's language unless asked otherwise, and keep their voice: a casual" +
  ' message stays casual.\n' +
  '4. Keep every fact (names, numbers, dates, places) as said. Invent nothing; where a' +
  " draft needs details you don't have, keep it general instead of adding placeholders.\n\n" +
  'Reply with the finished text only, ready to paste: no preface, no quotes, no comments.';

const ATTACHED =
  '\n\nThe user attached material to this dictation (pasted text, images or files). Use it' +
  ' where the dictation refers to it, for example the offer to reply to.';

/** The system prompt: instructions, the template asked for, attachments, about the user. */
function system(prompt, template, attached, context) {
  return prompt + template + (attached ? ATTACHED : '') + (context.trim() === '' ? '' : '\n\nAbout the user:\n' + context);
}

/**
 * Refine. deps: { prefs, vault, openRouter?, anthropic?, fetch? } (clients are made from vault and
 * fetch when not given). template: Templates.prompt(), "" when none was named. attachments:
 * message parts (pasted text, images, PDFs) from Attachments.parts(), sent along with the
 * dictation. With an Anthropic key, Anthropic answers; if that fails, the same request goes
 * through OpenRouter.
 *
 * Returns { text, cost, anthropic, fallback, keyProblem, model }: anthropic: Anthropic answered
 * directly, with the user's Anthropic key (its cost computed at list price, paid from Anthropic
 * credits); fallback: why the Anthropic call failed when OpenRouter answered instead (else null);
 * keyProblem: that was the key or the credits, which the note warns about; model: the ID that
 * answered.
 */
async function refine(deps, text, attachments, prompt, template, context) {
  const { prefs, vault } = deps;
  let user = text;
  if (attachments.length > 0) user = [{ type: 'text', text }, ...attachments];
  const sys = system(prompt, template, attachments.length > 0, context);
  let fallback = null;
  let keyProblem = false;
  if (anthropic.hasKey(vault)) {
    try {
      const id = claude(prefs).id;
      const client = deps.anthropic || new anthropic.Anthropic({ vault, fetch: deps.fetch });
      const r = await client.messages(anthropic.body(id, sys, user, MAX_TOKENS));
      return { text: r.text, cost: r.cost, anthropic: true, fallback: null, keyProblem: false, model: id };
    } catch (ex) {
      if (ex instanceof anthropic.Failure) {
        fallback = ex.message;
        keyProblem = ex.problem === anthropic.Problem.KEY || ex.problem === anthropic.Problem.CREDITS;
      } else fallback = ex && ex.message ? ex.message : String((ex && ex.name) || ex);
    }
  }
  const messages = [
    { role: 'system', content: sys },
    { role: 'user', content: user },
  ];
  const m = model(prefs);
  const client = deps.openRouter || new openrouter.OpenRouter({ vault, fetch: deps.fetch });
  const r = await client.chat(openrouter.chatBody(m, messages, MAX_TOKENS, false));
  return { text: r.text, cost: r.cost, anthropic: false, fallback, keyProblem, model: m };
}

module.exports = {
  MODEL,
  NAME,
  PREF_MODEL,
  PREF_NAME,
  PREF_CLAUDE,
  MAX_TOKENS,
  DEFAULT_PROMPT,
  ATTACHED,
  model,
  modelName,
  claude,
  name,
  catalog,
  price,
  dollars,
  system,
  process: refine, // TextModel.process; "process" would shadow Node's global here
  refine,
};
