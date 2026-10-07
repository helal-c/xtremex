import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, createHash, sign } from 'node:crypto';
import { publicIdentity, verifyProof } from '../src/proof.ts';

const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const publicKey = keys.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
const payload = Buffer.from('{"userId":"rony","playing":true}');
const challenge = { id: '123', nonce: 'nonce', action: 'heartbeat', userId: 'rony', expiresAt: 61000 };
const canonical = `heartbeat\nrony\n123\nnonce\n${createHash('sha256').update(payload).digest('hex')}`;
const signature = sign('sha256', Buffer.from(canonical), keys.privateKey).toString('base64');

test('accepts Android-compatible P256 SPKI and DER signature', () => {
  assert.equal(publicIdentity(publicKey).fingerprint.length, 64);
  assert.equal(verifyProof(publicKey, challenge, payload, signature, 1000), true);
});
test('rejects altered payload, wrong installation, and expired challenge', () => {
  const other = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const otherKey = other.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  assert.equal(verifyProof(publicKey, challenge, Buffer.from('{}'), signature, 1000), false);
  assert.equal(verifyProof(otherKey, challenge, payload, signature, 1000), false);
  assert.equal(verifyProof(publicKey, challenge, payload, signature, 61000), false);
});
test('rejects unsupported public-key algorithms and malformed input', () => {
  const rsa = generateKeyPairSync('rsa', { modulusLength: 2048 });
  assert.throws(() => publicIdentity(rsa.publicKey.export({ type: 'spki', format: 'der' }).toString('base64')));
  assert.throws(() => publicIdentity('!not-base64'));
});
