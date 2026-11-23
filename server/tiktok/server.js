/**
 * NEXA TikTok LIVE Bridge
 * -----------------------------------------------------------------------
 * Menghubungkan live TikTok ke aplikasi NEXA AI BY NATAN.
 *
 *   TikTok LIVE ──(tiktok-live-connector, tidak resmi)──► server.js ──WebSocket──► aplikasi NEXA
 *
 * Event yang diteruskan (sudah dinormalisasi):
 *   follow, like (digabung per orang), gift (setelah combo selesai), share,
 *   guest_request (permintaan naik live), join, chat, connected, disconnected, stream_end, error, stats
 *
 * Jalankan di Termux:
 *   pkg install nodejs
 *   cd server/tiktok && npm install && NEXA_TOKEN=rahasia node server.js
 *
 * Variabel lingkungan:
 *   NEXA_TOKEN        token yang sama dengan di aplikasi (wajib)
 *   TT_PORT           default 8090
 *   TT_HOST           default 127.0.0.1 (pakai 0.0.0.0 hanya di jaringan tepercaya)
 *   EULER_API_KEY     opsional, kunci sign server Euler Stream (bila koneksi sering ditolak)
 *   TT_SESSION_ID     opsional, cookie sessionid akun host (beberapa event seperti
 *                     permintaan gabung tamu hanya terlihat oleh host yang login)
 *   TT_LIKE_WINDOW_MS default 8000  (like dari orang yang sama digabung dalam jendela ini)
 */
'use strict';

const http = require('http');
const { URL } = require('url');
const { WebSocketServer } = require('ws');
const lib = require('tiktok-live-connector');

const Connection = lib.TikTokLiveConnection || lib.WebcastPushConnection;
const TOKEN = process.env.NEXA_TOKEN || '';
const PORT = parseInt(process.env.TT_PORT || '8090', 10);
const HOST = process.env.TT_HOST || '127.0.0.1';
const LIKE_WINDOW = parseInt(process.env.TT_LIKE_WINDOW_MS || '8000', 10);

if (!TOKEN) {
  console.error('Set NEXA_TOKEN dulu (samakan dengan Token server di aplikasi).');
  process.exit(1);
}

// ------------------------------------------------------------------ state
let conn = null;
let username = '';
let reconnectTimer = null;
let wantConnected = false;
const clients = new Set();
const likeBuffer = new Map();   // uniqueId -> {user, count, timer}
const stats = resetStats();

function resetStats() {
  return { follows: 0, shares: 0, likes: 0, coins: 0, gifts: 0, joins: 0, chats: 0,
           guests: 0, viewers: 0, topGifters: {}, startedAt: Date.now() };
}

// ------------------------------------------------------------------ helpers
function userOf(d) {
  const u = (d && (d.user || d.applyUser || d.fromUser || d.sender)) || d || {};
  return {
    uniqueId: u.uniqueId || u.displayId || d?.uniqueId || '',
    nickname: u.nickname || d?.nickname || u.uniqueId || d?.uniqueId || 'seseorang',
    avatar: u.profilePicture?.url?.[0] || u.profilePictureUrl || d?.profilePictureUrl || '',
  };
}

/** Cari objek user di dalam pesan apa pun (untuk event yang strukturnya berubah-ubah). */
function findUser(obj, depth = 0) {
  if (!obj || typeof obj !== 'object' || depth > 4) return null;
  if (obj.uniqueId || obj.nickname) return obj;
  for (const v of Object.values(obj)) {
    const f = findUser(v, depth + 1);
    if (f) return f;
  }
  return null;
}

function broadcast(event) {
  const msg = JSON.stringify({ ...event, ts: Date.now() });
  for (const ws of clients) if (ws.readyState === 1) ws.send(msg);
}

function emitStats() {
  const top = Object.entries(stats.topGifters)
    .sort((a, b) => b[1].coins - a[1].coins).slice(0, 5)
    .map(([id, v]) => ({ uniqueId: id, nickname: v.nickname, coins: v.coins }));
  broadcast({ type: 'stats', stats: { ...stats, topGifters: top, username } });
}
setInterval(() => { if (conn) emitStats(); }, 5000);

// ------------------------------------------------------------------ TikTok
function bind(c) {
  c.on('connected', (state) => {
    console.log(`[tiktok] tersambung ke @${username} (room ${state?.roomId || '?'})`);
    broadcast({ type: 'connected', username, roomId: state?.roomId || '' });
  });

  c.on('disconnected', () => {
    console.log('[tiktok] terputus');
    broadcast({ type: 'disconnected', username });
    scheduleReconnect();
  });

  c.on('streamEnd', (actionId) => {
    broadcast({ type: 'stream_end', username, reason: actionId === 4 ? 'banned' : 'ended' });
    wantConnected = false;
  });

  c.on('error', (err) => {
    console.error('[tiktok] error', err?.message || err);
    broadcast({ type: 'error', message: String(err?.message || err).slice(0, 300) });
  });

  c.on('follow', (d) => {
    stats.follows++;
    broadcast({ type: 'follow', user: userOf(d) });
  });

  c.on('share', (d) => {
    stats.shares++;
    broadcast({ type: 'share', user: userOf(d) });
  });

  c.on('member', (d) => {
    stats.joins++;
    broadcast({ type: 'join', user: userOf(d) });
  });

  c.on('chat', (d) => {
    stats.chats++;
    broadcast({ type: 'chat', user: userOf(d), comment: d.comment || '' });
  });

  c.on('roomUser', (d) => { stats.viewers = d.viewerCount || stats.viewers; });

  // Like: digabung per orang supaya tidak spam
  c.on('like', (d) => {
    const user = userOf(d);
    const n = d.likeCount || 1;
    stats.likes = d.totalLikeCount || stats.likes + n;
    const key = user.uniqueId || user.nickname;
    const buf = likeBuffer.get(key);
    if (buf) { buf.count += n; return; }
    const entry = { user, count: n };
    entry.timer = setTimeout(() => {
      likeBuffer.delete(key);
      broadcast({ type: 'like', user: entry.user, likeCount: entry.count, totalLikes: stats.likes });
    }, LIKE_WINDOW);
    likeBuffer.set(key, entry);
  });

  // Gift: untuk gift combo (giftType 1) tunggu sampai combo selesai (repeatEnd)
  c.on('gift', (d) => {
    const g = d.giftDetails || {};
    const giftType = g.giftType ?? d.giftType;
    const repeatEnd = d.repeatEnd ?? d.repeat_end;
    if (giftType === 1 && !repeatEnd) return;
    const user = userOf(d);
    const count = d.repeatCount || 1;
    const diamonds = g.diamondCount ?? d.diamondCount ?? d.extendedGiftInfo?.diamond_count ?? 0;
    const name = g.giftName || d.giftName || d.extendedGiftInfo?.name || 'hadiah';
    const total = diamonds * count;
    stats.gifts += count;
    stats.coins += total;
    const key = user.uniqueId || user.nickname;
    const tg = stats.topGifters[key] || { nickname: user.nickname, coins: 0 };
    tg.coins += total; stats.topGifters[key] = tg;
    broadcast({ type: 'gift', user, gift: { name, count, diamonds, totalCoins: total } });
  });

  // Permintaan naik live / gabung sebagai tamu (co-host / guest)
  const onGuest = (d) => {
    const u = findUser(d);
    if (!u) return;
    stats.guests++;
    broadcast({ type: 'guest_request', user: userOf({ user: u }) });
  };
  c.on('linkMicMethod', (d) => {
    // Hanya teruskan jenis "apply/permintaan"; struktur berbeda antar versi → cek kata kunci
    const s = JSON.stringify(d || {}).toLowerCase();
    if (s.includes('apply') || s.includes('request') || s.includes('invite')) onGuest(d);
  });
  c.on('linkMicArmies', () => {});
}

async function connect(name) {
  await disconnect(false);
  username = name.replace(/^@/, '').trim();
  wantConnected = true;
  Object.assign(stats, resetStats());
  const options = {
    enableExtendedGiftInfo: true,
    processInitialData: false,
  };
  if (process.env.EULER_API_KEY) options.signApiKey = process.env.EULER_API_KEY;
  if (process.env.TT_SESSION_ID) {
    options.sessionId = process.env.TT_SESSION_ID;                    // v1
    options.session = { cookie: { sessionid: process.env.TT_SESSION_ID } }; // v2
  }
  conn = new Connection(username, options);
  bind(conn);
  const state = await conn.connect();
  return { ok: true, username, roomId: state?.roomId || '' };
}

async function disconnect(manual = true) {
  if (manual) wantConnected = false;
  clearTimeout(reconnectTimer);
  likeBuffer.forEach((v) => clearTimeout(v.timer));
  likeBuffer.clear();
  if (conn) {
    try { conn.removeAllListeners?.(); conn.disconnect(); } catch (_) { /* abaikan */ }
    conn = null;
  }
}

function scheduleReconnect() {
  if (!wantConnected) return;
  clearTimeout(reconnectTimer);
  reconnectTimer = setTimeout(() => {
    console.log('[tiktok] mencoba menyambung ulang…');
    connect(username).catch((e) => {
      broadcast({ type: 'error', message: 'Sambung ulang gagal: ' + e.message });
      scheduleReconnect();
    });
  }, 15000); // jeda supaya tidak kena rate limit
}

// ------------------------------------------------------------------ HTTP + WS
function auth(req, url) {
  return req.headers['x-nexa-token'] === TOKEN || url.searchParams.get('token') === TOKEN;
}

function send(res, code, obj) {
  res.writeHead(code, {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, X-Nexa-Token',
  });
  res.end(JSON.stringify(obj));
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (req.method === 'OPTIONS') return send(res, 204, {});
  if (url.pathname === '/health') return send(res, 200, { ok: true, connected: !!conn, username });
  if (!auth(req, url)) return send(res, 401, { error: 'token salah' });

  let body = '';
  req.on('data', (c) => { body += c; if (body.length > 1e5) req.destroy(); });
  req.on('end', async () => {
    let data = {};
    try { data = body ? JSON.parse(body) : {}; } catch (_) { /* kosong */ }
    try {
      if (url.pathname === '/connect' && req.method === 'POST') {
        if (!data.username) return send(res, 400, { error: 'username kosong' });
        return send(res, 200, await connect(data.username));
      }
      if (url.pathname === '/disconnect' && req.method === 'POST') {
        await disconnect(true);
        broadcast({ type: 'disconnected', username });
        return send(res, 200, { ok: true });
      }
      if (url.pathname === '/status') return send(res, 200, { connected: !!conn, username, stats });
      if (url.pathname === '/test' && req.method === 'POST') {
        // Event uji dari aplikasi, untuk mencoba template tanpa sedang live
        broadcast(data);
        return send(res, 200, { ok: true });
      }
      send(res, 404, { error: 'tidak ada' });
    } catch (e) {
      const msg = String(e?.message || e);
      const hint = /offline|not.*live|LIVE has ended|isn.?t online/i.test(msg)
        ? ' (akun belum/tidak sedang live)' : '';
      send(res, 500, { error: msg.slice(0, 300) + hint });
    }
  });
});

const wss = new WebSocketServer({ noServer: true });
server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname !== '/events' || !auth(req, url)) { socket.destroy(); return; }
  wss.handleUpgrade(req, socket, head, (ws) => {
    clients.add(ws);
    ws.send(JSON.stringify({ type: 'hello', connected: !!conn, username }));
    ws.on('close', () => clients.delete(ws));
  });
});

server.listen(PORT, HOST, () => {
  console.log(`NEXA TikTok Bridge di http://${HOST}:${PORT}  (WebSocket: /events?token=…)`);
});

process.on('SIGINT', async () => { await disconnect(true); process.exit(0); });
