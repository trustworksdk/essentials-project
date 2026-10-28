/* lens-3-lineage.js — the Information-completeness lens (design §10.3): "where does this field come from?"

   Every command, event and read model is a box listing its fields (payload fields from slice-source,
   identity first; read-model columns from the manifest). Boxes stand in three columns — Commands,
   Events, Read models — inside one band per bounded context, so the causal direction of the board
   (command → event → view) reads left to right everywhere. C3 carries no lineage geometry, so this
   lens places the boxes itself, deterministically: bands in context id order (no context last),
   boxes by name, row-major in a grid whose column count per kind is the same in every band, and the
   bands wrapped into as many columns as keep the drawing about as wide as a screen.

   Selecting a field — in the field index on the left, on any row of any box, or from the detail
   card — lights up every box in ATLAS.lineage.fields[<normalised name>] and draws the path between
   them: command → event (a slice handles the one and publishes the other), event → read model (the
   read model's slice consumes or projects the event), and event → command (an automation dispatches
   on it). Lineage is shown, never judged: the only issues are the precomputed
   completeness-unsourced-column ones, drawn on the read-model row they name. Columns on
   ATLAS.lineage.technicalColumns are muted and listed in the legend. A message without payload
   facts, or a read model without columns, says "not checked"; with neither anywhere, the whole lens
   does. Hash key: lineage.field=<normalised name>. */
(function () {
  'use strict';

  /* Geometry, world px. */
  const BOX_W = 236;
  const HEAD_H = 30;            // box header: the name
  const ROW_H = 19;             // one field row
  const BOX_PAD = 8;
  const GAP_X = 22, GAP_Y = 18; // between boxes of one cell
  const CELL_GAP = 150;         // between the Commands, Events and Read-models columns (room for edges)
  const BAND_HEAD = 34, BAND_PAD = 16, BAND_GAP = 44;
  const MARGIN = 48;
  const ROWS_PER_SUBCOL = 6;    // a cell grows a sub-column per this many boxes …
  const MAX_SUBCOLS = 4;        // … up to this many
  const ASPECT = 1.6;           // width : height the band columns aim for
  const COL_GAP = 120;          // between band columns
  const IDLE_EDGE_K = 0.25;     // below this scale only a traced field's edges are drawn
  /* Text. World sizes scale with the zoom; *_PX are constant screen sizes (drawn at px / k). */
  const NAME_SIZE = 14;
  const ROW_SIZE = 12.5;
  const LABEL_PX = 12;
  const CHAR_W = 0.57, MONO_W = 0.62;
  const K_STEP = 1.12;
  const CULL_MARGIN = 0.15;
  const FOCUS_PAD = 40;
  const MARK_PAD_PX = 6;
  const DOT_PX = 4.5;

  const TYPES = ['command', 'event', 'readModel'];
  const TYPE_LABEL = { command: 'Commands', event: 'Events', readModel: 'Read models' };
  const ROLE = { command: 'command', event: 'event', readModel: 'view' };
  const SHORT = { command: 'C', event: 'E', readModel: 'R' };

  const CSS = [
    '.lens-root[data-lens="lineage"] { display: flex; }',
    '.lin-side { flex: 0 0 272px; width: 272px; display: flex; flex-direction: column; min-height: 0; background: var(--panel);',
    '  border-right: 1px solid var(--rule); font-size: 13px; }',
    '.lin-head { padding: 10px 12px 8px; border-bottom: 1px solid var(--rule); }',
    '.lin-head h2 { margin: 0 0 4px; font-size: 13px; font-weight: 600; text-transform: uppercase; letter-spacing: .05em; color: var(--text-muted); }',
    '.lin-head p { margin: 0; font-size: 12px; color: var(--text-muted); }',
    '.lin-sel { padding: 8px 12px; border-bottom: 1px solid var(--rule); background: var(--panel-2); }',
    '.lin-sel[hidden] { display: none; }',
    '.lin-sel .nm { font-family: var(--mono); font-weight: 700; font-size: 13px; overflow-wrap: anywhere; }',
    '.lin-sel .grp { margin-top: 4px; font-size: 12px; }',
    '.lin-sel .grp > span { color: var(--text-muted); margin-right: 4px; }',
    '.lin-sel .lnk { font-size: 12px; margin-right: 8px; }',
    '.lin-list { overflow-y: auto; flex: 1 1 auto; padding: 4px 0 12px; overscroll-behavior: contain; }',
    '.lin-f { display: flex; width: 100%; align-items: baseline; gap: 6px; border: 0; border-left: 3px solid transparent; background: none;',
    '  padding: 4px 12px 4px 9px; text-align: left; cursor: pointer; font: inherit; font-size: 12.5px; color: var(--text); }',
    '.lin-f:hover { background: var(--panel-strong); }',
    '.lin-f:focus-visible, .lin-sel button:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }',
    '.lin-f[aria-pressed="true"] { background: var(--panel-strong); border-left-color: var(--accent); font-weight: 600; }',
    '.lin-f.dim { opacity: .4; }',
    '.lin-f .fn { font-family: var(--mono); flex: 1 1 auto; overflow-wrap: anywhere; }',
    '.lin-f .fc { font-size: 11.5px; color: var(--text-muted); white-space: nowrap; }',
    '.lin-f .tag { font-size: 11px; padding: 0 5px; border-radius: 4px; border: 1px solid var(--rule-strong); color: var(--text-muted); white-space: nowrap; }',
    '.lin-f .tag.uns { border-color: var(--warn); color: var(--warn); }',
    '.lin-foot { padding: 8px 12px 12px; border-top: 1px solid var(--rule); font-size: 12px; color: var(--text-muted); }',
    '.lin-stage { position: relative; flex: 1 1 auto; min-width: 0; }',
    '.lin-empty { padding: 28px; max-width: 64ch; }',
    '.lin-empty h2 { font-size: 16px; margin: 0 0 8px; }',
    '.lin-empty p { color: var(--text-muted); margin: 0 0 10px; }',
    '.lin-band { fill: var(--ctx-fill); stroke: var(--rule-strong); stroke-width: 1.2; }',
    'svg.lens-svg text.lin-muted { fill: var(--text-muted); }',
    'svg.lens-svg text.lin-strong { font-weight: 700; }',
    'svg.lens-svg text.lin-halo { paint-order: stroke; stroke: var(--surface); stroke-width: 3px; stroke-linejoin: round; }',
    'svg.lens-svg .st text.lin-cell { font-family: var(--mono); }',
    'svg.lens-svg .st text.lin-tech { opacity: .55; }',
    'svg.lens-svg .st text.lin-nc { font-style: italic; opacity: .7; }',
    'svg.lens-svg .st text.lin-on { font-weight: 700; }',
    'svg.lens-svg .st text.lin-uns { fill: var(--warn); }',
    'svg.lens-svg .lin-nopt { pointer-events: none; }',
    '.lin-row { fill: transparent; cursor: pointer; }',
    '.lin-row:hover { fill: var(--surface); fill-opacity: .45; }',
    '.lin-row.on { fill: var(--surface); fill-opacity: .8; stroke: var(--accent); stroke-width: 2; }',
    '.lin-box.on > rect.bg { stroke: var(--accent); stroke-width: 3; }',
    '.lin-box:focus { outline: none; }',
    '.lin-box:focus-visible > rect.bg { stroke: var(--accent); stroke-width: 3.5; stroke-dasharray: 4 2; }',
    '.ed.lin-idle { stroke-opacity: .45; }',
    '.ed.lin-path { stroke: var(--accent); stroke-width: 2.8; stroke-dasharray: none; }',
    '.lin-arrow-publishes, .lin-arrow-feeds { fill: var(--evt-rule); }',
    '.lin-arrow-dispatches { fill: var(--cmd-rule); }',
    '.lin-arrow-path { fill: var(--accent); }',
    '.lin-dot { cursor: pointer; }',
    '@media (max-width: 760px) {',
    '  .lens-root[data-lens="lineage"] { flex-direction: column; }',
    '  .lin-side { flex: 0 0 auto; width: auto; max-height: 38%; border-right: 0; border-bottom: 1px solid var(--rule); }',
    '  .lin-foot { display: none; }',
    '}'
  ].join('\n');

  const byStr = (a, b) => a < b ? -1 : a > b ? 1 : 0;
  const norm = (s) => String(s).replace(/[_-]/g, '').toLowerCase();
  const fieldName = (f) => String(f).split(':')[0].trim();
  const fieldType = (f) => { const i = String(f).indexOf(':'); return i < 0 ? '' : String(f).slice(i + 1).trim(); };
  const intersects = (a, b) => a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
  const rectOf = (o) => ({ x: o.x, y: o.y, w: o.w, h: o.h });
  const union = (rs) => {
    const x0 = Math.min.apply(null, rs.map(r => r.x)), y0 = Math.min.apply(null, rs.map(r => r.y));
    const x1 = Math.max.apply(null, rs.map(r => r.x + r.w)), y1 = Math.max.apply(null, rs.map(r => r.y + r.h));
    return { x: x0, y: y0, w: x1 - x0, h: y1 - y0 };
  };
  const plural = (n, one, many) => n + ' ' + (n === 1 ? one : (many || one + 's'));
  const ROW_K = Atlas.MIN_TEXT_PX / ROW_SIZE;     // rows and names are drawn at world size from here up

  /* ------------------------------------------------------------------ model */
  /** Boxes, edges, fields and row issues, from ATLAS alone. */
  function buildModel(ctx) {
    const D = ctx.data, U = ctx.util, arr = U.arr, nameOf = U.nameOf, idx = ctx.index;
    const lin = D.lineage || {};
    const tech = new Set(arr(lin.technicalColumns).map(norm));
    const nodes = new Map();
    arr(D.messages).forEach(m => {
      if (!m || (m.type !== 'command' && m.type !== 'event') || !m.name) return;
      const key = 'msg:' + m.type + ':' + m.name;
      if (nodes.has(key)) return;
      const checked = !!m.declaredIn && Array.isArray(m.fields);
      const slices = m.type === 'command' ? idx.handlers(m.name).concat(idx.dispatchers(m.name)) : idx.publishers(m.name).concat(idx.consumers(m.name));
      const home = slices.map(id => idx.slice(id)).find(s => s && s.bc);
      const idf = m.identity ? fieldName(m.identity) : null;
      const rows = checked ? arr(m.fields).filter(f => typeof f === 'string').map(f => ({ name: fieldName(f), type: fieldType(f), n: norm(fieldName(f)), id: false })) : [];
      rows.forEach(r => { r.id = r.name === idf; });
      rows.sort((a, b) => (b.id ? 1 : 0) - (a.id ? 1 : 0));   // stable: identity first, declaration order after
      nodes.set(key, { key, type: m.type, name: m.name, bc: home ? home.bc : null, checked, rows,
        target: { kind: 'message', id: m.type + ':' + m.name }, slices });
    });
    arr(D.slices).forEach(s => {
      if (!s || s.error) return;
      arr(s.readModels).forEach(rm => {
        if (!rm || !rm.name) return;
        const key = 'rm:' + rm.name;
        if (nodes.has(key)) { const n = nodes.get(key); if (!n.slices.includes(s.id)) n.slices.push(s.id); return; }
        const cols = arr(rm.columns).map(c => typeof c === 'string' ? { name: c } : (c || {})).filter(c => typeof c.name === 'string');
        nodes.set(key, { key, type: 'readModel', name: rm.name, bc: s.bc || null, checked: cols.length > 0,
          rows: cols.map(c => ({ name: c.name, type: c.type || '', n: norm(c.name), tech: tech.has(norm(c.name)) })),
          target: { kind: 'slice', id: s.id }, slices: [s.id] });
      });
    });

    const inbound = (s) => {
      const out = [];
      arr(s.consumes).concat(arr(s.projections).flatMap(p => arr(p && p.from))).map(nameOf).forEach(n => { if (!out.includes(n)) out.push(n); });
      return out;
    };
    const edges = [], seen = new Map();
    const edge = (from, to, kind, via) => {
      if (!nodes.has(from) || !nodes.has(to) || from === to) return;
      const k = from + '>' + to;
      if (seen.has(k)) { const e = seen.get(k); if (!e.via.includes(via)) e.via.push(via); return; }
      const e = { from, to, kind, via: [via] };
      seen.set(k, e);
      edges.push(e);
    };
    arr(D.slices).forEach(s => {
      if (!s || s.error) return;
      const ins = inbound(s);
      arr(s.handles).map(nameOf).forEach(c => arr(s.publishes).map(nameOf).forEach(e => edge('msg:command:' + c, 'msg:event:' + e, 'publishes', s.id)));
      ins.forEach(e => arr(s.dispatches).map(nameOf).forEach(c => edge('msg:event:' + e, 'msg:command:' + c, 'dispatches', s.id)));
      ins.forEach(e => arr(s.readModels).forEach(rm => { if (rm && rm.name) edge('msg:event:' + e, 'rm:' + rm.name, 'feeds', s.id); }));
    });
    edges.sort((a, b) => byStr(a.from, b.from) || byStr(a.to, b.to));

    /* Fields: the precomputed index, with a display spelling and per-kind counts. */
    const spellings = new Map();
    nodes.forEach(n => n.rows.forEach(r => {
      if (!spellings.has(r.n)) spellings.set(r.n, new Map());
      const m = spellings.get(r.n);
      m.set(r.name, (m.get(r.name) || 0) + 1);
    }));
    const fields = new Map();
    const raw = lin.fields || {};
    Object.keys(raw).sort(byStr).forEach(n => {
      const refs = arr(raw[n]).filter(r => nodes.has(r)).sort(byStr);
      if (!refs.length) return;
      const sp = Array.from((spellings.get(n) || new Map()).entries()).sort((a, b) => b[1] - a[1] || byStr(a[0], b[0]));
      const counts = { command: 0, event: 0, readModel: 0 };
      refs.forEach(r => counts[nodes.get(r).type]++);
      fields.set(n, { n, label: sp.length ? sp[0][0] : n, refs, has: new Set(refs), counts, tech: tech.has(n) });
    });

    /* Issues drawn on a read-model row: target.part readModel:<Model>.<column>. */
    const rowIssues = new Map(), boxIssues = new Map();
    arr(D.issues).forEach(i => {
      const t = i.target || {};
      if (t.kind !== 'slice' || !t.part || !String(t.part).startsWith('readModel:')) return;
      const rest = String(t.part).slice(10), dot = rest.indexOf('.');
      const key = 'rm:' + (dot < 0 ? rest : rest.slice(0, dot));
      if (!nodes.has(key)) return;
      if (!boxIssues.has(key)) boxIssues.set(key, []);
      boxIssues.get(key).push(i);
      if (dot >= 0) {
        const rk = key + '|' + norm(rest.slice(dot + 1));
        if (!rowIssues.has(rk)) rowIssues.set(rk, []);
        rowIssues.get(rk).push(i);
      }
    });

    let msgs = 0, msgsChecked = 0, rms = 0, rmsChecked = 0;
    nodes.forEach(n => {
      if (n.type === 'readModel') { rms++; if (n.checked) rmsChecked++; } else { msgs++; if (n.checked) msgsChecked++; }
    });
    return { nodes, edges, fields, tech: arr(lin.technicalColumns).slice(), rowIssues, boxIssues,
      stats: { msgs, msgsChecked, rms, rmsChecked }, nothingChecked: msgsChecked === 0 && rmsChecked === 0 };
  }

  /** Bands per context; boxes row-major in a grid with a fixed sub-column count per kind; bands stacked
      in id order and wrapped into columns so the whole drawing keeps roughly the ASPECT of a screen. */
  function layoutModel(M) {
    const byBc = new Map();
    M.nodes.forEach(n => {
      const bc = n.bc || '';
      if (!byBc.has(bc)) byBc.set(bc, { bc, command: [], event: [], readModel: [] });
      byBc.get(bc)[n.type].push(n);
    });
    const bands = Array.from(byBc.values()).sort((a, b) => (a.bc === '' ? 1 : 0) - (b.bc === '' ? 1 : 0) || byStr(a.bc, b.bc));
    bands.forEach(b => TYPES.forEach(t => b[t].sort((x, y) => byStr(x.name, y.name) || byStr(x.key, y.key))));
    const boxH = (n) => HEAD_H + Math.max(1, n.rows.length) * ROW_H + BOX_PAD;
    const sub = {}, off = {};
    let x = 0;
    TYPES.forEach(t => {
      const most = Math.max.apply(null, bands.map(b => b[t].length).concat([1]));
      sub[t] = Math.min(MAX_SUBCOLS, Math.max(1, Math.ceil(most / ROWS_PER_SUBCOL)));
      off[t] = x + BAND_PAD;
      x += 2 * BAND_PAD + sub[t] * BOX_W + (sub[t] - 1) * GAP_X + (t === 'readModel' ? 0 : CELL_GAP - 2 * BAND_PAD);
    });
    const bandW = x;
    /* Inside a band, relative to its corner. */
    bands.forEach(b => {
      let bottom = BAND_HEAD;
      TYPES.forEach(t => {
        let ry = BAND_HEAD;
        for (let i = 0; i < b[t].length; i += sub[t]) {
          const row = b[t].slice(i, i + sub[t]);
          const h = Math.max.apply(null, row.map(boxH));
          row.forEach((n, j) => { n.rx = off[t] + j * (BOX_W + GAP_X); n.ry = ry; n.w = BOX_W; n.h = boxH(n); });
          ry += h + GAP_Y;
        }
        bottom = Math.max(bottom, ry - GAP_Y);
      });
      b.w = bandW; b.h = bottom + BAND_PAD;
    });
    /* Band columns: as many as make the drawing about ASPECT wide, filled top to bottom in id order. */
    const top = MARGIN + LABEL_PX * 2;
    const total = bands.reduce((s, b) => s + b.h + BAND_GAP, 0) - BAND_GAP;
    const cols = Math.max(1, Math.min(bands.length, Math.round(Math.sqrt(ASPECT * Math.max(total, 1) / bandW))));
    const target = total / cols;
    const heads = [{ x: MARGIN, y: top }];
    let cx = MARGIN, cy = top, maxY = top;
    bands.forEach(b => {
      if (cy > top && cy - top + b.h / 2 > target && heads.length < cols) {
        cx += bandW + COL_GAP; cy = top;
        heads.push({ x: cx, y: top });
      }
      b.x = cx; b.y = cy;
      TYPES.forEach(t => b[t].forEach(n => { n.x = b.x + n.rx; n.y = b.y + n.ry; }));
      cy += b.h + BAND_GAP;
      maxY = Math.max(maxY, b.y + b.h);
    });
    return { bands, heads, off, bounds: { x: 0, y: 0, w: cx + bandW + MARGIN, h: maxY + MARGIN } };
  }
  const rowRect = (n, i) => ({ x: n.x, y: n.y + HEAD_H + i * ROW_H, w: n.w, h: ROW_H });

  /* ------------------------------------------------------------------ the lens */
  let S = null;

  function mount(ctx) {
    const html = ctx.html, svg = ctx.svg;
    const M = buildModel(ctx);
    S = { ctx, M, G: null, vp: null, field: null, sel: null, mark: null, match: null, hover: null, ver: 0, raf: 0,
      layers: {}, items: new Map(), side: null, selBox: null };
    if (!M.nodes.size || M.nothingChecked) {
      const box = html('div', 'lin-empty');
      box.append(html('h2', null, M.nodes.size ? 'Not checked' : 'Nothing to trace'));
      box.append(html('p', null, M.nodes.size
        ? 'No message in this map has payload facts and no read model lists its columns, so there is no field to trace. ' +
          'Payload fields come from slice-source (the source facts /essentials:slice-map-html merges); read-model columns from the manifests.'
        : 'This map has no commands, events or read models.'));
      ctx.el.append(box);
      S.empty = true;
      return;
    }
    S.G = layoutModel(M);
    ctx.el.append(buildSide(ctx, M));
    const stage = html('div', 'lin-stage');
    ctx.el.append(stage);
    const root = svg('svg', { class: 'lens-svg', role: 'img', 'aria-label': 'Field lineage: commands, events and read models with their fields' });
    const defs = svg('defs');
    ['publishes', 'feeds', 'dispatches', 'path'].forEach(kind => {
      const m = svg('marker', { id: 'lin-arrow-' + kind, viewBox: '0 0 10 10', refX: '9', refY: '5', markerWidth: 11, markerHeight: 11,
        markerUnits: 'userSpaceOnUse', orient: 'auto-start-reverse' });
      m.append(svg('path', { d: 'M0,0 L10,5 L0,10 z', class: 'arrow lin-arrow-' + kind }));
      defs.append(m);
    });
    root.append(defs);
    const world = svg('g');
    ['band', 'edge', 'box', 'label', 'mark'].forEach(n => { const g = svg('g'); S.layers[n] = { g, drawn: new Map() }; world.append(g); });
    root.append(world);
    stage.append(root);
    S.vp = ctx.createViewport({ svg: root, world, bounds: S.G.bounds, minScale: 0.04, onChange: () => render() });
    ctx.onHash((h) => { const f = h['lineage.field'] || null; if (f !== S.field) selectField(f && M.fields.has(f) ? f : null, { write: false, fly: true }); });
    const f0 = ctx.getHash()['lineage.field'];
    if (f0 && M.fields.has(f0)) {
      const h = ctx.getHash();
      selectField(f0, { write: false, fly: !h.focus && !h.issue && !h.at });
    }
  }
  function unmount() {
    if (S && S.raf) cancelAnimationFrame(S.raf);
    S = null;
  }

  /* ---------- the field index ---------- */
  function buildSide(ctx, M) {
    const html = ctx.html;
    const side = html('aside', 'lin-side');
    side.setAttribute('aria-label', 'Fields');
    const head = html('div', 'lin-head');
    head.append(html('h2', null, 'Fields · ' + M.fields.size));
    const st = M.stats;
    head.append(html('p', null, st.msgsChecked + ' of ' + plural(st.msgs, 'message') + ' with payload facts · ' +
      st.rmsChecked + ' of ' + plural(st.rms, 'read model') + ' with columns' +
      (st.msgsChecked < st.msgs || st.rmsChecked < st.rms ? '; the rest show "not checked"' : '')));
    side.append(head);
    const sel = S.selBox = html('div', 'lin-sel');
    sel.hidden = true;
    side.append(sel);
    const list = html('div', 'lin-list');
    list.setAttribute('role', 'listbox');
    list.setAttribute('aria-label', 'Fields, by normalised name');
    S.buttons = new Map();
    M.fields.forEach(f => {
      const b = html('button', 'lin-f');
      b.type = 'button';
      b.setAttribute('aria-pressed', 'false');
      b.dataset.field = f.n;
      b.append(html('span', 'fn', f.label));
      b.append(html('span', 'fc', TYPES.filter(t => f.counts[t]).map(t => SHORT[t] + ' ' + f.counts[t]).join(' · ')));
      if (f.tech) b.append(html('span', 'tag', 'technical'));
      else if (f.counts.readModel && !f.counts.event) b.append(html('span', 'tag uns', 'no event'));
      b.title = f.label + ': ' + TYPES.filter(t => f.counts[t]).map(t => plural(f.counts[t], t === 'readModel' ? 'read model' : t)).join(', ') +
        (f.tech ? ' — a technical column, skipped by the completeness rule' : f.counts.readModel && !f.counts.event ? ' — no event carries it' : '');
      b.addEventListener('click', () => selectField(S.field === f.n ? null : f.n, { write: true, fly: true }));
      b.addEventListener('keydown', (e) => {
        if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
        e.preventDefault();
        const all = Array.from(S.buttons.values()).filter(x => !x.classList.contains('dim'));
        const i = all.indexOf(b) + (e.key === 'ArrowDown' ? 1 : -1);
        if (all[i]) all[i].focus();
      });
      S.buttons.set(f.n, b);
      list.append(b);
    });
    if (!M.fields.size) list.append(html('p', 'lens-msg', 'No field is known yet: no payload facts and no read-model columns.'));
    side.append(list);
    const foot = html('div', 'lin-foot', 'Technical columns, skipped by the completeness rule: ' + (M.tech.join(', ') || 'none') + '.');
    side.append(foot);
    S.side = side;
    return side;
  }
  function renderSel() {
    const ctx = S.ctx, html = ctx.html, box = S.selBox;
    box.textContent = '';
    const f = S.field ? S.M.fields.get(S.field) : null;
    box.hidden = !f;
    S.buttons.forEach((b, n) => b.setAttribute('aria-pressed', String(n === S.field)));
    if (!f) return;
    const top = html('div');
    top.append(html('span', 'nm', f.label), document.createTextNode('  '));
    const clear = html('button', 'lnk', 'clear');
    clear.type = 'button';
    clear.addEventListener('click', () => selectField(null, { write: true }));
    top.append(clear);
    box.append(top);
    TYPES.forEach(t => {
      const refs = f.refs.filter(r => S.M.nodes.get(r).type === t);
      if (!refs.length) return;
      const g = html('div', 'grp');
      g.append(html('span', null, TYPE_LABEL[t] + ':'));
      refs.forEach(r => {
        const n = S.M.nodes.get(r);
        const b = html('button', 'lnk mono', n.name);
        b.type = 'button';
        b.addEventListener('click', () => { focusNode(n, null, true); openNode(n); });
        g.append(b);
      });
      box.append(g);
    });
    if (f.tech) box.append(html('div', 'grp', 'A technical column: the completeness rule skips it.'));
    else if (f.counts.readModel && !f.counts.event) box.append(html('div', 'grp', 'No event carries it: every read model listing it has no source for it.'));
  }

  /* ---------- selection ---------- */
  function selectField(n, o) {
    if (!S || S.empty) return;
    o = o || {};
    S.field = n;
    if (o.write) S.ctx.setHash({ 'lineage.field': n || null });
    renderSel();
    if (n && S.buttons.get(n) && S.buttons.get(n).scrollIntoView) S.buttons.get(n).scrollIntoView({ block: 'nearest' });
    if (n && o.fly) {
      const f = S.M.fields.get(n);
      S.vp.flyTo(union(f.refs.map(r => rectOf(S.M.nodes.get(r)))), { pad: FOCUS_PAD, maxScale: 1.2, animate: o.animate !== false });
    }
    bump();
  }
  const carries = (node) => !!S.field && S.M.fields.has(S.field) && S.M.fields.get(S.field).has.has(node.key);
  function openNode(n) {
    if (n.type === 'readModel') S.ctx.openCard({ kind: 'slice', id: n.slices[0] }, { anchor: 'readModel:' + n.name });
    else S.ctx.openCard(n.target);
  }
  function focusNode(n, marks, animate, minK) {
    S.sel = n.key;
    S.mark = marks && marks.length ? marks : null;
    const r = marks && marks.length ? union(marks) : rectOf(n);
    S.vp.flyTo(r, { pad: FOCUS_PAD, minScale: minK != null ? minK : ROW_K * 1.02, maxScale: 1.6, animate });
    bump();
  }

  /* ---------- drawing ---------- */
  function sync(name, items) {
    const layer = S.layers[name], keep = new Set();
    items.forEach(it => {
      keep.add(it.key);
      if (layer.drawn.has(it.key)) return;
      const el = it.make();
      if (!el) return;
      layer.drawn.set(it.key, el);
      layer.g.append(el);
    });
    layer.drawn.forEach((el, key) => { if (!keep.has(key)) { el.remove(); layer.drawn.delete(key); } });
  }
  function bump() {
    if (!S || S.empty) return;
    S.ver++;
    if (S.raf) return;
    S.raf = requestAnimationFrame(() => { if (!S) return; S.raf = 0; render(); });
  }
  function render() {
    if (!S || S.empty) return;
    const v = S.vp.view(), k = v.k, detail = k >= ROW_K;
    const kq = Math.round(Math.log(k) / Math.log(K_STEP)), kk = Math.pow(K_STEP, kq);
    const area = { x: v.x - v.w * CULL_MARGIN, y: v.y - v.h * CULL_MARGIN, w: v.w * (1 + 2 * CULL_MARGIN), h: v.h * (1 + 2 * CULL_MARGIN) };
    const vis = (r) => intersects(r, area);
    const tag = '|' + S.ver + '|' + (detail ? 'd' : 'c'), ktag = tag + '|' + kq;
    const bands = [], labels = [], boxes = [], edges = [], marks = [];
    S.G.bands.forEach((b, i) => {
      if (!vis(b)) return;
      bands.push({ key: 'b' + i + tag, make: () => S.ctx.svg('rect', { x: b.x, y: b.y, width: b.w, height: b.h, rx: 12, class: 'lin-band' }) });
      labels.push({ key: 'bl' + i + ktag, make: () => bandLabel(b, k, kk) });
    });
    if (S.G.bands.length) labels.push({ key: 'cols' + ktag, make: () => columnLabels(k, kk) });
    S.M.nodes.forEach(n => { if (vis(n)) boxes.push({ key: n.key + (detail ? tag : ktag), make: () => drawBox(n, detail, k, kk) }); });
    const path = S.field ? S.M.fields.get(S.field) : null;
    S.M.edges.forEach((e, i) => {
      const a = S.M.nodes.get(e.from), z = S.M.nodes.get(e.to);
      const on = !!path && path.has.has(e.from) && path.has.has(e.to);
      if (path ? !on : k < IDLE_EDGE_K) return;
      if (!vis(union([a, z]))) return;
      edges.push({ key: 'e' + i + tag, make: () => drawEdge(e, a, z, on) });
    });
    if (S.mark) S.mark.forEach((r, i) => { if (vis(r)) marks.push({ key: 'm' + i + ktag, make: () => drawMark(r, kk) }); });
    sync('band', bands); sync('edge', edges); sync('box', boxes); sync('label', labels); sync('mark', marks);
    applyHover();
  }
  const label = (x, y, str, px, kk, cls, anchor) =>
    S.ctx.svg('text', { x, y, 'font-size': (px / kk).toFixed(3), class: 'lin-nopt ' + (cls || ''), 'text-anchor': anchor || 'start' }, str);
  function bandLabel(b, k, kk) {
    const chars = Math.floor(b.w * k / (CHAR_W * (LABEL_PX + 1)));
    if (chars < 3) return null;
    const n = TYPES.reduce((s, t) => s + b[t].length, 0);
    return label(b.x + 12 / kk, b.y + BAND_HEAD / 2 + LABEL_PX * 0.35 / kk,
      S.ctx.util.trunc((b.bc || 'no slice') + '  ·  ' + plural(n, 'box', 'boxes'), chars), LABEL_PX + 1, kk, 'lin-strong lin-halo');
  }
  function columnLabels(k, kk) {
    const g = S.ctx.svg('g');
    if (BOX_W * k < 3 * CHAR_W * LABEL_PX) return null;
    S.G.heads.forEach(h => TYPES.forEach(t => g.append(label(h.x + S.G.off[t], h.y - 8 / kk, TYPE_LABEL[t].toUpperCase(), LABEL_PX, kk, 'lin-muted lin-halo'))));
    return g;
  }
  function wtext(x, y, str, size, k, cls, anchor) {
    if (size * k < Atlas.MIN_TEXT_PX - 0.01) return null;
    return S.ctx.svg('text', { x, y, 'font-size': size, class: 'lin-nopt ' + (cls || ''), 'text-anchor': anchor || 'start' }, str);
  }
  function drawBox(n, detail, k, kk) {
    const ctx = S.ctx, svg = ctx.svg, trunc = ctx.util.trunc;
    const on = carries(n);
    const dim = (S.field && !on) || (S.match && !S.match.has(n.key));
    const g = svg('g', { class: 'st r-' + ROLE[n.type] + ' lin-box' + (on ? ' on' : '') + (dim ? ' dim' : '') + (S.sel === n.key ? ' sel' : ''),
      tabindex: '0', role: 'button', 'aria-label': (n.type === 'readModel' ? 'read model ' : n.type + ' ') + n.name });
    g.dataset.node = n.key;
    g.append(svg('rect', { class: 'bg', x: n.x, y: n.y, width: n.w, height: n.h, rx: 6 }));
    const padX = 8;
    if (detail) {
      const nm = wtext(n.x + padX, n.y + HEAD_H / 2 + NAME_SIZE * 0.36, trunc(n.name, Math.floor((n.w - 2 * padX - 10) / (CHAR_W * NAME_SIZE))), NAME_SIZE, k, 'lin-strong');
      if (nm) g.append(nm);
    } else {
      const font = LABEL_PX / kk, chars = Math.floor((n.w - 2 * padX) / (CHAR_W * font));
      if (chars >= 3) g.append(svg('text', { x: n.x + padX, y: n.y + HEAD_H / 2 + font * 0.35, 'font-size': font.toFixed(3), class: 'lin-nopt lin-strong' }, trunc(n.name, chars)));
    }
    const boxIss = S.M.boxIssues.get(n.key) || [];
    const lvl = ctx.levelOf(boxIss);
    if (lvl) g.append(svg('circle', { cx: n.x + n.w - 2, cy: n.y + 2, r: (DOT_PX / kk).toFixed(3), class: 'dot-' + lvl + ' lin-nopt' }));
    if (!n.checked) {
      const t = detail ? wtext(n.x + padX, n.y + HEAD_H + ROW_H * 0.7, n.type === 'readModel' ? 'columns not checked' : 'payload not checked', ROW_SIZE, k, 'lin-nc') : null;
      if (t) g.append(t);
    }
    const chars = Math.max(4, Math.floor((n.w - 2 * padX - 12) / (MONO_W * ROW_SIZE)));
    n.rows.forEach((r, i) => {
      const rr = rowRect(n, i);
      const hit = !!S.field && r.n === S.field;
      const iss = n.type === 'readModel' ? (S.M.rowIssues.get(n.key + '|' + r.n) || []) : [];
      if (detail || hit) {   // zoomed out, a row is only drawn as the traced field's stripe
        const bar = svg('rect', { x: rr.x + 3, y: rr.y + 1, width: rr.w - 6, height: rr.h - 2, rx: 3, class: 'lin-row' + (hit ? ' on' : '') });
        bar.addEventListener('click', (ev) => {
          ev.stopPropagation();
          if (S.vp.wasDrag()) return;
          selectField(S.field === r.n ? null : (S.M.fields.has(r.n) ? r.n : null), { write: true });
        });
        bar.addEventListener('mousemove', (ev) => ctx.tip(ev, rowTip(n, r, iss)));
        bar.addEventListener('mouseleave', () => ctx.hideTip());
        g.append(bar);
      }
      if (detail) {
        const cls = 'lin-cell' + (r.id || hit ? ' lin-on' : '') + (r.tech ? ' lin-tech' : '') + (iss.length ? ' lin-uns' : '');
        const t = wtext(rr.x + padX, rr.y + ROW_H * 0.7, trunc(r.name + (r.type ? ': ' + r.type : ''), chars), ROW_SIZE, k, cls);
        if (t) g.append(t);
      }
      if (iss.length) {
        const d = svg('circle', { cx: rr.x + rr.w - 10, cy: rr.y + ROW_H / 2, r: Math.max(DOT_PX / kk, 4).toFixed(3), class: 'dot-' + ctx.levelOf(iss) + ' lin-dot' });
        d.addEventListener('click', (ev) => { ev.stopPropagation(); if (!S.vp.wasDrag()) ctx.markIssue(iss[0].id); });
        d.addEventListener('mousemove', (ev) => ctx.tip(ev, iss.map(x => x.text).join('\n') + '\nclick for the issue'));
        d.addEventListener('mouseleave', () => ctx.hideTip());
        g.append(d);
      }
    });
    const activate = () => { S.sel = n.key; S.mark = null; openNode(n); bump(); };
    g.addEventListener('click', () => { if (!S.vp.wasDrag()) activate(); });
    g.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); activate(); } });
    g.addEventListener('mouseenter', () => { S.hover = n.key; applyHover(); });
    g.addEventListener('mouseleave', () => { if (S.hover === n.key) { S.hover = null; applyHover(); } });
    return g;
  }
  function rowTip(n, r, iss) {
    const f = S.M.fields.get(r.n);
    const lines = [r.name + (r.type ? ': ' + r.type : '') + (r.id ? '  (identity)' : '')];
    if (f) lines.push('in ' + TYPES.filter(t => f.counts[t]).map(t => plural(f.counts[t], t === 'readModel' ? 'read model' : t)).join(', '));
    if (r.tech) lines.push('technical column — skipped by the completeness rule');
    iss.forEach(i => lines.push(i.text));
    lines.push(S.field === r.n ? 'click to clear' : 'click to trace this field');
    return lines.join('\n');
  }
  /** Edge anchors: right side to left side, or the reverse for an event dispatching a command; at the
      traced field's row when there is one. */
  function anchorY(n) {
    if (S.field) { const i = n.rows.findIndex(r => r.n === S.field); if (i >= 0) return n.y + HEAD_H + (i + 0.5) * ROW_H; }
    return n.y + HEAD_H / 2;
  }
  function drawEdge(e, a, z, on) {
    const back = z.x + z.w <= a.x;   // event → command runs right to left
    const x1 = back ? a.x : a.x + a.w, x2 = back ? z.x + z.w : z.x;
    const y1 = anchorY(a), y2 = anchorY(z);
    const d = Math.max(40, Math.abs(x2 - x1) / 2) * (back ? -1 : 1);
    const p = S.ctx.svg('path', { d: 'M' + x1 + ',' + y1 + ' C' + (x1 + d) + ',' + y1 + ' ' + (x2 - d) + ',' + y2 + ' ' + x2 + ',' + y2,
      class: 'ed k-' + e.kind + (on ? ' lin-path' : ' lin-idle'), 'marker-end': 'url(#lin-arrow-' + (on ? 'path' : e.kind) + ')' });
    p.dataset.from = e.from; p.dataset.to = e.to;
    p.addEventListener('mousemove', (ev) => S.ctx.tip(ev, a.name + ' → ' + z.name + '\nvia ' + e.via.join(', ')));
    p.addEventListener('mouseleave', () => S.ctx.hideTip());
    return p;
  }
  function applyHover() {
    const id = S.hover;
    S.layers.edge.drawn.forEach(p => {
      p.classList.toggle('hi-in', !!id && p.dataset.to === id);
      p.classList.toggle('hi-out', !!id && p.dataset.from === id);
    });
  }
  function drawMark(r, kk) {
    const svg = S.ctx.svg, g = svg('g', { class: 'lin-nopt' }), d = MARK_PAD_PX / kk;
    g.append(svg('rect', { x: r.x - d, y: r.y - d, width: r.w + 2 * d, height: r.h + 2 * d, rx: 6 / kk, class: 'mark' }));
    g.append(svg('circle', { cx: r.x - d, cy: r.y - d, r: 9 / kk, class: 'mark-badge' }));
    g.append(svg('text', { x: r.x - d, y: r.y - d + 4.5 / kk, 'font-size': (13 / kk).toFixed(3), 'text-anchor': 'middle', class: 'mark-badge-t' }, '!'));
    return g;
  }

  /* ---------- the lens surface ---------- */
  /** The boxes a target names: a message's box; a slice's read models, else its handled and published messages. */
  function nodesFor(t) {
    if (!S || S.empty || !t) return [];
    const M = S.M;
    if (t.kind === 'message') { const n = M.nodes.get('msg:' + t.id); return n ? [n] : []; }
    if (t.kind !== 'slice') return [];
    const rms = [];
    M.nodes.forEach(n => { if (n.type === 'readModel' && n.slices.includes(t.id)) rms.push(n); });
    if (rms.length) return rms;
    const s = S.ctx.index.slice(t.id) || {}, U = S.ctx.util;
    return U.arr(s.handles).map(c => M.nodes.get('msg:command:' + U.nameOf(c)))
      .concat(U.arr(s.publishes).map(e => M.nodes.get('msg:event:' + U.nameOf(e)))).filter(Boolean);
  }
  function focus(t, o) {
    o = o || {};
    if (!S || S.empty) return false;
    const animate = o.animate !== false;
    if (!t) { S.sel = null; S.mark = null; if (S.field) selectField(null, { write: true }); else bump(); return true; }
    const part = t.part || (o.issue && o.issue.target && o.issue.target.part) || '';
    if (t.kind === 'slice' && part.startsWith('readModel:')) {
      const rest = part.slice(10), dot = rest.indexOf('.');
      const n = S.M.nodes.get('rm:' + (dot < 0 ? rest : rest.slice(0, dot)));
      if (!n) return false;
      const i = dot < 0 ? -1 : n.rows.findIndex(r => r.n === norm(rest.slice(dot + 1)));
      const r = i >= 0 ? rowRect(n, i) : rectOf(n);
      focusNode(n, o.issue ? [r] : null, animate);
      return true;
    }
    // Any other issue on a slice (a scenario, an endpoint …) is drawn where the Atlas lens draws it.
    if (o.issue && t.kind === 'slice') return false;
    const list = nodesFor(t);
    if (!list.length) return false;
    focusNode(list[0], o.issue ? list.map(rectOf) : null, animate);
    if (list.length > 1) S.vp.flyTo(union(list.map(rectOf)), { pad: FOCUS_PAD, maxScale: 1.6, animate });
    return true;
  }
  function canFocus(t) {
    if (!S) return false;            // unmounted: the Atlas lens, tried first, draws every target this lens does
    return !t || nodesFor(t).length > 0;
  }
  function search(q) {
    if (!S || S.empty) return null;
    const ctx = S.ctx;
    if (!q) { S.match = null; S.buttons.forEach(b => b.classList.remove('dim')); bump(); return null; }
    const keys = new Set(), match = new Set();
    S.M.nodes.forEach(n => {
      if (!ctx.match(q, n.name, n.rows.map(r => r.name).join(' '), n.bc || '')) return;
      match.add(n.key);
      keys.add(n.type === 'readModel' ? n.slices[0] : 'msg:' + n.target.id);
    });
    S.buttons.forEach((b, fn) => { const f = S.M.fields.get(fn); b.classList.toggle('dim', !ctx.match(q, f.label, fn)); });
    S.match = match;
    bump();
    return keys;
  }
  function minimap() {
    if (!S || S.empty) return [];
    const out = S.G.bands.map(b => ({ x: b.x, y: b.y, w: b.w, h: b.h, cls: 'ctx' }));
    S.M.nodes.forEach(n => out.push({ x: n.x, y: n.y, w: n.w, h: n.h, cls: 'card' }));
    return out;
  }
  function cardExtra(t) {
    if (!S || S.empty || !t) return null;
    const ctx = S.ctx, html = ctx.html, M = S.M;
    const traceBtn = (r) => {
      const b = html('button', 'lnk mono', r.name);
      b.type = 'button';
      b.title = 'Trace this field';
      b.addEventListener('click', () => selectField(r.n, { write: true, fly: true }));
      return b;
    };
    const sec = html('section');
    sec.append(html('h3', null, 'Lineage'));
    if (t.kind === 'message') {
      const n = M.nodes.get('msg:' + t.id);
      if (!n) return null;
      if (!n.checked) { sec.append(html('p', 'sum', 'Payload not checked: slice-source found no declaration for this message.')); return sec; }
      const ul = html('ul', 'changes');
      n.rows.forEach(r => {
        const f = M.fields.get(r.n), li = html('li');
        li.append(traceBtn(r), document.createTextNode(f ? '  — in ' + TYPES.filter(x => f.counts[x]).map(x => plural(f.counts[x], x === 'readModel' ? 'read model' : x)).join(', ') : ''));
        ul.append(li);
      });
      sec.append(ul);
      return sec;
    }
    if (t.kind !== 'slice') return null;
    const s = ctx.index.slice(t.id);
    const rms = nodesFor(t).filter(n => n.type === 'readModel');
    if (!s || !rms.length) return null;
    const U = ctx.util;
    const ins = U.arr(s.consumes).concat(U.arr(s.projections).flatMap(p => U.arr(p && p.from))).map(U.nameOf);
    rms.forEach(n => {
      sec.append(html('p', 'sum', n.name + ' — where each column comes from:'));
      if (!n.checked) { sec.append(html('p', 'sum', 'Columns not checked: the manifest lists none.')); return; }
      const ul = html('ul', 'changes');
      n.rows.forEach(r => {
        const f = M.fields.get(r.n), li = html('li');
        const from = f ? ins.filter((e, i) => ins.indexOf(e) === i && f.has.has('msg:event:' + e)) : [];
        li.append(traceBtn(r), document.createTextNode(r.tech ? '  — technical, skipped' : from.length ? '  ← ' + from.join(', ') : '  — no inbound event carries it'));
        ul.append(li);
      });
      sec.append(ul);
    });
    return sec;
  }
  function legend() {
    const f = document.createDocumentFragment();
    const h = (t) => { const e = document.createElement('h3'); e.textContent = t; f.append(e); };
    const row = (style, text, cls) => {
      const r = document.createElement('div'); r.className = 'lg';
      const sw = document.createElement('span'); sw.className = cls || 'sw';
      Object.assign(sw.style, style);
      r.append(sw, document.createTextNode(text));
      f.append(r);
    };
    const note = (text) => { const p = document.createElement('div'); p.style.color = 'var(--text-muted)'; p.style.marginTop = '4px'; p.textContent = text; f.append(p); };
    h('Boxes');
    row({ background: 'var(--cmd)', borderColor: 'var(--cmd-rule)' }, 'command, with its payload fields');
    row({ background: 'var(--evt)', borderColor: 'var(--evt-rule)' }, 'event, with its payload fields');
    row({ background: 'var(--view)', borderColor: 'var(--view-rule)' }, 'read model, with its columns');
    row({ background: 'var(--panel-2)', borderColor: 'var(--accent)', borderWidth: '3px' }, 'carries the traced field');
    h('Edges');
    row({ borderColor: 'var(--evt-rule)' }, 'command → event: a slice handles one, publishes the other', 'ln');
    row({ borderColor: 'var(--evt-rule)' }, 'event → read model: its slice consumes or projects the event', 'ln');
    row({ borderColor: 'var(--cmd-rule)' }, 'event → command: an automation dispatches on it', 'ln');
    row({ borderColor: 'var(--accent)', borderTopWidth: '3px' }, 'the traced field\'s path', 'ln');
    h('Completeness');
    row({ background: 'var(--info)' }, 'a read-model column no inbound event carries (the dot opens the issue)', 'dt');
    note('Technical columns, skipped by the rule: ' + ((S && S.M && S.M.tech.join(', ')) || 'none') + '.');
    note('"not checked": no payload facts for a message, or no columns for a read model — nothing is judged there.');
    note('Click a field — in the list, on a row, or in the detail card — to trace it. Esc clears.');
    return f;
  }

  Atlas.registerLens({
    id: 'lineage', label: 'Lineage', key: '3', css: CSS,
    mount, unmount, focus, canFocus, search, minimap, cardExtra, legend,
    api: { buildModel, layoutModel }
  });
})();
