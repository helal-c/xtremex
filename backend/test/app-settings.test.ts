import test from 'node:test';
import assert from 'node:assert/strict';
import { defaultAppSettings, validateAppSettings, publicAppSettings } from '../src/app-settings.ts';
test('optional features default off and public settings are allowlisted',()=>{
  const d=defaultAppSettings();assert.equal(d.donation.enabled,false);assert.equal(d.ads.enabled,false);
  assert.equal('passwordHash' in publicAppSettings({...d,passwordHash:'private'}),false);
});
test('donation requires a configured number or QR and rejects malformed images',()=>{
  const d=defaultAppSettings();d.donation.enabled=true;
  assert.throws(()=>validateAppSettings(d));
  d.donation.bkash.number='01712345678';assert.equal(validateAppSettings(d).donation.bkash.number,'01712345678');
  d.donation.bkash.qr='data:image/svg+xml;base64,AAAA';assert.throws(()=>validateAppSettings(d));
  d.donation.bkash.qr='data:image/png;base64,AAAA';assert.throws(()=>validateAppSettings(d));
});
test('sponsor links require HTTPS and reject credentials and missing content',()=>{
  const d=defaultAppSettings();d.ads.enabled=true;assert.throws(()=>validateAppSettings(d));
  d.ads.title='Our sponsor';d.ads.url='javascript:alert(1)';assert.throws(()=>validateAppSettings(d));
  d.ads.url='https://user:pass@example.com';assert.throws(()=>validateAppSettings(d));
  d.ads.url='https://example.com';assert.equal(validateAppSettings(d).ads.url,'https://example.com');
});

test('QR validation rejects truncated PNG and excessive dimensions',()=>{
 const d=defaultAppSettings();d.donation.enabled=true;d.donation.bkash.qr='data:image/png;base64,iVBORw0KGgo=';assert.throws(()=>validateAppSettings(d));
 const bytes=Buffer.alloc(24);Buffer.from([137,80,78,71,13,10,26,10]).copy(bytes);bytes.writeUInt32BE(100000,16);bytes.writeUInt32BE(100000,20);d.donation.bkash.qr='data:image/png;base64,'+bytes.toString('base64');assert.throws(()=>validateAppSettings(d));
});

test('legacy app config normalizes AdMob to off with test defaults',()=>{
 const d=defaultAppSettings();const {admob,...old}=d;
 assert.deepEqual(validateAppSettings(old).admob,{enabled:false,testMode:true,appId:'',bannerUnitId:''});
});
test('AdMob test mode needs no real IDs and preserves sponsor config',()=>{
 const d=defaultAppSettings();d.admob={enabled:true,testMode:true,appId:'',bannerUnitId:''};
 assert.equal(validateAppSettings(d).admob.enabled,true);assert.equal(validateAppSettings(d).ads.enabled,false);
});
test('live AdMob rejects missing, malformed, sample and mismatched publisher IDs',()=>{
 const d=defaultAppSettings();d.admob={enabled:true,testMode:false,appId:'',bannerUnitId:''};
 assert.throws(()=>validateAppSettings(d));
 d.admob.appId='ca-app-pub-1234567890123456~1234567890';d.admob.bannerUnitId='ca-app-pub-1234567890123456/1234567890';
 assert.equal(validateAppSettings(d).admob.testMode,false);
 d.admob.bannerUnitId='ca-app-pub-9876543210987654/1234567890';assert.throws(()=>validateAppSettings(d));
 d.admob.appId='ca-app-pub-3940256099942544~3347511713';d.admob.bannerUnitId='ca-app-pub-3940256099942544/6300978111';assert.throws(()=>validateAppSettings(d));
 d.admob.appId='x';assert.throws(()=>validateAppSettings(d));
});
