import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID, generateKeyPairSync } from 'node:crypto';
import pg from 'pg';
import { Admin, passwordHash } from '../../src/admin.ts';
import { hashToken } from '../../src/sessions.ts';

if (!process.env.TEST_DATABASE_URL) throw new Error('Disposable TEST_DATABASE_URL required');
const pool = new pg.Pool({ connectionString: process.env.TEST_DATABASE_URL });
const admin = new Admin(pool);
test.before(async () => {
  await pool.query(await readFile(new URL('../../sql/001_init.sql', import.meta.url),'utf8'));
  process.env.ADMIN_PASSWORD_HASH = passwordHash('Owner-password-test');
});
test.after(async () => { await pool.end(); });

test('admin login rejects wrong password and unknown/expired sessions', async () => {
  await assert.rejects(admin.login('wrong'));
  assert.equal(await admin.authenticate('missing'), null);
  const login = await admin.login('Owner-password-test');
  assert.ok(await admin.authenticate(login.token));
  await pool.query("UPDATE admin_sessions SET expires_at=now()-interval '1 second' WHERE token_hash=$1",[hashToken(login.token)]);
  assert.equal(await admin.authenticate(login.token), null);
});

test('approve checks exact binding; block/unblock/reset revoke old sessions and audit actions', async () => {
  const id = randomUUID(), userId = `admin-${id.slice(0,8)}`;
  const publicKey = generateKeyPairSync('ec',{namedCurve:'prime256v1'}).publicKey.export({format:'der',type:'spki'}).toString('base64');
  const fingerprint = id.replaceAll('-','').repeat(2);
  await pool.query('INSERT INTO accounts(id,user_id,public_key,fingerprint) VALUES($1,$2,$3,$4)',[id,userId,publicKey,fingerprint]);
  await assert.rejects(admin.mutate(id,'approve',{ generation:1, fingerprint:'wrong' }));
  await admin.mutate(id,'approve',{generation:1,fingerprint});
  assert.equal((await pool.query('SELECT status FROM accounts WHERE id=$1',[id])).rows[0].status,'approved');
  await pool.query("INSERT INTO sessions(token_hash,account_id,generation,expires_at) VALUES($1,$2,1,now()+interval '1 day')",[hashToken('old'),id]);
  await admin.mutate(id,'block',{generation:1,fingerprint});
  assert.equal((await pool.query('SELECT count(*) FROM sessions WHERE account_id=$1',[id])).rows[0].count,'0');
  await admin.mutate(id,'unblock',{generation:2,fingerprint});
  await admin.mutate(id,'reset',{generation:3,fingerprint});
  const row=(await pool.query('SELECT * FROM accounts WHERE id=$1',[id])).rows[0];
  assert.equal(row.status,'pending'); assert.equal(row.fingerprint,null); assert.equal(row.public_key,null); assert.equal(row.generation,4);
  assert.deepEqual((await pool.query('SELECT action FROM audit WHERE user_id=$1 ORDER BY id',[userId])).rows.map(r=>r.action),['approve','block','unblock','reset']);
});
