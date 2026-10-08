/* lens-1-atlas.js — the Atlas lens (design §10.1, board §8).

   Semantic zoom over one continuous geometry: a bounded context contains its process boxes, a
   process box is the extent of its board, and zooming in opens the box in place. Every
   coordinate comes from layout.atlas (C3); this file only chooses what to draw at which scale.

     L0  Contexts   k < LOD_L1           context regions, cross-context bridges (width = events)
     L1  Processes  LOD_L1 ≤ k < LOD_L2  process boxes (name, slice count, issue counts), links
     L2  Board      LOD_L2 ≤ k < LOD_L3  lanes, stickies with names, edges
     L3  Detail     k ≥ LOD_L3           + payload fields on stickies, scenario stacks or invariants

   Also the reference implementation of the lens API documented at the top of atlas.html. The
   board itself is a factory, api.createBoard(ctx, {filter}), so another lens (the Diff lens) can
   draw the same board with its own filter instead of re-implementing it. */
(function () {
  'use strict';

  /* Level-of-detail thresholds, in scale k (screen px per world px). Tuned on the layout's real
     sizes (scripts/slice_atlas_layout.py: 168 × 88 stickies, segments 336 / 544 / 960 wide at the
     10th / 50th / 90th percentile of the 60-context estate in tests/scripts/test_slice_atlas_layout.py)
     and on the page of tests/fixtures/atlas/, so that at the entry of each level no text is below
     Atlas.MIN_TEXT_PX (11) and a compact name shows at least 16 characters:
       LOD_L1 0.12  a median 544-wide process box is 65 px wide: its 12 px name wraps to two lines of
                    9 characters (18). At the old 0.08 it was 43 px, 5 characters a line. The estate's
                    fit-to-screen scale (about 0.05) stays at L0.
       LOD_L2 0.5   a sticky is 84 × 36 px: STICKY_LINES-wrapped 12 px names give two lines of 11
                    characters (22). At the old 0.3 it was 50 × 22 px, one line of 6.
       LOD_L3 0.9   the 14-unit name reaches 12.6 px (19 characters a line) and the 12.5-unit field and
                    scenario text 11.25 px — keep WORLD_SMALL × LOD_L3 ≥ Atlas.MIN_TEXT_PX.
     Change a card size in the layout and these move with it: rerun the numbers above. */
  const LOD_L1 = 0.12;
  const LOD_L2 = 0.5;
  const LOD_L3 = 0.9;
  const LEVEL_NAMES = ['Contexts', 'Processes', 'Board', 'Board · detail'];

  /* Text. *_PX are constant screen sizes (drawn at px / k); WORLD_* scale with the zoom. */
  const LABEL_PX = 12;
  const LABEL_BIG_PX = 15;
  const WORLD_NAME = 14;
  const WORLD_SMALL = 12.5;
  const LINE = 1.42;            // line height / font size
  const CHAR_W = 0.57;          // mean glyph advance / font size, for truncation
  const MONO_W = 0.62;          // the same for the monospace payload lines
  /* Culling and rebuilds. */
  const CULL_MARGIN = 0.15;     // fraction of the view added on each side before culling
  const K_STEP = 1.12;          // constant-size text is rebuilt each time k moves by this factor
  /* Camera. */
  const FOCUS_PAD = 40;         // screen px around a focused target
  const FOCUS_MAX_K = 1.6;
  const MARK_PAD_PX = 6;
  const DOT_PX = 5;
  const CTX_LABEL_SPILL = 2.5;   // an L0 context label may run this many times its region's width …
  const CTX_LABEL_MIN_PX = 170;  // … up to this many px,
  const CTX_LABEL_HIDE_PX = 32;  // and a region narrower than this on screen gets no label at all
  const CHIP_MIN_CHARS = 6;      // a scenario chip narrower than this becomes a "+n" count
  /* Compact names (L1 process boxes, L2 stickies) wrap instead of shrinking: constant-size text
     at LABEL_PX, broken at word and camel-case boundaries, at most this many lines. */
  const STICKY_LINES = 3;
  const STICKY_LINE = 1.15;     // line height / font size inside a sticky
  const STICKY_PAD = 3;         // screen px above and below the name block
  const PROC_NAME_LINES = 2;

  const ROLE_GLYPH = { automation: '⚙ ', external: '⇄ ', error: '✕ ' };
  const MAIN_ROLE = { command: 'command', view: 'view', automation: 'automation', translation: 'external' };
  const EDGE_KINDS = ['handles', 'publishes', 'feeds', 'dispatches', 'external'];

  const CSS = [
    '.atlas-ctx { fill: var(--ctx-fill); stroke: var(--rule-strong); stroke-width: 1.2; }',
    '.atlas-ctx.l0 { cursor: zoom-in; stroke-width: 1.6; }',
    '.atlas-ctx.marked { stroke: var(--err); }',
    'svg.lens-svg text.atlas-strong { font-weight: 700; }',
    'svg.lens-svg text.atlas-muted { fill: var(--text-muted); }',
    'svg.lens-svg text.atlas-halo { paint-order: stroke; stroke: var(--surface); stroke-width: 3px; stroke-linejoin: round; }',
    'svg.lens-svg .atlas-nopt { pointer-events: none; }',
    '.atlas-seg { stroke-width: 2; cursor: zoom-in; }',
    '.atlas-seg.board { cursor: default; fill-opacity: 0.03; }',
    '.atlas-seg.derived { stroke-dasharray: 7 5; }',
    '.atlas-seg-head { fill-opacity: 0.2; cursor: pointer; }',
    '.atlas-lane { fill: var(--lane-fill); }',
    '.atlas-lane.odd { fill: var(--surface); fill-opacity: 0; }',
    'svg.lens-svg text.atlas-lane-label { fill: var(--text-muted); letter-spacing: .04em; }',
    '.atlas-link { fill: none; stroke: var(--text-muted); stroke-width: 2; }',
    '.atlas-link.cross { stroke-dasharray: 8 5; }',
    '.atlas-bridge { fill: none; stroke: var(--accent); stroke-opacity: .55; cursor: help; }',
    '.atlas-bridge:hover { stroke-opacity: 1; }',
    '.atlas-arrow-handles, .atlas-arrow-dispatches { fill: var(--cmd-rule); }',
    '.atlas-arrow-publishes, .atlas-arrow-feeds { fill: var(--evt-rule); }',
    '.atlas-arrow-external { fill: var(--trg-rule); }',
    '.atlas-arrow-link { fill: var(--text-muted); }',
    '.atlas-arrow-bridge { fill: var(--accent); }',
    '.atlas-scn { cursor: pointer; }',
    '.atlas-scn > rect.bg { fill: var(--panel); stroke: var(--rule-strong); stroke-width: 1; }',
    '.atlas-scn.err > rect.bg { stroke: var(--err); }',
    '.atlas-chip rect { stroke-width: 1; }',
    'svg.lens-svg .atlas-chip text { fill: var(--on-sticky); }',
    '.atlas-chip.c-event rect { fill: var(--evt); stroke: var(--evt-rule); }',
    '.atlas-chip.c-command rect { fill: var(--cmd); stroke: var(--cmd-rule); }',
    '.atlas-chip.c-query rect, .atlas-chip.c-readModel rect { fill: var(--view); stroke: var(--view-rule); }',
    '.atlas-chip.c-state rect, .atlas-chip.c-schedule rect { fill: var(--trg); stroke: var(--trg-rule); }',
    '.atlas-chip.c-external rect { fill: var(--ext); stroke: var(--trg-rule); stroke-dasharray: 4 2; }',
    '.atlas-chip.c-error rect { fill: var(--err-bg); stroke: var(--err); }',
    'svg.lens-svg .atlas-chip.c-error text { fill: var(--err); font-weight: 700; }',
    '.atlas-chip.c-nothing rect { fill: none; stroke: var(--rule-strong); stroke-dasharray: 3 2; }',
    'svg.lens-svg .atlas-chip.c-nothing text { fill: var(--text-muted); }',
    'svg.lens-svg text.atlas-ok { fill: var(--ok); font-weight: 700; }',
    'svg.lens-svg text.atlas-warn { fill: var(--warn); font-weight: 700; }',
    'svg.lens-svg text.atlas-err { fill: var(--err); }',
    'svg.lens-svg text.atlas-cell { font-family: var(--mono); }',
    '.atlas-inv { cursor: pointer; }',
    '.atlas-inv > rect.bg { fill: var(--panel); stroke: var(--rule); stroke-dasharray: 4 3; }',
    '.atlas-level { position: absolute; top: 10px; left: 10px; z-index: 4; font-size: 12px; padding: 3px 9px;',
    '  border-radius: 999px; background: var(--panel-2); border: 1px solid var(--rule-strong); color: var(--text-muted);',
    '  pointer-events: none; }',
    '.atlas-level b { color: var(--text); font-weight: 600; }'
  ].join('\n');

  const levelOf = (k) => k < LOD_L1 ? 0 : k < LOD_L2 ? 1 : k < LOD_L3 ? 2 : 3;
  const LEVEL_RANGE = [[0, LOD_L1 * 0.98], [LOD_L1, LOD_L2 * 0.98], [LOD_L2, LOD_L3 * 0.98], [LOD_L3, Infinity]];
  let boardSeq = 0;

  /* ------------------------------------------------------------------ geometry helpers */
  const intersects = (a, b) => a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
  const union = (rs) => {
    const x0 = Math.min.apply(null, rs.map(r => r.x)), y0 = Math.min.apply(null, rs.map(r => r.y));
    const x1 = Math.max.apply(null, rs.map(r => r.x + r.w)), y1 = Math.max.apply(null, rs.map(r => r.y + r.h));
    return { x: x0, y: y0, w: x1 - x0, h: y1 - y0 };
  };
  const grow = (r, d) => ({ x: r.x - d, y: r.y - d, w: r.w + 2 * d, h: r.h + 2 * d });
  const rectOf = (o) => ({ x: o.x, y: o.y, w: o.w, h: o.h });
  const plural = (n, one, many) => n + ' ' + (n === 1 ? one : (many || one + 's'));
  /** Break a name into at most max lines of n characters, preferring a break after a space, `/`,
      `_`, `.` or `-`, or before a camel-case hump, in the second half of the line; the last line
      is truncated with an ellipsis. Never returns a line longer than n. */
  function wrapName(s, n, max) {
    s = String(s);
    const out = [];
    while (s.length && out.length < max) {
      if (s.length <= n) { out.push(s); s = ''; break; }
      if (out.length === max - 1) { out.push(s.slice(0, Math.max(1, n - 1)) + '…'); s = ''; break; }
      let cut = 0;
      for (let i = n; i >= Math.ceil(n / 2); i--) {
        const a = s[i - 1], b = s[i];
        if (/[\s/_.\-]/.test(a) || (/[a-z0-9]/.test(a) && /[A-Z]/.test(b))) { cut = i; break; }
      }
      if (!cut) cut = n;
      out.push(s.slice(0, cut).replace(/\s+$/, ''));
      s = s.slice(cut).replace(/^\s+/, '');
    }
    return out;
  }
  /** Anchors for an edge between two boxes: horizontal when they do not overlap in x, else vertical. */
  function connect(a, b) {
    const acx = a.x + a.w / 2, bcx = b.x + b.w / 2, acy = a.y + a.h / 2, bcy = b.y + b.h / 2;
    if (b.x >= a.x + a.w || b.x + b.w <= a.x) {
      const right = bcx >= acx;
      const x1 = right ? a.x + a.w : a.x, x2 = right ? b.x : b.x + b.w;
      const d = Math.max(30, Math.abs(x2 - x1) / 2) * (right ? 1 : -1);
      return { d: 'M' + x1 + ',' + acy + ' C' + (x1 + d) + ',' + acy + ' ' + (x2 - d) + ',' + bcy + ' ' + x2 + ',' + bcy,
               mid: [(x1 + x2) / 2, (acy + bcy) / 2] };
    }
    const down = bcy >= acy;
    const y1 = down ? a.y + a.h : a.y, y2 = down ? b.y : b.y + b.h;
    const d = Math.max(16, Math.abs(y2 - y1) / 2) * (down ? 1 : -1);
    return { d: 'M' + acx + ',' + y1 + ' C' + acx + ',' + (y1 + d) + ' ' + bcx + ',' + (y2 - d) + ' ' + bcx + ',' + y2,
             mid: [(acx + bcx) / 2, (y1 + y2) / 2] };
  }

  /* ------------------------------------------------------------------ the board */
  function createBoard(ctx, opts) {
    opts = opts || {};
    const U = ctx.util, arr = U.arr, trunc = U.trunc, svg = ctx.svg, html = ctx.html;
    const MIN = U.MIN_TEXT_PX;
    const L = ctx.layout && ctx.layout.atlas;
    const host = ctx.el;
    const stub = {
      focus: () => false, canFocus: () => false, search: () => null, minimap: () => [], setFilter: () => {},
      level: () => -1, render: () => {}, destroy: () => {}, viewport: null
    };
    if (!L || !arr(L.contexts).length) {
      host.append(html('p', 'lens-msg', L ? 'Nothing to draw: the map has no bounded contexts.'
        : 'This map carries no Atlas layout (layout.atlas is missing). Regenerate the page with slice-index.py atlas.'));
      return stub;
    }
    const uid = 'atl' + (++boardSeq);

    /* ---------- indexes over the layout ---------- */
    const contexts = arr(L.contexts), segments = arr(L.segments), cards = arr(L.cards), edges = arr(L.edges);
    const links = arr(L.links), bridges = arr(L.bridges), boxes = arr(L.scenarioBoxes);
    const ctxById = new Map(contexts.map(c => [c.id, c]));
    const segById = new Map(segments.map(s => [s.id, s]));
    const cardById = new Map(cards.map(c => [c.id, c]));
    const boxBySlice = new Map(boxes.map(b => [b.slice, b]));
    const colBySlice = new Map();                   // the full (non-portal) column of each slice
    const segsByCtx = new Map(), segsByProc = new Map(), sliceSegs = new Map();
    segments.forEach(seg => {
      if (!segsByCtx.has(seg.bc)) segsByCtx.set(seg.bc, []);
      segsByCtx.get(seg.bc).push(seg);
      if (!segsByProc.has(seg.process)) segsByProc.set(seg.process, []);
      segsByProc.get(seg.process).push(seg);
      const ys = arr(seg.lanes).map(l => l.y), ye = arr(seg.lanes).map(l => l.y + l.h);
      seg._lanesTop = ys.length ? Math.min.apply(null, ys) : seg.y;
      seg._lanesBottom = ye.length ? Math.max.apply(null, ye) : seg.y + seg.h;
      const cols = arr(seg.columns);
      seg._gutter = cols.length ? Math.max(0, Math.min.apply(null, cols.map(c => c.x)) - seg.x) : 0;
      cols.forEach(col => {
        if (!col.portal && !colBySlice.has(col.slice)) colBySlice.set(col.slice, { seg, col });
        if (!sliceSegs.has(col.slice)) sliceSegs.set(col.slice, []);
        sliceSegs.get(col.slice).push(seg);
      });
    });
    const fullCards = new Map(), mainCard = new Map();
    cards.forEach(c => {
      if (c.portal) return;
      if (!fullCards.has(c.slice)) fullCards.set(c.slice, []);
      fullCards.get(c.slice).push(c);
    });
    fullCards.forEach((list, id) => {
      const s = ctx.index.slice(id);
      const want = s && s.error ? 'error' : MAIN_ROLE[s && s.kind] || null;
      mainCard.set(id, list.find(c => c.role === want) || list.find(c => c.role !== 'trigger') || list[0]);
    });
    const cardsByMsg = new Map();                   // 'command:Name' / 'event:Name' → full cards
    cards.forEach(c => {
      if (c.portal || (c.role !== 'command' && c.role !== 'event')) return;
      const key = c.role + ':' + c.name;
      if (!cardsByMsg.has(key)) cardsByMsg.set(key, []);
      cardsByMsg.get(key).push(c);
    });
    cardsByMsg.forEach(list => list.sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    const columnRect = (id) => {
      const fc = colBySlice.get(id);
      if (!fc) return null;
      const box = boxBySlice.get(id);
      const bottom = box && box.h > 0 ? box.y + box.h : fc.seg._lanesBottom;
      return { x: fc.col.x, y: fc.seg._lanesTop, w: fc.col.w, h: bottom - fc.seg._lanesTop };
    };

    /* ---------- issues and diff, per drawn thing ---------- */
    const sliceIssues = (id) => ctx.issuesFor({ kind: 'slice', id });
    const countLevels = (list) => { const c = { error: 0, warn: 0, info: 0 }; list.forEach(i => { if (c[i.level] != null) c[i.level]++; }); return c; };
    const segIssues = new Map(), ctxIssues = new Map();
    segments.forEach(seg => {
      const seen = new Set(), all = [];
      const add = (list) => list.forEach(i => { if (!seen.has(i.id)) { seen.add(i.id); all.push(i); } });
      arr(seg.columns).forEach(col => { if (!col.portal) add(sliceIssues(col.slice)); });
      add(ctx.issuesFor({ kind: 'process', id: seg.process }));
      segIssues.set(seg.id, countLevels(all));
    });
    contexts.forEach(c => ctxIssues.set(c.id, countLevels(ctx.issuesFor({ kind: 'context', id: c.id }, { deep: true }))));
    const diffState = (id, card) => {
      if (card && card.ghost) return 'removed';
      return ctx.diff ? ctx.diff.state(id) : null;
    };
    const diffCounts = (sliceIds) => {
      const c = { added: 0, changed: 0, removed: 0 };
      if (!ctx.diff) return c;
      sliceIds.forEach(id => { const st = ctx.diff.state(id); if (st) c[st]++; });
      return c;
    };
    const segSlices = (seg) => arr(seg.columns).filter(c => !c.portal).map(c => c.slice);
    const nodeKey = (c) => c.role === 'command' ? 'cmd:' + c.name : c.role === 'event' ? 'evt:' + c.name : 'slice:' + c.slice;

    /* ---------- state ---------- */
    const S = {
      ver: 0, level: -1, sel: null, selMsg: null, match: null, mark: null, hover: null,
      filter: typeof opts.filter === 'function' ? opts.filter : null, raf: 0
    };
    const shown = (sliceId) => !S.filter || !!S.filter(sliceId);
    const cardMatches = (c) => !S.match || S.match.slices.has(c.slice) ||
      ((c.role === 'command' || c.role === 'event') && S.match.msgs.has(c.role + ':' + c.name));
    const segMatches = (seg) => !S.match || S.match.segs.has(seg.id);
    const ctxMatches = (c) => !S.match || S.match.ctxs.has(c.id);

    /* ---------- DOM ---------- */
    const root = svg('svg', { class: 'lens-svg', role: 'img', 'aria-label': 'Atlas of bounded contexts, processes and slices' });
    const defs = svg('defs');
    const marker = (kind, size) => {
      const m = svg('marker', { id: uid + '-arrow-' + kind, viewBox: '0 0 10 10', refX: '9', refY: '5', markerWidth: size, markerHeight: size,
        markerUnits: 'userSpaceOnUse', orient: 'auto-start-reverse' });
      m.append(svg('path', { d: 'M0,0 L10,5 L0,10 z', class: 'arrow atlas-arrow-' + kind }));
      defs.append(m);
    };
    EDGE_KINDS.forEach(k => marker(k, 12));
    marker('link', 40);
    marker('bridge', 120);
    root.append(defs);
    const world = svg('g', { class: 'atlas-world' });
    const layerNames = ['ctx', 'bridge', 'seg', 'lane', 'link', 'edge', 'card', 'scen', 'label', 'mark'];
    const layers = {};
    layerNames.forEach(n => { const g = svg('g', { class: 'atlas-l-' + n }); layers[n] = { g, drawn: new Map() }; world.append(g); });
    root.append(world);
    host.append(root);
    const chip = html('div', 'atlas-level');
    chip.setAttribute('aria-live', 'polite');
    host.append(chip);

    const vp = ctx.createViewport({ svg: root, world, bounds: L.bounds || union(contexts.map(rectOf)), minScale: LOD_L1 / 2, onChange: () => render() });

    /* ---------- drawing primitives ---------- */
    /** Text at a constant screen size px, for the current k bucket kk. */
    const label = (x, y, str, px, kk, cls, anchor) => {
      const t = svg('text', { x, y, 'font-size': (px / kk).toFixed(3), class: 'atlas-nopt ' + (cls || ''), 'text-anchor': anchor || 'start' }, str);
      return t;
    };
    /** Text at a world size; null when it would be drawn below the minimum size at scale k. */
    const wtext = (x, y, str, size, k, cls, anchor) => {
      if (size * k < MIN - 0.01) return null;
      return svg('text', { x, y, 'font-size': size, class: 'atlas-nopt ' + (cls || ''), 'text-anchor': anchor || 'start' }, str);
    };
    const fits = (worldW, fontWorld) => Math.floor(worldW / (CHAR_W * fontWorld));
    const levelDot = (cx, cy, level, kk) => svg('circle', { cx, cy, r: (DOT_PX / kk).toFixed(3), class: 'dot-' + level + ' atlas-nopt' });
    const countsText = (x, y, counts, px, kk, anchor) => {
      const t = label(x, y, '', px, kk, '', anchor);
      ['error', 'warn', 'info'].forEach(l => {
        if (!counts[l]) return;
        t.append(svg('tspan', { class: 'dot-' + l }, '● '), svg('tspan', { class: 'atlas-muted' }, counts[l] + '  '));
      });
      return t.childNodes.length ? t : null;
    };
    const diffText = (x, y, c, px, kk, anchor) => {
      const parts = [];
      if (c.added) parts.push('+' + c.added);
      if (c.changed) parts.push('~' + c.changed);
      if (c.removed) parts.push('−' + c.removed);
      return parts.length ? label(x, y, parts.join(' ') + ' in this change', px, kk, 'atlas-muted', anchor) : null;
    };

    /* ---------- reconciliation: each layer keeps what is still wanted, builds what is new ---------- */
    function sync(name, items) {
      const layer = layers[name], keep = new Set();
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

    function render() {
      const v = vp.view(), k = v.k, lvl = levelOf(k);
      if (lvl !== S.level) {
        S.level = lvl;
        chip.textContent = '';
        chip.append(html('b', null, 'L' + lvl + ' · ' + LEVEL_NAMES[lvl]), document.createTextNode('  ' + Math.round(k * 100) + '%'));
        ctx.setHash({ z: lvl });
      } else if (chip.lastChild) chip.lastChild.textContent = '  ' + Math.round(k * 100) + '%';
      const kq = Math.round(Math.log(k) / Math.log(K_STEP)), kk = Math.pow(K_STEP, kq);
      const area = { x: v.x - v.w * CULL_MARGIN, y: v.y - v.h * CULL_MARGIN, w: v.w * (1 + 2 * CULL_MARGIN), h: v.h * (1 + 2 * CULL_MARGIN) };
      const vis = (r) => intersects(r, area);
      const tag = '|' + S.ver + '|' + lvl, ktag = tag + '|' + kq;

      /* contexts: every level */
      const ctxItems = [], labelItems = [];
      contexts.forEach(c => {
        if (!vis(c)) return;
        ctxItems.push({ key: 'c:' + c.id + tag, make: () => drawContext(c, lvl) });
        labelItems.push({ key: 'cl:' + c.id + ktag, make: () => contextLabel(c, lvl, k, kk) });
      });
      sync('ctx', ctxItems);

      /* L0: bridges */
      const bridgeItems = [];
      if (lvl === 0) bridges.forEach((b, i) => {
        const a = ctxById.get(b.from), z = ctxById.get(b.to);
        if (a && z && (vis(a) || vis(z))) bridgeItems.push({ key: 'b:' + i + tag, make: () => drawBridge(b, a, z, i) });
      });
      sync('bridge', bridgeItems);

      /* L1+: segments; L1 links; L2+ lanes */
      const segItems = [], laneItems = [], linkItems = [];
      if (lvl >= 1) segments.forEach(seg => {
        if (!vis(seg)) return;
        segItems.push({ key: 's:' + seg.id + ktag, make: () => drawSegment(seg, lvl, k, kk) });
        if (lvl >= 2) laneItems.push({ key: 'ln:' + seg.id + ktag, make: () => drawLanes(seg, k, kk) });
      });
      if (lvl === 1) links.forEach((l, i) => {
        const a = segById.get(l.from), z = segById.get(l.to);
        if (a && z && vis(union([a, z]))) linkItems.push({ key: 'k:' + i + ktag, make: () => drawLink(l, a, z, kk) });
      });
      sync('seg', segItems); sync('lane', laneItems); sync('link', linkItems);

      /* L2+: edges, stickies; L3: scenario stacks */
      const edgeItems = [], cardItems = [], scenItems = [];
      if (lvl >= 2) {
        edges.forEach((e, i) => {
          const a = cardById.get(e.from), z = cardById.get(e.to);
          if (!a || !z || !shown(a.slice) || !shown(z.slice)) return;
          if (!vis(union([a, z]))) return;
          edgeItems.push({ key: 'e:' + i + tag, make: () => drawEdge(e, a, z, i) });
        });
        cards.forEach(c => {
          if (!shown(c.slice) || !vis(c)) return;
          cardItems.push({ key: 'd:' + c.id + (lvl === 2 ? ktag : tag), make: () => drawCard(c, lvl, k, kk) });
        });
        if (lvl === 3) colBySlice.forEach((fc, id) => {
          if (!shown(id)) return;
          const box = boxBySlice.get(id);
          const r = box && box.h > 0 ? box : { x: fc.col.x, y: fc.seg._lanesBottom, w: fc.col.w, h: WORLD_SMALL * 2 };
          if (vis(r)) scenItems.push({ key: 'n:' + id + tag, make: () => drawScenarios(id, fc, box, k) });
        });
      }
      sync('edge', edgeItems); sync('card', cardItems); sync('scen', scenItems);
      sync('label', labelItems);

      /* issue marker: every level */
      const markItems = [];
      if (S.mark) S.mark.rects.forEach((r, i) => { if (vis(r)) markItems.push({ key: 'm:' + i + ktag, make: () => drawMark(r, kk) }); });
      sync('mark', markItems);
      applyHover();
    }
    function bump() {
      S.ver++;
      if (S.raf) return;
      S.raf = requestAnimationFrame(() => { S.raf = 0; render(); });
    }

    /* ---------- L0 / L1 / L2 pieces ---------- */
    function drawContext(c, lvl) {
      const r = svg('rect', { x: c.x, y: c.y, width: c.w, height: c.h, rx: 14, class: 'atlas-ctx' + (lvl === 0 ? ' l0' : '') + (ctxMatches(c) ? '' : ' dim') });
      r.addEventListener('click', () => {
        if (vp.wasDrag()) return;
        if (S.level === 0) vp.flyTo(c, { pad: FOCUS_PAD, minScale: LOD_L1 * 1.02, maxScale: LOD_L2 * 0.97 });
        else ctx.openCard({ kind: 'context', id: c.id });
      });
      r.addEventListener('mousemove', (ev) => {
        if (S.level !== 0) return;
        ctx.tip(ev, c.id + '\n' + ctx.index.slicesOf(c.id).length + ' slices · ' + (segsByCtx.get(c.id) || []).length + ' process segments\nclick to open');
      });
      r.addEventListener('mouseleave', () => ctx.hideTip());
      return r;
    }
    function contextLabel(c, lvl, k, kk) {
      const g = svg('g', { class: ctxMatches(c) ? '' : 'dim' });
      const nSlices = ctx.index.slicesOf(c.id).length;
      if (lvl === 0) {
        const cx = c.x + c.w / 2, cy = c.y + c.h / 2;
        // A context's name is the point of L0: it may run past a small region (halo keeps it legible).
        if (c.w * k < CTX_LABEL_HIDE_PX) return null;   // too small to own a label: the tip names it
        const roomPx = Math.max(c.w * k, Math.min(CTX_LABEL_MIN_PX, c.w * k * CTX_LABEL_SPILL));
        const maxChars = Math.floor(roomPx / (CHAR_W * LABEL_BIG_PX)) - 1;
        if (maxChars < 3) return null;
        g.append(label(cx, cy - 6 / kk, trunc(c.id, maxChars), LABEL_BIG_PX, kk, 'atlas-strong atlas-halo', 'middle'));
        g.append(label(cx, cy + 12 / kk, trunc(plural(nSlices, 'slice') + ' · ' + plural((segsByCtx.get(c.id) || []).length, 'process', 'processes'), Math.floor(roomPx / (CHAR_W * LABEL_PX))), LABEL_PX, kk, 'atlas-muted atlas-halo', 'middle'));
        const ct = countsText(cx, cy + 28 / kk, ctxIssues.get(c.id) || {}, LABEL_PX, kk, 'middle');
        if (ct) g.append(ct);
        const ids = ctx.index.slicesOf(c.id).map(s => s.id).concat(arr(segsByCtx.get(c.id)).flatMap(s => segSlices(s)).filter(id => ctx.index.isGhost(id)));
        const dt = diffText(cx, cy + 44 / kk, diffCounts(Array.from(new Set(ids))), LABEL_PX, kk, 'middle');
        if (dt) g.append(dt);
        return g;
      }
      const segs = segsByCtx.get(c.id) || [];
      const head = segs.length ? Math.min.apply(null, segs.map(s => s.y)) - c.y : c.h;
      // Inside the context's header band once it is tall enough on screen, else just above the region.
      const inside = head * k >= LABEL_PX + 8;
      const x = c.x + (inside ? 12 / kk : 0);
      const y = inside ? c.y + head / 2 + LABEL_PX * 0.35 / kk : c.y - 6 / kk;
      const maxChars = Math.floor(c.w * k / (CHAR_W * (LABEL_PX + 1)));
      if (maxChars < 3) return null;
      g.append(label(x, y, trunc(c.id + '  ·  ' + nSlices + ' slices', maxChars), LABEL_PX + 1, kk, 'atlas-strong atlas-halo'));
      return g;
    }
    function drawBridge(b, a, z, i) {
      const ax = a.x + a.w / 2, ay = a.y + a.h / 2, zx = z.x + z.w / 2, zy = z.y + z.h / 2;
      const dx = zx - ax, dy = zy - ay, len = Math.hypot(dx, dy) || 1;
      const off = len * 0.08, mx = (ax + zx) / 2 - dy / len * off, my = (ay + zy) / 2 + dx / len * off;
      const n = arr(b.events).length;
      const p = svg('path', { d: 'M' + ax + ',' + ay + ' Q' + mx + ',' + my + ' ' + zx + ',' + zy, class: 'atlas-bridge',
        'stroke-width': 2 + 2 * Math.min(n, 6), 'marker-end': 'url(#' + uid + '-arrow-bridge)' });
      p.addEventListener('mousemove', (ev) => ctx.tip(ev, b.from + ' → ' + b.to + '  (' + n + ' event' + (n === 1 ? '' : 's') + ')\n' + arr(b.events).join('\n')));
      p.addEventListener('mouseleave', () => ctx.hideTip());
      p.dataset.i = i;
      return p;
    }
    function drawSegment(seg, lvl, k, kk) {
      const p = ctx.index.process(seg.process) || { id: seg.process, name: seg.process, declared: false, color: 0 };
      const color = ctx.procColor(p);
      const g = svg('g', { class: segMatches(seg) ? '' : 'dim' });
      const box = svg('rect', { x: seg.x, y: seg.y, width: seg.w, height: seg.h, rx: 8,
        class: 'atlas-seg' + (p.declared ? '' : ' derived') + (lvl >= 2 ? ' board' : '') });
      box.style.stroke = color;
      box.style.fill = color;
      if (lvl === 1) box.style.fillOpacity = '0.14';
      g.append(box);
      const ids = segSlices(seg);
      if (lvl === 1) {
        box.addEventListener('click', () => {
          if (vp.wasDrag()) return;
          vp.flyTo(seg, { pad: FOCUS_PAD, minScale: LOD_L2 * 1.02 });
        });
        box.addEventListener('mousemove', (ev) => ctx.tip(ev, p.name + (p.declared ? '' : ' (derived)') + ' · ' + seg.bc + '\n' + ids.join('\n')));
        box.addEventListener('mouseleave', () => ctx.hideTip());
        const cx = seg.x + seg.w / 2, cy = seg.y + seg.h / 2;
        const maxChars = Math.floor(seg.w * k / (CHAR_W * LABEL_PX)) - 1;
        const rows = Math.floor(seg.h * k / (LABEL_PX * LINE));
        if (maxChars >= 3 && rows >= 1) {
          const lines = [];
          const nameRows = wrapName(p.name, maxChars, Math.min(PROC_NAME_LINES, rows));   // the name before the counts
          nameRows.forEach(row => lines.push(() => label(cx, 0, row, LABEL_PX, kk, 'atlas-strong atlas-halo', 'middle')));
          lines.push(() => label(cx, 0, trunc(ids.length + ' slice' + (ids.length === 1 ? '' : 's') + (arr(p.bcs).length > 1 ? ' · ' + seg.bc : ''), maxChars), LABEL_PX, kk, 'atlas-muted atlas-halo', 'middle'));
          lines.push(() => countsText(cx, 0, segIssues.get(seg.id) || {}, LABEL_PX, kk, 'middle'));
          lines.push(() => diffText(cx, 0, diffCounts(ids), LABEL_PX, kk, 'middle'));
          const made = lines.map(f => f()).filter(Boolean).slice(0, rows);
          const step = LABEL_PX * LINE / kk, y0 = cy - (made.length - 1) * step / 2 + LABEL_PX * 0.35 / kk;
          made.forEach((t, i) => { t.setAttribute('y', y0 + i * step); g.append(t); });
        }
        return g;
      }
      const headH = Math.max(0, seg._lanesTop - seg.y);
      if (headH > 0) {
        const head = svg('rect', { x: seg.x, y: seg.y, width: seg.w, height: headH, rx: 8, class: 'atlas-seg-head' });
        head.style.fill = color;
        head.addEventListener('click', () => { if (!vp.wasDrag()) ctx.openCard({ kind: 'process', id: p.id }); });
        g.append(head);
        if (headH * k >= LABEL_PX + 2) {
          const counts = segIssues.get(seg.id) || {};
          const maxChars = Math.floor((seg.w - 16) * k / (CHAR_W * LABEL_PX));
          g.append(label(seg.x + 10 / kk, seg.y + headH / 2 + LABEL_PX * 0.35 / kk,
            trunc(p.name + (p.declared ? '' : ' (derived)') + '  ·  ' + seg.bc + '  ·  ' + ids.length + ' slices', maxChars - 6), LABEL_PX, kk, 'atlas-strong'));
          const ct = countsText(seg.x + seg.w - 10 / kk, seg.y + headH / 2 + LABEL_PX * 0.35 / kk, counts, LABEL_PX, kk, 'end');
          if (ct) g.append(ct);
        }
      }
      return g;
    }
    function drawLanes(seg, k, kk) {
      const g = svg('g', { class: 'atlas-nopt' + (segMatches(seg) ? '' : ' dim') });
      arr(seg.lanes).forEach((l, i) => {
        g.append(svg('rect', { x: seg.x + 1, y: l.y, width: seg.w - 2, height: l.h, class: 'atlas-lane' + (i % 2 ? ' odd' : '') }));
        const room = seg._gutter * k;
        if (l.h * k >= LABEL_PX * 1.4 && room >= 30) {
          const chars = Math.floor((seg._gutter - 12 / kk) / (CHAR_W * LABEL_PX / kk));
          if (chars >= 3) g.append(label(seg.x + 8 / kk, l.y + 6 / kk + LABEL_PX / kk, trunc(String(l.label || l.id).toUpperCase(), chars), LABEL_PX, kk, 'atlas-lane-label'));
        }
      });
      return g;
    }
    function drawLink(l, a, z, kk) {
      const g = svg('g');
      const c = connect(a, z);
      const p = svg('path', { d: c.d, class: 'atlas-link' + (l.cross ? ' cross' : ''), 'marker-end': 'url(#' + uid + '-arrow-link)' });
      p.addEventListener('mousemove', (ev) => ctx.tip(ev, l.from.replace('@', ' @ ') + '\n→ ' + l.to.replace('@', ' @ ') + '\n' + arr(l.events).join('\n')));
      p.addEventListener('mouseleave', () => ctx.hideTip());
      g.append(p);
      const text = arr(l.events).join(', ');
      if (text) g.append(label(c.mid[0], c.mid[1] - 4 / kk, trunc(text, 36), LABEL_PX, kk, 'atlas-muted atlas-halo', 'middle'));
      return g;
    }

    /* ---------- L2 / L3: stickies and edges ---------- */
    function cardDetail(c) {
      const s = ctx.index.slice(c.slice) || {};
      if (c.role === 'command' || c.role === 'event') {
        const m = ctx.index.message(c.role, c.name);
        if (!m) return [];
        const idf = m.identity ? String(m.identity) : null;
        return (idf ? [idf] : []).concat(arr(m.fields).map(String).filter(f => f !== idf));
      }
      if (c.role === 'view') {
        const rms = arr(s.readModels);
        const rm = rms.find(r => r.name === c.name) || rms[0];
        return rm ? arr(rm.columns).map(col => typeof col === 'string' ? col : col.name + (col.type ? ': ' + col.type : '')) : [];
      }
      if (c.role === 'trigger') return arr(s.endpoints).map(e => e.method + ' ' + e.path + (e.auth ? ' [' + e.auth + ']' : ''));
      if (c.role === 'automation') {
        const ins = arr(s.consumes).map(U.nameOf);
        return (s.schedule ? ['schedule ' + U.fmtSchedule(s.schedule)] : []).concat(ins.length ? ['on ' + ins.join(', ')] : []);
      }
      if (c.role === 'external') return [(s.direction || 'external') + ' · ' + (s.name || c.slice)];
      if (c.role === 'error') return arr(s.flags).map(f => f.text).concat(['manifest did not parse']);
      return [];
    }
    /** What a card lists at L3 — names only, the types stay in the hover and the detail panel —
        and the noun its overflow counts in: { items, noun, strong }, strong = the first item is the
        identity property. A trigger leaves out the endpoint it is already named after. */
    function cardItems(c) {
      const s = ctx.index.slice(c.slice) || {};
      const bare = (f) => String(f).split(':')[0].trim();
      if (c.role === 'command' || c.role === 'event') {
        const m = ctx.index.message(c.role, c.name);
        if (!m) return { items: [], noun: 'field' };
        const idf = m.identity ? bare(m.identity) : null;
        const rest = arr(m.fields).map(bare).filter(f => f !== idf);
        return { items: (idf ? [idf] : []).concat(rest), noun: 'field', strong: !!idf };
      }
      if (c.role === 'view') {
        const rms = arr(s.readModels);
        const rm = rms.find(r => r.name === c.name) || rms[0];
        return { items: rm ? arr(rm.columns).map(col => typeof col === 'string' ? bare(col) : col.name) : [], noun: 'column' };
      }
      if (c.role === 'trigger') {
        const eps = arr(s.endpoints);
        const named = eps.findIndex(e => e.method + ' ' + e.path === c.name);
        const items = named >= 0 && eps[named].auth ? ['auth ' + eps[named].auth] : [];
        eps.forEach((e, i) => { if (i !== named) items.push(e.method + ' ' + e.path); });
        return { items, noun: 'endpoint' };
      }
      return { items: cardDetail(c), noun: 'line' };
    }
    /** Pack items into at most room lines of chars characters, joined by ", ". When some do not fit,
        the last line ends in "+N <noun>s", N counting the items not shown. Returns [{ text, strong }],
        strong set on the first line when it holds only the identity property. */
    function packItems(ci, chars, room) {
      const items = ci.items.map(it => trunc(String(it), chars));
      const text = (xs) => xs.join(', ');
      const lines = [];
      items.forEach(it => {
        const cur = lines[lines.length - 1];
        if (cur && text(cur.concat([it])).length <= chars) cur.push(it);
        else lines.push([it]);
      });
      const shown = lines.slice(0, room);
      let hidden = items.length - shown.reduce((n, l) => n + l.length, 0);
      const out = shown.map(text);
      if (hidden > 0) {
        const last = shown[shown.length - 1];
        const tail = () => '+' + plural(hidden, ci.noun);
        while (last.length && (text(last) + ' ' + tail()).length > chars) { last.pop(); hidden++; }
        out[out.length - 1] = last.length ? text(last) + ' ' + tail() : tail();
      }
      return out.map((t, i) => ({ text: t, strong: i === 0 && !!ci.strong && t === items[0] }));
    }
    function drawCard(c, lvl, k, kk) {
      const st = diffState(c.slice, c);
      const cls = ['st', 'r-' + c.role];
      if (c.portal) cls.push('portal');
      if (st) cls.push(st);
      if ((S.sel && S.sel === c.slice && !c.portal) || (S.selMsg && S.selMsg === c.role + ':' + c.name)) cls.push('sel');
      if (!cardMatches(c)) cls.push('dim');
      const g = svg('g', { class: cls.join(' ') });
      g.dataset.card = c.id;
      g.append(svg('rect', { class: 'bg', x: c.x, y: c.y, width: c.w, height: c.h, rx: 6 }));
      const padX = 8;
      const glyph = (c.portal ? '↗ ' : '') + (ROLE_GLYPH[c.role] || '');
      if (lvl === 2) {
        const font = LABEL_PX / kk, lh = font * STICKY_LINE;
        const chars = fits(c.w - 2 * padX, font);
        const n = Math.min(STICKY_LINES, Math.floor((c.h - 2 * STICKY_PAD / kk) / lh));
        if (chars >= 3 && n >= 1) {
          const rows = wrapName(glyph + c.name, chars, n);
          const y0 = c.y + c.h / 2 - (rows.length - 1) * lh / 2 + font * 0.35;
          rows.forEach((row, i) => g.append(svg('text', { x: c.x + padX, y: y0 + i * lh, 'font-size': font.toFixed(3), class: 'atlas-nopt' }, row)));
        }
      } else {
        let y = c.y + 6 + WORLD_SMALL;
        const capText = c.portal ? 'portal · drawn in full elsewhere' : c.role === 'event' && String(c.lane).startsWith('stream:') ? 'event · ' + c.lane.slice(7) : c.role;
        const cap = wtext(c.x + padX, y, trunc(capText, fits(c.w - 2 * padX, WORLD_SMALL)), WORLD_SMALL, k, 'cap');
        if (cap) g.append(cap);
        y += WORLD_NAME * LINE - 2;
        const nm = wtext(c.x + padX, y, trunc(glyph + c.name, fits(c.w - 2 * padX, WORLD_NAME)), WORLD_NAME, k, 'atlas-strong');
        if (nm) g.append(nm);
        const lh = WORLD_SMALL * LINE, room = Math.floor((c.y + c.h - 4 - y) / lh);
        const chars = Math.max(4, Math.floor((c.w - 2 * padX) / (MONO_W * WORLD_SMALL)));
        if (room > 0 && !c.portal) {
          packItems(cardItems(c), chars, room).forEach((r, i) => {
            const t = wtext(c.x + padX, y + (i + 1) * lh, r.text, WORLD_SMALL, k, r.strong ? 'atlas-cell atlas-strong' : 'atlas-cell');
            if (t) g.append(t);
          });
        }
      }
      const mc = mainCard.get(c.slice);
      if (mc && mc.id === c.id) {
        const lv = ctx.levelOf(sliceIssues(c.slice));
        if (lv) g.append(levelDot(c.x + c.w - 2, c.y + 2, lv, kk));
      }
      g.addEventListener('click', (ev) => {
        ev.stopPropagation();
        if (vp.wasDrag()) return;
        // A portal's slice is focused on its full column, so the click flies there.
        ctx.focusTarget({ kind: 'slice', id: c.slice });
      });
      g.addEventListener('mouseenter', () => { S.hover = c.id; applyHover(); });
      g.addEventListener('mouseleave', () => { if (S.hover === c.id) { S.hover = null; applyHover(); } ctx.hideTip(); });
      g.addEventListener('mousemove', (ev) => ctx.tip(ev, cardTip(c)));
      return g;
    }
    function cardTip(c) {
      const f = document.createDocumentFragment();
      const b = document.createElement('b');
      b.textContent = c.name;
      f.append(b, document.createTextNode('\n' + c.role + ' · ' + c.slice + (c.portal ? '\nportal — click to fly to the full column' : '\nclick for details')));
      const st = diffState(c.slice, c);
      if (st) f.append(document.createTextNode('\n' + st + ' in this change'));
      const lines = cardDetail(c);
      if (lines.length) f.append(document.createTextNode('\n\n' + lines.slice(0, 8).join('\n') + (lines.length > 8 ? '\n…' : '')));
      return f;
    }
    function drawEdge(e, a, z, i) {
      const c = connect(a, z);
      const st = ctx.diff ? ctx.diff.edgeState(nodeKey(a), nodeKey(z), e.kind) : null;
      const dim = S.match && !cardMatches(a) && !cardMatches(z);
      const sel = S.sel && (a.slice === S.sel || z.slice === S.sel);
      const p = svg('path', { d: c.d, class: 'ed k-' + e.kind + (e.cross ? ' cross' : '') + (st ? ' ' + st : '') + (sel ? ' hi' : '') + (dim ? ' dim' : ''),
        'marker-end': 'url(#' + uid + '-arrow-' + (EDGE_KINDS.includes(e.kind) ? e.kind : 'feeds') + ')' });
      p.dataset.from = e.from; p.dataset.to = e.to; p.dataset.i = i;
      return p;
    }
    function applyHover() {
      const id = S.hover;
      layers.edge.drawn.forEach(p => {
        p.classList.toggle('hi-in', !!id && p.dataset.to === id);
        p.classList.toggle('hi-out', !!id && p.dataset.from === id);
      });
    }

    /* ---------- L3: scenario stacks, or invariants when a slice declares none ---------- */
    function chipEl(x, y, w, h, kind, text) {
      const g = svg('g', { class: 'atlas-chip c-' + kind + ' atlas-nopt' });
      g.append(svg('rect', { x, y, width: w, height: h, rx: 3 }));
      g.append(svg('text', { x: x + 5, y: y + h / 2 + WORLD_SMALL * 0.36, 'font-size': WORLD_SMALL }, text));
      return g;
    }
    /** A row of chips from x0 to x1; returns false when nothing fitted. */
    function chipRow(g, x0, x1, y, h, items) {
      let x = x0;
      for (let i = 0; i < items.length; i++) {
        const it = items[i];
        const want = (it.text.length) * CHAR_W * WORLD_SMALL + 10;
        const restAfter = items.length - i - 1;
        const reserve = restAfter ? 4 * CHAR_W * WORLD_SMALL + 10 : 0;
        const room = x1 - x - reserve;
        if (room < CHIP_MIN_CHARS * CHAR_W * WORLD_SMALL + 10) {
          g.append(svg('text', { x, y: y + h / 2 + WORLD_SMALL * 0.36, 'font-size': WORLD_SMALL, class: 'atlas-muted atlas-nopt' }, '+' + (items.length - i)));
          return true;
        }
        const w = Math.min(want, room);
        const chars = Math.max(1, Math.floor((w - 10) / (CHAR_W * WORLD_SMALL)));
        g.append(chipEl(x, y, w, h, it.kind, trunc(it.text, chars)));
        x += w + 4;
      }
      return items.length > 0;
    }
    const stepItem = (st) => ({ kind: (st && st.type) || 'event', text: st && st.type === 'schedule' ? '⏱ schedule' : String((st && st.name) || (st && st.type) || '?') });
    function scenarioText(sc) {
      const fmt = (st) => (st.type === 'schedule' ? 'schedule' : st.type + ' ' + st.name) +
        (st.with && Object.keys(st.with).length ? ' { ' + Object.keys(st.with).sort().map(k2 => k2 + ': ' + JSON.stringify(st.with[k2])).join(', ') + ' }' : '');
      const th = sc.then || { kind: 'nothing' };
      const then = th.kind === 'steps' ? arr(th.steps).map(fmt).join('\n      ')
        : th.kind === 'error' ? 'error ' + th.error + (th.message ? ' — ' + th.message : '')
        : th.kind === 'readModel' ? th.readModel + ' (' + arr(th.rows).length + ' row' + (arr(th.rows).length === 1 ? '' : 's') + ')' : 'nothing';
      const tr = sc.testResolved || {};
      return sc.name + '\n' + 'given ' + (arr(sc.given).length ? arr(sc.given).map(fmt).join('\n      ') : '—') +
        (sc.when ? '\nwhen  ' + fmt(sc.when) : '') + '\nthen  ' + then +
        (sc.test ? '\ntest  ' + sc.test + (tr.status === 'found' ? '  ✓ ' + (tr.path || '') + (tr.line ? ':' + tr.line : '') : tr.status === 'missing' ? '  ⚠ not found' : '') : '');
    }
    function drawScenarios(id, fc, box, k) {
      const s = ctx.index.slice(id) || {};
      const scs = arr(s.scenarios);
      const g = svg('g', { class: S.match && !S.match.slices.has(id) ? 'dim' : '' });
      const lh = WORLD_SMALL * LINE, pad = 6, chipH = lh - 3;
      if (!scs.length) {
        if (s.error || ctx.index.isGhost(id)) return null;
        const inv = arr(s.invariants).map(i => (i.id ? i.id + ' ' : '') + (i.text || '') + (i.enforcedBy ? ' → ' + i.enforcedBy : ''));
        const tests = arr(s.tests).length ? 'tests: ' + arr(s.tests).join(', ') : null;
        const wrap = svg('g', { class: 'atlas-inv' });
        wrap.addEventListener('click', () => { if (!vp.wasDrag()) ctx.openCard({ kind: 'slice', id }); });
        if (box && box.h > 0) {
          // The layout reserves NO_SCENARIO_H under such a column (slice_atlas_layout.py): the label,
          // then invariants and test kinds as far as they fit, the rest counted.
          wrap.append(svg('rect', { class: 'bg', x: box.x, y: box.y, width: box.w, height: box.h, rx: 6 }));
          const chars = fits(box.w - 2 * pad, WORLD_SMALL);
          let lines = ['no scenarios declared'].concat(inv, tests ? [tests] : []);
          const room = Math.max(1, Math.floor((box.h - pad) / lh));
          if (lines.length > room) lines = lines.slice(0, room - 1).concat(['+' + (lines.length - room + 1) + ' more']);
          lines.forEach((line, i) => {
            const t = wtext(box.x + pad, box.y + pad + (i + 1) * lh - 4, trunc(line, chars), WORLD_SMALL, k, i === 0 || /^\+\d+ more$/.test(line) ? 'atlas-muted' : '');
            if (t) wrap.append(t);
          });
        } else {
          // Data laid out before the box was reserved (h 0): one line just under the lanes.
          const summary = 'no scenarios' + (inv.length ? ' · ' + inv.length + ' invariant' + (inv.length === 1 ? '' : 's') : '') + (tests ? ' · ' + tests : '');
          const y = fc.seg._lanesBottom + 4;
          wrap.append(svg('rect', { class: 'bg', x: fc.col.x + 6, y, width: fc.col.w - 12, height: lh + 4, rx: 4 }));
          const t = wtext(fc.col.x + 12, y + lh - 1, trunc(summary, fits(fc.col.w - 24, WORLD_SMALL)), WORLD_SMALL, k, 'atlas-muted');
          if (t) wrap.append(t);
        }
        const tip = inv.concat(tests ? [tests] : []).join('\n') || 'No invariants or tests declared either.';
        wrap.addEventListener('mousemove', (ev) => ctx.tip(ev, 'No scenarios declared\n' + tip));
        wrap.addEventListener('mouseleave', () => ctx.hideTip());
        g.append(wrap);
        return g;
      }
      if (!box || box.h <= 0) return null;
      const slotH = box.h / scs.length;
      scs.forEach((sc, i) => {
        const y0 = box.y + i * slotH, bottom = y0 + slotH - 4;
        const th = sc.then || { kind: 'nothing' };
        const sg = svg('g', { class: 'atlas-scn' + (th.kind === 'error' ? ' err' : '') });
        sg.append(svg('rect', { class: 'bg', x: box.x, y: y0, width: box.w, height: slotH - 4, rx: 6 }));
        const x0 = box.x + pad, x1 = box.x + box.w - pad;
        let y = y0 + pad;
        const room = () => y + lh <= bottom - 2;
        let clipped = false;
        const tr = sc.testResolved || {};
        const badge = tr.status === 'found' ? ['✓', 'atlas-ok'] : tr.status === 'missing' ? ['⚠', 'atlas-warn'] : null;
        if (room()) {
          const nm = wtext(x0, y + lh - 4, trunc(sc.name, fits(x1 - x0 - (badge ? 16 : 0), WORLD_SMALL)), WORLD_SMALL, k, 'atlas-strong');
          if (nm) sg.append(nm);
          if (badge) { const b = wtext(x1, y + lh - 4, badge[0], WORLD_SMALL, k, badge[1], 'end'); if (b) sg.append(b); }
          y += lh;
        }
        const rowLabel = (letter) => wtext(x0, y + chipH / 2 + WORLD_SMALL * 0.36, letter, WORLD_SMALL, k, 'atlas-muted');
        const LX = x0 + 14;
        const rows = [['G', arr(sc.given).length ? arr(sc.given).map(stepItem) : [{ kind: 'nothing', text: '—' }]]];
        if (sc.when) rows.push(['W', [stepItem(sc.when)]]);
        if (th.kind === 'steps') rows.push(['T', arr(th.steps).map(stepItem)]);
        else if (th.kind === 'error') rows.push(['T', [{ kind: 'error', text: '✗ ' + (th.error || 'error') }]]);
        else if (th.kind === 'readModel') rows.push(['T', [{ kind: 'readModel', text: (th.readModel || 'read model') + ' · ' + arr(th.rows).length + ' row' + (arr(th.rows).length === 1 ? '' : 's') }]]);
        else rows.push(['T', [{ kind: 'nothing', text: 'nothing' }]]);
        rows.forEach(([letter, items]) => {
          if (!room()) { clipped = true; return; }
          const l = rowLabel(letter);
          if (l) sg.append(l);
          chipRow(sg, LX, x1, y + 1, chipH, items);
          y += lh;
        });
        if (th.kind === 'error' && th.message) {
          if (room()) { const t = wtext(LX, y + lh - 4, trunc('“' + th.message + '”', fits(x1 - LX, WORLD_SMALL)), WORLD_SMALL, k, 'atlas-err'); if (t) sg.append(t); y += lh; }
          else clipped = true;
        }
        if (th.kind === 'readModel') {
          const rws = arr(th.rows), cols = [];
          rws.forEach(r => Object.keys(r || {}).forEach(c => { if (!cols.includes(c)) cols.push(c); }));
          const cw = cols.length ? (x1 - LX) / cols.length : 0;
          const cchars = Math.max(1, fits(cw - 4, WORLD_SMALL));
          const tableRow = (vals, cls) => {
            if (!room()) { clipped = true; return; }
            vals.forEach((v, ci) => { const t = wtext(LX + ci * cw, y + lh - 4, trunc(v, cchars), WORLD_SMALL, k, cls); if (t) sg.append(t); });
            y += lh;
          };
          if (cols.length) {
            tableRow(cols, 'atlas-cell atlas-muted');
            rws.forEach(r => tableRow(cols.map(c => r && r[c] != null ? String(r[c]) : ''), 'atlas-cell'));
          }
        }
        if (clipped) { const t = wtext(x1, bottom - 3, '…', WORLD_SMALL, k, 'atlas-muted', 'end'); if (t) sg.append(t); }
        sg.addEventListener('click', (ev) => { ev.stopPropagation(); if (!vp.wasDrag()) ctx.openCard({ kind: 'slice', id }, { anchor: 'scenario:' + sc.name }); });
        sg.addEventListener('mousemove', (ev) => ctx.tip(ev, scenarioText(sc)));
        sg.addEventListener('mouseleave', () => ctx.hideTip());
        g.append(sg);
      });
      return g;
    }

    /* ---------- issue marker ---------- */
    function drawMark(r, kk) {
      const g = svg('g', { class: 'atlas-nopt' });
      const d = MARK_PAD_PX / kk;
      g.append(svg('rect', { x: r.x - d, y: r.y - d, width: r.w + 2 * d, height: r.h + 2 * d, rx: 8 / kk, class: 'mark' }));
      const rr = 9 / kk;
      g.append(svg('circle', { cx: r.x - d, cy: r.y - d, r: rr, class: 'mark-badge' }));
      g.append(svg('text', { x: r.x - d, y: r.y - d + 4.5 / kk, 'font-size': (13 / kk).toFixed(3), 'text-anchor': 'middle', class: 'mark-badge-t' }, '!'));
      return g;
    }
    function partRects(id, part) {
      const col = columnRect(id);
      if (!part || !col) return col ? [col] : [];
      const list = fullCards.get(id) || [];
      const byRole = (role) => list.filter(c => c.role === role).map(rectOf);
      if (part.startsWith('scenario:')) {
        const name = part.slice(9), box = boxBySlice.get(id), scs = arr((ctx.index.slice(id) || {}).scenarios);
        const i = scs.findIndex(sc => sc.name === name);
        if (box && box.h > 0 && i >= 0) { const sh = box.h / scs.length; return [{ x: box.x, y: box.y + i * sh, w: box.w, h: sh - 4 }]; }
        return [col];
      }
      if (part.startsWith('readModel:')) { const r = byRole('view'); return r.length ? r : [col]; }
      if (part.startsWith('endpoint:')) { const r = byRole('trigger'); return r.length ? r : [col]; }
      const m = /^(consumes|publishes|handles|dispatches):(.+)$/.exec(part);
      if (m) {
        const name = m[2];
        const r = list.filter(c => c.name === name).map(rectOf);
        if (r.length) return r;
        const mc = mainCard.get(id);
        return mc ? [rectOf(mc)] : [col];
      }
      return [col];
    }

    /* ---------- the lens surface ---------- */
    function fly(rect, o, minK, maxK) {
      let lo = minK, hi = maxK;
      if (o && o.level != null && LEVEL_RANGE[o.level]) { lo = LEVEL_RANGE[o.level][0]; hi = Math.min(FOCUS_MAX_K * 2, LEVEL_RANGE[o.level][1]); }
      vp.flyTo(rect, { pad: FOCUS_PAD, minScale: lo, maxScale: hi, animate: !o || o.animate !== false });
    }
    function focus(t, o) {
      o = o || {};
      if (!t) { S.sel = null; S.selMsg = null; S.mark = null; bump(); return true; }
      let rect = null, marks = [];
      if (t.kind === 'slice') {
        if (!colBySlice.has(t.id) || !shown(t.id)) return false;
        rect = columnRect(t.id);
        const part = t.part || (o.issue && o.issue.target && o.issue.target.part) || '';
        if (o.issue) marks = partRects(t.id, part);
        S.sel = t.id; S.selMsg = null;
        // A scenario or a read-model column is only drawn at L3, so a marker on one flies there
        // and centres on the marked piece rather than on a column that may be taller than the screen.
        const detail = o.issue && /^(scenario|readModel):/.test(part);
        if (detail) fly(union(marks), o, LOD_L3 * 1.02, FOCUS_MAX_K);
        else fly(marks.length ? union([rect].concat(marks)) : rect, o, LOD_L2 * 1.02, FOCUS_MAX_K);
      } else if (t.kind === 'message') {
        const list = (cardsByMsg.get(t.id) || []).filter(c => shown(c.slice));
        if (!list.length) return false;
        rect = rectOf(list[0]);
        if (o.issue) marks = list.map(rectOf);
        S.sel = null; S.selMsg = t.id;
        fly(rect, o, LOD_L2 * 1.02, 1.2);
      } else if (t.kind === 'process') {
        const segs = segsByProc.get(t.id) || [];
        if (!segs.length) return false;
        rect = union(segs.map(rectOf));
        if (o.issue) marks = segs.map(rectOf);
        S.sel = null; S.selMsg = null;
        fly(rect, o, LOD_L1 * 1.02, 1.0);
      } else if (t.kind === 'context') {
        const c = ctxById.get(t.id);
        if (!c) return false;
        rect = rectOf(c);
        if (o.issue) marks = [rect];
        S.sel = null; S.selMsg = null;
        fly(rect, o, 0, LOD_L2 * 0.97);
      } else return false;
      S.mark = marks.length ? { rects: marks } : null;
      bump();
      return true;
    }
    function canFocus(t) {
      if (!t) return true;
      if (t.kind === 'slice') return colBySlice.has(t.id) && shown(t.id);
      if (t.kind === 'message') return (cardsByMsg.get(t.id) || []).some(c => shown(c.slice));
      if (t.kind === 'process') return segsByProc.has(t.id);
      if (t.kind === 'context') return ctxById.has(t.id);
      return false;
    }
    function search(q) {
      if (!q) { S.match = null; bump(); return null; }
      const slices = new Set(), msgs = new Set(), keys = new Set();
      sliceSegs.forEach((_, id) => {
        if (!shown(id)) return;
        if (ctx.match(q, ctx.sliceHay(ctx.index.slice(id)))) { slices.add(id); keys.add(id); }
      });
      cardsByMsg.forEach((list, key) => {
        const name = key.slice(key.indexOf(':') + 1);
        if (ctx.match(q, name) && list.some(c => shown(c.slice))) { msgs.add(key); keys.add('msg:' + key); }
      });
      S.match = { slices, msgs, segs: new Set(), ctxs: new Set() };
      segments.forEach(seg => { if (arr(seg.columns).some(col => slices.has(col.slice))) S.match.segs.add(seg.id); });
      cards.forEach(c => { if (cardMatches(c)) S.match.segs.add(c.segment); });
      segments.forEach(seg => { if (S.match.segs.has(seg.id)) S.match.ctxs.add(seg.bc); });
      bump();
      return keys;
    }
    function minimap() {
      return contexts.map(c => ({ x: c.x, y: c.y, w: c.w, h: c.h, cls: 'ctx' }))
        .concat(segments.map(s => ({ x: s.x, y: s.y, w: s.w, h: s.h, cls: 'seg', p: (ctx.index.process(s.process) || {}).color })));
    }
    function destroy() { if (S.raf) cancelAnimationFrame(S.raf); S.raf = 0; }

    render();
    return {
      focus, canFocus, search, minimap, destroy, render: bump, viewport: vp,
      setFilter: (fn) => { S.filter = typeof fn === 'function' ? fn : null; bump(); },
      level: () => S.level
    };
  }

  /* ------------------------------------------------------------------ legend */
  function legend(ctx) {
    const f = document.createDocumentFragment();
    const h = (t) => { const e = document.createElement('h3'); e.textContent = t; f.append(e); };
    const row = (style, text, cls) => {
      const r = document.createElement('div'); r.className = 'lg';
      const sw = document.createElement('span'); sw.className = cls || 'sw';
      Object.assign(sw.style, style);
      r.append(sw, document.createTextNode(text));
      f.append(r);
    };
    h('Stickies');
    row({ background: 'var(--trg)', borderColor: 'var(--trg-rule)' }, 'trigger — endpoint, schedule; ⚙ automation');
    row({ background: 'var(--cmd)', borderColor: 'var(--cmd-rule)' }, 'command');
    row({ background: 'var(--view)', borderColor: 'var(--view-rule)' }, 'view (read model)');
    row({ background: 'var(--evt)', borderColor: 'var(--evt-rule)' }, 'event, in the lane of its stream');
    row({ background: 'var(--ext)', borderColor: 'var(--trg-rule)', borderStyle: 'dashed' }, '⇄ external system');
    row({ background: 'var(--err-bg)', borderColor: 'var(--err)' }, '✕ manifest that did not parse');
    row({ background: 'var(--panel)', borderColor: 'var(--rule-strong)', borderStyle: 'dashed' }, '↗ portal: drawn in full in another process');
    h('Edges');
    row({ borderColor: 'var(--cmd-rule)' }, 'handles · dispatches', 'ln');
    row({ borderColor: 'var(--evt-rule)' }, 'publishes · feeds', 'ln');
    row({ borderColor: 'var(--trg-rule)', borderTopStyle: 'dashed' }, 'external; dashed = crosses a process box', 'ln');
    row({ borderColor: 'var(--accent)', borderTopWidth: '5px' }, 'bridge between contexts, width = events', 'ln');
    if (ctx && ctx.diff) {
      h('This change');
      row({ background: 'var(--panel-2)', borderColor: 'var(--ok)', borderWidth: '3px' }, 'added');
      row({ background: 'var(--panel-2)', borderColor: 'var(--warn)', borderWidth: '3px' }, 'changed');
      row({ background: 'var(--panel)', borderColor: 'var(--err)', borderStyle: 'dashed' }, 'removed (ghost)');
    }
    h('Scenarios (deepest zoom)');
    row({ background: 'var(--evt)', borderColor: 'var(--evt-rule)' }, 'G given / T then: events');
    row({ background: 'var(--cmd)', borderColor: 'var(--cmd-rule)' }, 'W when: command');
    row({ background: 'var(--view)', borderColor: 'var(--view-rule)' }, 'T then: read-model rows');
    row({ background: 'var(--err-bg)', borderColor: 'var(--err)' }, 'T then: error, with its message');
    row({ background: 'transparent', borderColor: 'var(--rule-strong)', borderStyle: 'dashed' }, 'T then: nothing emitted');
    h('Zoom');
    const p = document.createElement('div');
    p.style.color = 'var(--text-muted)';
    p.textContent = 'L0 contexts < ' + Math.round(LOD_L1 * 100) + '% · L1 processes < ' + Math.round(LOD_L2 * 100) +
      '% · L2 board < ' + Math.round(LOD_L3 * 100) + '% · L3 detail. Click a context or a process box to open it.';
    f.append(p);
    return f;
  }

  /* ------------------------------------------------------------------ registration */
  let board = null, lensCtx = null;
  Atlas.registerLens({
    id: 'atlas', label: 'Atlas', key: '1', css: CSS,
    mount(ctx) { lensCtx = ctx; board = createBoard(ctx, {}); },
    unmount() { if (board) board.destroy(); board = null; lensCtx = null; },
    focus(t, o) { return board ? board.focus(t, o) : false; },
    canFocus(t) { return board ? board.canFocus(t) : true; },
    search(q) { return board ? board.search(q) : null; },
    minimap() { return board ? board.minimap() : []; },
    legend() { return legend(lensCtx); },
    api: { createBoard, legend, levelOf, LOD: { L1: LOD_L1, L2: LOD_L2, L3: LOD_L3 } }
  });
})();
