import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeId } from '../src/identity.ts';

test('normalizes signup IDs so mixed-case retries cannot reserve a second account', () => {
  assert.equal(normalizeId('  Rony  '), 'rony');
  assert.equal(normalizeId('TV_01.A-B'), 'tv_01.a-b');
});
test('rejects IDs outside the bounded ASCII alphabet', () => {
  for (const id of ['', 'ab', 'a'.repeat(33), 'রনি', 'a b', 'a/b', 'a\nb']) {
    assert.throws(() => normalizeId(id));
  }
});
