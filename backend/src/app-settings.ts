export type PaymentMethod = {number:string;qr:string};
export type AppSettings = {donation:{enabled:boolean;message:string;bkash:PaymentMethod;nagad:PaymentMethod};ads:{enabled:boolean;title:string;message:string;url:string}};
export function defaultAppSettings(): AppSettings { return {donation:{enabled:false,message:'Support XtremeX TV. Donations are optional.',bkash:{number:'',qr:''},nagad:{number:'',qr:''}},ads:{enabled:false,title:'',message:'',url:''}}; }
function text(value:unknown,max:number):string { if(typeof value!=='string'||value.length>max) throw new Error('Invalid text');return value.trim(); }
function method(value:any):PaymentMethod {
  if(!value||typeof value!=='object')throw new Error('Invalid payment method');
  const number=text(value.number,16),qr=text(value.qr,180000);
  if(number&&!/^(?:\+?88)?01[3-9][0-9]{8}$/.test(number))throw new Error('Invalid mobile payment number');
  if(qr){const m=qr.match(/^data:image\/(png|jpeg);base64,([A-Za-z0-9+/]+={0,2})$/);if(!m)throw new Error('PNG or JPEG QR required');
    const bytes=Buffer.from(m[2],'base64');if(bytes.toString('base64')!==m[2]||bytes.length>130000)throw new Error('QR too large');
    if(m[1]==='png'&&!bytes.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])))throw new Error('Invalid PNG');
    if(m[1]==='jpeg'&&(bytes[0]!==255||bytes[1]!==216||bytes[2]!==255))throw new Error('Invalid JPEG');
  }return {number,qr};
}
export function validateAppSettings(value:any):AppSettings {
  if(!value?.donation||!value?.ads||typeof value.donation.enabled!=='boolean'||typeof value.ads.enabled!=='boolean')throw new Error('Invalid settings');
  const donation={enabled:value.donation.enabled,message:text(value.donation.message,300),bkash:method(value.donation.bkash),nagad:method(value.donation.nagad)};
  if(donation.enabled&&!donation.bkash.number&&!donation.bkash.qr&&!donation.nagad.number&&!donation.nagad.qr)throw new Error('Add a donation number or QR');
  const ads={enabled:value.ads.enabled,title:text(value.ads.title,80),message:text(value.ads.message,240),url:text(value.ads.url,1000)};
  if(ads.url){const u=new URL(ads.url);if(u.protocol!=='https:'||u.username||u.password)throw new Error('HTTPS URL required');}
  if(ads.enabled&&!ads.title)throw new Error('Sponsor title required');return {donation,ads};
}
export function publicAppSettings(value:unknown):AppSettings { return validateAppSettings(value); }
