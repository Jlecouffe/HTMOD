/* HTMod web — composants « Shack » et onglets (reproduction des panneaux Swing). */
'use strict';

/* ================================================================ etat partage et outils */
const S = {};                    // sections d'etat recues du PC
const RENDER = {};               // section -> fonctions de rendu
function onState(sec, fn) { (RENDER[sec] = RENDER[sec] || []).push(fn); }
function render(sec) { (RENDER[sec] || []).forEach(fn => { try { fn(S[sec]); } catch (e) { console.error(sec, e); } }); }

function h(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  if (attrs) for (const k in attrs) {
    const v = attrs[k];
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(e.style, v);
    else if (k.startsWith('on') && typeof v === 'function') e.addEventListener(k.slice(2), v);
    else if (k === 'html') e.innerHTML = v;
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat()) if (c != null && c !== false) e.append(c.nodeType ? c : document.createTextNode(String(c)));
  return e;
}
const $ = (sel, root) => (root || document).querySelector(sel);
function setText(e, t) { t = t == null ? '' : String(t); if (e.textContent !== t) e.textContent = t; }

/* icones au trait (Shack.Icon) */
const ICONS = {
  globe: '<circle cx="9" cy="9" r="7.6"/><ellipse cx="9" cy="9" rx="3.8" ry="7.6"/><path d="M1.4 9h15.2M2.6 5.2Q9 7.6 15.4 5.2M2.6 12.8Q9 10.4 15.4 12.8"/>',
  sliders: '<path d="M2.5 3.6h13M2.5 9h13M2.5 14.4h13"/><circle cx="12" cy="3.6" r="1.9" fill="#0A0E13"/><circle cx="6" cy="9" r="1.9" fill="#0A0E13"/><circle cx="10.8" cy="14.4" r="1.9" fill="#0A0E13"/>',
  bars: '<g stroke="none" fill="currentColor"><rect x="1.5" y="9.4" width="2.4" height="7.6" rx=".5"/><rect x="4.9" y="4.3" width="2.4" height="12.7" rx=".5"/><rect x="8.3" y="1" width="2.4" height="16" rx=".5"/><rect x="11.7" y="7.4" width="2.4" height="9.6" rx=".5"/><rect x="15.1" y="3.4" width="2.4" height="13.6" rx=".5"/></g>',
  pin: '<path d="M9 17C8.1 13.7 3 12.7 3 7.6A6 5.8 0 0 1 15 7.6C15 12.7 9.9 13.7 9 17Z"/><circle cx="9" cy="7.4" r="2.2"/>',
  list: '<path d="M6 4h10M6 9h10M6 14h10"/><circle cx="2.6" cy="4" r=".9" fill="currentColor"/><circle cx="2.6" cy="9" r=".9" fill="currentColor"/><circle cx="2.6" cy="14" r=".9" fill="currentColor"/>',
  wave: '<path d="M1 9h3l2-5 3 10 2-8 2 5 1-2h3"/>',
  menu: '<path d="M2.5 4.5h13M2.5 9h13M2.5 13.5h13"/>',
  speaker: '<path d="M2 6.5h3l4-3.5v12l-4-3.5H2z"/><path d="M12 6.2a4 4 0 0 1 0 5.6M14.3 4a7 7 0 0 1 0 10"/>',
  mute: '<path d="M2 6.5h3l4-3.5v12l-4-3.5H2z"/><path d="M12 6.5l4.5 5M16.5 6.5l-4.5 5"/>',
  target: '<circle cx="9" cy="9" r="4.5"/><path d="M9 1v4M9 13v4M1 9h4M13 9h4"/>',
  pager: '<rect x="1" y="3.6" width="16" height="10.8" rx="4.5"/><path d="M4.5 7.4h9M4.5 10.6h5.4"/>',
  ab: '<text x="1" y="13" font-size="11" font-family="Consolas,monospace" font-weight="700" fill="currentColor" stroke="none">A</text><path d="M9 3v12"/><text x="10.5" y="13" font-size="11" font-family="Consolas,monospace" font-weight="700" fill="currentColor" stroke="none">B</text>',
  picture: '<rect x="1" y="3" width="16" height="12" rx="3"/><path d="M3.2 13l4.4-4.9 3.2 3.1 1.8-1.8 2.6 3.6"/><circle cx="12.6" cy="6.6" r="1.2" fill="currentColor"/>',
};
function icon(name) {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 18 18');
  s.setAttribute('data-icon', name);
  s.innerHTML = ICONS[name] || '';
  return s;
}
function hydrateIcons(root) {
  (root || document).querySelectorAll('svg[data-icon]').forEach(s => { if (!s.innerHTML) { s.setAttribute('viewBox', '0 0 18 18'); s.innerHTML = ICONS[s.getAttribute('data-icon')] || ''; } });
}

/** Touche en relief. opt : primary, cls, icon, title, sel (couleur de selection) */
function key(text, onClick, opt) {
  opt = opt || {};
  const b = h('button', { class: 'key' + (opt.primary ? ' primary' : '') + (opt.cls ? ' ' + opt.cls : ''), title: opt.title, type: 'button' });
  if (opt.icon) b.append(icon(opt.icon));
  const t = h('span', null, text);
  if (text) b.append(t);
  if (opt.sel) b.style.setProperty('--sel', opt.sel);
  if (onClick) b.addEventListener('click', onClick);
  b.setText = s => setText(t, s);
  b.setSel = v => b.classList.toggle('sel', !!v);
  return b;
}
/** Groupe de touches exclusives (Shack.choice). */
function choice(labels, sel, onSel, opt) {
  const wrap = h('div', { class: 'keys' + (opt && opt.cls ? ' ' + opt.cls : '') });
  const ks = labels.map((l, i) => key(l, () => { ks.forEach((k, j) => k.setSel(j === i)); onSel(i); }, { cls: (opt && opt.keyCls) || 'small' }));
  ks.forEach((k, i) => k.setSel(i === sel));
  wrap.append(...ks);
  wrap.keys = ks;
  wrap.set = i => ks.forEach((k, j) => k.setSel(j === i));
  return wrap;
}
/** Interrupteur. */
function toggle(label, on, onChange) {
  const e = h('div', { class: 'switch' + (on ? ' on' : ''), tabindex: 0, role: 'switch' }, h('span', null, label), h('span', { class: 'pill' }));
  const flip = () => { const v = !e.classList.contains('on'); e.classList.toggle('on', v); onChange(v); };
  e.addEventListener('click', flip);
  e.addEventListener('keydown', ev => { if (ev.key === ' ' || ev.key === 'Enter') { ev.preventDefault(); flip(); } });
  e.set = v => e.classList.toggle('on', !!v);
  return e;
}
/** Pastille d'etat. */
function chip() {
  const i = h('i'), t = h('span');
  const e = h('div', { class: 'chip' }, i, t);
  e.set = (text, dot, fg) => {
    setText(t, text);
    e.style.setProperty('--dot', dot || C.muted);
    e.style.setProperty('--fg', fg || C.muted);
    e.classList.toggle('dim', !dot || dot.toUpperCase() === C.muted);
  };
  return e;
}
function label(t, extra) { return h('div', { class: 'label' + (extra ? ' ' + extra : '') }, t); }
function section(t) { return h('div', { class: 'section' }, t); }
function caption(t) { return h('div', { class: 'caption' }, t); }
function note(t) { return h('div', { class: 'note' }, t); }
function field(value, attrs) { return h('input', Object.assign({ class: 'field', value: value == null ? '' : value, autocomplete: 'off', spellcheck: 'false' }, attrs || {})); }

/** Tableau sombre ; cols = [{t, w, m(ono)}] ; rows mises a jour par set(rows, keyFn). */
function grid(cols, opt) {
  opt = opt || {};
  const tb = h('tbody');
  const tbl = h('table', { class: 'grid' },
    h('colgroup', null, cols.map(c => h('col', { style: c.w ? { width: c.w + 'px' } : null }))),
    h('thead', null, h('tr', null, cols.map(c => h('th', { class: c.m ? 'm' : null }, c.t)))), tb);
  const wrap = h('div', { class: 'table-wrap' }, tbl);
  let rows = [], selKey = null, keys = [];
  wrap.set = (data, keyFn) => {
    rows = data || [];
    const nk = rows.map((r, i) => keyFn ? keyFn(r, i) : i);
    if (nk.length === keys.length && nk.every((k, i) => k === keys[i])) {
      // memes lignes : mise a jour des cellules en place (selection et double-clic preserves)
      const trs = tb.children;
      rows.forEach((r, i) => {
        const cells = opt.cells ? opt.cells(r) : r, tr = trs[i];
        if (!tr) return;
        tr._row = r;
        tr.classList.toggle('sel', nk[i] === selKey);
        if (opt.title) tr.title = opt.title(r);
        for (let j = 0; j < cols.length; j++) setText(tr.children[j], cells[j] == null ? '' : cells[j]);
      });
      return;
    }
    keys = nk;
    const frag = document.createDocumentFragment();
    rows.forEach((r, i) => {
      const k = keyFn ? keyFn(r, i) : i;
      const cells = opt.cells ? opt.cells(r) : r;
      const tr = h('tr', { class: k === selKey ? 'sel' : null, title: opt.title ? opt.title(r) : null },
        cols.map((c, j) => h('td', { class: c.m ? 'm' : null, style: opt.cellStyle ? opt.cellStyle(r, j) : null }, cells[j] == null ? '' : cells[j])));
      tr._row = r;
      tr.addEventListener('click', () => { selKey = k; tb.querySelectorAll('tr.sel').forEach(x => x.classList.remove('sel')); tr.classList.add('sel'); if (opt.onSelect) opt.onSelect(tr._row); });
      tr.addEventListener('dblclick', () => { if (opt.onDouble) opt.onDouble(tr._row); });
      tr.addEventListener('contextmenu', ev => { if (opt.onContext) { ev.preventDefault(); opt.onContext(tr._row); } });
      frag.append(tr);
    });
    tb.replaceChildren(frag);
  };
  wrap.select = k => { selKey = k; };
  wrap.selected = () => selKey;
  return wrap;
}

/* ================================================================ dialogues, menus, messages */
function closeOverlays() { document.querySelectorAll('.menu,.modal-back').forEach(e => e.remove()); $('#overlay').classList.add('hidden'); }
function menu(anchor, items) {
  closeOverlays();
  const m = h('div', { class: 'menu' });
  for (const it of items) {
    if (it === '-') { m.append(h('div', { class: 'sep' })); continue; }
    const e = h('div', { class: 'it' + (it.disabled ? ' dis' : '') + (it.current ? ' cur' : '') },
      it.check !== undefined ? h('span', { class: 'ck' }, it.check ? '✓' : '') : null, it.text);
    if (!it.disabled) e.addEventListener('click', () => { closeOverlays(); it.run(); });
    m.append(e);
  }
  document.body.append(m);
  const r = anchor.getBoundingClientRect(), mw = m.offsetWidth, mh = m.offsetHeight;
  m.style.left = Math.max(8, Math.min(r.left, innerWidth - mw - 8)) + 'px';
  m.style.top = (r.bottom + mh > innerHeight - 8 ? Math.max(8, r.top - mh) : r.bottom) + 'px';
  const ov = $('#overlay');
  ov.classList.remove('hidden');
  ov.onclick = closeOverlays;
}
function modal(title, body, buttons) {
  closeOverlays();
  return new Promise(res => {
    const box = h('div', { class: 'modal', role: 'dialog' }, h('h3', null, title));
    if (typeof body === 'string') box.append(h('p', null, body)); else if (body) box.append(body);
    const btns = h('div', { class: 'btns' });
    (buttons || [{ text: 'OK', primary: true, value: true }]).forEach(b => btns.append(key(b.text, () => { back.remove(); res(b.value); }, { primary: b.primary })));
    box.append(btns);
    const back = h('div', { class: 'modal-back' }, box);
    back.addEventListener('click', e => { if (e.target === back) { back.remove(); res(null); } });
    document.body.append(back);
    const f = box.querySelector('input,select,textarea');
    if (f) f.focus();
  });
}
const alertBox = (t, title) => modal(title || 'HTMod', t);
const confirmBox = (title, t, okText) => modal(title, t, [{ text: 'ANNULER', value: false }, { text: okText || 'OK', primary: true, value: true }]);
let toastTimer = null;
function toast(t, err) {
  document.querySelectorAll('.toast').forEach(e => e.remove());
  const e = h('div', { class: 'toast' + (err ? ' err' : '') }, t);
  document.body.append(e);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => e.remove(), err ? 6000 : 2600);
}

/** Commande vers le PC ; les erreurs sont affichees comme dans l'appli (boite de dialogue). */
async function cmd(a, params) {
  const body = new URLSearchParams(Object.assign({ a }, params || {}));
  try {
    const r = await fetch('/api/cmd', { method: 'POST', body, headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
    if (r.status === 401) { location.reload(); return null; }
    const j = await r.json();
    if (!j.ok) { alertBox(j.error); return null; }
    return j.data === undefined ? true : j.data;
  } catch (e) {
    toast('Liaison avec le PC perdue', true);
    return null;
  }
}
async function upload(what, file, params) {
  const q = new URLSearchParams(Object.assign({ what }, params || {}));
  try {
    const r = await fetch('/api/upload?' + q, { method: 'POST', body: file });
    const j = await r.json();
    if (!j.ok) { alertBox(j.error); return false; }
    return true;
  } catch (e) { toast('Envoi impossible', true); return false; }
}
function pickFile(accept, fn) {
  const i = h('input', { type: 'file', accept, style: { display: 'none' } });
  i.addEventListener('change', () => { if (i.files[0]) fn(i.files[0]); i.remove(); });
  document.body.append(i);
  i.click();
}
function hms(t) { const d = new Date(t); return d.toTimeString().slice(0, 8); }

/* ================================================================ vues */
const VIEWS = {};

/* ---------------------------------------------------------------- CARTE */
VIEWS.CARTE = function (v) {
  const cv = h('canvas', { class: 'map' });
  const info = h('div', { class: 'card info-card hidden' });
  const well = h('div', { class: 'well map-well' }, cv, info);
  v.append(well);
  v.style.flexDirection = 'column';
  const map = new TileMap(cv, { key: 'map', night: S.options ? S.options.night : true, onSelect: m => showInfo(m) });
  const wind = new WindLayer(map, s => toast(s, true));
  map.underlay = wind;
  window.mainMap = map;
  const kWind = key('VENT', () => { wind.setOn(!wind.on); kWind.setSel(wind.on); try { localStorage.setItem('htmod.wind', wind.on ? '1' : '0'); } catch (e) {} }, { cls: 'txt', title: 'Vent animé (Open-Meteo, 10 m)' });
  const kTopo = key('TOPO', () => { map.setTopo(!map.topo); kTopo.setSel(map.topo); }, { cls: 'txt', title: 'Fond OpenTopoMap (relief)' });
  const kNight = key('NUIT', () => { map.setNight(!map.night); kNight.setSel(map.night); }, { cls: 'txt', title: 'Carte en couleurs inversées (mode nuit)' });
  const kFit = key('', () => map.fitAll(), { icon: 'target', title: 'Cadrer toutes les positions' });
  well.append(h('div', { class: 'map-keys' }, kWind, kTopo, kNight, kFit, key('−', () => map.zoomOut(), { title: 'Zoom arrière' }), key('+', () => map.zoomIn(), { title: 'Zoom avant' })));
  kTopo.setSel(map.topo);
  kNight.setSel(map.night);
  let w0 = null;
  try { w0 = localStorage.getItem('htmod.wind'); } catch (e) {}
  if (w0 === '1' || (w0 == null && S.options && S.options.wind)) { wind.setOn(true); kWind.setSel(true); }

  function showInfo(m) {
    if (!m) { info.classList.add('hidden'); return; }
    info.replaceChildren(
      h('div', { class: 't', style: { color: kindColor(m.kind) } }, m.title),
      h('div', { class: 's' }, m.sub + '  ·  ' + hms(m.time)),
      h('pre', null, m.details),
      h('div', { class: 'acts' },
        h('a', { onclick: () => window.open('https://www.openstreetmap.org/?mlat=' + m.lat.toFixed(6) + '&mlon=' + m.lon.toFixed(6) + '#map=13/' + m.lat.toFixed(6) + '/' + m.lon.toFixed(6), '_blank') }, 'Ouvrir sur OpenStreetMap ›'),
        h('a', { class: 'close', onclick: () => { info.classList.add('hidden'); map.selected = null; map.redraw(); } }, 'Fermer ×')));
    info.classList.remove('hidden');
  }
  onState('markers', d => map.setMarkers(d));
  v.onShow = () => map.resize();
};

/* ---------------------------------------------------------------- APRS */
VIEWS.APRS = function (v) {
  const right = h('div', { class: 'mono muted', style: { fontSize: '11px' } }, '0 paquet');
  const g = grid([{ t: 'Heure', w: 96, m: 1 }, { t: 'Indicatif', w: 150, m: 1 }, { t: 'Type', w: 110 }, { t: 'Position', w: 180, m: 1 }, { t: 'Informations' }], {
    title: r => r[5] + (r[6] ? '\nDouble-clic : voir la station sur la carte' : ''),
    onDouble: r => { if (r[6]) { selectTab('CARTE'); window.mainMap.focus(r[6]); } },
  });
  v.classList.add('stack-mobile');
  v.style.gap = '14px';
  v.append(h('div', { class: 'card table-card', style: { flex: 1 } }, h('div', { class: 'head' }, label('TRAFIC APRS'), right), g));
  onState('aprs', d => { setText(right, d.header); g.set(d.rows, r => r[0] + r[5]); });

  // emission par le TNC du poste, avec ses reglages APRS
  const params = h('pre', { class: 'mono', style: { fontSize: '12px', color: C.text2, whiteSpace: 'pre-wrap', margin: '8px 0' } }, ' ');
  const info = field('', { placeholder: 'vide = balise de position' });
  const auto = toggle('Balise automatique (intervalle du poste)', false, x => cmd('aprs.auto', { on: x ? 1 : 0 }));
  const txL = h('div', { class: 'mono', style: { fontSize: '11.5px', marginTop: '6px', color: C.muted } }, ' ');
  const send = key('ÉMETTRE TRAME', () => cmd('aprs.send', { info: info.value }), { primary: true, cls: 'wide' });
  send.style.height = '48px';
  v.append(h('div', { class: 'card tx-card' }, label('ÉMISSION APRS · RÉGLAGES DU POSTE', 'tracked'), params,
    key('RELIRE LE POSTE', () => cmd('aprs.reload'), { cls: 'small' }),
    caption('Champ d\'information (vide = balise de position)'), info, auto, txL, h('div', { style: { height: '8px' } }), send,
    h('div', { style: { height: '8px' } }),
    note('La trame est modulée par le TNC du poste (AFSK 1200) et émise dès que le canal est libre, sur le canal nommé « APRS » s\'il existe, sinon sur celui du VFO A. Réservé aux radioamateurs.')));
  let init = false;
  onState('aprsTx', d => {
    if (!init) { init = true; info.value = d.info; }
    setText(params, d.params); setText(txL, d.status); txL.style.color = d.statusColor;
    send.setText(d.sendKey); auto.set(d.auto);
  });
};

/* ---------------------------------------------------------------- DECODAGES */
VIEWS['DÉCODAGES'] = function (v) {
  const g = grid([{ t: 'Reçu (local)', w: 150, m: 1 }, { t: 'Type', w: 90 }, { t: 'Identifiant', w: 170, m: 1 }, { t: 'Latitude', w: 90, m: 1 },
    { t: 'Longitude', w: 90, m: 1 }, { t: 'Détails' }], {
    title: r => r[5] + (r[6] ? '\nDouble-clic : voir la position sur la carte' : ''),
    onDouble: r => { if (r[6]) { selectTab('CARTE'); window.mainMap.focus(r[6]); } },
  });
  v.append(h('div', { class: 'card table-card' }, h('div', { class: 'head' }, label('SARSAT & RADIOSONDES'), h('span')), g));
  onState('det', d => g.set(d, r => r[0] + r[2]));
};

/* ---------------------------------------------------------------- BANDSCOPE */
function levelColor(level) {
  const p = Math.max(0, Math.min(15, level)) / 15;
  const lerp = (a, b, t) => {
    t = Math.max(0, Math.min(1, t));
    const r = ((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t, g = ((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t,
      bl = (a & 255) + ((b & 255) - (a & 255)) * t;
    return `rgb(${r | 0},${g | 0},${bl | 0})`;
  };
  if (p <= 0.01) return '#0A0A2A';
  if (p < 0.33) return lerp(0x0A0A6E, 0x00C8C8, p / 0.33);
  if (p < 0.66) return lerp(0x00C8C8, 0x3DDC84, (p - 0.33) / 0.33);
  if (p < 0.9) return lerp(0x3DDC84, 0xFFC107, (p - 0.66) / 0.24);
  return lerp(0xFFC107, 0xFF3B30, (p - 0.9) / 0.1);
}
const sUnits = l => l <= 0 ? 'S0' : l <= 9 ? 'S' + l : 'S9+' + (l - 9) * 10;
const mhz = hz => (hz / 1e6).toFixed(4);

VIEWS.BANDSCOPE = function (v) {
  v.style.flexDirection = 'column';
  const center = h('div', { class: 'bigger' }, '—'), infoL = h('div', { class: 'mono muted', style: { fontSize: '11px' } }, 'Arrêté');
  const spans = choice(['100 kHz', '250 kHz', '500 kHz', '1 MHz'], -1, i => cmd('bs.span', { v: [100000, 250000, 500000, 1000000][i] }));
  const steps = choice(['6.25', '8.33', '12.5', '25'], -1, i => cmd('bs.step', { v: [6250, 8333, 12500, 25000][i] }));
  const back = key('RETOUR AU CANAL', () => cmd('bs.back'), { cls: 'small' });
  back.style.width = '150px';
  const start = key('BALAYER', () => cmd('bs.start'), { primary: true });
  start.style.width = '130px';
  v.append(h('div', { class: 'bs-head' },
    h('div', null, label('BANDSCOPE · FRÉQUENCE CENTRALE'), center, infoL),
    h('div', { class: 'bs-ctl' }, label('LARGEUR ±'), spans, label('PAS'), steps, back, start)));
  const cv = h('canvas', { title: 'Double-clic : écouter cette fréquence' });
  v.append(h('div', { class: 'well bs-well' }, cv));
  let mouseX = -1;
  cv.addEventListener('mousemove', e => { mouseX = e.offsetX; draw(); });
  cv.addEventListener('mouseleave', () => { mouseX = -1; draw(); });
  cv.addEventListener('dblclick', e => { const d = S.bs; if (d && d.center > 0) cmd('bs.park', { f: freqAt(e.offsetX) }); });
  new ResizeObserver(() => draw()).observe(cv);
  const x0 = 46;
  const pw = () => cv.clientWidth - x0 - 10;
  function freqAt(x) {
    const d = S.bs, n = Math.floor(2 * d.half / d.step) + 1;
    let i = Math.round((x - x0) / pw() * (n - 1));
    i = Math.max(0, Math.min(n - 1, i));
    return d.center - d.half + i * d.step;
  }
  function draw() {
    const d = S.bs;
    if (!d || !cv.isConnected) return;
    const dpr = devicePixelRatio || 1, w = cv.clientWidth, H = cv.clientHeight;
    if (!w || !H) return;
    cv.width = Math.round(w * dpr); cv.height = Math.round(H * dpr);
    const g = cv.getContext('2d');
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, H);
    const P = pw(), specTop = 18, specH = Math.round(H * 0.48), axisY = specTop + specH, wfTop = axisY + 44, wfH = H - wfTop - 4;
    const lv = d.levels, pk = d.peaks, sq = d.sq, n = lv.length > 0 ? lv.length : Math.floor(2 * d.half / d.step) + 1;
    g.font = '10px ' + FONT.mono;
    for (let s = 0; s <= 15; s += 3) {
      const y = axisY - Math.round(s / 15 * specH) + 0.5;
      g.strokeStyle = alpha(C.edge, s === 0 ? 1 : 0.55); g.lineWidth = 1;
      g.beginPath(); g.moveTo(x0, y); g.lineTo(x0 + P, y); g.stroke();
      g.fillStyle = C.muted; const l = sUnits(s);
      g.fillText(l, x0 - 8 - g.measureText(l).width, y + 4);
    }
    const tick = d.half >= 500000 ? 100000 : d.half >= 250000 ? 50000 : 25000, c = d.center;
    if (c > 0) {
      for (let f = (Math.floor((c - d.half) / tick) + 1) * tick; f <= c + d.half; f += tick) {
        const x = x0 + Math.round((f - (c - d.half)) / (2 * d.half) * P) + 0.5;
        g.strokeStyle = alpha(C.edge, 0.47); g.beginPath(); g.moveTo(x, specTop); g.lineTo(x, axisY); g.stroke();
        g.fillStyle = C.muted; const l = (f / 1e6).toFixed(3), lw = g.measureText(l).width;
        if (x - lw / 2 < x0 - 4 || x + lw / 2 > x0 + P + 4) continue;
        g.fillText(l, x - lw / 2, axisY + 15);
      }
    } else {
      g.font = '13px ' + FONT.body; g.fillStyle = C.muted;
      g.fillText('Connectez le poste puis cliquez sur BALAYER : le bandscope se centre sur la fréquence du canal.', x0 + 10, specTop + 30);
    }
    const bw = P / Math.max(1, n - 1);
    for (let i = 0; i < lv.length; i++) {
      const x = x0 + Math.round(i * bw - bw / 2), wb = Math.max(1, Math.round(bw) - (bw > 4 ? 1 : 0));
      if (pk.length === lv.length && pk[i] > 0) { g.fillStyle = alpha(C.amber, 0.43); g.fillRect(x, axisY - Math.round(pk[i] / 15 * specH), wb, 2); }
      if (lv[i] < 0) continue;
      const hh = Math.max(1, Math.round(lv[i] / 15 * specH)), col = sq.length === lv.length && sq[i] ? C.amber : C.cyan;
      g.fillStyle = alpha(col, 0.24); g.fillRect(x, axisY - hh, wb, hh);
      g.fillStyle = col; g.fillRect(x, axisY - hh, wb, Math.min(2, hh));
    }
    if (c > 0) {
      const xc = x0 + P / 2 + 0.5;
      g.strokeStyle = C.amber; g.setLineDash([4, 6]); g.beginPath(); g.moveTo(xc, specTop - 6); g.lineTo(xc, H - 4); g.stroke(); g.setLineDash([]);
      g.font = 'bold 11px ' + FONT.mono; g.fillStyle = C.amber;
      g.fillText((d.parked ? 'canal ' : '') + mhz(c), xc + 6, specTop + 4);
    }
    if (d.running && d.cursor >= 0) {
      const x = x0 + Math.round(d.cursor * bw) + 0.5;
      g.strokeStyle = alpha(C.cyan, 0.63); g.beginPath(); g.moveTo(x, specTop); g.lineTo(x, axisY); g.stroke();
    }
    if (d.parked && c > 0) {
      const x = x0 + Math.round((d.parked - (c - d.half)) / (2 * d.half) * P);
      g.strokeStyle = C.green; g.lineWidth = 2; g.beginPath(); g.moveTo(x, specTop); g.lineTo(x, axisY); g.stroke(); g.lineWidth = 1;
    }
    g.strokeStyle = alpha(C.edge, 0.78); g.strokeRect(x0 + 0.5, wfTop + 0.5, P, wfH);
    g.font = '9.5px ' + FONT.mono; g.fillStyle = C.muted;
    g.fillText('C A S C A D E   D E S   B A L A Y A G E S', x0, wfTop - 6);
    const rowH = 6;
    (d.history || []).forEach((hl, k) => {
      const y = wfTop + 1 + k * rowH;
      if (y + rowH > wfTop + wfH) return;
      const cw = (P - 1) / hl.length;
      for (let i = 0; i < hl.length; i++) { g.fillStyle = levelColor(hl[i]); g.fillRect(x0 + 1 + i * cw, y, Math.ceil(cw), rowH); }
    });
    if (mouseX >= x0 && mouseX <= x0 + P && c > 0) {
      const f = freqAt(mouseX), i = Math.floor((f - (c - d.half)) / d.step);
      const l = mhz(f) + ' MHz' + (i >= 0 && i < lv.length && lv[i] >= 0 ? '  ' + sUnits(lv[i]) : '');
      g.strokeStyle = alpha(C.text, 0.35); g.beginPath(); g.moveTo(mouseX + 0.5, specTop); g.lineTo(mouseX + 0.5, axisY); g.stroke();
      g.font = '11px ' + FONT.mono;
      const lw = g.measureText(l).width + 12, lx = Math.min(mouseX + 8, w - lw - 4);
      g.fillStyle = alpha(C.bg, 0.86); roundRect(g, lx, specTop + 14, lw, 20, 4); g.fill();
      g.fillStyle = C.text; g.fillText(l, lx + 6, specTop + 28);
    }
  }
  onState('bs', d => {
    setText(center, d.center > 0 ? mhz(d.center) + ' MHz' : '—');
    setText(infoL, d.info);
    spans.set(d.spans.indexOf(d.half));
    steps.set(d.steps.indexOf(d.step));
    start.setText(d.running ? 'ARRÊTER' : 'BALAYER');
    back.classList.toggle('hidden', d.running || !d.parked);
    draw();
  });
  v.onShow = draw;
};

/* ---------------------------------------------------------------- POCSAG */
VIEWS.POCSAG = function (v) {
  v.classList.add('stack-mobile');
  v.style.gap = '14px';
  const sync = h('div', { class: 'mono cyan', style: { fontSize: '11px' } }, 'En écoute…');
  const count = h('div', { class: 'mono muted', style: { fontSize: '11px' } }, '0 message');
  const g = grid([{ t: 'Heure', w: 86, m: 1 }, { t: 'RIC', w: 90, m: 1 }, { t: 'Fonction', w: 84 }, { t: 'Débit', w: 84, m: 1 }, { t: 'Type', w: 70 }, { t: 'Message' }],
    { title: r => r[5] });
  v.append(h('div', { class: 'card table-card', style: { flex: 1 } },
    h('div', { class: 'head' }, h('div', null, label('MESSAGES REÇUS · 512 / 1200 / 2400 BAUDS', 'tracked'), sync), count), g));

  const F = { func: 3, baud: 1, numeric: 0, level: 2, pre: 0, inv: 0 };
  const ric = field('', { inputmode: 'numeric' }), msg = field('');
  const kFunc = choice(['0', '1', '2', '3'], 3, i => F.func = i);
  const kBaud = choice(['512 bd', '1200 bd', '2400 bd'], 1, i => F.baud = i);
  const kType = choice(['Alphanumérique', 'Numérique'], 0, i => F.numeric = i);
  const kLevel = choice(['40 %', '60 %', '80 %', '100 %'], 2, i => F.level = i);
  const sPre = toggle('Pré-compensation (pré-accentuation)', false, x => F.pre = x ? 1 : 0);
  const sInv = toggle('Inverser la polarité', false, x => F.inv = x ? 1 : 0);
  const txL = h('div', { class: 'mono', style: { fontSize: '11.5px', marginTop: '6px', color: C.muted } }, ' ');
  const send = key('ÉMETTRE', async () => {
    const d = S.pocsag;
    if (d && d.busy) { cmd('pocsag.stop'); return; }
    const ok = await confirmBox('Émettre le message POCSAG ?', 'RIC ' + ric.value.trim() + ' · fonction ' + F.func + ' · ' + [512, 1200, 2400][F.baud] + ' bauds\n'
      + (d ? d.tx : '') + '\n\nJe confirme être radioamateur et émettre sur une fréquence autorisée.', 'ÉMETTRE');
    if (ok) cmd('pocsag.send', Object.assign({ ric: ric.value, msg: msg.value }, F));
  }, { primary: true, cls: 'wide' });
  send.style.height = '48px';
  v.append(h('div', { class: 'card tx-card' }, label('ÉMISSION', 'tracked'), caption('RIC (adresse du récepteur)'), ric, caption('Fonction'), kFunc, caption('Débit'), kBaud,
    caption('Type'), kType, caption('Message (vide = bip seul)'), msg, caption('Niveau audio'), kLevel, sPre, sInv, txL, h('div', { style: { height: '8px' } }), send,
    h('div', { style: { height: '8px' } }),
    note('Émission sur la fréquence du canal ou celle accordée dans l\'onglet VFO, réservée aux radioamateurs sur une fréquence autorisée (ex. DAPNET 439,9875 MHz). Vérifiez la réception avec un second poste ou un SDR.')));
  let init = false;
  onState('pocsag', d => {
    if (!init) {
      init = true;
      ric.value = d.ric; msg.value = d.msg;
      Object.assign(F, { func: d.func, baud: d.baud, numeric: d.numeric ? 1 : 0, level: d.level, pre: d.pre ? 1 : 0, inv: d.inv ? 1 : 0 });
      kFunc.set(d.func); kBaud.set(d.baud); kType.set(d.numeric ? 1 : 0); kLevel.set(d.level); sPre.set(d.pre); sInv.set(d.inv);
    }
    setText(sync, d.sync); setText(count, d.count); setText(txL, d.tx); txL.style.color = d.txColor;
    send.setText(d.sendKey);
    g.set(d.rows, r => r.join('|'));
  });
};

/* ---------------------------------------------------------------- SSTV */
VIEWS.SSTV = function (v) {
  v.classList.add('stack-mobile');
  v.style.gap = '14px';
  const state = h('div', { class: 'mono', style: { fontSize: '11.5px' } }, ' '), mode = h('div', { class: 'mono muted', style: { fontSize: '11px' } }, ' ');
  const rxSw = toggle('Réception', true, x => cmd('sstv.rx', { on: x ? 1 : 0 }));
  rxSw.style.width = '150px';
  const img = h('img', { alt: '' }), empty = h('div', { class: 'empty' }, 'En attente d\'une image SSTV…');
  const view = h('div', { class: 'well sstv-view' }, empty, img);
  img.classList.add('hidden');
  const gallery = h('div', { class: 'gallery' });
  const save = key('ENREGISTRER', () => { if (S.sstv && S.sstv.hasRx) { const a = h('a', { href: '/api/img?w=rx&v=' + Date.now(), download: 'sstv.png' }); a.click(); } }, { cls: 'small', title: 'Télécharger l\'image affichée' });
  v.append(h('div', { class: 'card col sstv-rx', style: { flex: 1, gap: '10px' } },
    h('div', { style: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '12px' } }, label('RÉCEPTION SSTV', 'tracked'),
      h('div', { class: 'keys' }, rxSw, key('TERMINER', () => cmd('sstv.finish'), { cls: 'small' }), save)),
    h('div', null, label('ROBOT 36 / 72 · MARTIN M1 / M2 · SCOTTIE S1 / S2 / DX · PD 90 À 240'), h('div', { style: { height: '6px' } }), state, mode),
    view, gallery));

  const prev = h('img', { alt: '' });
  const names = ['R36', 'R72', 'M1', 'M2', 'S1', 'S2', 'DX', 'PD90', 'PD120', 'PD160', 'PD180', 'PD240'];
  let modes = [];
  const kModes = choice(names, 0, i => cmd('sstv.mode', { v: modes[i] }), { cls: 'mode-grid' });
  const text = field('');
  text.addEventListener('change', () => cmd('sstv.text', { v: text.value }));
  text.addEventListener('keydown', e => { if (e.key === 'Enter') text.blur(); });
  const kLevel = choice(['40 %', '60 %', '80 %', '100 %'], 2, i => cmd('sstv.level', { v: i }));
  const txInfo = h('div', { class: 'mono', style: { fontSize: '11.5px', marginTop: '8px' } }), txFreq = h('div', { class: 'mono', style: { fontSize: '11.5px' } });
  const send = key('ÉMETTRE L\'IMAGE', async () => {
    const d = S.sstv;
    if (d && d.busy) { cmd('sstv.send'); return; }
    const ok = await confirmBox('Émettre l\'image SSTV ?', (d ? d.txInfo + '\n' + d.txFreq : '') + '\n\nJe confirme être radioamateur et émettre sur une fréquence autorisée.', 'ÉMETTRE');
    if (ok) cmd('sstv.send');
  }, { primary: true, cls: 'wide' });
  send.style.height = '48px';
  v.append(h('div', { class: 'card tx-card' }, label('ÉMISSION', 'tracked'), h('div', { style: { height: '8px' } }),
    h('div', { class: 'well tx-preview' }, prev), h('div', { style: { height: '8px' } }),
    h('div', { style: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px' } },
      key('CHOISIR UNE IMAGE…', () => pickFile('image/*', f => upload('sstv', f)), { cls: 'small' }),
      key('MIRE DE TEST', () => cmd('sstv.testcard'), { cls: 'small' })),
    caption('Mode'), kModes, caption('Texte incrusté (indicatif)'), text, caption('Niveau audio'), kLevel, txInfo, txFreq, h('div', { style: { height: '8px' } }), send,
    h('div', { style: { height: '8px' } }),
    note('Émission sur la fréquence du canal ou celle accordée dans l\'onglet VFO, réservée aux radioamateurs (ex. 145,500 MHz puis dégagement, 433,400 MHz…). Pensez au temps d\'émission du poste (TOT) pour les modes longs.')));

  let rxV = -1, txV = -1, lastRxLoad = 0, galleryKey = '', textInit = false, rxTimer = null;
  function loadRx(d) {
    const now = Date.now();
    if (now - lastRxLoad < 900) { clearTimeout(rxTimer); rxTimer = setTimeout(() => loadRx(S.sstv), 950 - (now - lastRxLoad)); return; }
    lastRxLoad = now;
    rxV = d.rxVersion;
    const n = new Image();
    n.onload = () => { img.src = n.src; img.classList.remove('hidden'); empty.classList.add('hidden'); };
    n.src = '/api/img?w=rx&v=' + d.rxVersion;
  }
  onState('sstv', d => {
    rxSw.set(d.rxOn);
    setText(state, d.state); state.style.color = d.stateColor;
    setText(mode, d.mode || ' ');
    if (d.hasRx && d.rxVersion !== rxV) loadRx(d);
    if (d.txVersion !== txV) { txV = d.txVersion; prev.src = '/api/img?w=tx&v=' + d.txVersion; }
    modes = d.modes;
    kModes.set(d.modes.indexOf(d.txMode));
    kLevel.set(d.level);
    if (!textInit || document.activeElement !== text) { if (text.value !== d.text && document.activeElement !== text) text.value = d.text; textInit = true; }
    setText(txInfo, d.txInfo); txInfo.style.color = d.txInfoColor;
    setText(txFreq, d.txFreq); txFreq.style.color = d.txFreqColor;
    send.setText(d.sendKey);
    const gk = d.gallery.join('|');
    if (gk !== galleryKey) {
      galleryKey = gk;
      gallery.replaceChildren(...d.gallery.map(n => {
        const t = h('img', { src: '/api/img?w=file&name=' + encodeURIComponent(n), loading: 'lazy', title: n + '\nDouble-clic : ouvrir', alt: n });
        t.addEventListener('click', () => cmd('sstv.show', { name: n }));
        t.addEventListener('dblclick', () => window.open('/api/img?w=file&name=' + encodeURIComponent(n), '_blank'));
        return t;
      }));
    }
  });
};

/* ---------------------------------------------------------------- VFO */
VIEWS.VFO = function (v) {
  v.style.flexDirection = 'column';
  const sc = h('div', { class: 'view-scroll' });
  v.append(sc);
  const vfoBox = (t) => {
    const name = h('div', { class: 'big' }, '–'), f = h('div', { class: 'mono muted', style: { fontSize: '12px' } }, ' ');
    return { el: h('div', { class: 'well vfo-box' }, label(t), name, f), name, f };
  };
  const A = vfoBox('VFO A'), B = vfoBox('VFO B');
  const dChip = chip();
  const kOn = key('DOUBLE VEILLE', () => cmd('vfo.dual', { on: 1 }), { cls: 'small' }), kOff = key('UN SEUL VFO', () => cmd('vfo.dual', { on: 0 }), { cls: 'small' });
  kOn.style.width = kOff.style.width = '170px';
  kOn.style.height = kOff.style.height = '42px';
  sc.append(section('Écoute des 2 VFO'), h('div', { class: 'card vfo-card' }, h('div', { class: 'vfo-ab' }, A.el, B.el), h('div', { style: { height: '10px' } }), dChip,
    h('div', { style: { height: '10px' } }), h('div', { class: 'keys', style: { gap: '8px' } }, kOn, kOff), h('div', { style: { height: '8px' } }),
    note('Le poste surveille le VFO A et le VFO B et bascule sur celui qui reçoit (canaux choisis sur le poste). Les décodeurs (APRS, SARSAT, sondes, POCSAG) suivent le VFO qui reçoit.')));

  const stepper = (t, a) => {
    const val = h('div', { class: 'big' }, '–');
    return { el: h('div', { class: 'well stepper' }, h('div', null, label(t), val), h('div', { class: 'keys' }, key('−', () => cmd('vfo.' + a, { d: -1 })), key('+', () => cmd('vfo.' + a, { d: 1 })))), val };
  };
  const vol = stepper('VOLUME DU POSTE (0–15)', 'vol'), sq = stepper('SQUELCH (0 = OUVERT · 9)', 'sq');
  const ptt = key('PTT · MAINTENIR POUR ÉMETTRE', null, { primary: true, cls: 'ptt' });
  let held = false;
  const down = e => { e.preventDefault(); if (held) return; held = true; ptt.setPointerCapture && e.pointerId !== undefined && ptt.setPointerCapture(e.pointerId); cmd('vfo.pttDown'); };
  const up = () => { if (!held) return; held = false; cmd('vfo.pttUp'); };
  ptt.addEventListener('pointerdown', down);
  ptt.addEventListener('pointerup', up);
  ptt.addEventListener('pointercancel', up);
  ptt.addEventListener('lostpointercapture', up);
  ptt.addEventListener('contextmenu', e => e.preventDefault());
  window.addEventListener('blur', up);
  const meter = h('div', { class: 'meter' }, h('i'));
  const gain = choice(['50 %', '100 %', '150 %', '200 %'], 1, i => cmd('vfo.gain', { v: i }));
  gain.keys.forEach(k => { k.style.width = '64px'; k.style.height = '32px'; k.style.minWidth = '0'; });
  const pState = h('div', { class: 'mono', style: { fontSize: '11.5px', marginTop: '8px' } }, ' ');
  sc.append(section('Poste : volume, squelch, émission'), h('div', { class: 'card vfo-card' }, h('div', { class: 'vfo-ab' }, vol.el, sq.el), h('div', { style: { height: '12px' } }),
    h('div', { class: 'ptt-row' }, ptt, h('div', { class: 'col', style: { gap: '6px' } }, label('MICRO DU PC'), meter, h('div', { class: 'keys' }, label('GAIN'), gain))),
    pState, h('div', { style: { height: '4px' } }),
    note('Maintenez le bouton PTT enfoncé pour parler : c\'est le micro du PC qui émet (pas celui de cet appareil). Le poste émet sur la fréquence du VFO actif (ou celle accordée ci-dessous) et repasse en réception au relâchement. Coupure de sécurité après 3 minutes.')));

  const group = h('select', { class: 'field', style: { width: '280px', height: '36px' } });
  group.addEventListener('change', () => cmd('vfo.group', { v: group.selectedIndex }));
  let chSel = null;
  const chg = grid([{ t: 'N°', w: 50, m: 1 }, { t: 'Nom', w: 170 }, { t: 'Réception', w: 120, m: 1 }, { t: 'Émission', w: 150, m: 1 }, { t: 'VFO', w: 70, m: 1 }], {
    cells: r => r.cells, onSelect: r => chSel = r.ch,
    onDouble: r => cmd('vfo.assign', { vfo: 1, ch: r.ch }), onContext: r => cmd('vfo.assign', { vfo: 2, ch: r.ch }),
  });
  chg.style.maxHeight = '330px';
  chg.style.flex = 'none';
  const chState = h('div', { class: 'mono muted', style: { fontSize: '11.5px', marginTop: '8px' } }, ' ');
  const assign = n => { if (chSel == null) { alertBox('Choisissez un canal dans la liste.'); return; } cmd('vfo.assign', { vfo: n, ch: chSel }); };
  const toA = key('METTRE SUR VFO A', () => assign(1), { primary: true, cls: 'small' }), toB = key('METTRE SUR VFO B', () => assign(2), { cls: 'small' });
  toA.style.width = toB.style.width = '170px';
  sc.append(section('Groupes et canaux mémoire'), h('div', { class: 'card vfo-card' },
    h('div', { class: 'keys', style: { gap: '8px' } }, label('GROUPE'), group, toA, toB), h('div', { style: { height: '10px' } }), chg, chState, h('div', { style: { height: '4px' } }),
    note('Double-clic : canal sur le VFO A (clic droit : VFO B). Le changement de groupe recharge les canaux du poste. Rien n\'est modifié dans les canaux eux-mêmes.')));

  const freq = field('', { inputmode: 'decimal' });
  freq.style.font = '18px ' + FONT.mono;
  freq.style.width = '170px';
  const tune = () => cmd('vfo.tune', { f: freq.value });
  freq.addEventListener('keydown', e => { if (e.key === 'Enter') tune(); });
  const kTune = key('ACCORDER', tune, { primary: true }), kBack = key('RETOUR AUX CANAUX', () => cmd('vfo.back'));
  kTune.style.width = '140px'; kTune.style.height = kBack.style.height = '44px'; kBack.style.width = '190px';
  const presets = [['DAPNET 439,9875', '439.9875'], ['APRS 144,800', '144.800'], ['ISS 145,800', '145.800'], ['Appel 2 m 145,500', '145.500'], ['Appel 70 cm 433,500', '433.500']];
  const tState = h('div', { class: 'mono cyan', style: { fontSize: '12px', marginTop: '10px' } }, ' ');
  sc.append(section('Changer de fréquence'), h('div', { class: 'card vfo-card' }, label('FRÉQUENCE (MHz) · RÉCEPTION ET ÉMISSION'), h('div', { style: { height: '6px' } }),
    h('div', { class: 'keys', style: { gap: '8px' } }, freq, kTune, kBack), h('div', { style: { height: '10px' } }),
    h('div', { class: 'keys', style: { gap: '8px' } }, presets.map(p => key(p[0], () => { freq.value = p[1]; tune(); }, { cls: 'small' }))),
    tState, h('div', { style: { height: '6px' } }),
    note('Le mode fréquence du poste est utilisé : aucun canal n\'est modifié en mémoire. L\'émission (PTT, POCSAG) se fait sur la fréquence accordée. Le bandscope se centre sur cette fréquence.')));

  let freqInit = false, groupsKey = '';
  onState('vfo', d => {
    setText(A.name, d.vfoA); A.name.style.color = d.colA; setText(A.f, d.freqA);
    setText(B.name, d.vfoB); B.name.style.color = d.colB; setText(B.f, d.freqB);
    dChip.set(d.chip, d.chipDot, d.chipFg);
    kOn.setSel(d.on && d.dual > 0); kOff.setSel(d.on && d.dual === 0);
    setText(vol.val, d.vol); setText(sq.val, d.sq);
    ptt.setText(d.pttKey);
    ptt.classList.toggle('live', d.ptt);
    meter.firstChild.style.width = Math.min(100, d.mic) + '%';
    meter.firstChild.style.background = d.mic > 90 ? C.red : d.mic > 60 ? C.amber : C.green;
    gain.set(d.gain);
    setText(pState, d.pttState); pState.style.color = d.pttColor;
    const gk = d.groups.join('|');
    if (gk !== groupsKey) { groupsKey = gk; group.replaceChildren(...d.groups.map(g => h('option', null, g))); }
    if (group.selectedIndex !== d.group && document.activeElement !== group) group.selectedIndex = d.group;
    group.disabled = d.groups.length <= 1;
    chg.select(chSel);
    chg.set(d.channels, r => r.ch);
    setText(chState, d.chState);
    setText(tState, d.tuneState);
    if (!freqInit) { freqInit = true; freq.value = d.freq; }
  });
};

/* ---------------------------------------------------------------- SATELLITES */
VIEWS.SATELLITES = function (v) {
  v.classList.add('stack-mobile');
  v.style.gap = '12px';
  const lat = field('', { inputmode: 'decimal' }), lon = field('', { inputmode: 'decimal' });
  lat.style.width = lon.style.width = '110px';
  const okObs = key('OK', () => cmd('sats.obs', { lat: lat.value, lon: lon.value }), { cls: 'small' });
  okObs.style.minWidth = '56px';
  const filt = choice(['TOUS', 'SSTV', 'VISIBLES'], 0, i => cmd('sats.filter', { v: i }));
  const ELS = [0, 10, 20, 30, 45];
  const els = choice(ELS.map(e => e + '°'), 0, i => cmd('sats.minEl', { v: ELS[i] }));
  els.keys.forEach(k => { k.style.minWidth = '48px'; k.style.width = '48px'; k.style.height = '34px'; });
  const search = field('', { placeholder: 'Rechercher (nom ou n° NORAD)' });
  let searchT = null;
  search.addEventListener('input', () => { clearTimeout(searchT); searchT = setTimeout(() => cmd('sats.search', { q: search.value }), 250); });
  const g = grid([{ t: 'Satellite', w: 120 }, { t: 'Élév.', w: 56, m: 1 }, { t: 'Prochain', w: 104, m: 1 }, { t: 'SSTV', w: 48 }], {
    cells: r => [r.n, r.el == null ? '' : Math.round(r.el) + '°', r.next, r.sstv ? '●' : ''],
    cellStyle: (r, j) => j === 3 ? { color: C.purple } : null,
    onSelect: r => cmd('sats.select', { id: r.id }),
    onDouble: r => { if (r.lat != null) satMap.centerOn(r.lat, r.lon); },
  });
  const status = h('div', { class: 'mono muted', style: { fontSize: '11px', padding: '6px 4px 0' } }, ' ');
  const left = h('div', { class: 'sat-left' },
    h('div', { class: 'card', style: { padding: '10px 14px' } }, label('POSITION D\'OBSERVATION (LAT / LON EN DEGRÉS)'), h('div', { class: 'keys', style: { marginTop: '6px' } }, lat, lon, okObs)),
    filt, h('div', { class: 'keys' }, label('ÉLÉV. MIN'), els), search,
    h('div', { class: 'card table-card', style: { padding: '8px 2px 6px' } }, g),
    h('div', { style: { display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: '6px' } },
      key('ACTUALISER', () => cmd('sats.refresh'), { cls: 'small', title: 'Recharger la liste du serveur Benshi (et les TLE CelesTrak si besoin)' }),
      key('AJOUTER…', () => addDialog(), { cls: 'small' }),
      key('SUPPRIMER', async () => {
        const s = S.sats && S.sats.sel;
        if (!s) return;
        if (!s.user) { alertBox('Seuls les satellites ajoutés par vous peuvent être supprimés (le filtre SSTV permet de masquer les autres).'); return; }
        if (await confirmBox('Satellites', 'Supprimer « ' + s.name + ' » ?')) cmd('sats.remove');
      }, { cls: 'small', title: 'Supprimer un satellite ajouté par vous' })),
    status);

  const cv = h('canvas', { class: 'map' });
  const overlay = {
    paint(ctx, m) {
      const d = S.sats;
      if (!d) return;
      const w = m.w, sel = d.sel;
      const poly = (pts, dash, color, width) => {
        if (pts.length < 2) return;
        ctx.beginPath();
        let prev = null;
        for (const p of pts) {
          const s = m.toScreen(p[0], p[1]);
          if (!prev || Math.abs(s.x - prev.x) > w / 2 + 200) ctx.moveTo(s.x, s.y); else ctx.lineTo(s.x, s.y);
          prev = s;
        }
        ctx.setLineDash(dash || []); ctx.strokeStyle = color; ctx.lineWidth = width; ctx.lineCap = 'round'; ctx.lineJoin = 'round'; ctx.stroke(); ctx.setLineDash([]);
      };
      if (sel && sel.track && sel.track.length) {
        poly(sel.track.slice(0, Math.max(1, sel.nowIdx + 1)), [4, 5], alpha(C.amber, 0.59), 1.6);
        poly(sel.track.slice(Math.max(0, sel.nowIdx)), null, alpha(C.amber, 0.86), 2);
      }
      if (sel && sel.look) {
        const l = sel.look, ang = l.foot / 6371, la = l.lat * Math.PI / 180, lo = l.lon * Math.PI / 180, circle = [];
        for (let i = 0; i <= 96; i++) {
          const b = 2 * Math.PI * i / 96;
          const lat2 = Math.asin(Math.sin(la) * Math.cos(ang) + Math.cos(la) * Math.sin(ang) * Math.cos(b));
          const lon2 = lo + Math.atan2(Math.sin(b) * Math.sin(ang) * Math.cos(la), Math.cos(ang) - Math.sin(la) * Math.sin(lat2));
          circle.push([lat2 * 180 / Math.PI, lon2 * 180 / Math.PI]);
        }
        poly(circle, null, alpha(C.cyan, 0.78), 1.6);
      }
      if (d.obs) {
        const p = m.toScreen(d.obs[0], d.obs[1]);
        ctx.strokeStyle = C.amber; ctx.lineWidth = 2;
        ctx.beginPath(); ctx.arc(p.x, p.y, 7, 0, Math.PI * 2); ctx.stroke();
        ctx.beginPath(); ctx.moveTo(p.x - 11, p.y); ctx.lineTo(p.x - 4, p.y); ctx.moveTo(p.x + 4, p.y); ctx.lineTo(p.x + 11, p.y); ctx.stroke();
      }
      ctx.font = 'bold 11px ' + FONT.mono;
      for (const s of d.list) {
        if (s.lat == null) continue;
        const p = m.toScreen(s.lat, s.lon);
        if (p.x < -20 || p.y < -20 || p.x > w + 20 || p.y > m.h + 20) continue;
        const isSel = sel && s.id === sel.id;
        const c = isSel ? C.amber : s.sstv ? C.purple : s.vis ? C.cyan : C.text2, r = isSel ? 7 : 5;
        ctx.fillStyle = 'rgba(0,0,0,.47)'; ctx.beginPath(); ctx.arc(p.x, p.y + 1, r + 1, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = c; ctx.beginPath(); ctx.arc(p.x, p.y, r, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = C.well; ctx.beginPath(); ctx.arc(p.x, p.y, 2, 0, Math.PI * 2); ctx.fill();
        if (isSel || s.vis || m.zoom >= 4 || d.list.length <= 25) {
          const lab = s.n.length > 18 ? s.n.slice(0, 18) + '…' : s.n, lw = ctx.measureText(lab).width + 8;
          ctx.fillStyle = alpha(C.bg, 0.78); roundRect(ctx, p.x + 8, p.y - 9, lw, 16, 3.5); ctx.fill();
          ctx.fillStyle = c; ctx.fillText(lab, p.x + 12, p.y + 3);
        }
      }
      ctx.font = '10.5px ' + FONT.mono;
      const parts = [['● visible   ', C.cyan], ['● SSTV   ', C.purple], ['● sous l\'horizon   ', C.text2], ['— trace   ○ empreinte', C.amber]];
      const tw = parts.reduce((a, p) => a + ctx.measureText(p[0]).width, 0);
      ctx.fillStyle = alpha(C.bg, 0.82); roundRect(ctx, 8, 8, tw + 16, 20, 4); ctx.fill();
      let x = 16;
      for (const p of parts) { ctx.fillStyle = p[1]; ctx.fillText(p[0], x, 22); x += ctx.measureText(p[0]).width; }
    },
    click(m, x, y) {
      const d = S.sats;
      if (!d) return false;
      let best = null, bd = 14;
      for (const s of d.list) {
        if (s.lat == null) continue;
        const p = m.toScreen(s.lat, s.lon), dd = Math.hypot(p.x - x, p.y - y);
        if (dd < bd) { bd = dd; best = s; }
      }
      if (!best) return false;
      cmd('sats.select', { id: best.id });
      return true;
    },
  };
  const dName = h('div', { class: 'big' }, 'Aucun satellite'), dSub = h('div', { class: 'mono muted', style: { fontSize: '11px' } }, ' ');
  const dPos = h('div', { class: 'mono text2', style: { fontSize: '12.5px', marginTop: '8px', lineHeight: '1.5' } });
  const sstvSw = toggle('Satellite SSTV', false, x => cmd('sats.sstv', { on: x ? 1 : 0 }));
  const rfKeys = h('div', { class: 'keys', style: { margin: '4px 0' } }), dRf = h('pre'), dPasses = h('pre');
  const track = key('SUIVRE AVEC LE POSTE', () => cmd('sats.track'), { primary: true });
  track.style.height = '46px'; track.style.width = '260px'; track.style.maxWidth = '100%';
  const detail = h('div', { class: 'card sat-detail' },
    h('div', { class: 'col' }, label('SATELLITE'), dName, dSub, dPos, h('div', { style: { height: '8px' } }), sstvSw),
    h('div', { class: 'col' }, label('RADIO (DOPPLER CORRIGÉ)'), rfKeys, dRf, key('MODIFIER', () => editRf(), { cls: 'small', title: 'Modifier les fréquences de la configuration choisie' }), h('div', { style: { height: '8px' } }), track),
    h('div', { class: 'col' }, label('PASSAGES (24 H, HEURE LOCALE)'), dPasses));
  v.append(left, h('div', { class: 'sat-center' }, h('div', { class: 'well map-well' }, cv), detail));
  const satMap = new TileMap(cv, { key: 'satMap', night: true, zoom: 3, overlay });

  let init = false, rfKey = '';
  onState('sats', d => {
    if (!init) {
      init = true;
      lat.value = d.obsLat; lon.value = d.obsLon; search.value = d.search;
    }
    filt.set(d.filter);
    els.set(ELS.indexOf(d.minEl));
    setText(status, d.status);
    g.select(d.sel ? d.sel.id : null);
    g.set(d.list, r => r.id);
    const s = d.sel;
    if (s) {
      setText(dName, s.name); dName.style.color = s.sstv ? C.amber : C.text;
      setText(dSub, s.sub);
      const l = s.look;
      dPos.innerHTML = '';
      if (!l) dPos.append('Position incalculable (TLE trop ancien ?)');
      else {
        dPos.append('Az ' + l.az.toFixed(1) + '°  ·  Él ', h('b', null, l.el.toFixed(1) + '°'), '  ·  ' + Math.round(l.range) + ' km', h('br'),
          'Alt ' + Math.round(l.alt) + ' km  ·  ' + l.lat.toFixed(3) + ', ' + l.lon.toFixed(3), h('br'),
          l.el > 0 ? h('span', { style: { color: C.cyan } }, 'VISIBLE') : 'sous l\'horizon',
          l.el > 0 ? '  ·  vitesse radiale ' + (l.rr >= 0 ? '+' : '') + l.rr.toFixed(2) + ' km/s' : '');
      }
      sstvSw.set(s.sstv);
      const rk = s.id + ':' + s.rfs.join('|');
      if (rk !== rfKey) {
        rfKey = rk;
        rfKeys.replaceChildren(...s.rfs.map((n, i) => { const k = key(n, () => cmd('sats.rf', { v: i }), { cls: 'small' }); k.style.height = '34px'; k.style.minWidth = '60px'; k.style.fontSize = '11px'; return k; }));
      }
      [...rfKeys.children].forEach((k, i) => k.setSel(i === s.rf));
      setText(dRf, s.rfText);
      setText(dPasses, s.passes);
      track.setText(s.trackKey);
    } else {
      setText(dName, 'Aucun satellite'); dName.style.color = C.text;
      setText(dSub, ' '); dPos.innerHTML = ''; rfKeys.replaceChildren(); setText(dRf, ''); setText(dPasses, ''); rfKey = '';
    }
    satMap.redraw();
  });
  v.onShow = () => satMap.resize();

  async function editRf() {
    const s = S.sats && S.sats.sel;
    if (!s || !s.rfs.length) { toast('Choisissez un satellite avec une configuration radio.', true); return; }
    const e = s.edit;
    const f = { down: field(e.down, { inputmode: 'decimal' }), up: field(e.up, { inputmode: 'decimal' }), tone: field(e.tone, { inputmode: 'decimal' }) };
    const body = h('div', { class: 'col', style: { gap: '4px' } }, caption('Réception (descente), MHz'), f.down, caption('Émission (montée), MHz — vide = pas d'émission'), f.up,
      caption('CTCSS à l'émission, Hz — vide = aucun'), f.tone, e.orig ? caption('Origine : ' + e.orig) : null);
    const btns = [{ text: 'ANNULER', value: 0 }];
    if (e.orig) btns.push({ text: 'RÉTABLIR L'ORIGINE', value: 2 });
    btns.push({ text: 'ENREGISTRER', primary: true, value: 1 });
    const c = await modal(s.name + ' · ' + s.rfs[s.rf], body, btns);
    if (c === 1) cmd('sats.rfEdit', { down: f.down.value, up: f.up.value, tone: f.tone.value });
    else if (c === 2) cmd('sats.rfReset');
  }

  async function addDialog() {
    const f = { name: field(''), norad: field('', { inputmode: 'numeric' }), l1: field(''), l2: field(''), rfName: field('FM'), down: field('', { placeholder: 'descente MHz' }),
      up: field('', { placeholder: 'montée MHz' }), tone: field('', { placeholder: 'CTCSS Hz' }) };
    f.l1.style.font = f.l2.style.font = '12px ' + FONT.mono;
    let sstv = false;
    const sw = toggle('Satellite SSTV', false, x => sstv = x);
    const fetchK = key('RÉCUPÉRER LE TLE', async () => {
      const r = await cmd('sats.fetchTle', { norad: f.norad.value });
      if (r && r.l1) { if (!f.name.value.trim()) f.name.value = r.name; f.l1.value = r.l1; f.l2.value = r.l2; }
    }, { cls: 'small' });
    const body = h('div', null, caption('Nom'), f.name, caption('N° NORAD (pour récupérer le TLE sur CelesTrak)'),
      h('div', { class: 'keys' }, f.norad, fetchK), caption('TLE ligne 1'), f.l1, caption('TLE ligne 2'), f.l2,
      caption('Configuration radio (facultatif) : nom, descente MHz, montée MHz, CTCSS Hz'),
      h('div', { style: { display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: '6px' } }, f.rfName, f.down, f.up, f.tone), sw);
    f.norad.style.width = '120px';
    const ok = await modal('Ajouter un satellite', body, [{ text: 'ANNULER', value: false }, { text: 'AJOUTER', primary: true, value: true }]);
    if (!ok) return;
    const p = { sstv: sstv ? 1 : 0 };
    for (const k in f) p[k] = f[k].value;
    cmd('sats.add', p);
  }
};

/* ---------------------------------------------------------------- JOURNAL */
VIEWS.JOURNAL = function (v) {
  const pre = h('pre');
  v.append(h('div', { class: 'well log-well' }, pre));
  onState('log', d => {
    const atEnd = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 30;
    pre.textContent = d.join('\n');
    if (atEnd) pre.scrollTop = pre.scrollHeight;
  });
  v.onShow = () => { pre.scrollTop = pre.scrollHeight; };
};
