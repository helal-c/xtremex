import type { IncomingMessage, ServerResponse } from 'node:http';
import { createHmac } from 'node:crypto';
import { database } from './db.ts';
import { Accounts } from './accounts.ts';
import { Admin, sameOrigin, validCsrf } from './admin.ts';

export function send(res: ServerResponse, status: number, body: unknown) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.end(JSON.stringify(body));
}

export async function readJson(req: IncomingMessage, maxBytes = 16384): Promise<any> {
  if (!req.headers['content-type']?.startsWith('application/json')) throw new Error('JSON content type required');
  const parsed = (req as IncomingMessage & { body?: unknown }).body;
  if (parsed !== undefined) {
    const encoded = typeof parsed === 'string' ? parsed : JSON.stringify(parsed);
    if (Buffer.byteLength(encoded) > maxBytes) throw new Error('Request too large');
    return JSON.parse(encoded);
  }
  const chunks: Buffer[] = []; let length = 0;
  for await (const chunk of req) {
    const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    length += bytes.length;
    if (length > maxBytes) throw new Error('Request too large');
    chunks.push(bytes);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

export async function throttle(req: IncomingMessage, category: string, limit: number): Promise<boolean> {
  const ip = String(req.headers['x-vercel-forwarded-for'] ?? req.socket.remoteAddress ?? 'unknown');
  const key = createHmac('sha256', process.env.SESSION_SECRET!).update(`${category}:${ip}`).digest('hex');
  const result = await database().query(`INSERT INTO rate_limits(bucket,window_start,count) VALUES($1,now(),1)
    ON CONFLICT(bucket) DO UPDATE SET count=CASE WHEN rate_limits.window_start < now()-interval '1 minute' THEN 1 ELSE rate_limits.count+1 END,
    window_start=CASE WHEN rate_limits.window_start < now()-interval '1 minute' THEN now() ELSE rate_limits.window_start END RETURNING count`, [key]);
  return result.rows[0].count <= limit;
}

export async function handler(req: IncomingMessage, res: ServerResponse) {
  res.setHeader('Cache-Control', 'no-store');
  if (!process.env.DATABASE_URL || !process.env.SESSION_SECRET || process.env.SESSION_SECRET.length < 32) {
    return send(res, 503, { error: 'Service is not configured' });
  }
  const path = new URL(req.url ?? '/', 'https://localhost').pathname;
  try {
    if (path.startsWith('/api/admin/')) {
      const admin = new Admin(database());
      const origin = process.env.ADMIN_ORIGIN ?? (process.env.VERCEL_URL ? `https://${process.env.VERCEL_URL}` : '');
      const mutation = req.method !== 'GET';
      if (mutation && !sameOrigin(req.headers.origin, origin)) return send(res,403,{error:'Origin denied'});
      if (path === '/api/admin/login' && req.method === 'POST') {
        if (!process.env.ADMIN_PASSWORD_HASH) return send(res,503,{error:'Admin login is not configured'});
        if (!await throttle(req,'admin-login',5)) return send(res,429,{error:'Too many login attempts'});
        const body=await readJson(req);
        let login;
        try { login=await admin.login(body.password); } catch { return send(res,401,{error:'Invalid credentials'}); }
        res.setHeader('Set-Cookie',[
          `__Host-xtremex_admin=${login.token}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=43200`,
          `__Host-xtremex_csrf=${login.csrf}; Path=/; Secure; SameSite=Strict; Max-Age=43200`
        ]);
        return send(res,200,{status:'ok'});
      }
      const token = req.headers.cookie?.split(';').map(x=>x.trim()).find(x=>x.startsWith('__Host-xtremex_admin='))?.slice('__Host-xtremex_admin='.length);
      const session=await admin.authenticate(token);
      if (!session) return send(res,401,{error:'Login required'});
      if (mutation && !validCsrf(req.headers['x-csrf-token'],session.csrf_hash)) return send(res,403,{error:'CSRF denied'});
      if (path==='/api/admin/logout' && req.method==='POST') {
        await admin.logout(token!);
        res.setHeader('Set-Cookie',['__Host-xtremex_admin=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0','__Host-xtremex_csrf=; Path=/; Secure; SameSite=Strict; Max-Age=0']);
        return send(res,200,{status:'ok'});
      }
      if(path==='/api/admin/password' && req.method==='POST') {
        if(!await throttle(req,'admin-password',5))return send(res,429,{error:'Too many attempts'});
        const body=await readJson(req);
        try { await admin.changePassword(token!,body.currentPassword,body.newPassword); }
        catch { return send(res,400,{error:'Check your current password and use a different new password of 12–128 characters'}); }
        res.setHeader('Set-Cookie',['__Host-xtremex_admin=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0','__Host-xtremex_csrf=; Path=/; Secure; SameSite=Strict; Max-Age=0']);
        return send(res,200,{status:'ok'});
      }
      if(path==='/api/admin/app-settings' && req.method==='GET')return send(res,200,await admin.appSettings());
      if(path==='/api/admin/app-settings' && req.method==='PATCH')return send(res,200,await admin.updateAppSettings(await readJson(req,400000)));
      if (path==='/api/admin/dashboard' && req.method==='GET') return send(res,200,await admin.dashboard());
      if (path==='/api/admin/accounts' && req.method==='GET') {
        const params=new URL(req.url!,'https://localhost').searchParams;
        const page=Number(params.get('page')??'0');
        if (!Number.isSafeInteger(page)||page<0) return send(res,400,{error:'Invalid page'});
        return send(res,200,await admin.accounts(params.get('search')?.toLowerCase()??'',params.get('status')??'',page));
      }
      if (path==='/api/admin/audit' && req.method==='GET') return send(res,200,await admin.audit());
      if (path==='/api/admin/settings' && req.method==='GET') return send(res,200,await admin.settings());
      if (path==='/api/admin/settings' && req.method==='PATCH') return send(res,200,await admin.updateSettings((await readJson(req)).supportNumber));
      const planAction=path.match(/^\/api\/admin\/accounts\/([a-f0-9-]{36})\/plan$/);
      if(planAction&&req.method==='POST'){const body=await readJson(req);return send(res,200,await admin.setPlan(planAction[1],body.plan,body));}
      const action=path.match(/^\/api\/admin\/accounts\/([a-f0-9-]{36})\/(approve|block|unblock|reset)$/);
      if (action && req.method==='POST') return send(res,200,await admin.mutate(action[1],action[2],await readJson(req)));
      return send(res,404,{error:'Not found'});
    }
    if(path==='/api/app-config' && req.method==='GET') return send(res,200,await new Admin(database()).appSettings());
    if (path === '/api/status' && req.method === 'GET') {
      await database().query('SELECT 1 FROM accounts LIMIT 1');
      return send(res, 200, { status: 'ok', version: '1.1.4' });
    }
    if (!['/api/challenge','/api/register','/api/session','/api/heartbeat'].includes(path)) return send(res,404,{error:'Not found'});
    if (req.method !== 'POST') return send(res,405,{error:'POST required'});
    if (!await throttle(req, 'app', 2400)) return send(res,429,{error:'Too many requests'});
    const body = await readJson(req);
    const accounts = new Accounts(database());
    if (path === '/api/challenge') {
      if (body.action === 'register' && !await throttle(req,'signup',10)) return send(res,429,{error:'Too many signup attempts'});
      return send(res,200,await accounts.challenge(body.action,body.userId,body.publicKey));
    }
    return send(res,200,await accounts.execute(path.slice(5),body));
  } catch (error) {
    // Never echo DB errors, credentials, raw payloads or session tokens.
    const code = (error as {code?: string}).code;
    return send(res, code ? 503 : 400, { error: code ? 'Service temporarily unavailable' : 'Invalid request or proof' });
  }
}
