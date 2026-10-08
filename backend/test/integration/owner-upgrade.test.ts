import test from 'node:test';
import assert from 'node:assert/strict';
import pg from 'pg';
import { readFile } from 'node:fs/promises';
import { Admin,passwordHash } from '../../src/admin.ts';
import { defaultAppSettings } from '../../src/app-settings.ts';
if(!process.env.TEST_DATABASE_URL)throw new Error('Disposable TEST_DATABASE_URL required');
const pool=new pg.Pool({connectionString:process.env.TEST_DATABASE_URL});const admin=new Admin(pool);
test.before(async()=>{await pool.query(await readFile(new URL('../../sql/001_init.sql',import.meta.url),'utf8'));process.env.ADMIN_PASSWORD_HASH=passwordHash('Original-password-123');});
test.after(async()=>{await pool.end();});
test('rotation verifies current password and revokes every admin session',async()=>{
 const a=await admin.login('Original-password-123'),b=await admin.login('Original-password-123');
 await assert.rejects(admin.changePassword(a.token,'wrong','Replacement-password-456'));
 await assert.rejects(admin.changePassword(a.token,'Original-password-123','short'));
 assert.ok(await admin.authenticate(a.token));
 await admin.changePassword(a.token,'Original-password-123','Replacement-password-456');
 assert.equal(await admin.authenticate(a.token),null);assert.equal(await admin.authenticate(b.token),null);
 await assert.rejects(admin.login('Original-password-123'));
 await assert.rejects(admin.changePassword(a.token,'Replacement-password-456','Another-password-789'));
 assert.ok(await admin.authenticate((await admin.login('Replacement-password-456')).token));
});
test('concurrent old-password login cannot survive rotation',async()=>{
 const owner=await admin.login('Replacement-password-456');
 const results=await Promise.allSettled([admin.login('Replacement-password-456'),admin.changePassword(owner.token,'Replacement-password-456','Final-password-789')]);
 assert.equal(results[1].status,'fulfilled');
 if(results[0].status==='fulfilled')assert.equal(await admin.authenticate(results[0].value.token),null);
 await assert.rejects(admin.login('Replacement-password-456'));
 assert.ok(await admin.authenticate((await admin.login('Final-password-789')).token));
});
test('public config never exposes password hash or unrelated settings',async()=>{
 const c=defaultAppSettings();c.donation.enabled=true;c.donation.bkash.number='01712345678';
 await admin.updateAppSettings(c);assert.deepEqual(await admin.appSettings(),c);
 assert.equal(JSON.stringify(await admin.appSettings()).includes('scrypt'),false);
 await admin.updateSettings('+8801712345678');assert.deepEqual(await admin.settings(),{supportNumber:'+8801712345678'});
});

test('per-user plan defaults Premium and changes only the selected account',async()=>{
 const id='10000000-0000-4000-8000-000000000001',other='10000000-0000-4000-8000-000000000002';
 await pool.query("INSERT INTO accounts(id,user_id) VALUES($1,'plan-one'),($2,'plan-two')",[id,other]);
 assert.equal((await admin.accounts('plan-one','',0)).accounts[0].plan,'premium');
 await assert.rejects(admin.setPlan(id,'free',{generation:99,fingerprint:null as any}));
 await admin.setPlan(id,'free',{generation:1,fingerprint:null as any});
 assert.equal((await admin.accounts('plan-one','',0)).accounts[0].plan,'free');
 assert.equal((await admin.accounts('plan-two','',0)).accounts[0].plan,'premium');
 await admin.setPlan(id,'premium',{generation:1,fingerprint:null as any});
 assert.equal((await admin.accounts('plan-one','',0)).accounts[0].plan,'premium');
 await assert.rejects(admin.setPlan(id,'invalid',{generation:1,fingerprint:null as any}));
});
