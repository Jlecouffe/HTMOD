/* HTMod web — demarrage, etat en direct (Server-Sent Events), colonne de gauche, onglets, menus. */
'use strict';

const TABS = [['CARTE', 'pin'], ['APRS', 'list'], ['DÉCODAGES', 'target'], ['BANDSCOPE', 'bars'], ['POCSAG', 'pager'], ['SSTV', 'picture'],
  ['VFO', 'ab'], ['SATELLITES', 'globe'], ['JOURNAL', 'wave']];
const SECTIONS = ['radio', 'options', 'counters', 'spec', 'aprs', 'aprsTx', 'det', 'markers', 'log', 'bs', 'pocsag', 'sstv', 'vfo', 'sats'];
const tabKeys = {}, views = {};
let currentTab = null;

/* ---------------------------------------------------------------- onglets */
function buildTabs() {
  const bar = $('#tabs'), host = $('#views');
  for (const [name, ic] of TABS) {
    const k = key(name, () => selectTab(name), { icon: ic, cls: 'tab' });
    tabKeys[name] = k;
    bar.append(k);
    const v = h('div', { class: 'view' });
    views[name] = v;
    host.append(v);
    VIEWS[name](v);
  }
}
function selectTab(name) {
  if (!views[name]) name = 'CARTE';
  currentTab = name;
  for (const n in views) { views[n].classList.toggle('on', n === name); tabKeys[n].setSel(n === name); }
  try { localStorage.setItem('htmod.tab', name); } catch (e) {}
  const v = views[name];
  if (v.onShow) requestAnimationFrame(() => v.onShow());
  tabKeys[name].scrollIntoView({ block: 'nearest', inline: 'nearest' });
  document.body.classList.remove('drawer');
}

/* ---------------------------------------------------------------- colonne de gauche (App.sideColumn) */
function buildSide() {
  const side = $('#side');
  // carte RADIO
  const model = h('div', { class: 'model' }, 'Aucun poste'), link = chip();
  const spec = h('canvas'), device = h('div', { class: 'device', title: 'Choisir le poste parmi les appareils Bluetooth appairés' }, 'Poste ›');
  const connect = key('SE CONNECTER', () => cmd('connect'), { primary: true });
  connect.style.width = '150px'; connect.style.height = '42px';
  const status = h('div', { class: 'status' }, 'Déconnecté');
  device.addEventListener('click', () => devicesMenu(device));
  side.append(h('div', { class: 'card r18' },
    h('div', { class: 'radio-top' }, h('div', null, label('RADIO'), model), link),
    h('div', { class: 'well spec-well' }, spec),
    h('div', { class: 'radio-bottom' }, device, connect), status));
  // reception
  const channel = h('div', { class: 'channel' }, '–'), mode = chip();
  side.append(section('Réception'), h('div', { class: 'card' }, label('CANAL'), channel, mode));
  // mode de decodage
  const kAuto = key('AUTO', () => cmd('forced', { v: 0 }), { title: 'Décode quand le canal en réception s\'appelle SARSAT ou Radiosonde' });
  const kSarsat = key('SARSAT', () => cmd('forced', { v: 1 }), { sel: C.red, title: 'Décoder l\'audio en SARSAT quel que soit le canal' });
  const kSonde = key('SONDE', () => cmd('forced', { v: 2 }), { sel: C.green, title: 'Décoder l\'audio en radiosonde quel que soit le canal' });
  side.append(section('Décodage SARSAT / sondes'), h('div', { class: 'mode-keys' }, kAuto, kSarsat, kSonde));
  // options
  const opt = (name, text) => toggle(text, false, v => {
    if (name === 'web' && !v) {
      confirmBox('Serveur web', 'Arrêter le serveur web ? Cette page ne sera plus accessible.', 'ARRÊTER').then(ok => { if (ok) cmd('option', { name, on: 0 }); else sw.web.set(true); });
      return;
    }
    cmd('option', { name, on: v ? 1 : 0 });
  });
  const sw = { listen: opt('listen', 'Écouter sur le PC'), muteAprs: opt('muteAprs', 'Couper le son des trames APRS'), wav: opt('wav', 'Enregistrer les WAV'),
    web: opt('web', 'Serveur web (réseau local)') };
  const browser = toggle('Écouter dans ce navigateur', false, v => setAudio(v));
  audioUi.push(on => browser.set(on));
  side.append(section('Options'), h('div', { class: 'card options' }, sw.listen, sw.muteAprs, sw.wav, sw.web,
    h('div', { class: 'web-info' }, location.host + ' · interface web'), browser));
  // compteurs
  const cnt = ['APRS', 'SONDES', 'SARSAT'].map(n => { const b = h('b', null, '0'); return { el: h('div', { class: 'counter' }, b, h('span', null, n)), b }; });
  side.append(section('Station'), h('div', { class: 'counters' }, cnt.map(c => c.el)));

  onState('radio', d => {
    setText(model, d.model);
    link.set(d.link, d.linkDot, d.linkFg);
    setText(device, d.device);
    connect.setText(d.connectKey);
    setText(status, d.status);
    setText(channel, d.channel); channel.style.color = d.channelColor;
    mode.set(d.mode, d.modeDot, d.modeFg);
    kAuto.setSel(d.forced === 0); kSarsat.setSel(d.forced === 1); kSonde.setSel(d.forced === 2);
    const st = $('#connState');
    setText(st, d.running ? '' : '');
  });
  onState('options', d => { sw.listen.set(d.listen); sw.muteAprs.set(d.muteAprs); sw.wav.set(d.wav); sw.web.set(d.web); });
  onState('counters', d => d.forEach((v, i) => { setText(cnt[i].b, v); cnt[i].b.style.color = v > 0 ? [C.cyan, C.green, C.red][i] : C.muted; }));

  // mini-spectre (SpectrumStrip) : anime en continu, valeurs du PC
  const vals = new Float32Array(100);
  let lastSpec = 0;
  onState('spec', d => { for (let i = 0; i < 100 && i < d.length; i++) vals[i] = d[i]; lastSpec = Date.now(); });
  (function anim() {
    const dpr = devicePixelRatio || 1, w = spec.clientWidth, hh = spec.clientHeight;
    if (w && hh) {
      if (spec.width !== Math.round(w * dpr)) { spec.width = Math.round(w * dpr); spec.height = Math.round(hh * dpr); }
      const g = spec.getContext('2d');
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.clearRect(0, 0, w, hh);
      if (Date.now() - lastSpec > 600) for (let i = 0; i < 100; i++) vals[i] *= 0.92;
      const base = hh * 0.88, t = Date.now();
      let peak = 0;
      for (let i = 0; i < 100; i++) if (vals[i] > vals[peak]) peak = i;
      const line = new Path2D(), area = new Path2D();
      for (let i = 0; i < 100; i++) {
        const x = i / 99 * w, noise = hh * 0.03 * Math.abs(Math.sin(x * 0.11 + t / 110)), y = base - noise - vals[i] * hh * 0.78;
        if (i === 0) { line.moveTo(x, y); area.moveTo(x, hh); area.lineTo(x, y); } else { line.lineTo(x, y); area.lineTo(x, y); }
      }
      area.lineTo(w, hh); area.closePath();
      g.fillStyle = alpha(C.cyan, 0.14); g.fill(area);
      g.strokeStyle = C.cyan; g.lineWidth = 1.4; g.lineJoin = 'round'; g.stroke(line);
      if (vals[peak] > 0.15) {
        const px = Math.round(peak / 99 * w) + 0.5;
        g.strokeStyle = C.amber; g.lineWidth = 1; g.setLineDash([4, 6]);
        g.beginPath(); g.moveTo(px, 0); g.lineTo(px, hh); g.stroke(); g.setLineDash([]);
        g.font = '10px ' + FONT.mono; g.fillStyle = C.amber;
        const f = (peak * 16 / 99).toFixed(1) + ' kHz';
        g.fillText(f, Math.min(px + 5, w - g.measureText(f).width - 4), 12);
      }
    }
    setTimeout(() => requestAnimationFrame(anim), 60);
  })();
}

function devicesMenu(anchor) {
  const d = S.radio || { devices: [] };
  const items = d.devices.length ? d.devices.map(x => ({ text: x.name + '   ' + x.mac, current: x.mac === d.mac, run: () => cmd('device', { mac: x.mac }) }))
    : [{ text: 'Aucun appareil appairé dans Windows', disabled: true }];
  items.push('-', { text: 'Actualiser la liste', run: () => cmd('devices') });
  menu(anchor, items);
}

function settingsMenu(anchor) {
  const o = S.options || {};
  const wav = (mode, what) => pickFile('.wav,audio/wav', f => upload('wav', f, { mode }).then(ok => { if (ok) toast('Décodage ' + what + ' lancé (voir le JOURNAL)'); }));
  menu(anchor, [
    { text: 'Décoder un WAV SARSAT…', run: () => wav(1, 'SARSAT') },
    { text: 'Décoder un WAV radiosonde…', run: () => wav(2, 'radiosonde') },
    { text: 'Décoder un WAV APRS…', run: () => wav(-1, 'APRS') },
    { text: 'Décoder un WAV POCSAG…', run: () => wav(-2, 'POCSAG') },
    '-',
    { text: 'Dossier des résultats : ' + (o.outDir || ''), disabled: true },
    { text: 'Effacer la carte et les tableaux', run: () => confirmBox('HTMod', 'Effacer la carte et les tableaux APRS et décodages ?', 'EFFACER').then(ok => ok && cmd('clear')) },
    { text: 'APRS : démodulateur logiciel en plus du TNC', check: !!o.swAprs, run: () => cmd('option', { name: 'swAprs', on: o.swAprs ? 0 : 1 }) },
    '-',
    { text: 'Mode d\'emploi', run: help },
    { text: 'Version d\'écran (image de la fenêtre du PC)', run: () => window.open('/ecran', '_blank') },
  ]);
}

function help() {
  modal('HTMod ' + ((S.options && S.options.version) || ''),
    '1. Appairez le poste dans Paramètres Windows > Bluetooth (une seule fois).\n'
    + '2. Coupez le Bluetooth du téléphone : le poste n\'accepte qu\'une connexion.\n'
    + '3. Choisissez le poste (Poste ›) et cliquez sur SE CONNECTER.\n'
    + '4. SARSAT / radiosondes : canal nommé SARSAT ou Radiosonde, ou mode forcé.\n'
    + '   APRS : toujours actif (TNC du poste + démodulateur logiciel).\n'
    + '5. BANDSCOPE : le poste balaie ±100 kHz à ±1 MHz autour du canal (S-mètre à chaque pas,\n'
    + '   rien n\'est écrit en mémoire). Double-clic pour écouter une fréquence.\n'
    + '6. POCSAG et SSTV : décodage permanent ; émission (radioamateurs) depuis l\'onglet.\n'
    + '7. VFO : double veille, volume, squelch, PTT (micro du PC), groupes et canaux, accord libre.\n'
    + '8. SATELLITES : passages, carte, suivi Doppler avec le poste.\n\n'
    + 'Carte : glisser pour déplacer, molette ou pincement pour zoomer, clic sur une épingle.\n'
    + 'Cette page pilote l\'application du PC : tout ce que vous faites ici s\'applique au poste.');
}

/* ---------------------------------------------------------------- son dans le navigateur (PCM 16 kHz) */
let actx = null, reader = null, nextT = 0, audioOn = false;
const audioUi = [];        // interrupteur de la colonne + touche SON du bandeau
function showAudio(on) {
  audioOn = on;
  audioUi.forEach(u => u(on));
}
function setAudio(on) { if (on) startAudio(); else stopAudio(); }
async function startAudio() {
  if (audioOn) return;
  try {
    actx = new (window.AudioContext || window.webkitAudioContext)();
    // les navigateurs demarrent le son « suspendu » : reprise explicite pendant le clic
    if (actx.state === 'suspended') await actx.resume();
  } catch (e) { actx = null; showAudio(false); alertBox('Lecture audio impossible dans ce navigateur.'); return; }
  if (actx.state !== 'running') { toast('Touchez encore SON pour autoriser le son', true); }
  showAudio(true);
  nextT = 0;
  toast('Son du poste : à l\'écoute (le son arrive quand le poste reçoit)');
  try {
    const r = await fetch('/audio', { cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    reader = r.body.getReader();
    let rest = null;
    for (;;) {
      const { value, done } = await reader.read();
      if (done || !actx) break;
      if (actx.state === 'suspended') actx.resume();
      let b = value;
      if (rest) { const m = new Uint8Array(rest.length + b.length); m.set(rest); m.set(b, rest.length); b = m; rest = null; }
      const n = b.length >> 1;
      if (b.length & 1) rest = b.slice(b.length - 1);
      if (!n) continue;
      const dv = new DataView(b.buffer, b.byteOffset, n * 2), buf = actx.createBuffer(1, n, 16000), ch = buf.getChannelData(0);
      for (let i = 0; i < n; i++) ch[i] = dv.getInt16(2 * i, true) / 32768;
      const src = actx.createBufferSource();
      src.buffer = buf; src.connect(actx.destination);
      const now = actx.currentTime;
      if (nextT < now + 0.05 || nextT > now + 1.5) nextT = now + 0.25;   // reprise apres un silence, ou retard
      src.start(nextT);
      nextT += buf.duration;
    }
  } catch (e) { if (actx) toast('Son interrompu : ' + e.message, true); }
  if (actx) stopAudio();
}
function stopAudio() {
  if (reader) { try { reader.cancel(); } catch (e) {} reader = null; }
  if (actx) { try { actx.close(); } catch (e) {} actx = null; }
  showAudio(false);
}
/* ---------------------------------------------------------------- etat en direct */
let es = null, offlineEl = null;
function connectEvents() {
  es = new EventSource('/api/events');
  for (const sec of SECTIONS) {
    es.addEventListener(sec, e => {
      try { S[sec] = JSON.parse(e.data); } catch (err) { return; }
      render(sec);
    });
  }
  es.onopen = () => { if (offlineEl) { offlineEl.remove(); offlineEl = null; } };
  es.onerror = async () => {
    if (!offlineEl) { offlineEl = h('div', { class: 'offline' }, 'Liaison avec le PC perdue — nouvel essai…'); document.body.append(offlineEl); }
    try { const r = await fetch('/api/state', { method: 'GET' }); if (r.status === 401) location.reload(); } catch (e) {}
  };
}

/* ---------------------------------------------------------------- demarrage */
(async function boot() {
  hydrateIcons();
  try {
    const r = await fetch('/api/state');
    if (r.status === 401) { location.reload(); return; }
    const st = await r.json();
    Object.assign(S, st);
  } catch (e) {}
  buildSide();
  buildTabs();
  let t = null;
  try { t = localStorage.getItem('htmod.tab'); } catch (e) {}
  selectTab(t || 'CARTE');
  for (const sec of SECTIONS) if (S[sec] !== undefined) render(sec);
  connectEvents();
  $('#settingsBtn').addEventListener('click', e => settingsMenu(e.currentTarget));
  const sb = $('#soundBtn');
  sb.addEventListener('click', () => setAudio(!audioOn));
  audioUi.push(on => { sb.classList.toggle('on', on); sb.querySelector('svg').innerHTML = ICONS[on ? 'speaker' : 'mute']; sb.title = on ? 'Couper le son dans ce navigateur' : 'Écouter le son du poste dans ce navigateur'; });
  showAudio(false);
  $('#drawerBtn').addEventListener('click', () => document.body.classList.toggle('drawer'));
  document.addEventListener('keydown', e => { if (e.key === 'Escape') { closeOverlays(); document.body.classList.remove('drawer'); } });
  document.addEventListener('click', e => {
    if (document.body.classList.contains('drawer') && !e.target.closest('#side') && !e.target.closest('#drawerBtn') && !e.target.closest('.menu,.modal-back'))
      document.body.classList.remove('drawer');
  });
})();
