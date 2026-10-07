import test from 'node:test';
import assert from 'node:assert/strict';
import { passwordHash, verifyPassword, sameOrigin, validCsrf } from '../src/admin.ts';
import { hashToken } from '../src/sessions.ts';

test('admin password hash verifies only the exact password and handles malformed hashes', () => {
  const hash = passwordHash('Correct-password-123');
  assert.equal(verifyPassword('Correct-password-123', hash), true);
  assert.equal(verifyPassword('wrong', hash), false);
  assert.equal(verifyPassword('', hash), false);
  assert.equal(verifyPassword('a'.repeat(513), hash), false);
  assert.equal(verifyPassword('anything', 'bad-format'), false);
});
test('admin mutations deny missing or different origin and CSRF values', () => {
  assert.equal(sameOrigin('https://admin.example', 'https://admin.example'), true);
  assert.equal(sameOrigin(undefined, 'https://admin.example'), false);
  assert.equal(sameOrigin('https://admin.example.evil.test', 'https://admin.example'), false);
  assert.equal(validCsrf('abc', hashToken('abc')), true);
  assert.equal(validCsrf(undefined, hashToken('abc')), false);
  assert.equal(validCsrf('other', hashToken('abc')), false);
});
