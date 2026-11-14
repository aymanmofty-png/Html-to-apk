const H={'Access-Control-Allow-Origin':'*','Access-Control-Allow-Methods':'GET, POST, OPTIONS','Access-Control-Allow-Headers':'Content-Type'};
const J=(o,s)=>new Response(JSON.stringify(o),{status:s||200,headers:Object.assign({'Content-Type':'application/json'},H)});
const API='https://generativelanguage.googleapis.com/v1beta/';
export default{async fetch(req,env){
if(req.method==='OPTIONS')return new Response(null,{headers:H});
const codes=String(env.ACCESS_CODES||'').split(',').map(s=>s.trim()).filter(Boolean);
if(req.method==='GET'){const u=new URL(req.url),c=u.searchParams.get('list');
if(c===null)return J({error:'POST only'},405);
if(codes.length&&codes.indexOf(c)<0)return J({error:'bad code'},401);
const r=await fetch(API+'models?pageSize=100',{headers:{'x-goog-api-key':env.GEMINI_KEY}});const d=await r.json().catch(()=>({}));
if(!r.ok)return J({error:(d.error&&d.error.message)||'err'},r.status);
return J({models:(d.models||[]).filter(m=>(m.supportedGenerationMethods||[]).indexOf('generateContent')>=0).map(m=>m.name.replace('models/',''))})}
if(req.method!=='POST')return J({error:'POST only'},405);
let b;try{b=await req.json()}catch(e){return J({error:'bad json'},400)}
const prompt=String(b.prompt||'').slice(0,24000);
if(!prompt)return J({error:'empty prompt'},400);
if(!env.GEMINI_KEY)return J({error:'server not configured'},500);
if(codes.length&&codes.indexOf(String(b.code||''))<0)return J({error:'رمز الدعوة غير صحيح'},401);
const limit=parseInt(env.DAILY_LIMIT||'30',10);
const id=String(b.code||'')+':'+String(b.device||req.headers.get('CF-Connecting-IP')||'x').slice(0,64);
if(env.RL){const key=id+':'+new Date().toISOString().slice(0,10);const n=parseInt((await env.RL.get(key))||'0',10);
if(n>=limit)return J({error:'وصلت الحد اليومي، جرب غداً'},429);
await env.RL.put(key,String(n+1),{expirationTtl:172800})}
const list=[env.MODEL||'gemini-3.8-flash'].concat(String(env.FALLBACKS||'gemini-flash-latest,gemini-flash-lite-latest,gemini-2.5-flash-lite,gemini-2.0-flash').split(',').map(s=>s.trim()).filter(Boolean));
const body=JSON.stringify({contents:[{role:'user',parts:[{text:prompt}]}],generationConfig:{temperature:0.7,responseMimeType:b.json?'application/json':'text/plain'}});
let err='',st=500;
for(const m of list){
let r;try{r=await fetch(API+'models/'+m+':generateContent',{method:'POST',headers:{'Content-Type':'application/json','x-goog-api-key':env.GEMINI_KEY},body:body})}catch(e){err=String(e);continue}
const d=await r.json().catch(()=>({}));
if(r.ok){const c=(d.candidates||[])[0];const ps=(c&&c.content&&c.content.parts)||[];const t=ps.map(p=>p.text).join('');if(t)return J({text:t,model:m})}
err=(d.error&&d.error.message)||('error '+r.status);st=r.status}
return J({error:err+' (جُرّبت '+list.length+' نماذج)'},st>=400?st:500)}};
