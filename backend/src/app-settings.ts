import { PNG } from 'pngjs';
import jpeg from 'jpeg-js';
export type PaymentMethod = {number:string;qr:string};
export type AdMobSettings = {enabled:boolean;testMode:boolean;appId:string;bannerUnitId:string};
export type AppSettings = {admob:AdMobSettings;donation:{enabled:boolean;message:string;bkash:PaymentMethod;nagad:PaymentMethod};ads:{enabled:boolean;title:string;message:string;url:string}};
export function defaultAppSettings(): AppSettings { return {admob:{enabled:false,testMode:true,appId:'',bannerUnitId:''},donation:{enabled:false,message:'Support XtremeX TV. Donations are optional.',bkash:{number:'',qr:''},nagad:{number:'',qr:''}},ads:{enabled:false,title:'',message:'',url:''}}; }
function text(value:unknown,max:number):string { if(typeof value!=='string'||value.length>max) throw new Error('Invalid text');return value.trim(); }
function method(value:any):PaymentMethod {
  if(!value||typeof value!=='object')throw new Error('Invalid payment method');
  const number=text(value.number,16),qr=text(value.qr,180000);
  if(number&&!/^(?:\+?88)?01[3-9][0-9]{8}$/.test(number))throw new Error('Invalid mobile payment number');
  if(qr){const m=qr.match(/^data:image\/(png|jpeg);base64,([A-Za-z0-9+/]+={0,2})$/);if(!m)throw new Error('PNG or JPEG QR required');
    const bytes=Buffer.from(m[2],'base64');if(bytes.toString('base64')!==m[2]||bytes.length>130000)throw new Error('QR too large');
    if(m[1]==='png'&&!bytes.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])))throw new Error('Invalid PNG');
    if(m[1]==='jpeg'&&(bytes[0]!==255||bytes[1]!==216||bytes[2]!==255))throw new Error('Invalid JPEG');
    if(m[1]==='png') {
      if(bytes.length<24)throw new Error('Invalid PNG');
      const width=bytes.readUInt32BE(16),height=bytes.readUInt32BE(20);
      if(width<1||height<1||width>2048||height>2048)throw new Error('QR dimensions exceed 2048');
      PNG.sync.read(bytes,{checkCRC:true});
    } else {
      const decoded=jpeg.decode(bytes,{useTArray:true,tolerantDecoding:false,maxResolutionInMP:4.2,maxMemoryUsageInMB:64});
      if(decoded.width<1||decoded.height<1||decoded.width>2048||decoded.height>2048)throw new Error('QR dimensions exceed 2048');
    }

  }return {number,qr};
}
export function validateAppSettings(value:any):AppSettings {
  if(!value?.donation||!value?.ads||typeof value.donation.enabled!=='boolean'||typeof value.ads.enabled!=='boolean')throw new Error('Invalid settings');
  const donation={enabled:value.donation.enabled,message:text(value.donation.message,300),bkash:method(value.donation.bkash),nagad:method(value.donation.nagad)};
  if(donation.enabled&&!donation.bkash.number&&!donation.bkash.qr&&!donation.nagad.number&&!donation.nagad.qr)throw new Error('Add a donation number or QR');
  const ads={enabled:value.ads.enabled,title:text(value.ads.title,80),message:text(value.ads.message,240),url:text(value.ads.url,1000)};
  if(ads.url){const u=new URL(ads.url);if(u.protocol!=='https:'||u.username||u.password)throw new Error('HTTPS URL required');}
  if(ads.enabled&&!ads.title)throw new Error('Sponsor title required');
  const raw=value.admob===undefined?{enabled:false,testMode:true,appId:'',bannerUnitId:''}:value.admob;
  if(!raw||typeof raw.enabled!=='boolean'||typeof raw.testMode!=='boolean')throw new Error('Invalid AdMob settings');
  const admob={enabled:raw.enabled,testMode:raw.testMode,appId:text(raw.appId,80),bannerUnitId:text(raw.bannerUnitId,80)};
  const app=admob.appId.match(/^ca-app-pub-([0-9]{16})~[0-9]{10}$/);
  const banner=admob.bannerUnitId.match(/^ca-app-pub-([0-9]{16})\/[0-9]{10}$/);
  if(admob.appId&&!app||admob.bannerUnitId&&!banner)throw new Error('Invalid AdMob ID');
  if(admob.enabled&&!admob.testMode&&(!app||!banner||app[1]!==banner[1]||app[1]==='3940256099942544'))throw new Error('Live AdMob needs matching real App and Banner IDs');
  return {donation,ads,admob};
}
export function publicAppSettings(value:unknown):AppSettings { return validateAppSettings(value); }
