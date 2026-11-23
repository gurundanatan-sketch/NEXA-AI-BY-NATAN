// NEXA AI BY NATAN — server pribadi (Cloudflare Workers, gratis, online 24 jam).
// Secret (diatur dari Termux: bash tools/setup_server.sh):
//   GEMINI_KEY, OPENAI_KEY, FISH_KEY, TIKTOK_KEY, APP_TOKEN, PREMIUM_SECRET
// Binding KV: DB  (akun, profil, saran & masukan)
const G = 'https://generativelanguage.googleapis.com';
const O = 'https://api.openai.com';
const F = 'https://api.fish.audio';
const LIVE = G + '/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent';
const DAY = 86400000;

const hits = new Map();
const te = new TextEncoder();

function json(o, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { 'content-type': 'application/json' } });
}
const err = (msg, status = 400) => json({ error: msg }, status);
const today = () => Math.floor(Date.now() / DAY);

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const p = url.pathname;
    if (p === '/') return json({ name: 'NEXA AI BY NATAN server', ok: true });

    // Hanya aplikasi NEXA yang boleh memakai server ini.
    if (env.APP_TOKEN && req.headers.get('x-nexa-app') !== env.APP_TOKEN) return err('unauthorized', 401);

    // Batas permintaan per IP per menit (melindungi kuota Anda).
    const ip = req.headers.get('cf-connecting-ip') || '-';
    const key = ip + ':' + Math.floor(Date.now() / 60000);
    const n = (hits.get(key) || 0) + 1;
    hits.set(key, n);
    if (hits.size > 5000) hits.clear();
    if (n > Number(env.RATE_PER_MIN || 120)) return err('Terlalu banyak permintaan, coba lagi sebentar.', 429);

    try {
      if (p === '/health') return health(env, url.searchParams.has('deep'));
      if (p.startsWith('/auth/') || p.startsWith('/me') || p.startsWith('/premium/') || p.startsWith('/feedback')) {
        return await accountApi(req, env, p);
      }

      // ---------- fitur AI: wajib login + premium aktif ----------
      const gate = await premiumGate(req, env);
      if (gate) return gate;

      if (p === '/openai-ws') {
        const model = url.searchParams.get('model') || 'gpt-realtime-2.1';
        return proxyWs(req, O + '/v1/realtime?model=' + encodeURIComponent(model), { Authorization: 'Bearer ' + (env.OPENAI_KEY || '') });
      }
      if (p === '/gemini-ws') return proxyWs(req, LIVE + '?key=' + encodeURIComponent(env.GEMINI_KEY || ''), {});
      if (p === '/tiktok-ws') {
        const base = url.searchParams.get('provider') === 'tiktool' ? 'https://api.tik.tools' : 'https://ws.eulerstream.com';
        const t = new URL(base);
        t.searchParams.set('uniqueId', url.searchParams.get('uniqueId') || '');
        t.searchParams.set('apiKey', env.TIKTOK_KEY || '');
        return proxyWs(req, t.toString(), {});
      }
      if (p.startsWith('/gemini/')) return forward(req, G + p.slice(7) + url.search, { 'x-goog-api-key': env.GEMINI_KEY || '' });
      if (p.startsWith('/openai/')) return forward(req, O + p.slice(7) + url.search, { authorization: 'Bearer ' + (env.OPENAI_KEY || '') });
      if (p.startsWith('/fish/')) return forward(req, F + p.slice(5) + url.search, { authorization: 'Bearer ' + (env.FISH_KEY || '') });
      return err('not found', 404);
    } catch (e) {
      return err('server: ' + (e && e.message ? e.message : String(e)), 500);
    }
  },
};

// ======================================================================= kripto
async function hmacHex(secret, msg) {
  const k = await crypto.subtle.importKey('raw', te.encode(secret || 'nexa'), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const s = await crypto.subtle.sign('HMAC', k, te.encode(msg));
  return [...new Uint8Array(s)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function hashPass(pass, salt) {
  const k = await crypto.subtle.importKey('raw', te.encode(pass), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: te.encode(salt), iterations: 5000 }, k, 256);
  return [...new Uint8Array(bits)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function rand(n = 12) {
  return [...crypto.getRandomValues(new Uint8Array(n))].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * Kode premium dibuat di Termux (tools/buat_kode.sh):
 *   NX-<T><HARI>-<16 HEX>   T = O (pembuat, selamanya) | P (premium) | G (hadiah/gratis)
 *   HARI = hari kedaluwarsa (hari sejak 1970), 0 = selamanya
 *   tanda tangan = HMAC-SHA256(PREMIUM_SECRET, "<idPerangkat>|<T>|<HARI>") 16 hex pertama
 */
async function checkCode(env, device, code) {
  const m = /^NX-([OPG])(\d+)-([0-9A-F]{16})$/.exec(String(code || '').trim().toUpperCase());
  if (!m || !device) return null;
  const [, tier, exp, sig] = m;
  const want = (await hmacHex(env.PREMIUM_SECRET, `${device}|${tier}|${exp}`)).slice(0, 16).toUpperCase();
  if (want !== sig) return null;
  const e = Number(exp);
  return { tier, exp: e, expired: e !== 0 && e < today() };
}

// Sesi login: <uid>.<hariKedaluwarsa>.<tanda tangan>
async function makeSession(env, uid) {
  const exp = today() + 365;
  return `${uid}.${exp}.${(await hmacHex(env.PREMIUM_SECRET, `s|${uid}|${exp}`)).slice(0, 24)}`;
}
async function readSession(env, token) {
  const [uid, exp, sig] = String(token || '').split('.');
  if (!uid || !exp || !sig) return null;
  if (Number(exp) < today()) return null;
  const want = (await hmacHex(env.PREMIUM_SECRET, `s|${uid}|${exp}`)).slice(0, 24);
  return want === sig ? uid : null;
}

async function premiumGate(req, env) {
  if (!env.PREMIUM_SECRET) return null; // premium belum diatur → bebas (mode lama)
  const uid = await readSession(env, req.headers.get('x-nexa-session'));
  if (!uid) return err('Silakan login dulu (email / nomor telepon) untuk memakai NEXA.', 401);
  const c = await checkCode(env, req.headers.get('x-nexa-device'), req.headers.get('x-nexa-code'));
  if (!c) return err('Fitur ini khusus Premium. Aktifkan Premium di menu Akun.', 402);
  if (c.expired) return err('Masa Premium sudah habis. Perpanjang di menu Akun → Langganan Premium.', 402);
  return null;
}

// ======================================================================= akun & saran
function normLogin(v) {
  v = String(v || '').trim().toLowerCase();
  if (v.includes('@')) return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v) ? v : '';
  let d = v.replace(/[^\d+]/g, '');
  if (d.startsWith('+')) d = d.slice(1);
  if (d.startsWith('0')) d = '62' + d.slice(1);
  return /^\d{9,15}$/.test(d) ? d : '';
}

function publicProfile(u) {
  return {
    uid: u.uid, login: u.login, name: u.name || '', username: u.username || '', bio: u.bio || '',
    photo: u.photo || '', premium: u.premium || null, created: u.created,
  };
}

async function getUser(env, uid) {
  const raw = await env.DB.get('u:' + uid);
  return raw ? JSON.parse(raw) : null;
}
const putUser = (env, u) => env.DB.put('u:' + u.uid, JSON.stringify(u));

async function accountApi(req, env, p) {
  if (!env.DB) return err('Database akun belum dipasang. Jalankan lagi: bash tools/setup_server.sh', 503);
  if (!env.PREMIUM_SECRET) return err('Server premium belum diatur. Jalankan lagi: bash tools/setup_server.sh', 503);
  const body = req.method === 'POST' ? await req.json().catch(() => ({})) : {};

  // ---------- daftar / masuk ----------
  if (p === '/auth/register' || p === '/auth/login') {
    const login = normLogin(body.login);
    if (!login) return err('Masukkan email atau nomor telepon yang benar.');
    if (String(body.password || '').length < 6) return err('Kata sandi minimal 6 karakter.');
    const existing = await env.DB.get('l:' + login);
    if (p === '/auth/register') {
      if (existing) return err('Akun ini sudah terdaftar. Silakan masuk.');
      const uid = rand(8);
      const salt = rand(8);
      const u = {
        uid, login, salt, pass: await hashPass(body.password, salt),
        name: String(body.name || '').slice(0, 40) || login.split('@')[0], username: '', bio: '', photo: '',
        created: Date.now(), fb: [],
      };
      await putUser(env, u);
      await env.DB.put('l:' + login, uid);
      return json({ session: await makeSession(env, uid), profile: publicProfile(u) });
    }
    if (!existing) return err('Akun belum terdaftar. Pilih "Daftar" dulu.');
    const u = await getUser(env, existing);
    if (!u || (await hashPass(body.password, u.salt)) !== u.pass) return err('Kata sandi salah.');
    return json({ session: await makeSession(env, u.uid), profile: publicProfile(u) });
  }

  const uid = await readSession(env, req.headers.get('x-nexa-session'));
  if (!uid) return err('Sesi login habis, silakan masuk lagi.', 401);
  const u = await getUser(env, uid);
  if (!u) return err('Akun tidak ditemukan.', 404);

  // ---------- profil ----------
  if (p === '/me' && req.method === 'GET') return json({ profile: publicProfile(u) });
  if (p === '/me' && req.method === 'POST') {
    if (body.username !== undefined) {
      const un = String(body.username).trim().toLowerCase().replace(/^@/, '');
      if (un && !/^[a-z0-9._]{3,20}$/.test(un)) return err('Username 3–20 huruf kecil, angka, titik, atau garis bawah.');
      if (un !== (u.username || '')) {
        if (un) {
          const taken = await env.DB.get('n:' + un);
          if (taken && taken !== uid) return err('Username sudah dipakai orang lain.');
          await env.DB.put('n:' + un, uid);
        }
        if (u.username) await env.DB.delete('n:' + u.username);
        u.username = un;
      }
    }
    if (body.name !== undefined) u.name = String(body.name).trim().slice(0, 40);
    if (body.bio !== undefined) u.bio = String(body.bio).slice(0, 200);
    if (body.photo !== undefined) {
      const ph = String(body.photo);
      if (ph && (!ph.startsWith('data:image/') || ph.length > 400000)) return err('Foto terlalu besar.');
      u.photo = ph;
    }
    await putUser(env, u);
    return json({ profile: publicProfile(u) });
  }

  // ---------- premium ----------
  if (p === '/premium/activate') {
    const c = await checkCode(env, body.device, body.code);
    if (!c) return err('Kode aktivasi salah atau bukan untuk perangkat ini.');
    if (c.expired) return err('Kode ini sudah kedaluwarsa.');
    u.premium = { tier: c.tier, exp: c.exp, device: body.device, code: String(body.code).trim().toUpperCase(), at: Date.now() };
    await putUser(env, u);
    return json({ ok: true, premium: u.premium, profile: publicProfile(u) });
  }
  if (p === '/premium/status') {
    const c = await checkCode(env, req.headers.get('x-nexa-device'), req.headers.get('x-nexa-code'));
    return json({ premium: c, owner: !!(c && c.tier === 'O' && !c.expired) });
  }

  // ---------- saran & masukan ----------
  if (p === '/feedback' && req.method === 'POST') {
    const text = String(body.text || '').trim().slice(0, 2000);
    if (text.length < 3) return err('Tulis saran atau masukan dulu.');
    const id = `${Date.now()}-${rand(3)}`;
    const fb = { id, uid, name: u.name, login: u.login, photo: u.photo ? 1 : 0, text, ts: Date.now(), replies: [] };
    await env.DB.put('f:' + id, JSON.stringify(fb));
    u.fb = [id, ...(u.fb || [])].slice(0, 50);
    await putUser(env, u);
    return json({ ok: true, feedback: fb });
  }
  if (p === '/feedback/mine') {
    const list = (await Promise.all((u.fb || []).map((id) => env.DB.get('f:' + id)))).filter(Boolean).map((x) => JSON.parse(x));
    return json({ items: list });
  }

  // ---------- khusus pembuat aplikasi (kode O) ----------
  const c = await checkCode(env, req.headers.get('x-nexa-device'), req.headers.get('x-nexa-code'));
  const owner = !!(c && c.tier === 'O' && !c.expired);
  if (p === '/feedback/all' || p === '/feedback/reply') {
    if (!owner) return err('Khusus pembuat aplikasi.', 403);
    if (p === '/feedback/all') {
      const keys = await env.DB.list({ prefix: 'f:', limit: 100 });
      const items = (await Promise.all(keys.keys.map((k) => env.DB.get(k.name)))).filter(Boolean).map((x) => JSON.parse(x));
      items.sort((a, b) => b.ts - a.ts);
      return json({ items });
    }
    const raw = await env.DB.get('f:' + body.id);
    if (!raw) return err('Saran tidak ditemukan.', 404);
    const fb = JSON.parse(raw);
    const text = String(body.text || '').trim().slice(0, 2000);
    if (!text) return err('Tulis balasan dulu.');
    fb.replies = [...(fb.replies || []), { text, ts: Date.now() }];
    await env.DB.put('f:' + fb.id, JSON.stringify(fb));
    return json({ ok: true, feedback: fb });
  }
  return err('not found', 404);
}

// ======================================================================= proxy AI
async function forward(req, target, add) {
  const h = new Headers(req.headers);
  ['x-nexa-app', 'x-nexa-session', 'x-nexa-device', 'x-nexa-code', 'host', 'authorization', 'x-goog-api-key',
    'cf-connecting-ip', 'x-forwarded-for', 'x-real-ip'].forEach((x) => h.delete(x));
  for (const [k, v] of Object.entries(add)) h.set(k, v);
  const hasBody = !['GET', 'HEAD'].includes(req.method);
  const r = await fetch(target, { method: req.method, headers: h, body: hasBody ? req.body : undefined, redirect: 'follow' });
  const out = new Headers(r.headers);
  out.delete('set-cookie');
  return new Response(r.body, { status: r.status, headers: out });
}

async function proxyWs(req, target, add) {
  if ((req.headers.get('upgrade') || '').toLowerCase() !== 'websocket') return err('butuh websocket', 426);
  const up = await fetch(target, { headers: { Upgrade: 'websocket', ...add } });
  const remote = up.webSocket;
  if (!remote) return json({ error: 'server tujuan menolak', status: up.status, detail: (await up.text()).slice(0, 300) }, 502);
  remote.accept();
  const pair = new WebSocketPair();
  const client = pair[0], edge = pair[1];
  edge.accept();
  const safeCode = (c) => (c === 1005 || c === 1006 || !c ? 1000 : c);
  edge.addEventListener('message', (e) => { try { remote.send(e.data); } catch (_) {} });
  remote.addEventListener('message', (e) => { try { edge.send(e.data); } catch (_) {} });
  edge.addEventListener('close', (e) => { try { remote.close(safeCode(e.code), e.reason); } catch (_) {} });
  remote.addEventListener('close', (e) => { try { edge.close(safeCode(e.code), e.reason); } catch (_) {} });
  edge.addEventListener('error', () => { try { remote.close(1011, 'client error'); } catch (_) {} });
  remote.addEventListener('error', () => { try { edge.close(1011, 'upstream error'); } catch (_) {} });
  return new Response(null, { status: 101, webSocket: client });
}

async function health(env, deep) {
  const has = {
    gemini: !!env.GEMINI_KEY, openai: !!env.OPENAI_KEY, fish: !!env.FISH_KEY, tiktok: !!env.TIKTOK_KEY,
    akun: !!env.DB, premium: !!env.PREMIUM_SECRET,
  };
  if (!deep) return json(has);
  const test = async (ok, url, headers) => {
    if (!ok) return 'kosong';
    try {
      const r = await fetch(url, { headers });
      return r.ok ? 'ok' : 'gagal: HTTP ' + r.status + ' ' + (await r.text()).slice(0, 90);
    } catch (e) { return 'gagal: ' + e.message; }
  };
  return json({
    gemini: await test(has.gemini, G + '/v1beta/models?pageSize=1', { 'x-goog-api-key': env.GEMINI_KEY }),
    openai: await test(has.openai, O + '/v1/models', { authorization: 'Bearer ' + env.OPENAI_KEY }),
    fish: await test(has.fish, F + '/model?page_size=1', { authorization: 'Bearer ' + env.FISH_KEY }),
    tiktok: has.tiktok ? 'ok' : 'kosong',
    akun: has.akun ? 'ok' : 'kosong',
    premium: has.premium ? 'ok' : 'kosong',
  });
}
