'use strict';
// TextModelTest.java
const test = require('node:test');
const assert = require('node:assert/strict');
const TextModel = require('../src/core/text-model');
const Templates = require('../src/core/templates');

test('refineWorksOutThePurposeFollowsInstructionsAndKeepsTheFacts', () => {
  const p = TextModel.DEFAULT_PROMPT;
  assert.ok(p.includes('Work out what the text is for'));
  assert.ok(p.includes('Follow any instructions in the dictation'));
  assert.ok(p.includes("Keep the user's language"));
  assert.ok(p.includes('Invent nothing'));
  assert.ok(p.includes('Reply with the finished text only'));
});

test('theSystemPromptAddsTheTemplateAttachmentsAndTheUser', () => {
  const template = '\n\n' + Templates.INSTRUCTIONS + '\n\n<template name="Weekly update">\nDone: …\n</template>';
  const s = TextModel.system('PROMPT', template, true, 'Usually dictates: Emails.');
  assert.ok(s.startsWith('PROMPT\n\n' + Templates.INSTRUCTIONS));
  assert.ok(s.indexOf('<template') < s.indexOf(TextModel.ATTACHED.trim()));
  assert.ok(s.endsWith('\n\nAbout the user:\nUsually dictates: Emails.'));
  assert.equal(TextModel.system('PROMPT', '', false, ' '), 'PROMPT');
});

test('thePickerOffersTextModelsTheDefaultFirst', () => {
  const catalog = JSON.parse(
    '{"data": [' +
      '{"id": "z/zeta", "name": "Zeta", "pricing": {"prompt": "0.000001", "completion": "0.000002"},' +
      ' "architecture": {"input_modalities": ["text"], "output_modalities": ["text"]}},' +
      '{"id": "img/maker", "name": "Maker", "pricing": {"prompt": "0", "completion": "0"},' +
      ' "architecture": {"input_modalities": ["text"], "output_modalities": ["image"]}},' +
      '{"id": "a/alpha", "name": "Alpha", "pricing": {"prompt": "0.0000005", "completion": "0.0000015"},' +
      ' "architecture": {"input_modalities": ["text", "image"], "output_modalities": ["text"]}},' +
      '{"id": "anthropic/claude-sonnet-5.5", "name": "Claude Sonnet 5.5", "pricing": {"prompt": "0.000002", "completion": "0.00001"},' +
      ' "architecture": {"input_modalities": ["text"], "output_modalities": ["text"]}},' +
      '{"id": "no/arch", "name": "No arch"}]}',
  );
  const list = TextModel.catalog(catalog);
  assert.equal(list.length, 3);
  assert.equal(list[0].id, TextModel.MODEL);
  assert.equal(list[1].id, 'a/alpha');
  assert.equal(list[2].id, 'z/zeta');
  assert.equal(TextModel.price(list[0]), '$2.00 / $10.00');
  assert.equal(TextModel.price(list[1]), '$0.50 / $1.50');
  assert.equal(TextModel.price({}), '');
});
