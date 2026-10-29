'use strict';
// AnthropicTest.java
const test = require('node:test');
const assert = require('node:assert/strict');
const Anthropic = require('../src/core/anthropic');
const TextModel = require('../src/core/text-model');

test('theModelIdComesFromTheOpenRouterOne', () => {
  assert.equal(Anthropic.model(TextModel.MODEL), 'claude-sonnet-5-5');
});

test('theRequestHasThinkingOffAtLowEffort', () => {
  const b = Anthropic.body('claude-sonnet-5-5', 'Refine this.', 'hello', 4096);
  assert.equal(b.model, 'claude-sonnet-5-5');
  assert.equal(b.max_tokens, 4096);
  assert.equal(b.system, 'Refine this.');
  // between_tools takes no other field.
  assert.deepEqual(b.thinking, { type: 'between_tools' });
  assert.equal(b.output_config.effort, 'low');
  const m = b.messages[0];
  assert.equal(m.role, 'user');
  assert.equal(m.content, 'hello');
  // Only Sonnet 5.5 takes between_tools; the others think adaptively, still at low effort.
  const opus = Anthropic.body('claude-opus-5-5', 's', 'hi', 10);
  assert.ok(!('thinking' in opus));
  assert.equal(opus.output_config.effort, 'low');
});

test('eachClaudeModelHasItsListPrice', () => {
  assert.equal(Anthropic.MODELS[0].id, 'claude-sonnet-5-5');
  assert.equal(Anthropic.MODELS[0].id, Anthropic.model(TextModel.MODEL));
  const usage = { usage: { input_tokens: 1000, output_tokens: 100 } };
  // Opus 5.5: 1,000 × $4/M + 100 × $20/M = $0.006; Haiku 5.5: $0.0001 + $0.00005.
  assert.equal(Anthropic.cost(usage, 'claude-opus-5-5'), 0.006);
  assert.equal(Anthropic.cost(usage, 'claude-haiku-5-5'), 0.00015);
  assert.equal(Anthropic.cost(usage, 'claude-unknown'), null);
});

test('attachmentsBecomeAnthropicBlocksMediaFirst', () => {
  const openRouter = [
    { type: 'text', text: 'reply to this' },
    { type: 'image_url', image_url: { url: 'data:image/png;base64,iVBOR' } },
    { type: 'file', file: { filename: 'offer.pdf', file_data: 'data:application/pdf;base64,JVBER' } },
    { type: 'text', text: '<pasted>x</pasted>' },
  ];
  const blocks = Anthropic.parts(openRouter);
  assert.equal(blocks.length, 4);
  const image = blocks[0];
  assert.equal(image.type, 'image');
  assert.equal(image.source.type, 'base64');
  assert.equal(image.source.media_type, 'image/png');
  assert.equal(image.source.data, 'iVBOR');
  const pdf = blocks[1];
  assert.equal(pdf.type, 'document');
  assert.equal(pdf.source.media_type, 'application/pdf');
  assert.equal(pdf.source.data, 'JVBER');
  assert.equal(blocks[2].text, 'reply to this');
  assert.equal(blocks[3].text, '<pasted>x</pasted>');
  // Through body(): the parts are the user's content.
  const b = Anthropic.body('m', 's', openRouter, 10);
  assert.equal(b.messages[0].content.length, 4);
  assert.equal(Anthropic.dataUrl('https://example.com/x.png'), null);
});

test('theAnswerIsItsTextBlocksAndCostsListPrice', () => {
  const r = {
    content: [
      { type: 'thinking', thinking: '' },
      { type: 'text', text: 'Hi ' },
      { type: 'text', text: 'there.' },
    ],
    stop_reason: 'end_turn',
    usage: { input_tokens: 1000, output_tokens: 100 },
  };
  const result = Anthropic.parse(r, 'claude-sonnet-5-5');
  assert.equal(result.text, 'Hi there.');
  // 1,000 × $2/M + 100 × $10/M = $0.003
  assert.equal(result.cost, 0.003);
  assert.equal(Anthropic.cost({}, 'claude-sonnet-5-5'), null);
});

test('aRefusalOrNothingFails', () => {
  assert.throws(
    () => Anthropic.parse({ content: [], stop_reason: 'refusal' }, 'claude-sonnet-5-5'),
    (f) => f instanceof Anthropic.Failure && f.problem === Anthropic.Problem.OTHER,
  );
  assert.throws(
    () => Anthropic.parse({ content: [{ type: 'thinking', thinking: '' }] }, 'claude-sonnet-5-5'),
    (f) => f instanceof Anthropic.Failure && f.problem === Anthropic.Problem.OTHER,
  );
});

test('errorsSayWhatWentWrong', () => {
  assert.equal(Anthropic.failure(401, 'invalid x-api-key').problem, Anthropic.Problem.KEY);
  assert.equal(Anthropic.failure(403, '').problem, Anthropic.Problem.KEY);
  assert.equal(
    Anthropic.failure(400, 'Your credit balance is too low to access the Anthropic API.').problem,
    Anthropic.Problem.CREDITS,
  );
  assert.equal(Anthropic.failure(429, '').problem, Anthropic.Problem.BUSY);
  assert.equal(Anthropic.failure(529, 'Overloaded').problem, Anthropic.Problem.BUSY);
  assert.equal(Anthropic.failure(500, '').problem, Anthropic.Problem.OTHER);
  const bad = Anthropic.failure(400, 'thinking: unknown type');
  assert.equal(bad.problem, Anthropic.Problem.OTHER);
  assert.ok(bad.message.includes('thinking: unknown type'), bad.message);
});
