/* HTMod web — carte a tuiles (portage de MapPanel.java) et calque de vent (WindLayer.java). */
'use strict';

const C = {
  bg: '#0A0E13', keyTop: '#1A222D', keyBot: '#121821', edge: '#263241', well: '#070A0E', text: '#E8EDF2', text2: '#C3CCD6',
  muted: '#8B97A6', amber: '#F5A524', cyan: '#5FC8E8', red: '#E7625F', green: '#6DD69A', purple: '#C24DD6',
};
const FONT = {
  mono: '"Cascadia Mono", Consolas, "JetBrains Mono", ui-monospace, monospace',
  body: '"Segoe UI", system-ui, -apple-system, Roboto, Arial, sans-serif',
  display: '"Bahnschrift SemiBold Condensed", "Bahnschrift Condensed", Bahnschrift, "Barlow Semi Condensed", sans-serif',
};
function alpha(hex, a) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}
function kindColor(k) { return k === 2 ? C.red : k === 1 ? C.green : C.cyan; }

/** Contour d'epingle (Shack.pinPath). */
function pinPath(ctx, s, oy) {
  const cx = s / 2, top = s * 0.1 + oy, cy = s * 0.42 + oy, tip = s + oy;
  ctx.beginPath();
  ctx.moveTo(cx, tip);
  ctx.bezierCurveTo(cx - s * 0.05, cy + s * 0.28, cx - s * 0.38, cy + s * 0.18, cx - s * 0.38, cy);
  ctx.ellipse(cx, top + s * 0.32, s * 0.38, s * 0.32, 0, Math.PI, 0, false);
  ctx.bezierCurveTo(cx + s * 0.38, cy + s * 0.18, cx + s * 0.05, cy + s * 0.28, cx, tip);
  ctx.closePath();
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

const TILE = 256;
const tileCache = new Map();   // cle -> Image (chargee ou en cours)

class TileMap {
  constructor(canvas, opt) {
    this.cv = canvas;
    this.ctx = canvas.getContext('2d');
    this.key = opt.key || 'map';
    this.onSelect = opt.onSelect || (() => {});
    this.overlay = opt.overlay || null;     // { paint(ctx, map), click(map, x, y) }
    this.underlay = null;
    this.markers = [];
    this.selected = null;
    let v = {};
    try { v = JSON.parse(localStorage.getItem('htmod.' + this.key) || '{}'); } catch (e) {}
    this.night = v.night !== undefined ? v.night : (opt.night !== undefined ? opt.night : true);
    this.topo = v.topo !== undefined ? v.topo : !!opt.topo;
    this.zoom = v.zoom || opt.zoom || 6;
    this.setCenter(v.lat !== undefined ? v.lat : (opt.lat || 46.6), v.lon !== undefined ? v.lon : (opt.lon || 2.4));
    this.fitted = !!v.fitted;
    this.pending = false;
    this.w = 0; this.h = 0;
    this._bind();
    new ResizeObserver(() => this.resize()).observe(canvas);
    this.resize();
  }

  // ------------------------------------------------------------ projection
  ws() { return TILE * Math.pow(2, this.zoom); }
  lonToX(lon) { return (lon + 180) / 360 * this.ws(); }
  latToY(lat) {
    const l = Math.max(-85.05, Math.min(85.05, lat)) * Math.PI / 180;
    return (1 - Math.log(Math.tan(l) + 1 / Math.cos(l)) / Math.PI) / 2 * this.ws();
  }
  xToLon(x) { return x / this.ws() * 360 - 180; }
  yToLat(y) { const n = Math.PI - 2 * Math.PI * y / this.ws(); return Math.atan(Math.sinh(n)) * 180 / Math.PI; }
  setCenter(lat, lon) { this.cx = this.lonToX(lon); this.cy = this.latToY(lat); }
  clampY() { this.cy = Math.max(0, Math.min(this.ws(), this.cy)); }
  maxZoom() { return this.topo ? 17 : 19; }
  toScreen(lat, lon) {
    const ws = this.ws();
    let x = this.lonToX(lon);
    const y = this.latToY(lat);
    while (x - this.cx > ws / 2) x -= ws;
    while (x - this.cx < -ws / 2) x += ws;
    return { x: x - this.cx + this.w / 2, y: y - this.cy + this.h / 2 };
  }
  fromScreen(x, y) { return { lat: this.yToLat(this.cy - this.h / 2 + y), lon: this.xToLon(this.cx - this.w / 2 + x) }; }
  pixelsPerKm(lat) { return this.ws() / (40075.016686 * Math.cos(Math.max(-85, Math.min(85, lat)) * Math.PI / 180)); }

  save() {
    try {
      localStorage.setItem('htmod.' + this.key, JSON.stringify({ night: this.night, topo: this.topo, zoom: this.zoom,
        lat: this.yToLat(this.cy), lon: this.xToLon(this.cx), fitted: this.fitted }));
    } catch (e) {}
  }

  zoomAt(dz, sx, sy) {
    const nz = Math.max(2, Math.min(this.maxZoom(), this.zoom + dz));
    if (nz === this.zoom) return;
    const lat = this.yToLat(this.cy + sy - this.h / 2), lon = this.xToLon(this.cx + sx - this.w / 2);
    this.zoom = nz;
    this.cx = this.lonToX(lon) - (sx - this.w / 2);
    this.cy = this.latToY(lat) - (sy - this.h / 2);
    this.clampY();
    this.save();
    this.redraw();
  }
  zoomIn() { this.zoomAt(1, this.w / 2, this.h / 2); }
  zoomOut() { this.zoomAt(-1, this.w / 2, this.h / 2); }
  centerOn(lat, lon) { this.setCenter(lat, lon); this.save(); this.redraw(); }
  setNight(v) { this.night = v; this.save(); this.redraw(); }
  setTopo(v) { this.topo = v; if (v && this.zoom > 17) this.zoom = 17; this.save(); this.redraw(); }

  focus(key) {
    const m = this.markers.find(x => x.key === key);
    if (!m) return;
    if (this.zoom < 11) this.zoom = 11;
    this.setCenter(m.lat, m.lon);
    this.selected = key;
    this.onSelect(m);
    this.save();
    this.redraw();
  }

  fitAll() {
    const all = this.markers;
    if (!all.length || !this.w) return;
    let a = 90, b = -90, c = 180, d = -180;
    for (const m of all) { a = Math.min(a, m.lat); b = Math.max(b, m.lat); c = Math.min(c, m.lon); d = Math.max(d, m.lon); }
    for (this.zoom = 13; this.zoom > 2; this.zoom--) {
      const w = this.lonToX(d) - this.lonToX(c), h = this.latToY(a) - this.latToY(b);
      if (w < this.w * 0.8 && h < this.h * 0.75) break;
    }
    this.cx = (this.lonToX(c) + this.lonToX(d)) / 2;
    this.cy = (this.latToY(a) + this.latToY(b)) / 2;
    this.save();
    this.redraw();
  }

  setMarkers(list) {
    this.markers = list || [];
    if (!this.fitted && this.markers.length && this.w) {
      const m = this.markers[this.markers.length - 1];
      this.fitted = true;
      this.zoom = Math.max(this.zoom, 9);
      this.setCenter(m.lat, m.lon);
      this.save();
    }
    if (this.selected) {
      const m = this.markers.find(x => x.key === this.selected);
      if (m) this.onSelect(m); else { this.selected = null; this.onSelect(null); }
    }
    this.redraw();
  }

  // ------------------------------------------------------------ interaction
  _bind() {
    const cv = this.cv, pts = new Map();
    let drag = null, pinch = null, lastTap = 0;
    cv.addEventListener('pointerdown', e => {
      cv.setPointerCapture(e.pointerId);
      pts.set(e.pointerId, { x: e.offsetX, y: e.offsetY });
      if (pts.size === 1) drag = { x: e.offsetX, y: e.offsetY, cx: this.cx, cy: this.cy, moved: false };
      else if (pts.size === 2) {
        const [p, q] = [...pts.values()];
        pinch = { d: Math.hypot(p.x - q.x, p.y - q.y), mx: (p.x + q.x) / 2, my: (p.y + q.y) / 2 };
        drag = null;
      }
    });
    cv.addEventListener('pointermove', e => {
      if (!pts.has(e.pointerId)) return;
      pts.set(e.pointerId, { x: e.offsetX, y: e.offsetY });
      if (pinch && pts.size === 2) {
        const [p, q] = [...pts.values()];
        const d = Math.hypot(p.x - q.x, p.y - q.y);
        if (d > pinch.d * 1.45) { this.zoomAt(1, pinch.mx, pinch.my); pinch.d = d; }
        else if (d < pinch.d / 1.45) { this.zoomAt(-1, pinch.mx, pinch.my); pinch.d = d; }
        return;
      }
      if (!drag) return;
      const dx = e.offsetX - drag.x, dy = e.offsetY - drag.y;
      if (Math.hypot(dx, dy) > 4) { drag.moved = true; cv.classList.add('drag'); }
      if (drag.moved) {
        this.cx = drag.cx - dx;
        this.cy = drag.cy - dy;
        this.clampY();
        this.redraw();
      }
    });
    const up = e => {
      if (!pts.has(e.pointerId)) return;
      pts.delete(e.pointerId);
      cv.classList.remove('drag');
      if (pts.size < 2) pinch = null;
      if (drag && !drag.moved && e.type === 'pointerup') {
        const now = Date.now(), dbl = now - lastTap < 350;
        lastTap = now;
        this._click(e.offsetX, e.offsetY, dbl);
      }
      if (drag) this.save();
      drag = null;
    };
    cv.addEventListener('pointerup', up);
    cv.addEventListener('pointercancel', up);
    cv.addEventListener('wheel', e => { e.preventDefault(); this.zoomAt(e.deltaY < 0 ? 1 : -1, e.offsetX, e.offsetY); }, { passive: false });
    cv.addEventListener('contextmenu', e => e.preventDefault());
  }

  _click(x, y, dbl) {
    if (this.overlay && this.overlay.click && this.overlay.click(this, x, y)) { this.redraw(); return; }
    let best = null, bd = 26;
    for (const m of this.markers) {
      const s = this.toScreen(m.lat, m.lon);
      const d = Math.hypot(s.x - x, s.y - 22 - y);
      if (d < bd) { bd = d; best = m; }
    }
    if (!best && dbl) { this.zoomAt(1, x, y); return; }
    this.selected = best ? best.key : null;
    this.onSelect(best);
    this.redraw();
  }

  // ------------------------------------------------------------ dessin
  resize() {
    const r = this.cv.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
    if (r.width < 2 || r.height < 2) return; // carte cachee (autre onglet)
    this.w = Math.max(1, Math.round(r.width));
    this.h = Math.max(1, Math.round(r.height));
    this.cv.width = Math.round(this.w * dpr);
    this.cv.height = Math.round(this.h * dpr);
    this.dpr = dpr;
    if (this.underlay && this.underlay.reset) this.underlay.reset();
    this.draw();
  }

  redraw() {
    if (this.pending) return;
    this.pending = true;
    requestAnimationFrame(() => { this.pending = false; this.draw(); });
  }

  tile(z, x, y) {
    const style = (this.topo ? 't' : 'o') + (this.night ? 'n' : 'j');
    const k = style + '/' + z + '/' + x + '/' + y;
    let img = tileCache.get(k);
    if (!img) {
      img = new Image();
      img.decoding = 'async';
      img.onload = () => { img.ok = true; this.redraw(); };
      img.onerror = () => { img.failed = true; };
      img.src = '/tiles/' + k + '.png';
      tileCache.set(k, img);
      if (tileCache.size > 900) { const first = tileCache.keys().next().value; tileCache.delete(first); }
    }
    return img.ok ? img : null;
  }

  parentTile(z, x, y) {
    if (z <= 0) return null;
    const style = (this.topo ? 't' : 'o') + (this.night ? 'n' : 'j');
    const img = tileCache.get(style + '/' + (z - 1) + '/' + (x >> 1) + '/' + (y >> 1));
    return img && img.ok ? img : null;
  }

  draw() {
    const ctx = this.ctx, w = this.w, h = this.h;
    if (!w || !this.cv.isConnected || !this.cv.offsetParent) return; // onglet cache : rien a dessiner
    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    ctx.fillStyle = this.night ? C.well : '#E6E2DA';
    ctx.fillRect(0, 0, w, h);
    ctx.imageSmoothingEnabled = true;
    const ox = this.cx - w / 2, oy = this.cy - h / 2, n = 1 << this.zoom;
    const tx0 = Math.floor(ox / TILE), ty0 = Math.floor(oy / TILE), tx1 = Math.floor((ox + w) / TILE), ty1 = Math.floor((oy + h) / TILE);
    for (let ty = ty0; ty <= ty1; ty++) {
      if (ty < 0 || ty >= n) continue;
      for (let tx = tx0; tx <= tx1; tx++) {
        const wx = ((tx % n) + n) % n;
        const px = Math.round(tx * TILE - ox), py = Math.round(ty * TILE - oy);
        const img = this.tile(this.zoom, wx, ty);
        if (img) ctx.drawImage(img, px, py, TILE, TILE);
        else {
          const p = this.parentTile(this.zoom, wx, ty);
          if (p) { const sx = (wx % 2) * 128, sy = (ty % 2) * 128; ctx.drawImage(p, sx, sy, 128, 128, px, py, TILE, TILE); }
          else { ctx.strokeStyle = this.night ? C.edge : '#D0CBC0'; ctx.lineWidth = 1; ctx.strokeRect(px + 0.5, py + 0.5, TILE, TILE); }
        }
      }
    }
    if (this.underlay) { ctx.save(); this.underlay.paint(ctx, this); ctx.restore(); }

    // traces puis epingles (selection au-dessus)
    const all = this.markers.slice().sort((a, b) => a.key === this.selected ? 1 : b.key === this.selected ? -1 : a.time - b.time);
    for (const m of all) {
      if (!m.track || m.track.length < 2) continue;
      const sel = m.key === this.selected;
      ctx.beginPath();
      m.track.forEach((p, i) => { const x = this.lonToX(p[1]) - ox, y = this.latToY(p[0]) - oy; i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); });
      ctx.strokeStyle = alpha(kindColor(m.kind), sel ? 0.9 : 0.59);
      ctx.lineWidth = sel ? 3 : 2;
      ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      ctx.stroke();
    }
    for (const m of all) {
      const x = this.lonToX(m.lon) - ox, y = this.latToY(m.lat) - oy;
      if (x < -60 || y < -80 || x > w + 60 || y > h + 60) continue;
      this.drawPin(ctx, Math.round(x), Math.round(y), m, m.key === this.selected);
    }
    if (this.overlay) { ctx.save(); this.overlay.paint(ctx, this); ctx.restore(); }

    // attribution (obligatoire) et position
    const attr = this.topo ? '© OpenStreetMap, SRTM · © OpenTopoMap (CC-BY-SA)' : '© contributeurs OpenStreetMap';
    ctx.font = '11px ' + FONT.body;
    const aw = ctx.measureText(attr).width + 12;
    ctx.fillStyle = alpha(C.bg, 0.78);
    roundRect(ctx, w - aw - 6, h - 22, aw, 18, 4); ctx.fill();
    ctx.fillStyle = C.text2;
    ctx.fillText(attr, w - aw, h - 9);
    const z = 'Z' + this.zoom + '  ' + this.yToLat(this.cy).toFixed(5) + ', ' + this.xToLon(this.cx).toFixed(5);
    ctx.font = '11px ' + FONT.mono;
    const zw = ctx.measureText(z).width + 14;
    ctx.fillStyle = alpha(C.bg, 0.78);
    roundRect(ctx, 8, h - 24, zw, 18, 4); ctx.fill();
    ctx.fillStyle = C.muted;
    ctx.fillText(z, 15, h - 11);
  }

  drawPin(ctx, x, y, m, sel) {
    const s = sel ? 40 : 32, c = kindColor(m.kind);
    ctx.save();
    ctx.translate(x - s / 2, y - s);
    pinPath(ctx, s, 3); ctx.fillStyle = 'rgba(0,0,0,0.35)'; ctx.fill();
    pinPath(ctx, s, 0); ctx.fillStyle = c; ctx.fill();
    if (sel) { ctx.strokeStyle = C.amber; ctx.lineWidth = 2; ctx.stroke(); }
    const r = s * 0.24, ccx = s / 2, ccy = s * 0.42;
    ctx.beginPath(); ctx.arc(ccx, ccy, r, 0, Math.PI * 2); ctx.fillStyle = C.well; ctx.fill();
    ctx.font = 'bold ' + (s * 0.32) + 'px ' + FONT.mono;
    ctx.fillStyle = c; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText(m.sym || '•', ccx, ccy + 0.5);
    ctx.restore();
    ctx.font = 'bold 11.5px ' + FONT.mono;
    const lw = ctx.measureText(m.title).width + 10, lx = x - lw / 2, ly = y + 3;
    ctx.fillStyle = alpha(C.bg, 0.85);
    roundRect(ctx, lx, ly, lw, 17, 4); ctx.fill();
    ctx.fillStyle = sel ? C.amber : C.text;
    ctx.textAlign = 'left'; ctx.textBaseline = 'alphabetic';
    ctx.fillText(m.title, lx + 5, ly + 13);
  }
}

/* ---------------------------------------------------------------- vent anime (WindLayer.java) */
class WindLayer {
  constructor(map, log) {
    this.map = map;
    this.log = log || (() => {});
    this.on = false;
    this.grid = null;
    this.fetching = false;
    this.lastFetch = 0;
    this.failures = 0;
    this.status = '';
    this.px = new Float32Array(0); this.py = new Float32Array(0); this.age = new Int32Array(0);
    this.trail = document.createElement('canvas');
    this.ref = null;
    this.timer = null;
  }
  setOn(v) {
    this.on = v;
    this.reset();
    if (v && !this.timer) {
      const loop = () => { if (!this.on) { this.timer = null; return; } this.map.redraw(); this.timer = setTimeout(loop, 33); };
      loop();
    }
    this.map.redraw();
  }
  reset() { this.ref = null; }

  maybeFetch(latN, latS, lonW, lonE) {
    const g = this.grid, now = Date.now();
    if (this.fetching || now - this.lastFetch < (this.failures ? 15000 * Math.min(this.failures, 8) : 1500)) return;
    const need = !g || now - g.time > 30 * 60000 || g.zoom !== this.map.zoom || latN > g.lat1 || latS < g.lat0 || lonW < g.lon0 || lonE > g.lon1;
    if (!need) return;
    const dLat = (latN - latS) * 0.2, dLon = (lonE - lonW) * 0.2;
    const la1 = Math.min(85, latN + dLat), la0 = Math.max(-85, latS - dLat);
    let lo0 = lonW - dLon, lo1 = lonE + dLon;
    if (lo1 - lo0 > 360) { const c = (lo0 + lo1) / 2; lo0 = c - 180; lo1 = c + 180; }
    this.fetching = true;
    this.lastFetch = now;
    const COLS = 12, ROWS = 8, lats = [], lons = [];
    for (let r = 0; r < ROWS; r++) for (let c = 0; c < COLS; c++) {
      lats.push((la0 + (la1 - la0) * r / (ROWS - 1)).toFixed(3));
      const lo = lo0 + (lo1 - lo0) * c / (COLS - 1);
      lons.push((((lo + 180) % 360 + 360) % 360 - 180).toFixed(3));
    }
    const zoom = this.map.zoom;
    fetch('https://api.open-meteo.com/v1/forecast?latitude=' + lats.join(',') + '&longitude=' + lons.join(',')
      + '&current=wind_speed_10m,wind_direction_10m&wind_speed_unit=kmh&timezone=UTC')
      .then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(j => {
        const arr = Array.isArray(j) ? j : [j];
        const u = new Float32Array(COLS * ROWS), v = new Float32Array(COLS * ROWS);
        let max = 0;
        arr.forEach((o, i) => {
          const s = o.current.wind_speed_10m, d = o.current.wind_direction_10m * Math.PI / 180;
          u[i] = -s * Math.sin(d); v[i] = -s * Math.cos(d); max = Math.max(max, s);
        });
        this.grid = { lat0: la0, lat1: la1, lon0: lo0, lon1: lo1, u, v, zoom, time: Date.now(), COLS, ROWS };
        this.failures = 0;
        this.status = 'max ' + Math.round(max) + ' km/h';
      })
      .catch(e => { this.failures++; this.status = 'indisponible'; if (this.failures === 1) this.log('Vent (Open-Meteo) : ' + e.message); })
      .finally(() => { this.fetching = false; });
  }

  sample(g, lat, lon) {
    while (lon < g.lon0) lon += 360;
    while (lon > g.lon1 && lon - 360 >= g.lon0) lon -= 360;
    const fx = (lon - g.lon0) / (g.lon1 - g.lon0) * (g.COLS - 1), fy = (lat - g.lat0) / (g.lat1 - g.lat0) * (g.ROWS - 1);
    if (fx < 0 || fy < 0 || fx > g.COLS - 1 || fy > g.ROWS - 1) return null;
    const x0 = Math.min(fx | 0, g.COLS - 2), y0 = Math.min(fy | 0, g.ROWS - 2), ax = fx - x0, ay = fy - y0;
    const i00 = y0 * g.COLS + x0, i10 = i00 + 1, i01 = i00 + g.COLS, i11 = i01 + 1;
    return [(g.u[i00] * (1 - ax) + g.u[i10] * ax) * (1 - ay) + (g.u[i01] * (1 - ax) + g.u[i11] * ax) * ay,
            (g.v[i00] * (1 - ax) + g.v[i10] * ax) * (1 - ay) + (g.v[i01] * (1 - ax) + g.v[i11] * ax) * ay];
  }

  palette(night) {
    return night ? ['rgba(180,200,220,.67)', 'rgba(120,220,255,.78)', 'rgba(90,240,170,.82)', 'rgba(250,230,90,.86)', 'rgba(255,170,60,.9)', 'rgba(255,100,70,.94)', 'rgba(255,70,200,.98)']
      : ['rgba(40,70,110,.59)', 'rgba(20,110,190,.75)', 'rgba(10,140,110,.82)', 'rgba(200,140,0,.86)', 'rgba(220,90,0,.9)', 'rgba(200,30,30,.94)', 'rgba(160,0,160,.98)'];
  }

  paint(ctx, m) {
    if (!this.on) return;
    const w = m.w, h = m.h;
    const nw = m.fromScreen(0, 0), se = m.fromScreen(w, h);
    let lonE = se.lon;
    if (lonE < nw.lon) lonE += 360;
    this.maybeFetch(nw.lat, se.lat, nw.lon, lonE);
    const g = this.grid;
    if (g) {
      const ref = m.toScreen(0, 0), dpr = m.dpr;
      if (!this.ref || this.trail.width !== Math.round(w * dpr) || this.trail.height !== Math.round(h * dpr) || this.ref.z !== m.zoom
          || Math.abs(ref.x - this.ref.x) > 0.5 || Math.abs(ref.y - this.ref.y) > 0.5) {
        this.trail.width = Math.round(w * dpr);
        this.trail.height = Math.round(h * dpr);
        this.ref = { x: ref.x, y: ref.y, z: m.zoom };
        this.seed(w, h, true);
      }
      this.step(g, m, w, h);
      ctx.drawImage(this.trail, 0, 0, w, h);
    }
    // legende
    const s = 'VENT 10 m · ' + (g ? this.status : (this.fetching ? 'chargement…' : (this.status || '…'))) + ' · Open-Meteo';
    ctx.font = '11px ' + FONT.mono;
    const tw = ctx.measureText(s).width, pal = this.palette(true), bw = 12 * pal.length;
    const ly = w < 640 ? 62 : 12; // ecran etroit : sous la rangee de touches
    ctx.fillStyle = alpha(C.bg, 0.82);
    roundRect(ctx, 12, ly, tw + bw + 30, 24, 5); ctx.fill();
    ctx.fillStyle = C.text2;
    ctx.fillText(s, 22, ly + 16);
    pal.forEach((p, k) => { ctx.fillStyle = p.replace(/,[.\d]+\)$/, ',1)'); ctx.fillRect(12 + tw + 18 + k * 12, ly + 9, 12, 6); });
  }

  seed(w, h, all) {
    const n = Math.max(300, Math.min(4000, Math.round(w * h / 1100)));
    if (this.px.length !== n) { this.px = new Float32Array(n); this.py = new Float32Array(n); this.age = new Int32Array(n); all = true; }
    if (all) for (let i = 0; i < n; i++) this.respawn(i, w, h, true);
  }
  respawn(i, w, h, randomAge) { this.px[i] = Math.random() * w; this.py[i] = Math.random() * h; this.age[i] = randomAge ? (Math.random() * 90) | 0 : 0; }

  step(g, m, w, h) {
    this.seed(w, h, false);
    const t = this.trail.getContext('2d'), dpr = m.dpr;
    t.setTransform(dpr, 0, 0, dpr, 0, 0);
    t.globalCompositeOperation = 'destination-in';
    t.fillStyle = 'rgba(0,0,0,0.88)';
    t.fillRect(0, 0, w, h);
    t.globalCompositeOperation = 'source-over';
    t.lineWidth = 1.5; t.lineCap = 'round';
    const STEPS = [5, 10, 20, 30, 45, 65], paths = STEPS.map(() => new Path2D()).concat([new Path2D()]);
    const scale = 0.075;
    for (let i = 0; i < this.px.length; i++) {
      if (++this.age[i] > 70 + (i % 40)) { this.respawn(i, w, h, false); continue; }
      const ll = m.fromScreen(this.px[i], this.py[i]);
      const uv = this.sample(g, ll.lat, ll.lon);
      if (!uv) { this.respawn(i, w, h, false); continue; }
      const s = Math.hypot(uv[0], uv[1]);
      const nx = this.px[i] + uv[0] * scale, ny = this.py[i] - uv[1] * scale;
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) { this.respawn(i, w, h, false); continue; }
      let k = 0;
      while (k < STEPS.length && s > STEPS[k]) k++;
      paths[k].moveTo(this.px[i], this.py[i]);
      paths[k].lineTo(nx, ny);
      this.px[i] = nx; this.py[i] = ny;
    }
    const pal = this.palette(m.night);
    paths.forEach((p, k) => { t.strokeStyle = pal[k]; t.stroke(p); });
  }
}
