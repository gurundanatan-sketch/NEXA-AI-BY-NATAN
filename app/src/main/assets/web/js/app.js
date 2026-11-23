/* =========================================================================
   NEXA AI BY NATAN — logika antarmuka
   Kotlin ⇄ JS: window.Android (NexaBridge.kt) dan window.NEXA.onEvent(...)
   ========================================================================= */
(function () {
  'use strict';

  // ---------------------------------------------------------------- bridge
  // Saat dibuka di browser biasa (pratinjau desain), pakai tiruan sederhana.
  const A = window.Android || mockBridge();
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const esc = (t) => String(t ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  let S = {};        // pengaturan
  let status = {};   // status sistem
  let ttPaused = false;
  let recording = null;   // tujuan rekaman: own | sample | consent
  const upl = { own: null, sample: null, consent: null, bgm: null };

  function toast(t, ms = 2600) {
    const el = $('#toast');
    el.textContent = t; el.classList.add('show');
    clearTimeout(toast._t); toast._t = setTimeout(() => el.classList.remove('show'), ms);
  }

  // ---------------------------------------------------------------- navigasi
  let page = 'home';
  function go(p) {
    if (p === 'live') { A.toggleLive(); return; }
    page = p;
    $$('.page').forEach((el) => el.classList.toggle('active', el.id === 'page-' + p));
    $$('#nav button').forEach((b) => b.classList.toggle('on', b.dataset.go === p));
    $('#main').scrollTop = 0;
    if (p === 'studio') { loadVoices(); loadJobs(); }
    if (p === 'control') { renderApps(); renderNotes(); }
    if (p === 'settings') loadSettings();
  }
  $$('[data-go]').forEach((b) => b.addEventListener('click', () => go(b.dataset.go)));

  // ---------------------------------------------------------------- pengaturan (data-pref)
  function loadSettings() {
    try { S = JSON.parse(A.getSettings()); } catch (e) { S = {}; }
    $$('[data-pref]').forEach((el) => {
      const k = el.dataset.pref, v = S[k];
      if (v === undefined) return;
      if (el.type === 'checkbox') el.checked = !!v; else el.value = v;
    });
    $('#rateVal').textContent = Number(S.tt_rate || 1).toFixed(2);
    $$('.aname').forEach((el) => (el.textContent = S.assistant_name || 'Nexa'));
    $('#orbHint').innerHTML = `Ketuk orb untuk ngobrol, atau ucapkan <b>"${esc(S.assistant_name || 'Nexa')}"</b>`;
    $('#ttUser').value = S.tt_username || '';
    renderTemplates();
  }

  function collect(scope = document) {
    const out = {};
    $$('[data-pref]', scope).forEach((el) => {
      const k = el.dataset.pref;
      out[k] = el.type === 'checkbox' ? el.checked : el.type === 'number' || el.type === 'range' ? Number(el.value) : el.value;
    });
    return out;
  }

  function save(partial) {
    Object.assign(S, partial);
    A.saveSettings(JSON.stringify(partial));
  }

  $('#saveSettings').addEventListener('click', () => { save(collect($('#page-settings'))); toast('Pengaturan disimpan ✓'); loadSettings(); });
  // Pengaturan TikTok tersimpan otomatis saat diubah
  $('#page-tiktok').addEventListener('change', (e) => {
    if (e.target.dataset.pref) save({ [e.target.dataset.pref]: e.target.type === 'checkbox' ? e.target.checked : e.target.type === 'number' || e.target.type === 'range' ? Number(e.target.value) : e.target.value });
  });
  $('[data-pref="tt_rate"]').addEventListener('input', (e) => ($('#rateVal').textContent = Number(e.target.value).toFixed(2)));

  // ---------------------------------------------------------------- status
  function renderStatus() {
    const items = [
      ['apiKey', 'Gemini API key', () => go('settings')],
      ['mic', 'Izin mikrofon', () => A.requestPermissions()],
      ['accessibility', 'Kontrol layar (Aksesibilitas)', () => A.openSetting('accessibility')],
      ['notifications', 'Baca & balas chat (Akses notifikasi)', () => A.openSetting('notifications')],
      ['phone', 'Angkat/tolak telepon', () => A.requestPermissions()],
      ['battery', 'Tetap aktif di latar belakang', () => A.openSetting('battery')],
    ];
    const box = $('#statusList');
    box.innerHTML = '';
    let ok = 0;
    items.forEach(([k, label, fn]) => {
      const on = !!status[k]; if (on) ok++;
      const row = document.createElement('div');
      row.className = 's';
      row.innerHTML = `<i class="dot ${on ? 'ok' : ''}"></i><span>${label}</span>` +
        (on ? '<span class="tag ok auto" style="flex:0">AKTIF</span>' : '<button class="btn ghost sm auto">Aktifkan</button>');
      if (!on) row.querySelector('button').addEventListener('click', fn);
      box.appendChild(row);
    });
    $('#readyTag').textContent = `${ok}/${items.length}`;
    $('#readyTag').className = 'tag ' + (ok === items.length ? 'ok' : 'warn');
    $('#wakeToggle').checked = !!status.wake || !!S.wake_enabled;
    setLive(!!status.live);
    setTikTok(!!status.tiktok);
    if (status.version) $('#verText').textContent = `NEXA AI BY NATAN · v${status.version}`;
  }

  function setLive(on) {
    $('#orb').classList.toggle('live', on);
    $('#orbLabel').textContent = on ? 'MENDENGAR' : 'KETUK';
    $('#orbHint').innerHTML = on ? 'Silakan bicara — kamu bisa <b>menyela</b> kapan saja. Ketuk lagi untuk berhenti.'
      : `Ketuk orb untuk ngobrol, atau ucapkan <b>"${esc(S.assistant_name || 'Nexa')}"</b>`;
    $('#liveTag').style.display = on ? '' : 'none';
    $('#liveTag').className = 'tag live';
    $$('#nav .center').forEach((b) => b.classList.toggle('on', on));
  }

  $('#orb').addEventListener('click', () => A.toggleLive());
  $('#wakeToggle').addEventListener('change', (e) => { A.setWake(e.target.checked); toast(e.target.checked ? `Ucapkan "${S.assistant_name || 'Nexa'}" untuk memanggil` : 'Panggil-suara dimatikan'); });
  $('#permBtn').addEventListener('click', () => A.requestPermissions());
  $$('[data-open]').forEach((b) => b.addEventListener('click', () => A.openSetting(b.dataset.open)));
  $$('[data-url]').forEach((a) => a.addEventListener('click', (e) => { e.preventDefault(); A.openUrl(a.dataset.url); }));

  // ---------------------------------------------------------------- aksi cepat
  $$('[data-quick]').forEach((b) => b.addEventListener('click', () => {
    const r = A.quick(b.dataset.quick, '');
    const out = $('#quickOut');
    if (['device', 'screen', 'notifications'].includes(b.dataset.quick) || r.startsWith('GAGAL')) {
      out.style.display = ''; out.textContent = r;
    } else toast(r.replace(/^OK: /, ''));
  }));

  // ---------------------------------------------------------------- log & chat
  function addHomeLog(text) {
    const box = $('#homeLog');
    if (box.querySelector('.empty')) box.innerHTML = '';
    const el = document.createElement('div');
    el.className = 'ev';
    const ic = text.startsWith('Kamu') ? '🗣️' : text.startsWith('🔧') ? '🛠️' : /^(Nexa|Lunara|\w+):/.test(text) ? '✨' : '•';
    el.innerHTML = `<span class="ic">${ic}</span><div class="b">${esc(text)}</div>`;
    box.prepend(el);
    while (box.children.length > 40) box.lastChild.remove();
  }

  function addChat(kind, text) {
    const el = document.createElement('div');
    el.className = 'msg ' + kind;
    el.textContent = text;
    $('#chat').appendChild(el);
    if (page === 'chat') $('#main').scrollTop = $('#main').scrollHeight;
  }

  function onLog(text) {
    addHomeLog(text);
    const name = (S.assistant_name || 'Nexa');
    if (/^Kamu( \(ketik\))?:/.test(text)) addChat('me', text.replace(/^Kamu( \(ketik\))?:\s*/, ''));
    else if (text.startsWith(name + ':') || text.startsWith('Nexa:')) addChat('ai', text.replace(/^[^:]+:\s*/, ''));
    else if (!text.startsWith('Perintah:') && !text.startsWith('Jawaban:')) addChat('sys', text);
  }

  function sendChat(t) {
    t = (t ?? $('#chatInput').value).trim();
    if (!t) return;
    $('#chatInput').value = '';
    if (!status.live) addChat('me', t);
    A.sendText(t);
  }
  $('#chatSend').addEventListener('click', () => sendChat());
  $('#chatInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') sendChat(); });
  $('#dictate').addEventListener('click', () => A.dictate());
  $$('[data-say]').forEach((c) => c.addEventListener('click', () => { go('chat'); sendChat(c.dataset.say); }));

  // ---------------------------------------------------------------- TikTok LIVE
  const TT = [
    ['follow', '💜', 'Follow', 'Saat ada yang follow'],
    ['like', '❤️', 'Tap-tap layar', 'Like digabung per orang'],
    ['gift', '🎁', 'Hadiah', 'Nama hadiah, jumlah & koin'],
    ['share', '🔁', 'Membagikan live', ''],
    ['guest', '🙋', 'Permintaan naik live', 'Permintaan gabung sebagai tamu'],
    ['join', '👋', 'Masuk live', 'Penonton baru bergabung'],
    ['chat', '💭', 'Komentar', 'Membacakan komentar'],
  ];
  const testType = { guest: 'guest_request' };

  function renderTemplates() {
    const box = $('#tplList');
    box.innerHTML = '';
    TT.forEach(([k, ic, title, desc]) => {
      const d = document.createElement('details');
      d.className = 'tpl';
      d.innerHTML = `<summary><span class="ic">${ic}</span><span>${title}${desc ? `<br><small class="hint" style="margin:0">${desc}</small>` : ''}</span>
          <label class="toggle" onclick="event.stopPropagation()"><input type="checkbox" data-pref="tt_on_${k}"><span></span></label></summary>
        <textarea data-pref="tt_tpl_${k}"></textarea>
        <div class="row mt"><button class="btn ghost sm" data-test="${testType[k] || k}">▶ Coba</button></div>`;
      box.appendChild(d);
      d.querySelector(`[data-pref="tt_on_${k}"]`).checked = !!S['tt_on_' + k];
      d.querySelector(`[data-pref="tt_tpl_${k}"]`).value = S['tt_tpl_' + k] || '';
      d.querySelector('[data-test]').addEventListener('click', (e) => {
        save(collect($('#page-tiktok')));
        A.tiktokTest(e.target.dataset.test);
      });
    });
  }

  function setTikTok(on) {
    const t = $('#ttTag');
    t.textContent = on ? 'TERSAMBUNG' : 'Terputus';
    t.className = 'tag ' + (on ? 'live' : '');
  }

  $('#ttConnect').addEventListener('click', () => {
    const u = $('#ttUser').value.trim().replace(/^@/, '');
    if (!u) return toast('Isi username TikTok dulu');
    toast(A.tiktokConnect(u).replace(/^OK: /, ''));
  });
  $('#ttDisconnect').addEventListener('click', () => { A.tiktokDisconnect(); setTikTok(false); });
  $('#ttPause').addEventListener('click', (e) => {
    ttPaused = !ttPaused; A.tiktokPause(ttPaused);
    e.target.textContent = ttPaused ? '▶ Lanjut baca' : '⏸ Jeda baca';
  });

  const TT_ICON = { follow: '💜', like: '❤️', gift: '🎁', share: '🔁', guest_request: '🙋', join: '👋', chat: '💭', ai_answer: '🤖', info: 'ℹ️', error: '⚠️', connected: '🟢', disconnected: '🔌', stream_end: '🏁' };

  function onTikTok(ev) {
    if (ev.type === 'stats') return renderStats(ev.stats || {});
    if (ev.type === 'connected') setTikTok(true);
    if (ev.type === 'disconnected' || ev.type === 'stream_end') setTikTok(false);
    if (ev.type === 'hello') return;
    const feed = $('#ttFeed');
    if (feed.querySelector('.empty')) feed.innerHTML = '';
    const u = ev.user || {};
    let line = '';
    switch (ev.type) {
      case 'follow': line = `<b>${esc(u.nickname)}</b> follow`; break;
      case 'like': line = `<b>${esc(u.nickname)}</b> tap layar ${ev.likeCount}×`; break;
      case 'gift': { const g = ev.gift || {}; line = `<b>${esc(u.nickname)}</b> kirim ${g.count}× ${esc(g.name)} · ${g.totalCoins} koin`; break; }
      case 'share': line = `<b>${esc(u.nickname)}</b> membagikan live`; break;
      case 'guest_request': line = `<b>${esc(u.nickname)}</b> minta naik live`; break;
      case 'join': line = `<b>${esc(u.nickname)}</b> masuk`; break;
      case 'chat': line = `<b>${esc(u.nickname)}</b>: ${esc(ev.comment)}`; break;
      case 'ai_answer': line = 'Jawaban AI'; break;
      default: line = esc(ev.message || ev.type);
    }
    const el = document.createElement('div');
    el.className = 'ev ' + ev.type;
    el.innerHTML = `<span class="ic">${TT_ICON[ev.type] || '•'}</span><div class="b">${line}${ev.speech ? `<small>🔊 ${esc(ev.speech)}</small>` : ''}</div>`;
    feed.prepend(el);
    while (feed.children.length > 80) feed.lastChild.remove();
    if (ev.type === 'info' || ev.type === 'error') toast(ev.message || '');
  }

  function renderStats(s) {
    const set = (id, v) => ($('#' + id).textContent = Number(v || 0).toLocaleString('id-ID'));
    set('sViewers', s.viewers); set('sFollows', s.follows); set('sLikes', s.likes);
    set('sCoins', s.coins); set('sShares', s.shares); set('sGuests', s.guests);
    const top = s.topGifters || [];
    $('#topGifters').innerHTML = top.length
      ? top.map((t, i) => `<div class="voice-item"><b style="color:var(--amber)">#${i + 1}</b><span>${esc(t.nickname)}</span><span class="tag">${t.coins} koin</span></div>`).join('')
      : '<div class="empty">Belum ada hadiah</div>';
  }

  // ---------------------------------------------------------------- server NEXA (fetch)
  function srv() { return (S.backend_url || 'http://127.0.0.1:5000').replace(/\/$/, ''); }
  async function api(path, opt = {}) {
    const headers = Object.assign({ 'X-Nexa-Token': S.backend_token || '' }, opt.json ? { 'Content-Type': 'application/json' } : {});
    const r = await fetch(srv() + path, { method: opt.method || (opt.json || opt.form ? 'POST' : 'GET'), headers, body: opt.json ? JSON.stringify(opt.json) : opt.form });
    const data = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(data.error || `HTTP ${r.status}`);
    return data;
  }
  const fileUrl = (name) => `${srv()}/files/${encodeURIComponent(name)}?token=${encodeURIComponent(S.backend_token || '')}`;

  async function uploadFile(file) {
    const fd = new FormData(); fd.append('file', file);
    return (await api('/upload', { form: fd })).file_id;
  }

  // ---------------------------------------------------------------- Studio: tab
  $$('#studioSeg button').forEach((b) => b.addEventListener('click', () => {
    $$('#studioSeg button').forEach((x) => x.classList.toggle('on', x === b));
    $$('#page-studio .subpage').forEach((s) => s.classList.toggle('on', s.id === 'sub-' + b.dataset.sub));
    if (b.dataset.sub === 'gallery') loadGallery();
    if (b.dataset.sub === 'voice') loadVoices();
  }));

  let vSrc = 'script';
  $$('#vSrcSeg button').forEach((b) => b.addEventListener('click', () => {
    vSrc = b.dataset.src;
    $$('#vSrcSeg button').forEach((x) => x.classList.toggle('on', x === b));
    $('#vScriptBox').style.display = vSrc === 'script' ? '' : 'none';
    $('#vTopicBox').style.display = vSrc === 'topic' ? '' : 'none';
    $('#vOwnBox').style.display = vSrc === 'own' ? '' : 'none';
    $('#vVoiceBox').style.display = vSrc === 'own' ? 'none' : '';
  }));

  // rekaman (Kotlin MediaRecorder → upload → event "uploaded")
  function recToggle(btn, purpose, statusEl) {
    if (recording === purpose) {
      A.recordStop(purpose); recording = null;
      btn.innerHTML = '⏺ Rekam'; $(statusEl).textContent = 'Mengunggah…';
    } else {
      if (recording) return toast('Sedang merekam yang lain');
      const r = A.recordStart();
      if (!r.startsWith('OK')) return toast(r);
      recording = purpose;
      btn.innerHTML = '<span class="rec"></span>Stop'; $(statusEl).textContent = 'Merekam… bicara dengan jelas';
    }
  }
  $('#vOwnRec').addEventListener('click', (e) => recToggle(e.currentTarget, 'own', '#vOwnStatus'));
  $('#cRec').addEventListener('click', (e) => recToggle(e.currentTarget, 'sample', '#cSampleStatus'));
  $('#cConsentRec').addEventListener('click', (e) => recToggle(e.currentTarget, 'consent', '#cConsentStatus'));

  async function pickUpload(input, key, statusEl) {
    const f = input.files[0]; if (!f) return;
    $(statusEl).textContent = 'Mengunggah ' + f.name + '…';
    try { upl[key] = await uploadFile(f); $(statusEl).textContent = '✓ ' + f.name; }
    catch (e) { $(statusEl).textContent = 'Gagal: ' + e.message; }
  }
  $('#vOwnFile').addEventListener('change', (e) => pickUpload(e.target, 'own', '#vOwnStatus'));
  $('#cFile').addEventListener('change', (e) => pickUpload(e.target, 'sample', '#cSampleStatus'));
  $('#cConsentFile').addEventListener('change', (e) => pickUpload(e.target, 'consent', '#cConsentStatus'));
  $('#vBgm').addEventListener('change', (e) => pickUpload(e.target, 'bgm', '#vBgmName'));
  $('#cEngine').addEventListener('change', (e) => ($('#cConsentBox').style.display = e.target.value === 'gemini' ? '' : 'none'));

  // ---------------------------------------------------------------- Studio: video
  $('#vMake').addEventListener('click', async () => {
    const req = { mode: $('#vMode').value, aspect: $('#vAspect').value, subtitles: $('#vSubs').checked, burn_subs: true };
    if (vSrc === 'script') { req.script = $('#vScript').value.trim(); if (!req.script) return toast('Naskah masih kosong'); }
    if (vSrc === 'topic') { req.topic = $('#vTopic').value.trim(); req.duration_min = Number($('#vDur').value || 1); if (!req.topic) return toast('Isi topik dulu'); }
    if (vSrc === 'own') { if (!upl.own) return toast('Rekam/pilih audio suara asli dulu'); req.voice = 'own'; req.own_audio_id = upl.own; }
    else req.voice = $('#vVoice').value;
    if (upl.bgm) req.bgm_id = upl.bgm;
    const btn = $('#vMake'); btn.disabled = true; btn.innerHTML = '<span class="spinner"></span> Mengirim…';
    try { await api('/video', { json: req }); toast('Video masuk antrean render 🎬'); loadJobs(); }
    catch (e) { toast('Gagal: ' + e.message, 4000); }
    btn.disabled = false; btn.innerHTML = '✨ Buat video';
  });

  let jobTimer = null;
  async function loadJobs() {
    try {
      const { jobs } = await api('/jobs');
      const box = $('#jobs');
      box.innerHTML = jobs.length ? '' : '<div class="empty">Belum ada pekerjaan</div>';
      jobs.forEach((j) => {
        const el = document.createElement('div');
        el.className = 'job';
        const st = j.status === 'done' ? '<span class="tag ok">SELESAI</span>' : j.status === 'error' ? '<span class="tag warn">GAGAL</span>' : '<span class="spinner"></span>';
        el.innerHTML = `<div class="row"><b style="flex:1">${esc(j.title)}</b>${st}</div>
          <div class="hint">${esc(j.message)}${j.duration ? ' · ' + j.duration + ' dtk' : ''}</div>
          ${j.status === 'running' ? `<div class="progress"><i style="width:${j.progress || 0}%"></i></div>` : ''}
          ${j.status === 'done' ? `<div class="row mt"><button class="btn sm" data-save="${esc(j.file)}">💾 Simpan ke Galeri</button><button class="btn ghost sm" data-play="${esc(j.file)}">▶ Putar</button></div>` : ''}`;
        box.appendChild(el);
      });
      $$('[data-save]', box).forEach((b) => b.addEventListener('click', () => { A.saveFromServer(b.dataset.save); toast('Menyimpan…'); }));
      $$('[data-play]', box).forEach((b) => b.addEventListener('click', () => A.openUrl(fileUrl(b.dataset.play))));
      clearTimeout(jobTimer);
      if (jobs.some((j) => j.status === 'running')) jobTimer = setTimeout(loadJobs, 5000);
    } catch (e) {
      $('#jobs').innerHTML = `<div class="empty">Server belum tersambung (${esc(e.message)})</div>`;
    }
  }
  $('#jobsRefresh').addEventListener('click', loadJobs);

  // ---------------------------------------------------------------- Studio: suara
  async function loadVoices() {
    try {
      const { voices } = await api('/voices');
      const sel = $('#vVoice'); const cur = sel.value || S.video_voice;
      sel.innerHTML = voices.map((v) => `<option value="${esc(v.id)}">${v.cloned ? '🧬 ' : ''}${esc(v.name)}</option>`).join('');
      if (cur && voices.some((v) => v.id === cur)) sel.value = cur;
      $('#voiceList').innerHTML = voices.map((v) => `<div class="voice-item"><span>${v.cloned ? '🧬' : '🔈'} ${esc(v.name)}<br><small class="hint" style="margin:0">${esc(v.id)}</small></span>
        <button class="btn ghost sm auto" data-vtest="${esc(v.id)}">▶</button>
        <button class="btn ghost sm auto" data-vdef="${esc(v.id)}">★</button>
        ${v.cloned ? `<button class="btn ghost sm auto" data-vdel="${esc(v.id)}">🗑</button>` : ''}</div>`).join('');
      $$('[data-vtest]').forEach((b) => b.addEventListener('click', () => testServerVoice(b.dataset.vtest)));
      $$('[data-vdef]').forEach((b) => b.addEventListener('click', () => { save({ video_voice: b.dataset.vdef, server_voice: b.dataset.vdef }); toast('Jadi suara default video & server ★'); }));
      $$('[data-vdel]').forEach((b) => b.addEventListener('click', async () => { await api('/voices/' + encodeURIComponent(b.dataset.vdel), { method: 'DELETE' }); loadVoices(); }));
    } catch (e) {
      $('#voiceList').innerHTML = `<div class="empty">Server belum tersambung (${esc(e.message)})</div>`;
    }
  }
  $('#voicesRefresh').addEventListener('click', loadVoices);

  async function testServerVoice(id) {
    try {
      toast('Membuat contoh suara…');
      const r = await fetch(srv() + '/tts', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Nexa-Token': S.backend_token || '' },
        body: JSON.stringify({ text: `Halo, ini suara ${S.assistant_name || 'Nexa'}. Siap membantu kamu hari ini!`, voice: id }) });
      if (!r.ok) throw new Error((await r.json().catch(() => ({}))).error || r.status);
      new Audio(URL.createObjectURL(await r.blob())).play();
    } catch (e) { toast('Gagal: ' + e.message, 4000); }
  }

  $('#cMake').addEventListener('click', async () => {
    if (!$('#cAgree').checked) return toast('Centang pernyataan izin pemilik suara dulu');
    if (!upl.sample) return toast('Rekam atau pilih sampel suara dulu');
    const engine = $('#cEngine').value;
    if (engine === 'gemini' && !upl.consent) return toast('Gemini butuh rekaman persetujuan');
    const fd = new FormData();
    fd.append('name', $('#cName').value || 'Suara Saya'); fd.append('engine', engine); fd.append('agree', '1');
    fd.append('sample_id', upl.sample); if (upl.consent) fd.append('consent_id', upl.consent);
    const btn = $('#cMake'); btn.disabled = true; btn.innerHTML = '<span class="spinner"></span> Mengkloning…';
    try { const r = await api('/voices/clone', { form: fd }); toast('Suara siap: ' + r.name + ' 🧬', 4000); loadVoices(); }
    catch (e) { toast('Gagal: ' + e.message, 5000); }
    btn.disabled = false; btn.innerHTML = '🧬 Kloning sekarang';
  });

  // ---------------------------------------------------------------- Studio: gambar & galeri
  $('#imgMake').addEventListener('click', () => {
    const p = $('#imgPrompt').value.trim(); if (!p) return toast('Tulis deskripsi gambar');
    $('#imgMake').disabled = true; $('#imgOut').innerHTML = '<span class="spinner"></span> Membuat gambar…';
    A.generateImage(p, $('#imgEnhance').checked);
  });

  async function loadGallery() {
    try {
      const { items } = await api('/gallery');
      $('#gallery').innerHTML = items.length ? items.map((it) => `<div class="it">${it.type === 'video'
        ? `<video src="${fileUrl(it.file)}" preload="metadata" controls playsinline></video>`
        : `<img src="${fileUrl(it.file)}" loading="lazy">`}<button class="btn sm" data-save="${esc(it.file)}">💾</button></div>`).join('')
        : '<div class="empty" style="grid-column:1/-1">Belum ada hasil</div>';
      $$('#gallery [data-save]').forEach((b) => b.addEventListener('click', () => { A.saveFromServer(b.dataset.save); toast('Menyimpan ke Galeri…'); }));
    } catch (e) { $('#gallery').innerHTML = `<div class="empty" style="grid-column:1/-1">Server belum tersambung (${esc(e.message)})</div>`; }
  }
  $('#galRefresh').addEventListener('click', loadGallery);

  // ---------------------------------------------------------------- Kontrol
  $('#volRange').addEventListener('change', (e) => toast(A.quick('volume', e.target.value).replace(/^OK: /, '')));
  $('#alarmSet').addEventListener('click', () => toast(A.quick('alarm', $('#alarmTime').value || '06:00')));
  $('#timerSet').addEventListener('click', () => toast(A.quick('timer', String(Math.round(Number($('#timerMin').value || 1) * 60)))));
  let appsCache = null;
  function renderApps() {
    if (!appsCache) { try { appsCache = JSON.parse(A.apps()); } catch (e) { appsCache = []; } }
    const q = $('#appSearch').value.toLowerCase();
    $('#appList').innerHTML = appsCache.filter((a) => a.name.toLowerCase().includes(q)).slice(0, 60)
      .map((a) => `<div class="a" data-app="${esc(a.pkg)}"><span>${esc(a.name)}</span><span>›</span></div>`).join('');
    $$('[data-app]').forEach((el) => el.addEventListener('click', () => toast(A.quick('open_app', el.dataset.app))));
  }
  $('#appSearch').addEventListener('input', renderApps);

  function getNotes() { try { return JSON.parse(A.notes()); } catch (e) { return []; } }
  function renderNotes() {
    const n = getNotes();
    $('#notes').innerHTML = n.length ? n.map((x, i) => `<div class="voice-item"><span>${esc(x.text)}<br><small class="hint" style="margin:0">${esc(x.time)}</small></span><button class="btn ghost sm auto" data-ndel="${i}">✓</button></div>`).reverse().join('')
      : '<div class="empty">Belum ada catatan</div>';
    $$('[data-ndel]').forEach((b) => b.addEventListener('click', () => { const arr = getNotes(); arr.splice(Number(b.dataset.ndel), 1); A.saveNotes(JSON.stringify(arr)); renderNotes(); }));
  }
  $('#noteAdd').addEventListener('click', () => {
    const t = $('#noteInput').value.trim(); if (!t) return;
    const arr = getNotes();
    arr.push({ time: new Date().toLocaleString('id-ID', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }), text: t });
    A.saveNotes(JSON.stringify(arr)); $('#noteInput').value = ''; renderNotes();
  });

  // ---------------------------------------------------------------- Pengaturan: tes
  $('#testVoice').addEventListener('click', () => { save(collect($('#page-settings'))); A.testVoice(`Halo, aku ${S.assistant_name || 'Nexa'}. Ada yang bisa kubantu?`, ''); });
  $('#pingServer').addEventListener('click', async () => {
    save(collect($('#page-settings')));
    const out = $('#pingOut'); out.style.display = ''; out.textContent = 'Mengecek…';
    const lines = [];
    try { const r = await fetch(srv() + '/health'); lines.push('Server media: ' + JSON.stringify(await r.json())); }
    catch (e) { lines.push('Server media: TIDAK TERJANGKAU (' + e.message + ')'); }
    try { const u = (S.tt_bridge || '').replace(/^ws/, 'http'); const r = await fetch(u + '/health'); lines.push('TikTok bridge: ' + JSON.stringify(await r.json())); }
    catch (e) { lines.push('TikTok bridge: TIDAK TERJANGKAU (' + e.message + ')'); }
    out.textContent = lines.join('\n');
  });

  // ---------------------------------------------------------------- event dari Kotlin
  window.NEXA = {
    onEvent(raw) {
      let ev; try { ev = typeof raw === 'string' ? JSON.parse(raw) : raw; } catch (e) { return; }
      const d = ev.data || {};
      switch (ev.type) {
        case 'status': status = d; renderStatus(); break;
        case 'log': onLog(ev.text || ''); break;
        case 'live': status.live = ev.on; setLive(ev.on); break;
        case 'tiktok': onTikTok(d); break;
        case 'dictation': if (ev.text) sendChat(ev.text); break;
        case 'toast': toast(d.text || ''); break;
        case 'busy': toast(d.text || '…'); break;
        case 'image_done':
          $('#imgMake').disabled = false; $('#imgOut').textContent = (d.result || '').replace(/^OK: /, '✓ ');
          toast((d.result || '').startsWith('OK') ? 'Gambar tersimpan di Galeri 🖼️' : d.result, 4000); break;
        case 'uploaded': {
          const map = { own: '#vOwnStatus', sample: '#cSampleStatus', consent: '#cConsentStatus' };
          upl[d.purpose] = d.file_id;
          if (map[d.purpose]) $(map[d.purpose]).textContent = '✓ Rekaman siap (' + d.file_id + ')';
          break;
        }
      }
    },
    back() { if (page !== 'home') { go('home'); return true; } return false; },
  };

  // ---------------------------------------------------------------- mulai
  loadSettings();
  try { status = JSON.parse(A.getStatus()); } catch (e) { status = {}; }
  renderStatus();
  addChat('sys', 'Halo! Aku ' + (S.assistant_name || 'Nexa') + '. Ketik atau tekan 🎙️ untuk mulai.');

  // ---------------------------------------------------------------- tiruan untuk pratinjau browser
  function mockBridge() {
    const st = { gemini_key: '', assistant_name: 'Nexa', wake_words: 'nexa', live_voice: 'Kore', live_idle_sec: 45, tt_rate: 1.05,
      tt_on_follow: true, tt_on_like: true, tt_on_gift: true, tt_on_share: true, tt_on_guest: true,
      tt_tpl_follow: 'Terima kasih {nama} sudah follow!', tt_tpl_like: 'Makasih {nama} tap-tapnya!', tt_tpl_gift: 'Terima kasih {nama} untuk {jumlah} {hadiah}!',
      tt_tpl_share: 'Makasih {nama} sudah share!', tt_tpl_guest: '{nama} ingin naik live.', tt_tpl_join: 'Halo {nama}!', tt_tpl_chat: '{nama} bilang {komentar}',
      backend_url: 'http://127.0.0.1:5000', tt_bridge: 'ws://127.0.0.1:8090', tt_min_coins: 1, tt_like_min: 15, tt_cooldown_sec: 30 };
    const noop = () => 'OK';
    return { getSettings: () => JSON.stringify(st), saveSettings: noop, getStatus: () => JSON.stringify({ apiKey: true, mic: true, accessibility: true, notifications: false, phone: false, battery: false, version: '2.0.0' }),
      openSetting: noop, requestPermissions: noop, toggleLive: () => window.NEXA.onEvent(JSON.stringify({ type: 'live', on: !$('#orb').classList.contains('live') })),
      setWake: noop, sendText: noop, stopAgent: noop, dictate: noop, testVoice: noop, quick: () => 'OK: contoh', apps: () => '[{"name":"YouTube","pkg":"x"},{"name":"WhatsApp","pkg":"y"}]',
      notes: () => '[]', saveNotes: noop, tiktokConnect: () => 'OK', tiktokDisconnect: noop, tiktokTest: noop, tiktokPause: noop, tiktokStats: () => '',
      generateImage: noop, saveFromServer: noop, recordStart: () => 'OK', recordStop: noop, openUrl: noop };
  }
})();
