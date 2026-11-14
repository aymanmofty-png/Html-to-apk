// Cloudflare Worker: وسيط بين السبورة و Gemini (المفتاح يبقى هنا فقط)
// الأسرار/المتغيرات (Settings > Variables):
//   GEMINI_KEY  (Secret)  مفتاح Gemini
//   ACCESS_CODES (اختياري) رموز دعوة مفصولة بفاصلة، مثل: abc123,xyz789
//   DAILY_LIMIT  (اختياري) عدد الطلبات اليومية لكل جهاز، الافتراضي 30
//   MODEL        (اختياري) الافتراضي gemini-2.5-flash
// اختياري للحدود: اربط KV باسم RL
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
};
const J = (o, s = 200) => new Response(JSON.stringify(o), { status: s, headers: { ...CORS, 'Content-Type': 'application/json' } });

export default {
  async fetch(req, env) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    if (req.method !== 'POST') return J({ error: 'POST only' }, 405);
    let b;
    try { b = await req.json(); } catch { return J({ error: 'bad json' }, 400); }
    const prompt = String(b.prompt || '').slice(0, 24000);
    if (!prompt) return J({ error: 'empty prompt' }, 400);
    if (!env.GEMINI_KEY) return J({ error: 'server not configured' }, 500);

    const codes = (env.ACCESS_CODES || '').split(',').map(s => s.trim()).filter(Boolean);
    if (codes.length && !codes.includes(String(b.code || ''))) return J({ error: 'رمز الدعوة غير صحيح' }, 401);

    const limit = parseInt(env.DAILY_LIMIT || '30', 10);
    const id = String(b.code || '') + ':' + String(b.device || req.headers.get('CF-Connecting-IP') || 'x').slice(0, 64);
    if (env.RL) {
      const key = id + ':' + new Date().toISOString().slice(0, 10);
      const n = parseInt((await env.RL.get(key)) || '0', 10);
      if (n >= limit) return J({ error: 'وصلت الحد اليومي، جرب غداً' }, 429);
      await env.RL.put(key, String(n + 1), { expirationTtl: 172800 });
    }

    const model = env.MODEL || 'gemini-2.5-flash';
    const body = {
      contents: [{ role: 'user', parts: [{ text: prompt }] }],
      generationConfig: { temperature: 0.7, responseMimeType: b.json ? 'application/json' : 'text/plain' },
    };
    const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': env.GEMINI_KEY },
      body: JSON.stringify(body),
    });
    const d = await r.json().catch(() => ({}));
    if (!r.ok) return J({ error: (d.error && d.error.message) || 'خطأ من Gemini' }, r.status);
    const text = (((d.candidates || [])[0] || {}).content || {}).parts?.map(p => p.text).join('') || '';
    return J({ text });
  },
};
