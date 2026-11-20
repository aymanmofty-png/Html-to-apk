const H={'Access-Control-Allow-Origin':'*','Access-Control-Allow-Methods':'GET, POST, OPTIONS','Access-Control-Allow-Headers':'Content-Type'};
const J=(o,s)=>new Response(JSON.stringify(o),{status:s||200,headers:Object.assign({'Content-Type':'application/json'},H)});
const API='https://generativelanguage.googleapis.com/v1beta/';

async function room(b,env,req,codes){
if(!env.DAILY_API_KEY)return J({error:'DAILY_API_KEY غير مضبوط في الـ Worker'},500);
if(codes.length&&codes.indexOf(String(b.code||''))<0)return J({error:'رمز الدعوة غير صحيح'},401);
const lim=parseInt(env.ROOM_LIMIT||'20',10);
if(env.RL){const id=String(b.code||'')+':room:'+String(b.device||req.headers.get('CF-Connecting-IP')||'x').slice(0,64)+':'+new Date().toISOString().slice(0,10);const n=parseInt((await env.RL.get(id))||'0',10);if(n>=lim)return J({error:'وصلت الحد اليومي لإنشاء الاجتماعات'},429);await env.RL.put(id,String(n+1),{expirationTtl:172800})}
const hd={'Content-Type':'application/json','Authorization':'Bearer '+env.DAILY_API_KEY};
const exp=Math.floor(Date.now()/1000)+4*3600,name='sb'+Math.random().toString(36).slice(2,10)+Date.now().toString(36).slice(-4);
let r,d;
try{r=await fetch('https://api.daily.co/v1/rooms',{method:'POST',headers:hd,body:JSON.stringify({name,privacy:'private',properties:{exp,max_participants:4}})});d=await r.json().catch(()=>({}))}catch(e){return J({error:'تعذر الوصول إلى Daily: '+e},502)}
if(!r.ok||!d.url)return J({error:'Daily: '+(d.info||d.error||('error '+r.status))},r.status>=400?r.status:502);
const tok=async(owner,un)=>{const t=await fetch('https://api.daily.co/v1/meeting-tokens',{method:'POST',headers:hd,body:JSON.stringify({properties:{room_name:name,is_owner:owner,exp,user_name:un}})});const j=await t.json().catch(()=>({}));if(!t.ok||!j.token)throw new Error(j.info||j.error||('error '+t.status));return j.token};
try{const owner=await tok(true,String(b.name||'المعلم').slice(0,40));const guest=await tok(false,'طالب');return J({url:d.url,name,exp,owner,guest})}catch(e){return J({error:'Daily token: '+(e.message||e)},502)}}
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
if(b.op==='room')return await room(b,env,req,codes);
if(b.op==='image')return await image(b,env,req,codes);
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
const body=JSON.stringify({contents:[{role:'user',parts:[{text:prompt}].concat((Array.isArray(b.images)?b.images:[]).slice(0,8).filter(x=>x&&x.data).map(x=>({inline_data:{mime_type:x.mime||'image/jpeg',data:String(x.data)}})))}],generationConfig:{temperature:0.7,responseMimeType:b.json?'application/json':'text/plain'}});
let err='',st=500;
for(const m of list){
let r;try{r=await fetch(API+'models/'+m+':generateContent',{method:'POST',headers:{'Content-Type':'application/json','x-goog-api-key':env.GEMINI_KEY},body:body})}catch(e){err=String(e);continue}
const d=await r.json().catch(()=>({}));
if(r.ok){const c=(d.candidates||[])[0];const ps=(c&&c.content&&c.content.parts)||[];const t=ps.map(p=>p.text).join('');if(t)return J({text:t,model:m})}
err=(d.error&&d.error.message)||('error '+r.status);st=r.status}
return J({error:err+' (جُرّبت '+list.length+' نماذج)'},st>=400?st:500)}};

/* توليد الصور: يجرّب جيمناي (إن سمحت خطتك بالصور) ثم Workers AI (إن رُبط env.AI) */
async function image(b,env,req,codes){
const prompt=String(b.prompt||'').slice(0,1500);
if(!prompt)return J({error:'empty prompt'},400);
if(codes.length&&codes.indexOf(String(b.code||''))<0)return J({error:'رمز الدعوة غير صحيح'},401);
const lim=parseInt(env.IMG_LIMIT||'20',10);
if(env.RL){const id=String(b.code||'')+':img:'+String(b.device||req.headers.get('CF-Connecting-IP')||'x').slice(0,64)+':'+new Date().toISOString().slice(0,10);const n=parseInt((await env.RL.get(id))||'0',10);if(n>=lim)return J({error:'وصلت الحد اليومي لتوليد الصور'},429);await env.RL.put(id,String(n+1),{expirationTtl:172800})}
let err='';
if(env.GEMINI_KEY){
const ms=[env.IMG_MODEL||'gemini-2.5-flash-image'].concat(String(env.IMG_FALLBACKS||'gemini-2.0-flash-preview-image-generation').split(',').map(x=>x.trim()).filter(Boolean));
for(const m of ms){let r;try{r=await fetch(API+'models/'+m+':generateContent',{method:'POST',headers:{'Content-Type':'application/json','x-goog-api-key':env.GEMINI_KEY},body:JSON.stringify({contents:[{role:'user',parts:[{text:prompt}]}],generationConfig:{responseModalities:['TEXT','IMAGE']}})})}catch(e){err=String(e);continue}
const d=await r.json().catch(()=>({}));
if(r.ok){const ps=((d.candidates||[])[0]||{}).content;const parts=(ps&&ps.parts)||[];for(const p of parts){const x=p.inlineData||p.inline_data;if(x&&x.data)return J({image:x.data,mime:x.mimeType||x.mime_type||'image/png',model:m})}err='no image in response'}
else err=(d.error&&d.error.message)||('error '+r.status)}}
if(env.AI){try{const r=await env.AI.run(env.CF_IMG_MODEL||'@cf/black-forest-labs/flux-1-schnell',{prompt:prompt,steps:4});if(r&&r.image)return J({image:r.image,mime:'image/jpeg',model:'workers-ai'})}catch(e){err=(err?err+' | ':'')+String(e)}}
return J({error:err||'لا يوجد مزوّد صور مضبوط في الـ Worker'},502)}
