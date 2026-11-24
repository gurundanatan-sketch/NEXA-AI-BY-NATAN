/* =========================================================================
   NEXA AI BY NATAN — Akun, Premium, Saran & Masukan, Pengaturan berkategori
   Memakai window.NEXA_UI dari app.js dan server pribadi NEXA (Android.api).
   ========================================================================= */
(function () {
  'use strict';
  const U = window.NEXA_UI;
  if (!U) return;
  const { A, $, $$, esc, toast, go, save } = U;
  const S = () => U.S;

  const PRICE = 'Rp50.000';
  const BANK = 'BNI 2117806403 a.n. Natanel Liaha';
  const WA = '6285176894962';

  // ------------------------------------------------------------------ panggilan server
  const pending = {};
  let seq = 0;
  function api(method, path, body) {
    if (!S().server_active) return localApi(method, path, body || {});
    return new Promise((resolve) => {
      const id = 'r' + ++seq;
      pending[id] = resolve;
      try { A.api(id, method, path, body ? JSON.stringify(body) : ''); } catch (e) { delete pending[id]; resolve({ ok: false, data: { error: e.message } }); }
      setTimeout(() => { if (pending[id]) { delete pending[id]; resolve({ ok: false, data: { error: 'Server tidak menjawab' } }); } }, 35000);
    });
  }
  window.NEXA_EXT = (ev) => {
    if (ev.type !== 'api') return;
    const d = ev.data || {};
    const fn = pending[d.id];
    if (fn) { delete pending[d.id]; fn(d); }
  };
  const errText = (r) => (r && r.data && r.data.error) || 'Gagal terhubung ke server';

  // ------------------------------------------------------------------ MODE MANDIRI (tanpa server): akun di HP
  async function sha(t) {
    try {
      const b = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(t));
      return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
    } catch (e) { let h = 0; for (const c of t) h = (h * 31 + c.charCodeAt(0)) | 0; return 'h' + h; }
  }
  function normLogin(v) {
    v = String(v || '').trim().toLowerCase();
    if (v.includes('@')) return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v) ? v : '';
    let d = v.replace(/[^\d+]/g, ''); if (d.startsWith('+')) d = d.slice(1); if (d.startsWith('0')) d = '62' + d.slice(1);
    return /^\d{9,15}$/.test(d) ? d : '';
  }
  const ok = (data) => ({ ok: true, status: 200, data });
  const bad = (error, status = 400) => ({ ok: false, status, data: { error } });
  function users() { try { return JSON.parse(S().local_users || '{}'); } catch (e) { return {}; } }
  const pub = (u) => ({ uid: u.uid, login: u.login, name: u.name || '', username: u.username || '', bio: u.bio || '', photo: u.photo || '', created: u.created });
  async function localApi(method, path, body) {
    const all = users();
    if (path === '/auth/register' || path === '/auth/login') {
      const login = normLogin(body.login);
      if (!login) return bad('Masukkan email atau nomor telepon yang benar.');
      if (String(body.password || '').length < 6) return bad('Kata sandi minimal 6 karakter.');
      const u = all[login];
      if (path === '/auth/register') {
        if (u) return bad('Akun ini sudah terdaftar di HP ini. Silakan masuk.');
        const salt = Math.random().toString(36).slice(2);
        const nu = { uid: 'L' + Date.now().toString(36), login, salt, pass: await sha(salt + body.password),
          name: String(body.name || '').slice(0, 40) || login.split('@')[0], username: '', bio: '', photo: '', created: Date.now() };
        all[login] = nu; save({ local_users: JSON.stringify(all) });
        return ok({ session: 'local:' + login, profile: pub(nu) });
      }
      if (!u) return bad('Akun belum terdaftar di HP ini. Pilih "Daftar" dulu.');
      if ((await sha(u.salt + body.password)) !== u.pass) return bad('Kata sandi salah.');
      return ok({ session: 'local:' + login, profile: pub(u) });
    }
    const login = String(S().session || '').replace(/^local:/, '');
    const u = all[login];
    if (!u) return bad('Sesi login habis, silakan masuk lagi.', 401);
    if (path === '/me' && method === 'GET') return ok({ profile: pub(u) });
    if (path === '/me') {
      if (body.username !== undefined) {
        const un = String(body.username).trim().toLowerCase().replace(/^@/, '');
        if (un && !/^[a-z0-9._]{3,20}$/.test(un)) return bad('Username 3–20 huruf kecil, angka, titik, atau garis bawah.');
        u.username = un;
      }
      if (body.name !== undefined) u.name = String(body.name).trim().slice(0, 40);
      if (body.bio !== undefined) u.bio = String(body.bio).slice(0, 200);
      if (body.photo !== undefined) u.photo = String(body.photo);
      all[login] = u; save({ local_users: JSON.stringify(all) });
      return ok({ profile: pub(u) });
    }
    if (path === '/premium/activate') {
      let info = {}; try { info = JSON.parse(A.premiumCheck(body.code)); } catch (e) { info = { invalid: true }; }
      if (info.invalid) return bad('Kode aktivasi salah atau bukan untuk HP ini.');
      if (info.expired) return bad('Kode ini sudah kedaluwarsa.');
      return ok({ premium: info, profile: pub(u) });
    }
    if (path === '/feedback' && method === 'POST') {
      const list = (() => { try { return JSON.parse(S().local_feedback || '[]'); } catch (e) { return []; } })();
      const fb = { id: Date.now() + '', name: u.name, login: u.login, text: body.text, ts: Date.now(), replies: [] };
      list.unshift(fb); save({ local_feedback: JSON.stringify(list.slice(0, 50)) });
      const msg = `💌 Saran untuk NEXA AI BY NATAN\nDari: ${u.name} (${u.login})\n\n${body.text}`;
      A.openUrl(`https://wa.me/${WA}?text=${encodeURIComponent(msg)}`);
      return ok({ ok: true, feedback: fb });
    }
    if (path === '/feedback/mine') {
      try { return ok({ items: JSON.parse(S().local_feedback || '[]') }); } catch (e) { return ok({ items: [] }); }
    }
    if (path.startsWith('/feedback/')) return bad('Saran pengguna dikirim langsung ke WhatsApp pembuat aplikasi.');
    return bad('Tidak tersedia di mode mandiri.');
  }

  // ------------------------------------------------------------------ status akun & premium
  function account() { try { return JSON.parse(S().account || 'null'); } catch (e) { return null; } }
  function accounts() { try { return JSON.parse(S().accounts || '[]'); } catch (e) { return []; } }
  const loggedIn = () => !!(S().session && account());
  const today = () => Math.floor(Date.now() / 86400000);
  function codeInfo() {
    if (!S().server_active) { const i = localCode(); return i ? { tier: i.tier, exp: i.exp, expired: !!i.expired } : null; }
    const m = /^NX-([OPG])(\d+)-/.exec(String(S().premium_code || '').toUpperCase());
    if (!m) return null;
    const exp = Number(m[2]);
    return { tier: m[1], exp, expired: exp !== 0 && exp < today() };
  }
  function localCode() {
    if (S().server_active) return null;
    try { const i = JSON.parse(A.premiumCheck(S().premium_code || '')); return i.invalid ? null : i; } catch (e) { return null; }
  }
  const isOwner = () => {
    if (!S().server_active) { try { return !!A.isOwner(); } catch (e) { return false; } }
    const c = codeInfo(); return loggedIn() && !!c && c.tier === 'O' && !c.expired;
  };
  function premiumOK() {
    if (!S().server_active) {
      if (!S().premium_secret_set) return true;    // Premium belum diatur pembuat → bebas
      try { return !!A.premiumAllowed(); } catch (e) { return false; }
    }
    const c = codeInfo();
    return loggedIn() && !!c && !c.expired;
  }
  function tierLabel() {
    const c = codeInfo();
    if (!loggedIn()) return ['BELUM LOGIN', ''];
    if (!c) return ['GRATIS', ''];
    if (c.expired) return ['PREMIUM HABIS', 'warn'];
    const until = c.exp === 0 ? 'selamanya' : 's/d ' + new Date(c.exp * 86400000).toLocaleDateString('id-ID');
    if (c.tier === 'O') return ['PEMBUAT', 'ok', until];
    return [c.tier === 'G' ? 'PREMIUM (HADIAH)' : 'PREMIUM', 'ok', until];
  }

  function avatarHtml(acc, cls) {
    if (acc && acc.photo) return `<img src="${esc(acc.photo)}" alt="Foto profil" class="${cls || ''}">`;
    const n = (acc && (acc.name || acc.login) || '?').trim();
    return acc ? esc(n.charAt(0).toUpperCase()) : '👤';
  }

  function storeLogin(session, profile) {
    const list = accounts().filter((a) => a.login !== profile.login);
    list.unshift({ login: profile.login, name: profile.name, photo: profile.photo ? profile.photo.slice(0, 60000) : '', session });
    save({ session, account: JSON.stringify(profile), user_name: profile.name || '', accounts: JSON.stringify(list.slice(0, 6)) });
  }
  function storeProfile(profile) {
    const list = accounts().map((a) => (a.login === profile.login ? { ...a, name: profile.name, photo: profile.photo ? profile.photo.slice(0, 60000) : '' } : a));
    save({ account: JSON.stringify(profile), user_name: profile.name || '', accounts: JSON.stringify(list) });
  }

  // ------------------------------------------------------------------ tampilan header & akun
  function renderHeader() {
    const acc = loggedIn() ? account() : null;
    $('#acctAvatar').innerHTML = avatarHtml(acc);
    $('#acctBtn').classList.toggle('has-pic', !!(acc && acc.photo));
    $('#notifBtn').style.display = isOwner() && S().server_active ? '' : 'none';
    $$('.owner-only').forEach((el) => (el.style.display = isOwner() && (S().server_active || el.dataset.go !== 'inbox') ? '' : 'none'));
  }

  function renderAccount() {
    const acc = loggedIn() ? account() : null;
    $('#acctPic').innerHTML = avatarHtml(acc);
    $('#acctName').textContent = acc ? (acc.name || 'Tanpa nama') : 'Belum login';
    $('#acctUser').textContent = acc ? (acc.username ? '@' + acc.username + ' · ' : '') + acc.login : 'Masuk untuk memakai NEXA';
    $('#acctBio').textContent = acc ? acc.bio || '' : '';
    const [t, cls, until] = tierLabel();
    const tag = $('#acctTier'); tag.textContent = t; tag.className = 'tag ' + (cls || '');
    $('#acctPremSub').textContent = until ? `Aktif ${until}` : `${PRICE} / bulan`;
    $('#acctLoginBtn').style.display = acc ? 'none' : '';
    $$('#page-account .need-login').forEach((el) => (el.style.display = acc ? '' : 'none'));
    renderHeader();
  }
  $('#acctHead').addEventListener('click', () => (loggedIn() ? go('profile') : go('login')));

  // ------------------------------------------------------------------ login / daftar
  let loginMode = 'login';
  $$('#loginSeg button').forEach((b) => b.addEventListener('click', () => {
    loginMode = b.dataset.mode;
    $$('#loginSeg button').forEach((x) => x.classList.toggle('on', x === b));
    $('#regNameBox').style.display = loginMode === 'register' ? '' : 'none';
    $('#loginGo').textContent = loginMode === 'register' ? 'Daftar' : 'Masuk';
  }));
  $('#loginGo').addEventListener('click', async () => {
    const body = { login: $('#loginId').value.trim(), password: $('#loginPass').value, name: $('#loginName').value.trim() };
    if (!body.login || !body.password) return toast('Isi email/nomor telepon dan kata sandi');
    const btn = $('#loginGo'); btn.disabled = true;
    const r = await api('POST', loginMode === 'register' ? '/auth/register' : '/auth/login', body);
    btn.disabled = false;
    if (!r.ok) return toast(errText(r), 4000);
    storeLogin(r.data.session, r.data.profile);
    $('#loginPass').value = '';
    toast(`Halo, ${r.data.profile.name || 'teman'}! 👋`);
    renderAccount();
    go('account');
    greet(true);
  });

  /** Nexa aktif otomatis & menyapa nama pengguna (Premium). */
  function greet(justLoggedIn) {
    const acc = account();
    if (!acc || S().auto_greet === false || !premiumOK()) return;
    const name = acc.name || 'teman';
    A.liveStart(`[INFO SISTEM] Pengguna bernama ${name} ${justLoggedIn ? 'baru saja login' : 'baru membuka aplikasi'}. ` +
      `Sapa dia dengan menyebut namanya, hangat dan santai, lalu bilang singkat kamu siap bantu apa aja.`);
  }

  // ------------------------------------------------------------------ edit profil
  let newPhoto = null;
  function renderProfile() {
    const acc = account(); if (!acc) return go('login');
    newPhoto = null;
    $('#profPic').innerHTML = avatarHtml(acc);
    $('#profName').value = acc.name || '';
    $('#profUser').value = acc.username || '';
    $('#profBio').value = acc.bio || '';
  }
  $('#profPhoto').addEventListener('change', (e) => {
    const f = e.target.files && e.target.files[0]; if (!f) return;
    const img = new Image();
    const rd = new FileReader();
    rd.onload = () => { img.src = rd.result; };
    img.onload = () => {
      const size = 256, c = document.createElement('canvas');
      c.width = c.height = size;
      const s = Math.min(img.width, img.height);
      c.getContext('2d').drawImage(img, (img.width - s) / 2, (img.height - s) / 2, s, s, 0, 0, size, size);
      newPhoto = c.toDataURL('image/jpeg', 0.82);
      $('#profPic').innerHTML = `<img src="${newPhoto}" alt="Foto profil baru">`;
    };
    rd.readAsDataURL(f);
  });
  $('#profSave').addEventListener('click', async () => {
    const body = { name: $('#profName').value.trim(), username: $('#profUser').value.trim(), bio: $('#profBio').value.trim() };
    if (newPhoto) body.photo = newPhoto;
    const btn = $('#profSave'); btn.disabled = true;
    const r = await api('POST', '/me', body);
    btn.disabled = false;
    if (!r.ok) return toast(errText(r), 4000);
    storeProfile(r.data.profile);
    toast('Profil disimpan ✓');
    renderAccount(); go('account');
  });

  // ------------------------------------------------------------------ premium
  function renderPremium() {
    const dev = (A.deviceId && A.deviceId()) || '—';
    $('#devId').textContent = dev;
    const [t, cls, until] = tierLabel();
    const tag = $('#premTag'); tag.textContent = t; tag.className = 'tag ' + (cls || '');
    const c = codeInfo();
    $('#premStatus').innerHTML = !loggedIn()
      ? '⚠️ Login dulu (email / nomor telepon) untuk berlangganan Premium.'
      : c && !c.expired
        ? `✅ Premium aktif <b>${esc(until || '')}</b>. Semua fitur asisten AI terbuka.`
        : `Premium membuka semua fitur <b>asisten AI</b>: Live real-time, kontrol HP lewat suara, chat AI, panggil dengan suara, Studio video & gambar AI, kloning suara, asisten TikTok LIVE. Hanya <b>${PRICE} / bulan</b>. Menu lain tetap gratis.`;
    $('#premCode').value = '';
  }
  function copy(text, label) {
    const done = () => toast((label || 'Teks') + ' disalin ✓');
    if (A.copyText) { A.copyText(text); return done(); }
    if (navigator.clipboard) navigator.clipboard.writeText(text).then(done, () => toast(text, 5000)); else toast(text, 5000);
  }
  $$('[data-copy]').forEach((b) => b.addEventListener('click', () => copy(b.dataset.copy, 'Nomor rekening')));
  $('#devCopy').addEventListener('click', () => copy($('#devId').textContent, 'ID perangkat'));
  $('#premWa').addEventListener('click', () => {
    if (!loggedIn()) { toast('Login dulu ya'); return go('login'); }
    const acc = account();
    const msg = `Halo Baginda Natan, saya mau berlangganan NEXA Premium (${PRICE}/bulan).\n` +
      `Sudah transfer ke ${BANK}.\nAkun: ${acc.name} (${acc.login})\nID perangkat: ${$('#devId').textContent}\n(bukti transfer terlampir)`;
    A.openUrl(`https://wa.me/${WA}?text=${encodeURIComponent(msg)}`);
  });
  $('#premGo').addEventListener('click', async () => {
    if (!loggedIn()) { toast('Login dulu ya — Premium hanya untuk pengguna yang sudah login'); return go('login'); }
    const code = $('#premCode').value.trim().toUpperCase();
    if (!/^NX-[OPG]\d+-[0-9A-F]{16}$/.test(code)) return toast('Format kode salah. Contoh: NX-P20000-ABCDEF0123456789', 4000);
    const btn = $('#premGo'); btn.disabled = true;
    const r = await api('POST', '/premium/activate', { device: $('#devId').textContent, code });
    btn.disabled = false;
    if (!r.ok) return toast(errText(r), 4500);
    save({ premium_code: code });
    storeProfile(r.data.profile);
    toast(code.startsWith('NX-O') ? 'Selamat datang, Baginda Natan 👑' : 'Premium aktif! Semua fitur asisten AI terbuka 🎉', 4000);
    renderPremium(); renderAccount();
    greet(false);
  });

  // ------------------------------------------------------------------ saran & masukan
  const fmt = (ts) => new Date(ts).toLocaleString('id-ID', { dateStyle: 'medium', timeStyle: 'short' });
  function fbHtml(f, owner) {
    const reps = (f.replies || []).map((x) => `<div class="reply"><b>Balasan pembuat</b> · <small>${fmt(x.ts)}</small><br>${esc(x.text)}</div>`).join('');
    return `<div class="fb" data-fb="${esc(f.id)}">
      ${owner ? `<div class="hint" style="margin:0"><b>${esc(f.name || 'Pengguna')}</b> · ${esc(f.login || '')}</div>` : ''}
      <div>${esc(f.text)}</div><small class="hint" style="margin:0">${fmt(f.ts)}</small>${reps}
      ${owner ? `<textarea class="rep" placeholder="Tulis balasan…"></textarea><button class="btn sm mt" data-reply="${esc(f.id)}">Balas</button>` : ''}</div>`;
  }
  async function loadMine() {
    const r = await api('GET', '/feedback/mine');
    $('#fbMine').innerHTML = r.ok && r.data.items.length ? r.data.items.map((f) => fbHtml(f, false)).join('') : `<div class="empty">${r.ok ? 'Belum ada saran' : esc(errText(r))}</div>`;
  }
  $('#fbSend').addEventListener('click', async () => {
    if (!loggedIn()) { toast('Login dulu untuk mengirim saran'); return go('login'); }
    const text = $('#fbText').value.trim();
    if (text.length < 3) return toast('Tulis saran atau masukan dulu');
    const r = await api('POST', '/feedback', { text });
    if (!r.ok) return toast(errText(r), 4000);
    $('#fbText').value = '';
    toast(S().server_active ? 'Terima kasih! Saranmu terkirim ke pembuat aplikasi 💌' : 'Saranmu dibuka di WhatsApp — tekan kirim ya 💌', 4000);
    loadMine();
  });
  $('#fbRefresh').addEventListener('click', loadMine);

  async function loadInbox() {
    $('#inboxList').innerHTML = '<div class="empty">Memuat…</div>';
    const r = await api('GET', '/feedback/all');
    if (!r.ok) { $('#inboxList').innerHTML = `<div class="empty">${esc(errText(r))}</div>`; return; }
    const items = r.data.items || [];
    const unanswered = items.filter((f) => !(f.replies || []).length).length;
    const b = $('#notifBadge'); b.textContent = unanswered; b.style.display = unanswered ? '' : 'none';
    $('#inboxList').innerHTML = items.length ? items.map((f) => `<div class="card">${fbHtml(f, true)}</div>`).join('') : '<div class="empty">Belum ada saran masuk</div>';
    $$('[data-reply]').forEach((btn) => btn.addEventListener('click', async () => {
      const box = btn.closest('.fb'); const text = box.querySelector('.rep').value.trim();
      if (!text) return toast('Tulis balasan dulu');
      btn.disabled = true;
      const rr = await api('POST', '/feedback/reply', { id: btn.dataset.reply, text });
      btn.disabled = false;
      if (!rr.ok) return toast(errText(rr), 4000);
      toast('Balasan terkirim ✓'); loadInbox();
    }));
  }
  $('#inboxRefresh').addEventListener('click', loadInbox);

  // ------------------------------------------------------------------ panel pembuat (kode Premium tanpa Termux)
  $('#ownTier').addEventListener('change', (e) => ($('#ownDaysBox').style.display = e.target.value === 'G' ? '' : 'none'));
  $('#ownMine').addEventListener('click', () => ($('#ownDev').value = (A.deviceId && A.deviceId()) || ''));
  $('#ownGo').addEventListener('click', async () => {
    const body = { password: $('#ownPass').value, device: $('#ownDev').value.trim().toUpperCase(), tier: $('#ownTier').value, days: Number($('#ownDays').value || 0) };
    if (!body.password) return toast('Isi kata sandi pembuat');
    if (!body.device) return toast('Isi ID perangkat');
    const btn = $('#ownGo'); btn.disabled = true;
    const r = await api('POST', '/premium/owner-code', body);
    btn.disabled = false;
    if (!r.ok) return toast(errText(r), 4000);
    $('#ownCode').textContent = r.data.code;
    $('#ownResult').style.display = '';
    $('#ownUse').style.display = body.device === ((A.deviceId && A.deviceId()) || '').toUpperCase() ? '' : 'none';
    toast('Kode Premium jadi ✓');
  });
  $('#ownCopy').addEventListener('click', () => copy($('#ownCode').textContent, 'Kode'));
  $('#ownWa').addEventListener('click', () => {
    const msg = `Terima kasih sudah berlangganan NEXA Premium! 🎉\nKode aktivasi kamu:\n${$('#ownCode').textContent}\n\nCara pakai: buka NEXA → Akun → Langganan Premium → tempel kode → Aktifkan.`;
    A.openUrl(`https://wa.me/?text=${encodeURIComponent(msg)}`);
  });
  $('#ownUse').addEventListener('click', () => { go('premium'); setTimeout(() => ($('#premCode').value = $('#ownCode').textContent), 100); });

  // ------------------------------------------------------------------ beralih akun & keluar
  function renderSwitch() {
    const cur = account();
    const list = accounts();
    $('#switchList').innerHTML = list.length ? list.map((a, i) => `<button data-sw="${i}"><i class="avatar sm">${avatarHtml(a)}</i>
      <span>${esc(a.name || a.login)}<small>${esc(a.login)}${cur && cur.login === a.login ? ' · sedang dipakai' : ''}</small></span></button>`).join('')
      : '<div class="empty">Belum ada akun tersimpan</div>';
    $$('[data-sw]').forEach((b) => b.addEventListener('click', async () => {
      const a = list[Number(b.dataset.sw)];
      save({ session: a.session });
      const r = await api('GET', '/me');
      if (!r.ok) { toast('Sesi akun ini habis, silakan masuk lagi'); save({ session: '', account: '' }); return go('login'); }
      storeLogin(a.session, r.data.profile);
      toast(`Beralih ke ${r.data.profile.name || a.login} ✓`);
      renderAccount(); go('account');
    }));
  }
  $('#logoutBtn').addEventListener('click', () => {
    const acc = account();
    const list = accounts().filter((a) => !acc || a.login !== acc.login);
    save({ session: '', account: '', user_name: '', accounts: JSON.stringify(list) });
    toast('Kamu sudah keluar dari akun');
    renderAccount();
    go(list.length ? 'switch' : 'login');
  });

  // ------------------------------------------------------------------ pengaturan berkategori
  const CAT_TITLE = { umum: 'Umum', suara: 'Pengaturan suara', bahasa: 'Bahasa', terbatas: 'Izin setelan terbatas',
    izin: 'Izin & sistem', kunci: 'Kunci API (khusus pembuat)', tentang: 'Tentang NEXA' };
  function showCat(cat) {
    if (cat === 'kunci' && !isOwner()) { toast('Menu kunci API khusus pembuat aplikasi 🔐'); cat = null; }
    $('#setMenu').style.display = cat ? 'none' : '';
    $('#setBack').style.display = cat ? '' : 'none';
    $('#setTitle').textContent = cat ? CAT_TITLE[cat] || 'Pengaturan' : 'Pengaturan';
    $$('#page-settings [data-scat]').forEach((el) => {
      const show = el.dataset.scat === cat && !(el.classList.contains('manual-voice') && S().gemini_voice_auto !== false);
      el.style.display = show ? '' : 'none';
    });
    $('#saveSettings').style.display = cat && cat !== 'terbatas' && cat !== 'tentang' ? '' : 'none';
    if (cat === 'izin') renderPerms();
    $('#main').scrollTop = 0;
  }
  $$('[data-cat]').forEach((b) => b.addEventListener('click', () => { if (U.page !== 'settings') go('settings'); showCat(b.dataset.cat); }));
  $('#setBack').addEventListener('click', () => showCat(null));
  function renderPerms() {
    let p = {}; try { p = JSON.parse(A.permStatus()); } catch (e) { p = {}; }
    const rows = [['mic', 'Mikrofon'], ['overlay', 'Tampil di atas aplikasi lain'], ['accessibility', 'Aksesibilitas'],
      ['notifications', 'Akses notifikasi'], ['battery', 'Bebas hemat baterai']];
    $('#permList').innerHTML = rows.map(([k, t]) => `<div class="hint" style="margin:3px 0">${p[k] ? '✅' : '⚪'} ${t}</div>`).join('');
  }
  $('#askAbout').addEventListener('click', () => {
    if (!premiumOK()) { toast('Fitur ini khusus Premium'); return go('premium'); }
    A.liveStart('[INFO SISTEM] Pengguna minta kamu menjelaskan seluruh fungsi, keunggulan, dan manfaat aplikasi NEXA, ' +
      'termasuk siapa penciptanya. Jelaskan santai, runtut, dan tidak terlalu panjang.');
  });

  // ------------------------------------------------------------------ kunci fitur Premium
  // Premium hanya mengunci fitur ASISTEN AI. Akun, pengaturan, pusat kendali, aksi cepat HP,
  // alarm, catatan, galeri, saran & masukan tetap GRATIS.
  const GATED = '#orb, [data-go="live"], #chatSend, #dictate, .chip[data-say], #vMake, #imgMake, #cMake, ' +
    '#ttConnect, #wakeToggle, #testVoice, #gVoiceTest, [data-vtest], #askAbout';
  document.addEventListener('click', (e) => {
    const el = e.target.closest(GATED);
    if (!el || premiumOK()) return;
    e.preventDefault(); e.stopImmediatePropagation();
    toast(loggedIn() ? 'Fitur ini khusus Premium 👑 — aktifkan dulu ya' : 'Login dulu, lalu aktifkan Premium 👑', 3500);
    go(loggedIn() ? 'premium' : 'login');
  }, true);
  document.addEventListener('change', (e) => {
    if (e.target.id === 'wakeToggle' && !premiumOK()) { e.target.checked = false; e.stopImmediatePropagation(); }
  }, true);

  // ------------------------------------------------------------------ navigasi halaman baru
  window.NEXA_ONGO = (p) => {
    if (p === 'account') renderAccount();
    if (p === 'profile') renderProfile();
    if (p === 'premium') renderPremium();
    if (p === 'feedback') { if (!loggedIn()) { toast('Login dulu ya'); return go('login'); } loadMine(); }
    if (p === 'inbox') { if (!isOwner()) { toast('Khusus pembuat aplikasi'); return go('account'); } loadInbox(); }
    if (p === 'switch') renderSwitch();
    if (p === 'owner') { if (!$('#ownDev').value) $('#ownDev').value = ''; }
    if (p === 'settings') { showCat(null); renderHeader(); }
    $('#acctBtn').classList.toggle('on', ['account', 'login', 'profile', 'premium', 'feedback', 'switch'].includes(p));
  };

  // ------------------------------------------------------------------ mulai
  renderHeader();
  if (loggedIn()) {
    // segarkan profil & status premium di latar
    api('GET', '/me').then((r) => {
      if (r.ok) { storeProfile(r.data.profile); renderHeader(); }
      else if (r.status === 401) { save({ session: '', account: '' }); renderHeader(); }
    });
    if (isOwner()) api('GET', '/feedback/all').then((r) => {
      if (!r.ok) return;
      const n = (r.data.items || []).filter((f) => !(f.replies || []).length).length;
      const b = $('#notifBadge'); b.textContent = n; b.style.display = n ? '' : 'none';
    });
    try { if (!sessionStorage.getItem('nexaGreeted')) { sessionStorage.setItem('nexaGreeted', '1'); setTimeout(() => greet(false), 1200); } } catch (e) { /* abaikan */ }
  }
})();
