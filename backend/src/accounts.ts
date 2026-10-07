import { randomUUID } from 'node:crypto';
import type pg from 'pg';
import { transaction } from './db.ts';
import { normalizeId } from './identity.ts';
import { decodeBase64, publicIdentity, verifyProof } from './proof.ts';
import { hashToken, newToken } from './sessions.ts';
import { dhakaDay, watchIntervals } from './monitoring.ts';

export type Envelope = { challengeId: string; payload: string; signature: string };
export type Access = { status: string; token?: string; leaseSeconds?: number; supportNumber?: string };

export class Accounts {
  pool: pg.Pool;
  constructor(pool: pg.Pool) { this.pool = pool; }

  async challenge(action: string, rawId: unknown, encodedKey: unknown) {
    if (!['register', 'session', 'heartbeat'].includes(action)) throw new Error('Invalid action');
    const userId = normalizeId(rawId), identity = publicIdentity(encodedKey);
    // Keep only a short diagnostic grace period; inactive proofs must not grow indefinitely.
    await this.pool.query("DELETE FROM challenges WHERE expires_at < now()-interval '5 minutes'");
    const id = randomUUID(), nonce = newToken();
    const result = await this.pool.query(
      "INSERT INTO challenges(id,nonce,action,user_id,fingerprint,expires_at) VALUES($1,$2,$3,$4,$5,now()+interval '60 seconds') RETURNING expires_at",
      [id, nonce, action, userId, identity.fingerprint]
    );
    return { challengeId: id, nonce, expiresAt: result.rows[0].expires_at.toISOString() };
  }

  async execute(action: string, envelope: Envelope): Promise<Access> {
    const bytes = decodeBase64(envelope.payload, 4096);
    const payload = JSON.parse(bytes.toString('utf8'));
    const userId = normalizeId(payload.userId), identity = publicIdentity(payload.publicKey);
    if (typeof envelope.challengeId !== 'string' || !/^[a-f0-9-]{36}$/.test(envelope.challengeId)) throw new Error('Invalid challenge');
    if (!['register','session','heartbeat'].includes(action)) throw new Error('Invalid action');
    return transaction(this.pool, async client => {
      const q = await client.query('SELECT *,clock_timestamp() AS server_now FROM challenges WHERE id=$1 FOR UPDATE', [envelope.challengeId]);
      const ch = q.rows[0];
      if (!ch || ch.consumed_at || ch.action !== action || ch.user_id !== userId || ch.fingerprint !== identity.fingerprint ||
          !verifyProof(identity.publicKey, { id: ch.id, nonce: ch.nonce, action, userId, expiresAt: ch.expires_at.getTime() }, bytes, envelope.signature, ch.server_now.getTime())) {
        throw new Error('Invalid or expired proof');
      }
      await client.query('UPDATE challenges SET consumed_at=now() WHERE id=$1', [ch.id]);
      // Both locks prevent ID and key races before consulting the current binding.
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))', [`id:${userId}`]);
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))', [`key:${identity.fingerprint}`]);
      let account = (await client.query('SELECT * FROM accounts WHERE user_id=$1 FOR UPDATE', [userId])).rows[0];
      const supportNumber = (await client.query("SELECT value FROM settings WHERE key='support_number'")).rows[0]?.value ?? '';
      if (action === 'register' && (!account || !account.fingerprint)) {
        const keyOwner = await client.query('SELECT id FROM accounts WHERE fingerprint=$1', [identity.fingerprint]);
        if (keyOwner.rowCount) return { status: 'device_registered', supportNumber };
        const deviceType = payload.deviceType;
        const deviceModel = payload.deviceModel;
        if (!['tv','mobile'].includes(deviceType) || typeof deviceModel !== 'string' || deviceModel.length > 128) throw new Error('Invalid device details');
        if (!account) {
          account = (await client.query('INSERT INTO accounts(id,user_id,public_key,fingerprint,device_type,device_model) VALUES($1,$2,$3,$4,$5,$6) RETURNING *',
            [randomUUID(), userId, identity.publicKey, identity.fingerprint, deviceType, deviceModel])).rows[0];
        } else {
          account = (await client.query("UPDATE accounts SET public_key=$2,fingerprint=$3,device_type=$4,device_model=$5,status='pending' WHERE id=$1 RETURNING *",
            [account.id, identity.publicKey, identity.fingerprint, deviceType, deviceModel])).rows[0];
        }
      }
      if (!account) return { status: 'unregistered', supportNumber };
      if (account.fingerprint !== identity.fingerprint) return { status: 'wrong_device', supportNumber };
      if (account.status !== 'approved' || action === 'register') return { status: account.status, supportNumber };
      if (action === 'session') {
        const token = newToken();
        await client.query('DELETE FROM sessions WHERE account_id=$1', [account.id]);
        await client.query("INSERT INTO sessions(token_hash,account_id,generation,expires_at) VALUES($1,$2,$3,now()+interval '30 days')", [hashToken(token), account.id, account.generation]);
        return { status: 'approved', token, leaseSeconds: 300, supportNumber };
      }
      if (typeof payload.token !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(payload.token)) throw new Error('Invalid session');
      const session = await client.query('SELECT 1 FROM sessions WHERE token_hash=$1 AND account_id=$2 AND generation=$3 AND expires_at>now()', [hashToken(payload.token), account.id, account.generation]);
      if (!session.rowCount) return { status: 'session_expired', supportNumber };
      if (typeof payload.playing !== 'boolean' || typeof payload.appVersion !== 'string' || payload.appVersion.length > 40) throw new Error('Invalid heartbeat');
      const now = (await client.query('SELECT clock_timestamp() AS time')).rows[0].time as Date;
      for (const part of watchIntervals(account.last_seen, now, account.was_playing)) {
        await client.query('INSERT INTO daily_activity(account_id,day,watch_seconds) VALUES($1,$2,$3) ON CONFLICT(account_id,day) DO UPDATE SET watch_seconds=daily_activity.watch_seconds+excluded.watch_seconds', [account.id, part.day, part.seconds]);
      }
      await client.query('INSERT INTO daily_activity(account_id,day) VALUES($1,$2) ON CONFLICT DO NOTHING', [account.id, dhakaDay(now)]);
      await client.query('UPDATE accounts SET last_seen=$2,was_playing=$3,app_version=$4 WHERE id=$1', [account.id, now, payload.playing, payload.appVersion]);
      return { status: 'approved', leaseSeconds: 300, supportNumber };
    });
  }
}
