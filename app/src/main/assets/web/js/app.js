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
    if (window.NEXA_ONGO) window.NEXA_ONGO(p);
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
    syncGeminiAuto();
    // Kunci tertanam otomatis → tampilkan status, kolom boleh kosong
    // Server NEXA aktif → kolom kunci disembunyikan (semua kunci diatur dari Termux)
    [['gemini_key', 'AIza…'], ['fish_key', '…'], ['tt_api_key', 'dari eulerstream.com atau tik.tools']].forEach(([k, ph]) => {
      const el = $(`[data-pref="${k}"]`);
      if (!el) return;
      el.placeholder = S['builtin_' + k] ? '✓ Terpasang otomatis' : ph;
      const hide = !!S.server_active;
      el.style.display = hide ? 'none' : '';
      const lab = el.previousElementSibling;
      if (lab && lab.tagName === 'LABEL') lab.style.display = hide ? 'none' : '';
      const hint = el.nextElementSibling;
      if (hint && hint.classList.contains('hint') && k !== 'gemini_key') hint.style.display = hide ? 'none' : '';
    });
    const sh = $('#serverHint');
    if (sh) sh.innerHTML = S.server_active
      ? '🌐 <b>Server NEXA aktif</b> — semua kunci API (Gemini, Fish Audio, TikTok) diatur dari Termux. Tidak perlu isi apa pun di sini.'
      : '🔐 Mode mandiri — kunci API tertanam di aplikasi (diatur pembuat lewat Termux: <b>bash tools/setup_keys.sh</b>). Isi kolom di bawah hanya untuk mengganti kunci di HP ini.';
  }

  // ---------------------------------------------------------------- suara Gemini otomatis (satu saklar)
  function syncGeminiAuto() {
    const on = S.gemini_voice_auto !== false;
    $$('.gauto').forEach((c) => (c.checked = on));
    $$('.manual-voice').forEach((el) => (el.style.display = on ? 'none' : ''));
    const hint = $('#gAutoHint');
    const hasGemini = S.gemini_key || S.builtin_gemini_key || S.server_active;
    const curSpec = S.voice_spec || ('gemini:' + (S.gemini_voice || 'Kore'));
    if ($('#gVoice') && !S.voice_spec) $('#gVoice').value = curSpec;
    if ($('#gLiveVoice')) $('#gLiveVoice').value = S.live_voice_spec || ('gemini:' + (S.gemini_voice || 'Kore'));
    if (hint) hint.innerHTML = !hasGemini ? '⚠️ Isi <b>Gemini API key</b> dulu di atas agar suara Gemini bisa dipakai.'
      : on ? `Aktif ✓ — suara asisten <b>${esc(curSpec.split(':')[1] || curSpec)}</b>, suara Live <b>${esc((S.live_voice_spec || 'gemini:' + (S.gemini_voice || 'Kore')).split(':')[1])}</b>.` : 'Nonaktif — suara diatur manual di bawah.';
  }
  $$('.gauto').forEach((c) => c.addEventListener('change', (e) => {
    save({ gemini_voice_auto: e.target.checked });
    syncGeminiAuto();
    toast(e.target.checked ? 'Suara Gemini otomatis aktif ✓' : 'Suara Gemini otomatis dimatikan');
  }));
  $('#gVoice').addEventListener('change', (e) => {
    const v = e.target.value, part = { voice_spec: v };
    if (v.startsWith('gemini:')) part.tts_voice = v.slice(7);
    save(part); syncGeminiAuto(); toast(`Suara: ${v.split(':')[1] || v} ✓`);
  });
  $('#gLiveVoice').addEventListener('change', (e) => {
    const v = e.target.value, part = { live_voice_spec: v };
    if (v.startsWith('gemini:')) part.gemini_voice = v.slice(7);
    save(part);
    toast(`Suara Live: ${v.split(':')[1]} ✓` + (v.startsWith('gemini:') ? '' : ' (otak Gemini + suara pilihanmu)') + (status.live ? ' — matikan & nyalakan Live lagi' : ''));
  });
  $('#gVoiceNew').addEventListener('click', () => {
    go('studio');
    const b = $('[data-sub="voice"]'); if (b) b.click();
    toast('Rekam/unggah sampel suara lalu tekan Kloning 🧬', 4000);
  });
  $('#checkKeys').addEventListener('click', () => { $('#keysResult').innerHTML = '<div class="hint">Mengecek…</div>'; A.checkKeys(); });
  function renderKeys(d) {
    const names = d.server ? { server: 'Server NEXA', gemini: 'Gemini (AI Studio)', fish: 'Fish Audio', tiktok: 'TikTok LIVE' }
      : { gemini: 'Gemini (AI Studio)', fish: 'Fish Audio', tiktok: 'TikTok LIVE' };
    $('#keysResult').innerHTML = Object.keys(names).map((k) => {
      const v = d[k] || 'kosong';
      const ic = v === 'ok' ? '✅' : v === 'kosong' ? '⚪' : '❌';
      const txt = v === 'ok' ? 'tersambung' : v === 'kosong' ? 'belum ada kunci' : esc(v);
      return `<div class="hint" style="margin:4px 0">${ic} <b>${names[k]}</b> — ${txt}</div>`;
    }).join('');
  }
  $('#gVoiceTest').addEventListener('click', () => {
    save(collect($('#page-settings')));
    toast('Membuat contoh suara…');
    A.previewVoice($('#gVoice').value || 'gemini:Kore', `Halo, aku ${S.assistant_name || 'Nexa'}! Gimana kabarnya hari ini? Aku siap bantu kamu kapan aja.`);
  });

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

  // ---------------------------------------------------------------- Studio: tab (semua diproses di HP)
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

  // rekaman (Kotlin MediaRecorder) & pilih file → event "uploaded" {purpose, path}
  const STATUS_EL = { own: '#vOwnStatus', sample: '#cSampleStatus', consent: '#cConsentStatus', bgm: '#vBgmName' };
  function recToggle(btn, purpose) {
    if (recording === purpose) {
      A.recordStop(purpose); recording = null;
      btn.innerHTML = '⏺ Rekam';
    } else {
      if (recording) return toast('Sedang merekam yang lain');
      const r = A.recordStart();
      if (!r.startsWith('OK')) return toast(r);
      recording = purpose;
      btn.innerHTML = '<span class="rec"></span>Stop'; $(STATUS_EL[purpose]).textContent = 'Merekam… bicara dengan jelas';
    }
  }
  $('#vOwnRec').addEventListener('click', (e) => recToggle(e.currentTarget, 'own'));
  $('#cRec').addEventListener('click', (e) => recToggle(e.currentTarget, 'sample'));
  $('#cConsentRec').addEventListener('click', (e) => recToggle(e.currentTarget, 'consent'));
  $('#vOwnFile').addEventListener('click', () => A.pickAudio('own'));
  $('#vOwnVideo').addEventListener('click', () => (A.pickVideoAudio ? A.pickVideoAudio('own') : A.pickAudio('own')));
  $('#cFile').addEventListener('click', () => A.pickAudio('sample'));
  $('#cVideo').addEventListener('click', () => (A.pickVideoAudio ? A.pickVideoAudio('sample') : A.pickAudio('sample')));
  $('#cConsentFile').addEventListener('click', () => A.pickAudio('consent'));
  $('#vBgm').addEventListener('click', () => A.pickAudio('bgm'));
  $('#cEngine').addEventListener('change', (e) => ($('#cConsentBox').style.display = e.target.value === 'gemini' ? '' : 'none'));

  // ---------------------------------------------------------------- Studio: banyak tokoh
  let VOICES = [];
  let CHARS = [{ name: 'Narator', voice: '', style: '' }, { name: '', voice: '', style: '' }];
  const STYLES = [['', 'Gaya default'], ['ceria, bersemangat, energik', 'Ceria'], ['tenang, lembut, menenangkan', 'Lembut'],
    ['marah, tegas, penuh tekanan', 'Marah/tegas'], ['sedih, pelan, bergetar', 'Sedih'], ['lucu, kocak, banyak tertawa', 'Kocak'],
    ['berbisik, misterius', 'Misterius'], ['dramatis, penuh emosi, seperti narator film', 'Dramatis'], ['anak kecil yang polos dan ceria', 'Anak kecil'],
    ['orang tua yang bijak dan pelan', 'Orang tua']];
  function voiceOpts(sel) {
    const list = VOICES.length ? VOICES : [{ id: 'gemini:Kore', name: 'Gemini Kore' }];
    return list.map((v) => `<option value="${esc(v.id)}" ${v.id === sel ? 'selected' : ''}>${v.cloned ? '🧬 ' : ''}${esc(v.name)}</option>`).join('');
  }
  function renderChars() {
    const box = $('#vCharList'); if (!box) return;
    box.innerHTML = CHARS.map((c, i) => `<div class="char-row" data-ci="${i}">
      <input type="text" class="cn" value="${esc(c.name)}" placeholder="Nama tokoh">
      <select class="cv">${voiceOpts(c.voice)}</select>
      <select class="cs">${STYLES.map(([v, t]) => `<option value="${esc(v)}" ${v === c.style ? 'selected' : ''}>${t}</option>`).join('')}</select>
      <button class="btn ghost sm" data-cdel="${i}" aria-label="Hapus tokoh">✕</button></div>`).join('');
    $$('.char-row', box).forEach((row) => {
      const i = Number(row.dataset.ci);
      row.querySelector('.cn').addEventListener('input', (e) => (CHARS[i].name = e.target.value));
      row.querySelector('.cv').addEventListener('change', (e) => (CHARS[i].voice = e.target.value));
      row.querySelector('.cs').addEventListener('change', (e) => (CHARS[i].style = e.target.value));
    });
    $$('[data-cdel]', box).forEach((b) => b.addEventListener('click', () => { CHARS.splice(Number(b.dataset.cdel), 1); renderChars(); }));
  }
  $('#vMulti').addEventListener('change', (e) => { $('#vChars').style.display = e.target.checked ? '' : 'none'; renderChars(); });
  $('#vCharAdd').addEventListener('click', () => { CHARS.push({ name: '', voice: '', style: '' }); renderChars(); });

  // ---------------------------------------------------------------- Studio: video
  $('#vMake').addEventListener('click', () => {
    const req = { mode: $('#vMode').value, aspect: $('#vAspect').value, subtitles: $('#vSubs').checked };
    if (vSrc === 'script') { req.script = $('#vScript').value.trim(); if (!req.script) return toast('Naskah masih kosong'); }
    if (vSrc === 'topic') { req.topic = $('#vTopic').value.trim(); req.duration_min = Number($('#vDur').value || 1); if (!req.topic) return toast('Isi topik dulu'); }
    if (vSrc === 'own') { if (!upl.own) return toast('Rekam/pilih audio suara asli dulu'); req.voice = 'own'; req.own_audio = upl.own; }
    else req.voice = $('#vVoice').value;
    if (upl.bgm) req.bgm = upl.bgm;
    if ($('#vMulti').checked && vSrc !== 'own') {
      req.characters = CHARS.filter((c) => c.name.trim()).map((c) => ({ name: c.name.trim(), voice: c.voice || $('#vVoice').value, style: c.style }));
      if (!req.characters.length) return toast('Isi nama tokoh dulu');
    }
    const r = A.makeVideo(JSON.stringify(req));
    if (r.startsWith('OK')) { toast('Video mulai dibuat 🎬 — boleh tinggalkan aplikasi'); loadJobs(); }
    else toast(r, 4000);
  });

  function renderJob(j) {
    const st = j.status === 'done' ? '<span class="tag ok">SELESAI</span>' : j.status === 'error' ? '<span class="tag warn">GAGAL</span>' : '<span class="spinner"></span>';
    return `<div class="job" data-job="${esc(j.id)}"><div class="row"><b style="flex:1">${esc(j.title)}</b>${st}</div>
      <div class="hint">${esc(j.message)}${j.duration ? ' · ' + j.duration + ' dtk' : ''}</div>
      ${j.status === 'running' ? `<div class="progress"><i style="width:${j.progress || 0}%"></i></div><div class="row mt"><button class="btn ghost sm" data-cancel="${esc(j.id)}">✕ Batalkan</button></div>` : ''}
      ${j.status === 'done' ? `<video src="${mediaUrl(j.file)}" controls playsinline preload="metadata" style="width:100%;border-radius:12px;margin-top:10px;max-height:420px;background:#000"></video>` : ''}</div>`;
  }
  function loadJobs() {
    let jobs = [];
    try { jobs = JSON.parse(A.jobs()); } catch (e) { jobs = []; }
    const box = $('#jobs');
    box.innerHTML = jobs.length ? jobs.map(renderJob).join('') : '<div class="empty">Belum ada pekerjaan</div>';
    $$('[data-cancel]', box).forEach((b) => b.addEventListener('click', () => { A.cancelJob(b.dataset.cancel); toast('Dibatalkan'); }));
  }
  function onJob(j) {
    const el = document.querySelector(`[data-job="${j.id}"]`);
    if (!el) return loadJobs();
    if (j.status !== 'running') return loadJobs();
    const bar = el.querySelector('.progress i'); if (bar) bar.style.width = (j.progress || 0) + '%';
    const h = el.querySelector('.hint'); if (h) h.textContent = j.message || '';
  }
  $('#jobsRefresh').addEventListener('click', loadJobs);
  const mediaUrl = (name) => '/media/' + encodeURIComponent(name);

  // ---------------------------------------------------------------- Studio: suara
  function loadVoices() {
    let voices = [];
    try { voices = JSON.parse(A.voices()); } catch (e) { voices = []; }
    const sel = $('#vVoice'); const cur = sel.value || (S.gemini_voice_auto !== false ? (S.voice_spec || 'gemini:' + (S.gemini_voice || 'Kore')) : S.video_voice);
    VOICES = voices;
    const cl = $('#gVoiceClones');
    if (cl) {
      cl.innerHTML = voices.filter((v) => v.cloned).map((v) => `<option value="${esc(v.id)}">🧬 ${esc(v.name)}</option>`).join('');
      const lc = $('#gLiveClones');
      if (lc) lc.innerHTML = voices.filter((v) => v.cloned && !v.id.startsWith('gemini:'))
        .map((v) => `<option value="${esc(v.id)}">🧬 ${esc(v.name)}</option>`).join('');
      if ($('#gLiveVoice')) $('#gLiveVoice').value = S.live_voice_spec || ('gemini:' + (S.gemini_voice || 'Kore'));
      if (S.voice_spec) $('#gVoice').value = S.voice_spec;
    }
    renderChars();
    sel.innerHTML = voices.map((v) => `<option value="${esc(v.id)}">${v.cloned ? '🧬 ' : ''}${esc(v.name)}</option>`).join('');
    if (cur && voices.some((v) => v.id === cur)) sel.value = cur;
    $('#voiceList').innerHTML = voices.map((v) => `<div class="voice-item"><span>${v.cloned ? '🧬' : '🔈'} ${esc(v.name)}<br><small class="hint" style="margin:0">${esc(v.id)}</small></span>
      <button class="btn ghost sm auto" data-vtest="${esc(v.id)}">▶</button>
      <button class="btn ghost sm auto" data-vdef="${esc(v.id)}">★</button>
      ${v.cloned ? `<button class="btn ghost sm auto" data-vdel="${esc(v.id)}">🗑</button>` : ''}</div>`).join('') || '<div class="empty">Belum ada suara</div>';
    $$('[data-vtest]').forEach((b) => b.addEventListener('click', () => { toast('Membuat contoh suara…'); A.previewVoice(b.dataset.vtest, `Halo, ini suara ${S.assistant_name || 'Nexa'}. Siap membantu kamu hari ini!`); }));
    $$('[data-vdef]').forEach((b) => b.addEventListener('click', () => {
      const id = b.dataset.vdef, part = { video_voice: id };
      if (id.startsWith('fish:')) Object.assign(part, { fish_voice: id.slice(5), voice_engine: 'fish', gemini_voice_auto: false });
      if (id.startsWith('gemini:')) Object.assign(part, { tts_voice: id.slice(7), voice_engine: 'gemini', gemini_voice: id.slice(7) });
      if (S.gemini_voice_auto !== false) { part.voice_spec = id; delete part.gemini_voice_auto; delete part.voice_engine; }
      save(part); syncGeminiAuto(); toast('Jadi suara default video & asisten ★');
    }));
    $$('[data-vdel]').forEach((b) => b.addEventListener('click', () => { A.deleteVoice(b.dataset.vdel); loadVoices(); }));
  }
  $('#voicesRefresh').addEventListener('click', loadVoices);

  $('#cMake').addEventListener('click', () => {
    if (!$('#cAgree').checked) return toast('Centang pernyataan izin pemilik suara dulu');
    if (!upl.sample) return toast('Rekam atau pilih sampel suara dulu');
    const engine = $('#cEngine').value;
    if (engine === 'gemini' && !upl.consent) return toast('Gemini butuh rekaman persetujuan');
    const btn = $('#cMake'); btn.disabled = true; btn.innerHTML = '<span class="spinner"></span> Mengkloning…';
    A.cloneVoice(JSON.stringify({ name: $('#cName').value || 'Suara Saya', engine, sample: upl.sample, consent: upl.consent || '', agree: true }));
  });

  // ---------------------------------------------------------------- Studio: gambar & galeri
  $('#imgMake').addEventListener('click', () => {
    const p = $('#imgPrompt').value.trim(); if (!p) return toast('Tulis deskripsi gambar');
    $('#imgMake').disabled = true; $('#imgOut').innerHTML = '<span class="spinner"></span> Membuat gambar…';
    A.generateImage(p, $('#imgEnhance').checked);
  });

  function loadGallery() {
    let items = [];
    try { items = JSON.parse(A.gallery()); } catch (e) { items = []; }
    $('#gallery').innerHTML = items.length ? items.map((it) => `<div class="it">${it.type === 'video'
      ? `<video src="${mediaUrl(it.file)}" preload="metadata" controls playsinline></video>`
      : `<img src="${mediaUrl(it.file)}" loading="lazy">`}<button class="btn ghost sm" data-mdel="${esc(it.file)}">🗑</button></div>`).join('')
      : '<div class="empty" style="grid-column:1/-1">Belum ada hasil</div>';
    $$('#gallery [data-mdel]').forEach((b) => b.addEventListener('click', () => { A.deleteMedia(b.dataset.mdel); loadGallery(); }));
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
        case 'keys': renderKeys(d); break;
        case 'api': if (window.NEXA_EXT) window.NEXA_EXT(ev); break;
        case 'busy': toast(d.text || '…'); break;
        case 'image_done':
          $('#imgMake').disabled = false; $('#imgOut').textContent = (d.result || '').replace(/^OK: /, '✓ ');
          toast((d.result || '').startsWith('OK') ? 'Gambar tersimpan di Galeri 🖼️' : d.result, 4000); break;
        case 'uploaded':
          upl[d.purpose] = d.path;
          if (STATUS_EL[d.purpose]) $(STATUS_EL[d.purpose]).textContent = '✓ ' + (d.name || 'siap');
          break;
        case 'cloned':
          $('#cMake').disabled = false; $('#cMake').innerHTML = '🧬 Kloning sekarang';
          if (d.error) toast('Gagal: ' + d.error, 5000); else { toast('Suara siap: ' + d.name + ' 🧬', 4000); loadVoices(); }
          break;
        case 'job': onJob(d); break;
        default: if (window.NEXA_EXT) window.NEXA_EXT(ev);
      }
    },
    back() { if (page !== 'home') { go('home'); return true; } return false; },
  };

  // ---------------------------------------------------------------- untuk js/account.js
  window.NEXA_UI = { A, $, $$, esc, toast, go, save, collect, loadSettings, sendChat: (t) => sendChat(t), get S() { return S; }, get page() { return page; } };

  // ---------------------------------------------------------------- mulai
  loadSettings();
  try { status = JSON.parse(A.getStatus()); } catch (e) { status = {}; }
  renderStatus();
  addChat('sys', 'Halo! Aku ' + (S.assistant_name || 'Nexa') + '. Ketik atau tekan 🎙️ untuk mulai.');

  // ---------------------------------------------------------------- tiruan untuk pratinjau browser
  function mockBridge() {
    const st = { gemini_key: '', assistant_name: 'Nexa', wake_words: 'nexa', live_voice: 'Kore', gemini_voice_auto: true, gemini_voice: 'Kore', live_idle_sec: 45, tt_rate: 1.05,
      tt_on_follow: true, tt_on_like: true, tt_on_gift: true, tt_on_share: true, tt_on_guest: true,
      tt_tpl_follow: 'Terima kasih {nama} sudah follow!', tt_tpl_like: 'Makasih {nama} tap-tapnya!', tt_tpl_gift: 'Terima kasih {nama} untuk {jumlah} {hadiah}!',
      tt_tpl_share: 'Makasih {nama} sudah share!', tt_tpl_guest: '{nama} ingin naik live.', tt_tpl_join: 'Halo {nama}!', tt_tpl_chat: '{nama} bilang {komentar}',
      backend_url: 'http://127.0.0.1:5000', tt_bridge: 'ws://127.0.0.1:8090', tt_min_coins: 1, tt_like_min: 15, tt_cooldown_sec: 30 };
    const noop = () => 'OK';
    return { getSettings: () => JSON.stringify(st), saveSettings: noop, getStatus: () => JSON.stringify({ apiKey: true, mic: true, accessibility: true, notifications: false, phone: false, battery: false, version: '2.0.0' }),
      openSetting: noop, requestPermissions: noop, toggleLive: () => window.NEXA.onEvent(JSON.stringify({ type: 'live', on: !$('#orb').classList.contains('live') })),
      setWake: noop, sendText: noop, stopAgent: noop, dictate: noop, testVoice: noop, quick: () => 'OK: contoh', apps: () => '[{"name":"YouTube","pkg":"x"},{"name":"WhatsApp","pkg":"y"}]',
      notes: () => '[]', saveNotes: noop, tiktokConnect: () => 'OK', tiktokDisconnect: noop, tiktokTest: noop, tiktokPause: noop, tiktokStats: () => '',
      generateImage: noop, recordStart: () => 'OK', recordStop: noop, openUrl: noop, pickAudio: noop, makeVideo: () => 'OK:x', jobs: () => '[]', cancelJob: noop,
      gallery: () => '[]', deleteMedia: noop, voices: () => '[{"id":"gemini:Kore","name":"Gemini Kore"}]', deleteVoice: noop, cloneVoice: noop, previewVoice: noop, checkKeys: noop, pickVideoAudio: noop, permStatus: () => '{"overlay":false,"mic":true}',
      deviceId: () => 'A1B2C3D4E5F6', liveStart: noop, api: (id) => setTimeout(() => window.NEXA.onEvent({ type: 'api', data: { id, ok: false, status: 0, data: { error: 'pratinjau' } } }), 50) };
  }
})();
