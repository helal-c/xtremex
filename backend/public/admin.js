const $=id=>document.getElementById(id);
let page=0,total=0,activeTab='users',busy=false,appConfig=null;
const expandedUsers=new Set();
const notify=text=>{$('message').textContent=text;};
function loginView(show){$('login').hidden=!show;$('panel').hidden=show;$('logout').hidden=show;}
function node(tag,text,className){const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(className)e.className=className;return e;}
async function api(path,method='GET',body){
  const csrf=document.cookie.split(';').map(s=>s.trim()).find(s=>s.startsWith('__Host-xtremex_csrf='))?.split('=')[1]??'';
  const response=await fetch('/api/admin/'+path,{method,credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:body===undefined?undefined:JSON.stringify(body)});
  const data=await response.json();
  if(response.status===401)loginView(true);
  if(!response.ok)throw new Error(data.error??'Request failed');
  return data;
}
const date=value=>value?new Date(value).toLocaleString('en-GB',{timeZone:'Asia/Dhaka',dateStyle:'medium',timeStyle:'short'}):'Never';
const minutes=value=>`${Math.round(Number(value)/60)} min`;
async function dashboard(){
  const d=await api('dashboard');$('metrics').replaceChildren();
  for(const [label,value] of [['Total users',d.total],['Pending',d.pending],['Approved',d.approved],['Blocked',d.blocked],['Online now',d.online],['Active today',d.todayActive],['Watch time today',minutes(d.todayWatchSeconds)],['App versions',d.versions.map(v=>`${v.app_version}: ${v.users}`).join(' · ')||'—']]){
    const c=node('div',undefined,'metric');c.append(node('span',label),node('strong',String(value)));$('metrics').append(c);
  }
}
async function users(){
  const d=await api(`accounts?search=${encodeURIComponent($('search').value.trim())}&status=${$('filter-status').value}&page=${page}`);total=d.total;
  $('user-count').textContent=`${total} user${total===1?'':'s'}`;$('account-list').replaceChildren();
  if(!d.accounts.length)$('account-list').append(node('p','No users found. New app requests appear here.','muted'));
  for(const a of d.accounts){
    const card=node('details',undefined,'account'),heading=node('summary',undefined,'account-heading');
    card.open=expandedUsers.has(a.id);card.addEventListener('toggle',()=>{if(card.open)expandedUsers.add(a.id);else expandedUsers.delete(a.id);});
    const dot=node('span',undefined,`status-dot ${a.status}`);dot.setAttribute('aria-label',a.status);dot.title=a.status;
    heading.append(dot,node('span',a.user_id,'user-id'));card.append(heading);
    card.append(node('span',a.status,`badge ${a.status}`));
    const details=node('div',undefined,'details');
    for(const [label,value] of [['User ID',a.user_id],['Status',a.status],['Device',`${a.device_type} · ${a.device_model||'Awaiting device'}`],['Activity',a.online?'Online now':'Offline'],['Last seen',date(a.last_seen)],['Watch time today',minutes(a.today_watch_seconds)],['Total watch time',minutes(a.watch_seconds)],['Device fingerprint',a.fingerprint||'Awaiting device'],['Version',a.app_version||'—'],['Requested',date(a.created_at)]]){
      const field=node('div');field.append(node('span',label),node('div',value));details.append(field);
    }card.append(details);const actions=node('div',undefined,'actions');
    const options=[];if(a.status==='pending'&&a.fingerprint)options.push(['approve','Approve']);if(a.status==='approved')options.push(['block','Block']);if(a.status==='blocked')options.push(['unblock','Unblock']);if(a.fingerprint)options.push(['reset','Reset device']);
    for(const [action,label] of options){const b=node('button',label,action==='approve'?'primary':'');b.addEventListener('click',async()=>{
      if(action==='reset'&&!confirm(`Reset ${a.user_id}? The current installation loses access. A new device will need approval.`))return;
      b.disabled=true;try{await api(`accounts/${a.id}/${action}`,'POST',{generation:a.generation,fingerprint:a.fingerprint});notify(`${a.user_id}: ${label} completed`);await refresh();}catch(e){notify(e.message);}finally{b.disabled=false;}
    });actions.append(b);}card.append(actions);$('account-list').append(card);
  }
  $('page').textContent=`Page ${page+1}`;$('previous').disabled=page===0;$('next').disabled=(page+1)*50>=total;
}
async function audit(){const rows=await api('audit');$('audit-list').replaceChildren();if(!rows.length)$('audit-list').append(node('p','No admin actions yet.','muted'));for(const r of rows){const item=node('div',undefined,'audit-item');item.append(node('span',`${r.actor} · ${r.action.replaceAll('_',' ')}${r.user_id?' · '+r.user_id:''}`),node('span',date(r.created_at),'muted'));$('audit-list').append(item);}}
async function settings(){
  const [d,c]=await Promise.all([api('settings'),api('app-settings')]);$('support').value=d.supportNumber;appConfig=c;
  $('donation-enabled').checked=c.donation.enabled;$('donation-message').value=c.donation.message;
  for(const name of ['bkash','nagad']){$(name+'-number').value=c.donation[name].number;$(name+'-qr').value='';previewQr(name);}
  $('ads-enabled').checked=c.ads.enabled;for(const k of ['title','message','url'])$('ads-'+k).value=c.ads[k];
}
function previewQr(name){const img=$(name+'-preview');const qr=appConfig?.donation[name].qr||'';img.hidden=!qr;if(qr)img.src=qr;else img.removeAttribute('src');}

async function refresh(){if(busy)return;busy=true;try{await dashboard();await (activeTab==='users'?users():activeTab==='audit'?audit():settings());loginView(false);}catch(e){notify(e.message);}finally{busy=false;}}
$('login-form').addEventListener('submit',async e=>{e.preventDefault();const b=e.submitter;b.disabled=true;try{await api('login','POST',{password:$('password').value});$('password').value='';notify('');await refresh();}catch(error){notify(error.message);}finally{b.disabled=false;}});
$('logout').addEventListener('click',async()=>{try{await api('logout','POST',{});loginView(true);notify('');}catch(e){notify(e.message);}});
$('refresh').addEventListener('click',()=>{if(activeTab==='settings'&&!confirm('Reload settings and discard unsaved edits?'))return;refresh();});
$('filters').addEventListener('submit',e=>{e.preventDefault();page=0;refresh();});
$('previous').addEventListener('click',()=>{page=Math.max(0,page-1);refresh();});$('next').addEventListener('click',()=>{page++;refresh();});
document.querySelectorAll('[data-tab]').forEach(b=>b.addEventListener('click',()=>{activeTab=b.dataset.tab;for(const id of ['users','audit','settings'])$(id).hidden=id!==activeTab;document.querySelectorAll('[data-tab]').forEach(x=>x.classList.toggle('selected',x===b));refresh();}));
$('settings-form').addEventListener('submit',async e=>{e.preventDefault();try{await api('settings','PATCH',{supportNumber:$('support').value});notify('Support number saved.');}catch(error){notify(error.message);}});
for(const name of ['bkash','nagad']){
  $(name+'-clear').addEventListener('click',()=>{if(!appConfig)return;appConfig.donation[name].qr='';$(name+'-qr').value='';previewQr(name);});
  $(name+'-qr').addEventListener('change',async e=>{const file=e.target.files[0];if(!file||!appConfig)return;
    if(!['image/png','image/jpeg'].includes(file.type)||file.size>128000){notify('Choose a PNG/JPEG under 125 KB.');e.target.value='';return;}
    try{const value=await new Promise((resolve,reject)=>{const r=new FileReader();r.onload=()=>resolve(r.result);r.onerror=reject;r.readAsDataURL(file);});
      const image=new Image();image.src=value;await image.decode();if(image.width>2048||image.height>2048)throw new Error('QR dimensions must be 2048 × 2048 or smaller');
      appConfig.donation[name].qr=value;previewQr(name);
    }catch(error){notify(error.message||'Cannot read QR image');e.target.value='';}
  });
}
$('password-form').addEventListener('submit',async e=>{e.preventDefault();
  if($('new-password').value!==$('confirm-password').value){notify('New passwords do not match.');return;}
  e.submitter.disabled=true;try{await api('password','POST',{currentPassword:$('current-password').value,newPassword:$('new-password').value});e.target.reset();loginView(true);notify('Password changed. Sign in with your new password.');}catch(error){notify(error.message);}finally{e.submitter.disabled=false;}
});
async function saveAppConfig(e,section){e.preventDefault();if(!appConfig)return;e.submitter.disabled=true;
  try{
    // Read the latest other section so saving donation does not overwrite sponsor changes.
    const latest=await api('app-settings');
    if(section==='donation')latest.donation={enabled:$('donation-enabled').checked,message:$('donation-message').value,bkash:{number:$('bkash-number').value,qr:appConfig.donation.bkash.qr},nagad:{number:$('nagad-number').value,qr:appConfig.donation.nagad.qr}};
    else latest.ads={enabled:$('ads-enabled').checked,title:$('ads-title').value,message:$('ads-message').value,url:$('ads-url').value};
    await api('app-settings','PATCH',latest);appConfig[section]=latest[section];notify('Saved. Apps load the new settings when Settings opens.');
  }catch(error){notify(error.message+' — check numbers, QR images and HTTPS links.');}finally{e.submitter.disabled=false;}
}
$('donation-form').addEventListener('submit',e=>saveAppConfig(e,'donation'));
$('ads-form').addEventListener('submit',e=>saveAppConfig(e,'ads'));
refresh();setInterval(()=>{if(!$('panel').hidden&&activeTab!=='settings'&&!document.hidden)refresh();},15000);
