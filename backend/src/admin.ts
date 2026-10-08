import { randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';
import type pg from 'pg';
import { transaction } from './db.ts';
import { hashToken, newToken } from './sessions.ts';
import { defaultAppSettings, validateAppSettings } from './app-settings.ts';

export function passwordHash(password: string): string {
  if (!password || password.length > 512) throw new Error('Invalid admin password');
  const salt = randomBytes(16).toString('hex');
  return `scrypt$${salt}$${scryptSync(password,salt,64).toString('hex')}`;
}
export function verifyPassword(password: unknown, stored: string): boolean {
  if (typeof password !== 'string' || !password || password.length > 512) return false;
  const parts = stored.split('$');
  if (parts.length !== 3 || parts[0] !== 'scrypt' || !/^[a-f0-9]{32}$/.test(parts[1]) || !/^[a-f0-9]{128}$/.test(parts[2])) return false;
  return timingSafeEqual(scryptSync(password,parts[1],64),Buffer.from(parts[2],'hex'));
}
export function sameOrigin(origin: unknown, expected: string): boolean {
  return typeof origin === 'string' && expected.startsWith('https://') && origin === expected;
}
export function validCsrf(value: unknown, storedHash: string): boolean {
  if (typeof value !== 'string' || value.length > 128 || !/^[a-f0-9]{64}$/.test(storedHash)) return false;
  return timingSafeEqual(Buffer.from(hashToken(value),'hex'),Buffer.from(storedHash,'hex'));
}

export class Admin {
  pool: pg.Pool;
  constructor(pool: pg.Pool) { this.pool=pool; }
  async login(password: unknown) {
    return transaction(this.pool,async client=>{
      await client.query('SELECT pg_advisory_xact_lock(713013)');
      const stored=(await client.query("SELECT value FROM settings WHERE key='admin_password_hash'")).rows[0]?.value ?? process.env.ADMIN_PASSWORD_HASH ?? '';
      if (!verifyPassword(password,stored)) throw new Error('Invalid credentials');
      const token=newToken(), csrf=newToken();
      await client.query("INSERT INTO admin_sessions(token_hash,csrf_hash,expires_at) VALUES($1,$2,now()+interval '12 hours')",[hashToken(token),hashToken(csrf)]);
      return {token,csrf};
    });
  }
  async changePassword(token:string,current:unknown,next:unknown) {
    if(typeof next!=='string'||next.length<12||next.length>128||next===current)throw new Error('Use a different password of 12–128 characters');
    return transaction(this.pool,async client=>{
      await client.query('SELECT pg_advisory_xact_lock(713013)');
      const session=(await client.query('SELECT token_hash FROM admin_sessions WHERE token_hash=$1 AND expires_at>now()',[hashToken(token)])).rows[0];
      if(!session)throw new Error('Login required');
      const stored=(await client.query("SELECT value FROM settings WHERE key='admin_password_hash'")).rows[0]?.value ?? process.env.ADMIN_PASSWORD_HASH ?? '';
      if(!verifyPassword(current,stored))throw new Error('Invalid credentials');
      await client.query("INSERT INTO settings(key,value) VALUES('admin_password_hash',$1) ON CONFLICT(key) DO UPDATE SET value=excluded.value",[passwordHash(next)]);
      await client.query('DELETE FROM admin_sessions');
      await client.query("INSERT INTO audit(actor,action) VALUES('owner','password_changed')");
      return {status:'ok'};
    });
  }
  async appSettings() {
    const value=(await this.pool.query("SELECT value FROM settings WHERE key='app_settings'")).rows[0]?.value;
    return value ? validateAppSettings(JSON.parse(value)) : defaultAppSettings();
  }
  async updateAppSettings(value:unknown) {
    const settings=validateAppSettings(value);
    await transaction(this.pool,async client=>{
      await client.query("INSERT INTO settings(key,value) VALUES('app_settings',$1) ON CONFLICT(key) DO UPDATE SET value=excluded.value",[JSON.stringify(settings)]);
      await client.query("INSERT INTO audit(actor,action) VALUES('owner','app_settings_updated')");
    });return {status:'ok'};
  }
  async authenticate(token: string | undefined): Promise<{csrf_hash:string}|null> {
    if (!token || !/^[A-Za-z0-9_-]{43}$/.test(token)) return null;
    return (await this.pool.query('SELECT csrf_hash FROM admin_sessions WHERE token_hash=$1 AND expires_at>now()',[hashToken(token)])).rows[0] ?? null;
  }
  async logout(token: string) { await this.pool.query('DELETE FROM admin_sessions WHERE token_hash=$1',[hashToken(token)]); }
  async mutate(id: string, action: string, expected: {generation:number;fingerprint:string}) {
    if (!/^[a-f0-9-]{36}$/.test(id) || !['approve','block','unblock','reset'].includes(action)) throw new Error('Invalid action');
    return transaction(this.pool,async client=>{
      const row=(await client.query('SELECT * FROM accounts WHERE id=$1 FOR UPDATE',[id])).rows[0];
      if (!row || row.generation !== expected.generation || row.fingerprint !== expected.fingerprint) throw new Error('Account changed; reload before acting');
      if (!row.fingerprint && action !== 'reset') throw new Error('Device request required');
      if (action==='approve' && row.status!=='pending') throw new Error('Pending approval required');
      if (action==='unblock' && row.status!=='blocked') throw new Error('Blocked account required');
      if (action==='reset') await client.query("UPDATE accounts SET status='pending',public_key=NULL,fingerprint=NULL,generation=generation+1,was_playing=false,last_seen=NULL WHERE id=$1",[id]);
      else if (action==='approve') await client.query("UPDATE accounts SET status='approved' WHERE id=$1",[id]);
      else await client.query('UPDATE accounts SET status=$2,generation=generation+1,was_playing=false WHERE id=$1',[id,action==='block'?'blocked':'approved']);
      await client.query('DELETE FROM sessions WHERE account_id=$1',[id]);
      await client.query('INSERT INTO audit(actor,action,user_id) VALUES($1,$2,$3)',['owner',action,row.user_id]);
      return {status:'ok'};
    });
  }
  async setPlan(id:string,plan:unknown,expected:{generation:number;fingerprint:string}) {
    if(!/^[a-f0-9-]{36}$/.test(id)||!['free','premium'].includes(String(plan)))throw new Error('Invalid plan');
    return transaction(this.pool,async client=>{
      const row=(await client.query('SELECT * FROM accounts WHERE id=$1 FOR UPDATE',[id])).rows[0];
      if(!row||row.generation!==expected.generation||row.fingerprint!==expected.fingerprint)throw new Error('Account changed');
      await client.query('INSERT INTO settings(key,value) VALUES($1,$2) ON CONFLICT(key) DO UPDATE SET value=excluded.value',[`account_plan:${id}`,plan]);
      await client.query('INSERT INTO audit(actor,action,user_id) VALUES($1,$2,$3)',['owner',`plan_${plan}`,row.user_id]);
      return {status:'ok'};
    });
  }
  async dashboard() {
    const totals=(await this.pool.query(`SELECT count(*)::int AS total,
      count(*) FILTER(WHERE status='pending')::int AS pending,
      count(*) FILTER(WHERE status='approved')::int AS approved,
      count(*) FILTER(WHERE status='blocked')::int AS blocked,
      count(*) FILTER(WHERE status='approved' AND last_seen>now()-interval '120 seconds')::int AS online FROM accounts`)).rows[0];
    const today=(await this.pool.query("SELECT count(*)::int AS active,coalesce(sum(watch_seconds),0) AS watch_seconds FROM daily_activity WHERE day=(now() AT TIME ZONE 'Asia/Dhaka')::date")).rows[0];
    const versions=(await this.pool.query('SELECT app_version,count(*)::int AS users FROM accounts WHERE app_version IS NOT NULL GROUP BY app_version ORDER BY users DESC')).rows;
    return {...totals,todayActive:today.active,todayWatchSeconds:today.watch_seconds,versions};
  }
  async accounts(search: string, status: string, page: number) {
    const filter=['pending','approved','blocked'].includes(status)?status:'';
    const values=[search.slice(0,32),filter];
    const where="WHERE ($1='' OR position($1 in user_id)>0) AND ($2='' OR status=$2)";
    const rows=(await this.pool.query(`SELECT id,user_id,status,fingerprint,generation,coalesce((SELECT value FROM settings WHERE key='account_plan:'||accounts.id::text),'premium') AS plan,device_type,device_model,app_version,created_at,last_seen,
      (status='approved' AND last_seen>now()-interval '120 seconds') AS online,
      (SELECT coalesce(sum(watch_seconds),0) FROM daily_activity d WHERE d.account_id=accounts.id) AS watch_seconds,
      (SELECT coalesce(sum(watch_seconds),0) FROM daily_activity d WHERE d.account_id=accounts.id AND day=(now() AT TIME ZONE 'Asia/Dhaka')::date) AS today_watch_seconds
      FROM accounts ${where} ORDER BY created_at DESC LIMIT 50 OFFSET $3`,[...values,Math.min(Math.max(page,0),100000)*50])).rows;
    const total=(await this.pool.query(`SELECT count(*)::int AS total FROM accounts ${where}`,values)).rows[0].total;
    return {accounts:rows,total,page};
  }
  async settings() { return {supportNumber:(await this.pool.query("SELECT value FROM settings WHERE key='support_number'")).rows[0]?.value ?? ''}; }
  async updateSettings(value: unknown) {
    if (typeof value!=='string' || !/^[+0-9 ()-]{0,32}$/.test(value)) throw new Error('Invalid support number');
    await transaction(this.pool,async client=>{
      await client.query("INSERT INTO settings(key,value) VALUES('support_number',$1) ON CONFLICT(key) DO UPDATE SET value=excluded.value",[value.trim()]);
      await client.query("INSERT INTO audit(actor,action) VALUES('owner','support_number_updated')");
    });
    return {status:'ok'};
  }
  async audit() { return (await this.pool.query('SELECT actor,action,user_id,created_at FROM audit ORDER BY id DESC LIMIT 100')).rows; }
}
