import { createHash, createPublicKey, verify } from 'node:crypto';

export function decodeBase64(value: unknown, maxBytes: number): Buffer {
  if (typeof value !== 'string' || value.length > Math.ceil(maxBytes / 3) * 4 ||
      !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) {
    throw new Error('Invalid base64');
  }
  const bytes = Buffer.from(value, 'base64');
  if (!bytes.length || bytes.length > maxBytes || bytes.toString('base64') !== value) throw new Error('Invalid encoding');
  return bytes;
}

export function publicIdentity(encoded: unknown) {
  const bytes = decodeBase64(encoded, 256);
  const key = createPublicKey({ key: bytes, format: 'der', type: 'spki' });
  if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') {
    throw new Error('Installation key must be EC P-256');
  }
  const canonical = key.export({ type: 'spki', format: 'der' });
  if (!canonical.equals(bytes)) throw new Error('Noncanonical public key');
  return { key, publicKey: bytes.toString('base64'), fingerprint: createHash('sha256').update(bytes).digest('hex') };
}

export type Challenge = { id: string; nonce: string; action: string; userId: string; expiresAt: number };

export function verifyProof(publicKey: string, challenge: Challenge, payload: Buffer, signature: string, now: number): boolean {
  try {
    if (now >= challenge.expiresAt || payload.length > 4096) return false;
    const message = `${challenge.action}\n${challenge.userId}\n${challenge.id}\n${challenge.nonce}\n${createHash('sha256').update(payload).digest('hex')}`;
    return verify('sha256', Buffer.from(message), publicIdentity(publicKey).key, decodeBase64(signature, 80));
  } catch { return false; }
}
