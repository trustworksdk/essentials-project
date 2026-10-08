/* lens-5-metro.js — the Metro map lens (design §10.5): "how do the journeys interleave?"

   Each process is a line in its own colour (ctx.procColor), each slice a station, a slice in two
   or more processes an interchange. Every coordinate comes from layout.metro (C3): x is the global
   causal rank, one horizontal track per line, interchanges on their first line's track with the
   other lines bending to them. This file draws those points and chooses what to label at which
   scale; it moves nothing.

   Declared processes are thick and solid; derived ones (no flow: in any manifest) thin and dashed,
   which is the visual case for declaring them. Stations, line widths and labels keep a constant
   screen size, scaled down with the track spacing when zoomed far out; a station label is drawn
   only where it does not collide with one already placed (interchanges first, then left to right),
   so the level of detail drops instead of text shrinking below Atlas.MIN_TEXT_PX. Line names stay
   pinned to the left edge of the screen while their line is in view. */
(function () {
  'use strict';

  /* Screen sizes, px. */
  const LABEL_PX = 12;
  const LINE_LABEL_PX = 12.5;
  const STATION_PX = 6;          // station radius at full size
  const INTERCHANGE_PX = 8.5;
  const LINE_PX = 6;             // declared line width at full size; derived lines get DERIVED_RATIO of it
  const DERIVED_RATIO = 0.5;
  const MIN_LINE_PX = 1.5;
  const DOT_PX = 4;              // issue dot radius
  const MARK_PX = 15;            // issue marker ring radius
  const CHAR_W = 0.58;           // mean glyph advance / font size, for label boxes
  const STUB = 16;               // world half-length of the stub drawn for a one-station line
  const LABEL_ROOM = 150;        // world units added left of the map for the line names at fit
  const K_STEP = 1.12;           // constant-size geometry is rebuilt each time k moves by this factor
  const CULL_MARGIN = 0.1;
  const FOCUS_PAD = 60;          // screen px around a focused target
  const SLICE_MIN_K = 1.0;       // a focused station is shown at least this large, so its label is drawn
  const SLICE_MAX_K = 2.2;

  const CSS = [
    'svg.lens-svg .mt-line { fill: none; stroke-linecap: round; stroke-linejoin: round; cursor: pointer; }',
    'svg.lens-svg .mt-case { fill: none; stroke: var(--surface); stroke-linecap: round; stroke-linejoin: round; pointer-events: none; }',
    '.mt-line.derived { stroke-dasharray: 8 6; stroke-linecap: butt; }',
    '.mt-faded { opacity: .18; }',
    '.mt-st { cursor: pointer; outline: none; }',
    '.mt-st circle.ring { fill: var(--surface); stroke-width: 2.5; }',
    '.mt-st.ic circle.ring { stroke: var(--text); stroke-width: 3; }',
    '.mt-st:focus-visible circle.ring, .mt-st:hover circle.ring { stroke: var(--accent); stroke-width: 4; }',
    '.mt-st.added circle.ring { stroke: var(--ok); stroke-width: 4; }',
    '.mt-st.changed circle.ring { stroke: var(--warn); stroke-width: 4; }',
    '.mt-sel { fill: none; stroke: var(--accent); stroke-width: 3; pointer-events: none; }',
    'svg.lens-svg text.mt-lab { fill: var(--text); paint-order: stroke; stroke: var(--surface); stroke-width: 3px; stroke-linejoin: round; pointer-events: none; }',
    'svg.lens-svg text.mt-lab.ic { font-weight: 700; }',
    '.mt-pill { cursor: pointer; }',
    '.mt-pill rect { fill: var(--panel-2); stroke-width: 2; }',
    '.mt-pill.derived rect { stroke-dasharray: 5 3; }',
    'svg.lens-svg .mt-pill text { fill: var(--text); pointer-events: none; }',
    '.mt-pill:focus-visible rect, .mt-pill:hover rect { stroke-width: 3.5; }',
    '.mt-empty { padding: 24px; color: var(--text-muted); max-width: 60ch; }',
    '.mt-extra ul { list-style: none; margin: 0; padding: 0; }',
    '.mt-extra li { display: flex; align-items: center; gap: 8px; margin: 3px 0; font-size: 12px; }',
    '.mt-extra li i { width: 22px; height: 0; border-top-width: 4px; border-top-style: solid; flex: 0 0 auto; }',
    '.mt-extra li i.derived { border-top-width: 2px; border-top-style: dashed; }',
    '.mt-extra .lnk { font-family: var(--mono); font-size: 12px; overflow-wrap: anywhere; }'
  ].join('\n');

  let live = null;

  function mount(ctx) {
    const { arr, clamp, trunc } = ctx.util;
    const svg = ctx.svg;
    const M = ctx.layout && ctx.layout.metro;
    if (!M || !arr(M.stations).length) {
      ctx.el.append(ctx.html('p', 'mt-empty', 'No stations to draw: the map has no live slice in a process, or the page was rendered without a layout.'));
      live = { focus: () => false, canFocus: () => false, search: () => null, minimap: () => [], cardExtra: () => null, destroy() {} };
      return;
    }

    /* ---------------- data, as given ---------------- */
    const lines = arr(M.lines).filter(l => arr(l.points).length);
    const stations = arr(M.stations);
    const stBy = new Map(stations.map(s => [s.slice, s]));
    const lineBy = new Map(lines.map(l => [l.process, l]));
    const procName = (pid) => { const p = ctx.index.process(pid); return p ? String(p.name || pid) : String(pid); };
    const stroke = (l) => ctx.procColor({ color: l.color });
    // The y a line's name sits at: the track most of its points lie on (ties: the first point's).
    const homeY = (l) => {
      const n = new Map();
      arr(l.points).forEach(p => n.set(p[1], (n.get(p[1]) || 0) + 1));
      let best = l.points[0][1], bn = 0;
      arr(l.points).forEach(p => { const c = n.get(p[1]); if (c > bn) { bn = c; best = p[1]; } });
      return best;
    };
    const lineInfo = lines.map(l => {
      const xs = l.points.map(p => p[0]), ys = l.points.map(p => p[1]);
      return { l, y: homeY(l), x0: Math.min(...xs), x1: Math.max(...xs), y0: Math.min(...ys), y1: Math.max(...ys), name: procName(l.process) };
    });
    // Track spacing: the smallest gap between two distinct station or line y's; stations and lines
    // shrink with it when zoomed far out, so neighbouring tracks never merge into one band.
    const ys = Array.from(new Set(stations.map(s => s.y).concat(lineInfo.map(i => i.y)))).sort((a, b) => a - b);
    let gap = Infinity;
    for (let i = 1; i < ys.length; i++) gap = Math.min(gap, ys[i] - ys[i - 1]);
    if (!isFinite(gap) || gap <= 0) gap = 40;
    const B = M.bounds || { x: 0, y: 0, w: 1000, h: 400 };
    const bounds = { x: B.x - LABEL_ROOM, y: B.y, w: B.w + LABEL_ROOM, h: B.h };
    const sliceIssues = (id) => ctx.issuesFor({ kind: 'slice', id });
    // A station is labelled with its slice name, or with its full id where that name is not unique.
    const shortName = (id) => { const sl = ctx.index.slice(id); return sl && sl.name ? String(sl.name) : String(id); };
    const nameCount = new Map();
    stations.forEach(s => nameCount.set(shortName(s.slice), (nameCount.get(shortName(s.slice)) || 0) + 1));
    const stationLabel = (id) => nameCount.get(shortName(id)) > 1 ? String(id) : shortName(id);

    /* ---------------- DOM ---------------- */
    const root = svg('svg', { class: 'lens-svg', role: 'group', 'aria-label': 'Metro map: one line per process, one station per slice. Tab walks the stations.' });
    const world = svg('g', { class: 'mt-world' });
    const L = {};
    ['case', 'line', 'station', 'mark', 'label', 'pill'].forEach(n => { L[n] = svg('g', { class: 'mt-l-' + n }); world.append(L[n]); });
    root.append(world);
    ctx.el.append(root);

    const S = { sel: null, selLine: null, mark: null, match: null, kq: null, raf: 0 };
    const stEls = new Map(), lineEls = new Map();

    /* lines: drawn once; widths follow the zoom bucket */
    lines.forEach(l => {
      const pts = l.points.length === 1 ? [[l.points[0][0] - STUB, l.points[0][1]], [l.points[0][0] + STUB, l.points[0][1]]] : l.points;
      const d = pts.map(p => p[0] + ',' + p[1]).join(' ');
      const cs = svg('polyline', { points: d, class: 'mt-case' });
      const pl = svg('polyline', { points: d, class: 'mt-line' + (l.derived ? ' derived' : '') });
      pl.style.stroke = stroke(l);
      pl.addEventListener('click', (ev) => { ev.stopPropagation(); if (!vp.wasDrag()) ctx.focusTarget({ kind: 'process', id: l.process }); });
      pl.addEventListener('mousemove', (ev) => ctx.tip(ev, lineTip(l)));
      pl.addEventListener('mouseleave', () => ctx.hideTip());
      L.case.append(cs); L.line.append(pl);
      lineEls.set(l.process, { pl, cs });
    });

    /* stations: drawn once in station order (rank, track, id), which is also the Tab order */
    stations.forEach(s => {
      const g = svg('g', { class: 'mt-st' + (s.interchange ? ' ic' : ''), tabindex: '0', role: 'button',
        'aria-label': s.slice + (s.interchange ? ', interchange of ' : ', on ') + arr(s.lines).map(procName).join(', ') });
      const ring = svg('circle', { cx: s.x, cy: s.y, r: 1, class: 'ring' });
      if (!s.interchange) {
        const l = lineBy.get(arr(s.lines)[0]);
        if (l) ring.style.stroke = stroke(l);
      }
      g.append(ring);
      const st = ctx.diff ? ctx.diff.state(s.slice) : null;
      if (st === 'added' || st === 'changed') g.classList.add(st);
      const lv = ctx.levelOf(sliceIssues(s.slice));
      const dot = lv ? svg('circle', { cx: s.x, cy: s.y, r: 1, class: 'dot-' + lv }) : null;
      if (dot) { dot.style.pointerEvents = 'none'; g.append(dot); }
      const open = () => ctx.focusTarget({ kind: 'slice', id: s.slice });
      g.addEventListener('click', (ev) => { ev.stopPropagation(); if (!vp.wasDrag()) open(); });
      g.addEventListener('keydown', (ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); open(); } });
      g.addEventListener('focus', () => { const v = vp.view(); if (s.x < v.x || s.x > v.x + v.w || s.y < v.y || s.y > v.y + v.h) vp.centerOn(s.x, s.y); });
      g.addEventListener('mousemove', (ev) => ctx.tip(ev, stationTip(s)));
      g.addEventListener('mouseleave', () => ctx.hideTip());
      L.station.append(g);
      stEls.set(s.slice, { g, ring, dot, s });
    });

    function stationTip(s) {
      const f = document.createDocumentFragment();
      const b = document.createElement('b');
      b.textContent = s.slice;
      const sl = ctx.index.slice(s.slice);
      f.append(b, document.createTextNode('\n' + ((sl && sl.kind) || 'slice') + ' · ' + ((sl && sl.bc) || '?') +
        (s.interchange ? '\ninterchange of ' : '\non ') + arr(s.lines).map(procName).join(', ') +
        (sl && sl.summary ? '\n\n' + trunc(sl.summary, 240) : '')));
      const is = sliceIssues(s.slice);
      if (is.length) f.append(document.createTextNode('\n\n' + is.length + ' issue' + (is.length === 1 ? '' : 's') + ': ' + is.slice(0, 3).map(i => i.code).join(', ')));
      f.append(document.createTextNode('\nclick for details'));
      return f;
    }
    function lineTip(l) {
      const p = ctx.index.process(l.process);
      const n = stations.filter(s => arr(s.lines).includes(l.process)).length;
      return procName(l.process) + (l.derived ? '  (derived — no flow: declares it)' : '  (declared flow)') + '\n' + n + ' station' + (n === 1 ? '' : 's') +
        (p && arr(p.bcs).length ? ' · ' + arr(p.bcs).join(', ') : '') + '\nclick for details';
    }

    const vp = ctx.createViewport({ svg: root, world, bounds, onChange: () => render() });

    /* ---------------- per-frame drawing ---------------- */
    function schedule() {
      if (S.raf) return;
      S.raf = requestAnimationFrame(() => { S.raf = 0; render(); });
    }
    function sizes(kk) {
      const shrink = Math.min(1, (gap * kk) / 24);   // full size once tracks are 24 px apart on screen
      return {
        st: Math.max(1.5, STATION_PX * shrink), ic: Math.max(2, INTERCHANGE_PX * shrink),
        line: Math.max(MIN_LINE_PX, LINE_PX * shrink), dot: Math.max(2, DOT_PX * Math.max(shrink, 0.6))
      };
    }
    function render() {
      const v = vp.view(), k = v.k;
      const kq = Math.round(Math.log(k) / Math.log(K_STEP)), kk = Math.pow(K_STEP, kq);
      const z = sizes(k);
      if (kq !== S.kq) {
        S.kq = kq;
        lines.forEach(l => {
          const e = lineEls.get(l.process), w = l.derived ? Math.max(MIN_LINE_PX, z.line * DERIVED_RATIO) : z.line;
          e.pl.setAttribute('stroke-width', w.toFixed(2));
          e.cs.setAttribute('stroke-width', (w + 3).toFixed(2));
        });
        stEls.forEach(e => {
          const r = (e.s.interchange ? z.ic : z.st) / kk;
          e.ring.setAttribute('r', r.toFixed(3));
          if (e.dot) {
            e.dot.setAttribute('r', (z.dot / kk).toFixed(3));
            e.dot.setAttribute('cx', (e.s.x + r * 0.95).toFixed(2));
            e.dot.setAttribute('cy', (e.s.y + r * 0.95).toFixed(2));
          }
        });
      }
      applyClasses();
      drawMarks(kk, z);
      drawLabels(v, k, z);
      drawPills(v, k);
    }
    function applyClasses() {
      const m = S.match;
      lines.forEach(l => {
        const e = lineEls.get(l.process);
        const faded = (S.selLine && S.selLine !== l.process) || (m && !m.lines.has(l.process));
        e.pl.classList.toggle('mt-faded', !!faded);
        e.cs.classList.toggle('mt-faded', !!faded);
      });
      stEls.forEach((e, id) => {
        const faded = (S.selLine && !arr(e.s.lines).includes(S.selLine)) || (m && !m.slices.has(id));
        e.g.classList.toggle('mt-faded', !!faded);
      });
    }
    function drawMarks(kk, z) {
      L.mark.textContent = '';
      if (S.sel && stEls.has(S.sel)) {
        const s = stEls.get(S.sel).s;
        L.mark.append(svg('circle', { cx: s.x, cy: s.y, r: (((s.interchange ? z.ic : z.st) + 5) / kk).toFixed(3), class: 'mt-sel' }));
      }
      if (!S.mark) return;
      if (S.mark.slice && stEls.has(S.mark.slice)) {
        const s = stEls.get(S.mark.slice).s;
        L.mark.append(svg('circle', { cx: s.x, cy: s.y, r: (MARK_PX / kk).toFixed(3), class: 'mark' }));
      } else if (S.mark.line) {
        const i = lineInfo.find(x => x.l.process === S.mark.line);
        if (i) {
          const p = MARK_PX / kk;
          L.mark.append(svg('rect', { x: i.x0 - p, y: i.y0 - p, width: i.x1 - i.x0 + 2 * p, height: i.y1 - i.y0 + 2 * p, rx: p, class: 'mark' }));
        }
      }
    }
    /* Station labels: constant size, placed greedily in screen space; a label that would collide
       with one already placed, or with a station, is not drawn. Order: the selected station, the
       interchanges, then station order — the same at every frame, so labels do not flicker. */
    function drawLabels(v, k, z) {
      L.label.textContent = '';
      const area = { x: v.x - v.w * CULL_MARGIN, y: v.y - v.h * CULL_MARGIN, w: v.w * (1 + 2 * CULL_MARGIN), h: v.h * (1 + 2 * CULL_MARGIN) };
      const inView = (s) => s.x >= area.x && s.x <= area.x + area.w && s.y >= area.y && s.y <= area.y + area.h;
      const vis = stations.filter(inView);
      if (vis.length > 1500) return;               // far out on a huge estate: dots only
      const placed = [];
      const CELL = 64, grid = new Map();
      const keyOf = (cx, cy) => cx + ':' + cy;
      const hits = (b) => {
        for (let cx = Math.floor(b.x / CELL); cx <= Math.floor((b.x + b.w) / CELL); cx++) {
          for (let cy = Math.floor(b.y / CELL); cy <= Math.floor((b.y + b.h) / CELL); cy++) {
            const l = grid.get(keyOf(cx, cy));
            if (l && l.some(o => o.x < b.x + b.w && b.x < o.x + o.w && o.y < b.y + b.h && b.y < o.y + o.h)) return true;
          }
        }
        return false;
      };
      const put = (b) => {
        placed.push(b);
        for (let cx = Math.floor(b.x / CELL); cx <= Math.floor((b.x + b.w) / CELL); cx++) {
          for (let cy = Math.floor(b.y / CELL); cy <= Math.floor((b.y + b.h) / CELL); cy++) {
            const key = keyOf(cx, cy);
            if (!grid.has(key)) grid.set(key, []);
            grid.get(key).push(b);
          }
        }
      };
      const sx = (x) => (x - v.x) * k, sy = (y) => (y - v.y) * k;
      vis.forEach(s => { const r = (s.interchange ? z.ic : z.st) + 1; put({ x: sx(s.x) - r, y: sy(s.y) - r, w: 2 * r, h: 2 * r }); });
      const order = vis.slice().sort((a, b) => (b.slice === S.sel) - (a.slice === S.sel) || (b.interchange - a.interchange));
      const fs = LABEL_PX / k;
      order.forEach(s => {
        const text = trunc(stationLabel(s.slice), 32);
        const w = text.length * CHAR_W * LABEL_PX + 4, h = LABEL_PX + 3;
        const r = (s.interchange ? z.ic : z.st) + 3;
        const px = sx(s.x), py = sy(s.y);
        const cands = [
          { x: px + r, y: py - r - h, a: 'start' }, { x: px + r, y: py + r, a: 'start' },
          { x: px - r - w, y: py - r - h, a: 'end' }, { x: px - r - w, y: py + r, a: 'end' }
        ];
        const c = cands.find(cd => !hits({ x: cd.x, y: cd.y, w, h }));
        if (!c) return;
        put({ x: c.x, y: c.y, w, h });
        const tx = v.x + (c.a === 'start' ? c.x + 2 : c.x + w - 2) / k, ty = v.y + (c.y + h - 4) / k;
        const t = svg('text', { x: tx.toFixed(2), y: ty.toFixed(2), 'font-size': fs.toFixed(3), 'text-anchor': c.a, class: 'mt-lab' + (s.interchange ? ' ic' : '') }, text);
        const e = stEls.get(s.slice);
        if (e && e.g.classList.contains('mt-faded')) t.classList.add('mt-faded');
        L.label.append(t);
      });
    }
    /* Line names: a pill left of the line's first point, held at the left screen edge while the
       line is in view; clicking it opens the process. */
    function drawPills(v, k) {
      L.pill.textContent = '';
      const fs = LINE_LABEL_PX / k, padX = 7 / k, h = (LINE_LABEL_PX + 8) / k, lead = 10 / k;
      const minGapPx = LINE_LABEL_PX + 10;
      if (gap * k < minGapPx * 0.7 && lineInfo.length > 1) return;   // tracks too close for a name each
      lineInfo.forEach(i => {
        if (i.y < v.y - h || i.y > v.y + v.h + h || i.x1 < v.x) return;
        const text = trunc(i.name, 34);
        const w = text.length * CHAR_W * LINE_LABEL_PX / k + 2 * padX;
        const x = Math.max(i.x0 - lead - w, v.x + 6 / k);
        const g = svg('g', { class: 'mt-pill' + (i.l.derived ? ' derived' : ''), tabindex: '0', role: 'button',
          'aria-label': 'process ' + i.name + (i.l.derived ? ', derived' : ', declared') });
        const rect = svg('rect', { x: x.toFixed(2), y: (i.y - h / 2).toFixed(2), width: w.toFixed(2), height: h.toFixed(2), rx: (h / 2).toFixed(2) });
        rect.style.stroke = stroke(i.l);
        g.append(rect, svg('text', { x: (x + padX).toFixed(2), y: (i.y + fs * 0.36).toFixed(2), 'font-size': fs.toFixed(3) }, text));
        const pi = ctx.levelOf(ctx.issuesFor({ kind: 'process', id: i.l.process }));
        if (pi) g.append(svg('circle', { cx: (x + w).toFixed(2), cy: (i.y - h / 2).toFixed(2), r: (DOT_PX / k).toFixed(3), class: 'dot-' + pi }));
        if ((S.selLine && S.selLine !== i.l.process) || (S.match && !S.match.lines.has(i.l.process))) g.classList.add('mt-faded');
        const open = () => ctx.focusTarget({ kind: 'process', id: i.l.process });
        g.addEventListener('click', (ev) => { ev.stopPropagation(); if (!vp.wasDrag()) open(); });
        g.addEventListener('keydown', (ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); open(); } });
        g.addEventListener('mousemove', (ev) => ctx.tip(ev, lineTip(i.l)));
        g.addEventListener('mouseleave', () => ctx.hideTip());
        L.pill.append(g);
      });
    }

    /* ---------------- lens surface ---------------- */
    function canFocus(t) {
      if (!t) return true;
      if (t.kind === 'slice') return stBy.has(t.id);
      if (t.kind === 'process') return lineBy.has(t.id);
      return false;
    }
    function focus(t, o) {
      o = o || {};
      if (!t) { S.sel = null; S.selLine = null; S.mark = null; schedule(); return true; }
      if (!canFocus(t)) return false;
      const animate = o.animate !== false;
      if (t.kind === 'slice') {
        const s = stBy.get(t.id);
        S.sel = t.id; S.selLine = null;
        S.mark = o.issue ? { slice: t.id } : null;
        vp.flyTo({ x: s.x - 40, y: s.y - 30, w: 80, h: 60 }, { pad: FOCUS_PAD, minScale: SLICE_MIN_K, maxScale: SLICE_MAX_K, animate });
      } else {
        const i = lineInfo.find(x => x.l.process === t.id);
        S.sel = null; S.selLine = t.id;
        S.mark = o.issue ? { line: t.id } : null;
        vp.flyTo({ x: i.x0 - LABEL_ROOM, y: i.y0 - 20, w: i.x1 - i.x0 + LABEL_ROOM + 20, h: i.y1 - i.y0 + 40 }, { pad: FOCUS_PAD, maxScale: SLICE_MAX_K, animate });
      }
      schedule();
      return true;
    }
    function search(q) {
      if (!q) { S.match = null; schedule(); return null; }
      const slices = new Set(), lns = new Set(), keys = new Set();
      stations.forEach(s => {
        const sl = ctx.index.slice(s.slice);
        if (ctx.match(q, sl ? ctx.sliceHay(sl) : s.slice)) { slices.add(s.slice); keys.add(s.slice); arr(s.lines).forEach(p => lns.add(p)); }
      });
      lines.forEach(l => {
        if (ctx.match(q, l.process, procName(l.process))) { lns.add(l.process); keys.add('proc:' + l.process); }
      });
      S.match = { slices, lines: lns };
      schedule();
      return keys;
    }
    function minimap() {
      const thick = Math.max(4, gap * 0.3);
      return lineInfo.map(i => ({ x: i.x0, y: Math.min(i.y0, i.y - thick / 2), w: Math.max(thick, i.x1 - i.x0), h: Math.max(thick, i.y1 - i.y0), cls: 'seg', p: i.l.color }));
    }
    function cardExtra(t) {
      if (!t || t.kind !== 'slice' || !stBy.has(t.id)) return null;
      const s = stBy.get(t.id);
      const sec = document.createElement('section');
      sec.className = 'mt-extra';
      sec.append(ctx.html('h3', null, s.interchange ? 'Interchange of ' + arr(s.lines).length + ' lines' : 'On the metro line'));
      const ul = ctx.html('ul');
      arr(s.lines).forEach(pid => {
        const l = lineBy.get(pid);
        const li = ctx.html('li');
        const sw = ctx.html('i', l && l.derived ? 'derived' : null);
        if (l) sw.style.borderTopColor = stroke(l);
        const b = ctx.html('button', 'lnk', procName(pid) + (l && l.derived ? ' (derived)' : ''));
        b.type = 'button';
        b.addEventListener('click', () => ctx.focusTarget({ kind: 'process', id: pid }));
        li.append(sw, b);
        ul.append(li);
      });
      sec.append(ul);
      return sec;
    }

    live = {
      focus, canFocus, search, minimap, cardExtra,
      destroy() { if (S.raf) cancelAnimationFrame(S.raf); S.raf = 0; }
    };
    render();
  }

  /* ---------------- legend ---------------- */
  function legend(ctx) {
    const f = document.createDocumentFragment();
    const h = (t) => { const e = document.createElement('h3'); e.textContent = t; f.append(e); };
    const row = (cls, style, text) => {
      const r = document.createElement('div'); r.className = 'lg';
      const sw = document.createElement('span'); sw.className = cls;
      Object.assign(sw.style, style);
      r.append(sw, document.createTextNode(text));
      f.append(r);
    };
    h('Lines');
    row('ln', { borderTopColor: 'var(--p0)', borderTopWidth: '5px' }, 'declared process (flow: in the manifests)');
    row('ln', { borderTopColor: 'var(--p0)', borderTopWidth: '2px', borderTopStyle: 'dashed' }, 'derived process — declare it with flow:');
    h('Stations');
    row('dt', { background: 'var(--surface)', border: '2.5px solid var(--p0)' }, 'slice');
    row('dt', { background: 'var(--surface)', border: '3px solid var(--text)', width: '14px', height: '14px' }, 'interchange: a slice in several processes');
    row('dt', { background: 'var(--err)' }, 'error on the slice or process');
    row('dt', { background: 'var(--warn)' }, 'warning');
    row('dt', { background: 'var(--info)' }, 'info');
    if (ctx && ctx.diff) {
      row('dt', { background: 'var(--surface)', border: '3px solid var(--ok)' }, 'added in this change');
      row('dt', { background: 'var(--surface)', border: '3px solid var(--warn)' }, 'changed in this change');
    }
    const procs = ctx ? ctx.util.arr(ctx.data.processes) : [];
    const M = ctx && ctx.layout && ctx.layout.metro;
    const drawn = new Set(M ? ctx.util.arr(M.lines).map(l => l.process) : []);
    const shown = procs.filter(p => drawn.has(p.id));
    if (shown.length) {
      h('Processes');
      shown.slice(0, 24).forEach(p => row('ln', { borderTopColor: ctx.procColor(p), borderTopWidth: p.declared ? '5px' : '2px', borderTopStyle: p.declared ? 'solid' : 'dashed' }, String(p.name || p.id)));
      if (shown.length > 24) {
        const m = document.createElement('div');
        m.style.color = 'var(--text-muted)';
        m.textContent = '+ ' + (shown.length - 24) + ' more';
        f.append(m);
      }
    }
    const p = document.createElement('div');
    p.style.color = 'var(--text-muted)';
    p.style.marginTop = '6px';
    p.textContent = 'x = causal order across the whole map; one track per process. Station names appear where they fit — zoom in for more. Tab walks the stations, Enter opens one.';
    f.append(p);
    return f;
  }

  let lensCtx = null;
  Atlas.registerLens({
    id: 'metro', label: 'Metro', key: '5', css: CSS,
    mount(ctx) { lensCtx = ctx; live = null; mount(ctx); },
    unmount() { if (live) live.destroy(); live = null; lensCtx = null; },
    focus(t, o) { return live ? live.focus(t, o) : false; },
    canFocus(t) { return live ? live.canFocus(t) : true; },
    search(q) { return live ? live.search(q) : null; },
    minimap() { return live ? live.minimap() : []; },
    cardExtra(t) { return live ? live.cardExtra(t) : null; },
    legend() { return legend(lensCtx); }
  });
})();
