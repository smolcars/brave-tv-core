import assert from 'node:assert/strict';
import { test } from 'node:test';
import { preprocess } from './filter-preprocessor.mjs';

test('uses Chromium filters and retains ordinary rules', () => {
  const data = 'ordinary\n!#if env_chromium\nchromium\n!#else\nother\n!#endif';
  assert.equal(preprocess({ title: 'test', data }).data, 'ordinary\nchromium');
});

test('rejects a truncated conditional list', () => {
  assert.throws(() => preprocess({ title: 'test', data: '!#if env_chromium\nrule' }));
  assert.throws(() => preprocess({ title: 'test', data: '!#endif' }));
});

test('matches upstream handling of unknown capabilities and negation', () => {
  const data = '!#if !env_firefox\nkeep\n!#endif\n!#if unknown\na\n!#else\nb\n!#endif';
  assert.equal(preprocess({ title: 'test', data }).data, 'keep\na\nb');
});
