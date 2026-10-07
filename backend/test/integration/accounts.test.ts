import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { generateKeyPairSync, createHash, sign, randomUUID } from 'node:crypto';
import pg from 'pg';
import { Accounts } from '../../src/accounts.ts';

const pool = new pg.Pool({ connectionString: process.env.TEST_DATABASE_URL, connectionTimeoutMillis: 10000 });
if (!process.env.TEST_DATABASE_URL) throw new Error('TEST_DATABASE_URL is required: integration tests must use a disposable database');
const service = new Accounts(pool);
const device = () => {
  const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  return { ...keys, encoded: keys.publicKey.export({ format: 'der', type: 'spki' }).toString('base64') };
};
async function request(action: string, userId: string, key: ReturnType<typeof device>, extra = {}) {
  const ch = await service.challenge(action, userId, key.encoded);
  const bytes = Buffer.from(JSON.stringify({ userId, publicKey: key.encoded, ...(action === 'register' ? { deviceType: 'tv', deviceModel: 'test' } : {}), ...extra }));
  const message = `${action}\n${userId}\n${ch.challengeId}\n${ch.nonce}\n${createHash('sha256').update(bytes).digest('hex')}`;
  return { challengeId: ch.challengeId, payload: bytes.toString('base64'), signature: sign('sha256', Buffer.from(message), key.privateKey).toString('base64') };
}

test.before(async () => { await pool.query(await readFile(new URL('../../sql/001_init.sql', import.meta.url), 'utf8')); });
test.after(async () => { await pool.end(); });

test('concurrent registrations of one ID bind exactly one installation', async () => {
  const id = `race-${randomUUID().slice(0, 8)}`;
  const a = device(), b = device();
  const [ra, rb] = await Promise.all([request('register', id, a), request('register', id, b)]);
  const results = await Promise.all([service.execute('register', ra), service.execute('register', rb)]);
  assert.deepEqual(results.map(r => r.status).sort(), ['pending', 'wrong_device']);
  assert.equal((await pool.query('SELECT count(*) FROM accounts WHERE user_id=$1', [id])).rows[0].count, '1');
});
test('pending denies access; approved session works; blocking revokes it', async () => {
  const id = `flow-${randomUUID().slice(0, 8)}`, key = device();
  await service.execute('register', await request('register', id, key, { deviceType: 'tv', deviceModel: 'test' }));
  const pending = await service.execute('session', await request('session', id, key));
  assert.equal(pending.status, 'pending'); assert.equal(pending.token, undefined);
  await pool.query("UPDATE accounts SET status='approved' WHERE user_id=$1", [id]);
  const approved = await service.execute('session', await request('session', id, key));
  assert.equal(approved.status, 'approved'); assert.equal(approved.token?.length, 43);
  const beat = await request('heartbeat', id, key, { token: approved.token, playing: true, appVersion: '1.1.0' });
  assert.equal((await service.execute('heartbeat', beat)).status, 'approved');
  await assert.rejects(service.execute('heartbeat', beat));
  await pool.query("UPDATE accounts SET status='blocked',generation=generation+1 WHERE user_id=$1", [id]);
  assert.equal((await service.execute('heartbeat', await request('heartbeat', id, key, { token: approved.token, playing: true, appVersion: '1.1.0' }))).status, 'blocked');
});
test('expired or altered proof never creates an account', async () => {
  const id = `proof-${randomUUID().slice(0, 8)}`, key = device();
  const req = await request('register', id, key);
  await pool.query("UPDATE challenges SET expires_at=now()-interval '1 second' WHERE id=$1", [req.challengeId]);
  await assert.rejects(service.execute('register', req));
  const changed = await request('register', id, key);
  changed.payload = Buffer.from(JSON.stringify({ userId: id, publicKey: key.encoded, deviceModel: 'altered' })).toString('base64');
  await assert.rejects(service.execute('register', changed));
  assert.equal((await pool.query('SELECT count(*) FROM accounts WHERE user_id=$1', [id])).rows[0].count, '0');
});

test('challenge requests remove long-expired ephemeral proofs while preserving live proofs', async () => {
  const id = `cleanup-${randomUUID().slice(0, 8)}`, key = device();
  const expired = await service.challenge('session', id, key.encoded);
  const live = await service.challenge('session', id, key.encoded);
  await pool.query("UPDATE challenges SET expires_at=now()-interval '10 minutes' WHERE id=$1", [expired.challengeId]);
  await service.challenge('session', id, key.encoded);
  assert.equal((await pool.query('SELECT count(*) FROM challenges WHERE id=$1', [expired.challengeId])).rows[0].count, '0');
  assert.equal((await pool.query('SELECT count(*) FROM challenges WHERE id=$1', [live.challengeId])).rows[0].count, '1');
});
