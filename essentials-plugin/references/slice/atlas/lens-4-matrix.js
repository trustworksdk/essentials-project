/* lens-4-matrix.js — the Coupling matrix lens (design §10.4): "who touches what?"

   Two tabs over layout.matrix (C3); this file draws them and derives no coupling of its own.

     Slices × targets   rows: write targets and read models, grouped by owning context; columns:
                        slices, grouped by context, then kind. Cells W writes · O owns · R reads ·
                        R·via reads through a reader interface · R! a cross-context read without
                        one. A cell whose column's context is not the row's (cell.cross) is tinted,
                        so the coupling across a boundary is the mass off the diagonal blocks.
     Context × context  bcMatrix: the number of events the row context publishes that the column
                        context consumes; the diagonal is a context talking to itself.

   The grid is HTML, not SVG: a scrolling body with sticky row and column headers. It is always
   virtualised — only the rows and columns in view (plus OVERSCAN) are in the DOM — so a few
   hundred of each cost the same as ten. Keyboard: Tab into the grid, arrows move the cell cursor,
   PageUp/PageDown/Home/End jump, Enter opens both ends of the cell in the detail card.
   Hash: matrix.tab=ctx selects the second tab. */
(function () {
  'use strict';

  /* Geometry, in CSS px. Text is 12 px everywhere (never below Atlas.MIN_TEXT_PX). */
  const CW = 36;                // column width: room for the widest glyph, R·via, at 11 px
  const RH = 24;                // row height
  const BAND = 22;              // context band in either header
  const COL_HEAD_H = 170;       // column header: band + rotated slice names
  const MAX_UPRIGHT = 160;      // the deepest a standing column band gets
  const ROW_HEAD_W = 230;       // row header: band + target names
  const ROW_HEAD_W_NARROW = 150;
  const CTX_CW = 64;            // the context × context tab has wider cells and shorter headers
  const CTX_COL_HEAD_H = 120;
  const OVERSCAN = 6;           // rows / columns rendered beyond the visible edge
  const NARROW = '(max-width: 760px)';
  const VERB = { 'W': 'writes', 'O': 'owns', 'R': 'reads', 'R·via': 'reads through a reader interface', 'R!': 'reads across a context without a reader interface' };
  const KIND_CLASS = { command: 'k-command', view: 'k-view', automation: 'k-automation', translation: 'k-translation' };
  const LVL_CLASS = { error: 'e', warn: 'w', info: 'i' };
  /* An issue part that names a matrix row: readModel:<Model>.<column>, writes:<Target>, … */
  const ROW_PART = /^(?:readModel|writes|owns|reads|read|write):([^.:\s]+)/;

  const CSS = [
    '.mx-root { position: absolute; inset: 0; display: flex; flex-direction: column; font-size: 12px; }',
    '.mx-tabs { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; padding: 8px 12px; border-bottom: 1px solid var(--rule); background: var(--panel); }',
    '.mx-tabs .note { color: var(--text-muted); font-size: 12px; }',
    '.mx-grid { position: relative; flex: 1 1 auto; min-height: 0; overflow: hidden; background: var(--surface); }',
    '.mx-corner { position: absolute; left: 0; top: 0; z-index: 3; background: var(--panel); border-right: 1px solid var(--rule-strong);',
    '             border-bottom: 1px solid var(--rule-strong); padding: 8px 10px; color: var(--text-muted); font-size: 12px; overflow: hidden; }',
    '.mx-corner b { color: var(--text); font-weight: 600; display: block; margin-bottom: 4px; }',
    '.mx-colhead, .mx-rowhead { position: absolute; overflow: hidden; z-index: 2; background: var(--panel); }',
    '.mx-colhead { top: 0; right: 0; border-bottom: 1px solid var(--rule-strong); }',
    '.mx-rowhead { left: 0; bottom: 0; border-right: 1px solid var(--rule-strong); }',
    '.mx-in { position: absolute; left: 0; top: 0; will-change: transform; }',
    '.mx-body { position: absolute; right: 0; bottom: 0; overflow: auto; overscroll-behavior: contain; outline: none; }',
    '.mx-body:focus-visible { box-shadow: inset 0 0 0 2px var(--accent); }',
    '.mx-sizer { position: relative; }',
    '.mx-sizer > div, .mx-in > div { position: absolute; }',
    '.mx-band { background: var(--panel-strong); border: 1px solid var(--rule-strong); color: var(--text); font-weight: 600; overflow: hidden;',
    '           white-space: nowrap; cursor: pointer; }',
    '.mx-band span { position: absolute; padding: 0 6px; line-height: 20px; box-sizing: border-box; overflow: hidden; text-overflow: ellipsis; }',
    '.mx-rowhead .mx-band span { writing-mode: vertical-rl; transform: rotate(180deg); padding: 6px 0; line-height: 20px; }',
    '.mx-rowhead .mx-band.across span { writing-mode: horizontal-tb; transform: none; padding: 0 6px; }',
    '.mx-colhead .mx-band.upright span { writing-mode: vertical-rl; transform: rotate(180deg); padding: 6px 0; }',
    '.mx-probe { position: absolute; visibility: hidden; white-space: nowrap; font-weight: 600; left: -9999px; top: 0; }',
    '.mx-band.marked { border-color: var(--err); box-shadow: inset 0 0 0 2px var(--err); }',
    '.mx-cl, .mx-rl { cursor: pointer; color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }',
    '.mx-cl { border-right: 1px solid var(--rule); padding: 6px 0 4px; display: flex; flex-direction: column; align-items: center; }',
    '.mx-cl .t { writing-mode: vertical-rl; transform: rotate(180deg); overflow: hidden; text-overflow: ellipsis; flex: 1 1 auto; min-height: 0;',
    '            font-family: var(--mono); font-size: 12px; line-height: ' + (CW - 6) + 'px; }',
    '.mx-rl { border-bottom: 1px solid var(--rule); display: flex; align-items: center; gap: 6px; padding: 0 8px; font-family: var(--mono); line-height: ' + RH + 'px; }',
    '.mx-rl .t { overflow: hidden; text-overflow: ellipsis; flex: 1 1 auto; min-width: 0; }',
    '.mx-cl:hover, .mx-rl:hover { background: var(--panel-strong); }',
    '.mx-cl.hl, .mx-rl.hl { background: var(--panel-strong); font-weight: 700; }',
    '.mx-cl.marked, .mx-rl.marked { box-shadow: inset 0 0 0 2px var(--err); }',
    '.mx-sw { width: 10px; height: 10px; border-radius: 2px; border: 1.5px solid var(--rule-strong); flex: 0 0 auto; }',
    '.mx-cl .mx-sw { margin-bottom: 4px; }',
    '.mx-sw.k-command { background: var(--cmd); border-color: var(--cmd-rule); }',
    '.mx-sw.k-view, .mx-sw.k-read-model { background: var(--view); border-color: var(--view-rule); }',
    '.mx-sw.k-automation { background: var(--trg); border-color: var(--trg-rule); }',
    '.mx-sw.k-translation { background: var(--ext); border-color: var(--trg-rule); border-style: dashed; }',
    '.mx-sw.k-aggregate { background: var(--evt); border-color: var(--evt-rule); }',
    '.mx-sw.k-context { background: var(--ctx-fill); }',
    '.mx-dot { width: 8px; height: 8px; border-radius: 50%; flex: 0 0 auto; }',
    '.mx-dot.e { background: var(--err); } .mx-dot.w { background: var(--warn); } .mx-dot.i { background: var(--info); }',
    '.mx-cl .mx-dot { margin-top: 4px; }',
    '.mx-diag { background: var(--ctx-fill); border: 1px solid var(--rule-strong); }',
    '.mx-diag.marked { border: 2px solid var(--err); }',
    '.mx-gr { border-bottom: 1px solid var(--rule); }',
    '.mx-gc { border-right: 1px solid var(--rule); }',
    '.mx-gc.edge { border-right-color: var(--rule-strong); }',
    '.mx-gr.edge { border-bottom-color: var(--rule-strong); }',
    '.mx-hl { background: var(--accent); opacity: .12; pointer-events: none; }',
    '.mx-c { display: flex; align-items: center; justify-content: center; font-family: var(--mono); font-size: 12px; font-weight: 600;',
    '        color: var(--text); cursor: pointer; border-radius: 3px; white-space: nowrap; overflow: hidden; }',
    '.mx-c:hover { box-shadow: inset 0 0 0 1.5px var(--text-muted); }',
    '.mx-c.v-W { background: var(--cmd); color: var(--on-sticky); }',
    '.mx-c.v-O { background: var(--view); color: var(--on-sticky); box-shadow: inset 0 0 0 1.5px var(--view-rule); }',
    '.mx-c.v-R, .mx-c.v-Rvia { background: var(--panel-2); border: 1px solid var(--rule-strong); }',
    '.mx-c.v-Rvia { font-size: 11px; letter-spacing: -0.06em; }',
    '.mx-c.v-Rbang { background: var(--err-bg); color: var(--err); border: 1px solid var(--err); }',
    '.mx-c.cross { background: var(--warn-bg); color: var(--warn); border: 1px solid var(--warn); }',
    '.mx-c.v-Rbang.cross { background: var(--err-bg); color: var(--err); border-color: var(--err); }',
    '.mx-c.n { background: var(--panel-strong); color: var(--text); }',
    '.mx-c.n.cross { background: var(--warn-bg); color: var(--warn); border: 1px solid var(--warn); }',
    '.mx-c.sel { box-shadow: 0 0 0 2px var(--accent); }',
    '.mx-cur { border: 2px solid var(--accent); border-radius: 3px; pointer-events: none; }',
    '.mx-mark { border: 2px solid var(--err); pointer-events: none; }',
    '.mx-c.marked { box-shadow: 0 0 0 3px var(--err); }',
    '.mx-root .dim { opacity: .2; }',
    '.mx-status { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }',
    '.mx-empty { padding: 24px; color: var(--text-muted); max-width: 60ch; }',
    '.mx-extra ul { list-style: none; margin: 0; padding: 0; }',
    '.mx-extra li { margin: 3px 0; font-size: 12px; display: flex; gap: 8px; align-items: baseline; }',
    '.mx-extra li .v { font-family: var(--mono); font-weight: 700; min-width: 3.4em; }',
    '.mx-extra li.me { font-weight: 700; }',
    '.mx-extra .lnk { font-family: var(--mono); font-size: 12px; overflow-wrap: anywhere; }',
    '.mx-extra p { margin: 4px 0 8px; font-size: 13px; }',
    '@media (max-width: 760px) { .mx-tabs .note { flex-basis: 100%; } }'
  ].join('\n');

  let live = null;   // the mounted state; null while another lens is shown

  function mount(ctx) {
    const { arr, clamp } = ctx.util;
    const M = ctx.layout && ctx.layout.matrix;
    const root = ctx.html('div', 'mx-root');
    ctx.el.append(root);
    if (!M || !arr(M.cols).length) {
      root.append(ctx.html('p', 'mx-empty', 'No slices to put in a matrix: the map has no live slice, or the page was rendered without a layout.'));
      live = { focus: () => false, canFocus: () => false, search: () => null, cardExtra: () => null, destroy() {} };
      return;
    }
    const narrow = !!(window.matchMedia && window.matchMedia(NARROW).matches);

    /* ---------------- the two models, built once from C3 ---------------- */
    const groupsOf = (items) => {
      const out = [];
      items.forEach((it, i) => {
        const last = out[out.length - 1];
        if (last && last.id === it.group) last.end = i + 1;
        else out.push({ id: it.group, start: i, end: i + 1 });
      });
      return out;
    };
    const sliceModel = (() => {
      const rows = arr(M.rows).map(r => ({ id: String(r.id), kind: r.kind, bc: r.bc == null ? null : r.bc, group: r.bc == null ? '' : r.bc }));
      const cols = arr(M.cols).map(c => {
        const s = ctx.index.slice(c.slice);
        const name = s && s.name ? String(s.name) : String(c.slice).replace(/^[^.]*\./, '');
        return { id: String(c.slice), bc: c.bc, kind: c.kind, name, group: c.bc == null ? '' : c.bc };
      });
      const byRow = rows.map(() => []), byCol = cols.map(() => []);
      arr(M.cells).forEach(c => {
        if (!rows[c.r] || !cols[c.c]) return;
        const cell = { r: c.r, c: c.c, v: String(c.v), cross: !!c.cross };
        byRow[c.r].push(cell); byCol[c.c].push(cell);
      });
      byRow.forEach(l => l.sort((a, b) => a.c - b.c));
      byCol.forEach(l => l.sort((a, b) => a.r - b.r));
      const rowIdx = new Map();
      rows.forEach((r, i) => { if (!rowIdx.has(r.id)) rowIdx.set(r.id, i); });
      const rowIssues = rows.map(() => []);
      arr(ctx.data.issues).forEach(is => {
        const m = is && is.target && is.target.part ? ROW_PART.exec(String(is.target.part)) : null;
        if (m && rowIdx.has(m[1])) rowIssues[rowIdx.get(m[1])].push(is);
      });
      const colIdx = new Map();
      cols.forEach((c, i) => colIdx.set(c.id, i));
      return {
        id: 'slices', cw: CW, colHeadH: COL_HEAD_H, rows, cols, byRow, byCol, rowIdx, colIdx,
        rowGroups: groupsOf(rows), colGroups: groupsOf(cols),
        rowLevel: (i) => ctx.levelOf(rowIssues[i]),
        colLevel: (i) => ctx.levelOf(ctx.issuesFor({ kind: 'slice', id: cols[i].id })),
        cellText: (cell) => cell.v,
        cellClass: (cell) => 'v-' + cell.v.replace('R·via', 'Rvia').replace('R!', 'Rbang') + (cell.cross ? ' cross' : '')
      };
    })();
    const ctxModel = (() => {
      const B = M.bcMatrix || {};
      const bcs = arr(B.bcs).map(String);
      const rows = bcs.map(bc => ({ id: bc, kind: 'context', bc, group: bc }));
      const cols = bcs.map(bc => ({ id: bc, kind: 'context', bc, name: bc, group: bc }));
      const byRow = rows.map(() => []), byCol = cols.map(() => []);
      const counts = arr(B.counts), events = arr(B.events);
      for (let r = 0; r < bcs.length; r++) {
        for (let c = 0; c < bcs.length; c++) {
          const n = (arr(counts[r])[c]) | 0;
          if (!n) continue;
          const cell = { r, c, n, events: arr(arr(events[r])[c]).map(String), cross: r !== c };
          byRow[r].push(cell); byCol[c].push(cell);
        }
      }
      const idx = new Map();
      bcs.forEach((b, i) => idx.set(b, i));
      const lvl = (i) => ctx.levelOf(ctx.issuesFor({ kind: 'context', id: bcs[i] }));
      return {
        id: 'ctx', cw: CTX_CW, colHeadH: CTX_COL_HEAD_H, rows, cols, byRow, byCol, rowIdx: idx, colIdx: idx,
        rowGroups: [], colGroups: [], rowLevel: lvl, colLevel: lvl, diagCells: true,
        cellText: (cell) => String(cell.n),
        cellClass: (cell) => 'n' + (cell.cross ? ' cross' : '')
      };
    })();

    /* ---------------- state ---------------- */
    const S = {
      model: ctx.getHash()['matrix.tab'] === 'ctx' ? ctxModel : sliceModel,
      sel: null,       // { model, r?, c? } — the selected row, column or cell
      cur: null,       // { r, c } — keyboard cursor in the current model
      mark: null,      // { model, r?, c?, group?: {axis, id}, cells?: Set<'r,c'> } — an issue marker
      match: null,     // { slices: {rows, cols}, ctx: {rows, cols} } of index Sets, or null
      raf: 0
    };

    /* ---------------- DOM ---------------- */
    const tabs = ctx.html('div', 'mx-tabs');
    tabs.setAttribute('role', 'tablist');
    tabs.setAttribute('aria-label', 'Matrix');
    const tabBtn = (model, text) => {
      const b = ctx.html('button', 'btn', text);
      b.type = 'button';
      b.setAttribute('role', 'tab');
      b.addEventListener('click', () => switchTab(model, true));
      tabs.append(b);
      return b;
    };
    const tabSlices = tabBtn(sliceModel, 'Slices × targets');
    const tabCtx = tabBtn(ctxModel, 'Context × context events');
    const note = ctx.html('span', 'note');
    tabs.append(note);
    const grid = ctx.html('div', 'mx-grid');
    const corner = ctx.html('div', 'mx-corner');
    const colHead = ctx.html('div', 'mx-colhead'), colIn = ctx.html('div', 'mx-in');
    const rowHead = ctx.html('div', 'mx-rowhead'), rowIn = ctx.html('div', 'mx-in');
    const body = ctx.html('div', 'mx-body'), sizer = ctx.html('div', 'mx-sizer');
    const status = ctx.html('div', 'mx-status');
    status.setAttribute('aria-live', 'polite');
    body.tabIndex = 0;
    body.setAttribute('role', 'application');
    colHead.append(colIn); rowHead.append(rowIn); body.append(sizer);
    grid.append(body, colHead, rowHead, corner, status);
    root.append(tabs, grid);

    /* A context band whose name does not fit along it turns, so the name is never cut: when any row band is
       too short, every row band lies across, one width, widening into the row header; a column band too
       narrow stands up, deepening the column header. */
    const probe = ctx.html('span', 'mx-probe');
    grid.append(probe);
    const widths = new Map();
    function textW(text) {
      if (!widths.has(text)) {
        probe.textContent = text;
        widths.set(text, probe.offsetWidth || Math.ceil(text.length * 7.5));   // no layout (a test DOM): estimate
      }
      return widths.get(text);
    }
    const bandName = (g, none) => g.id || none;
    const rowBandW = (m) => {               // BAND when every name fits upright; else one width that fits them all
      const need = m.rowGroups.map(g => textW(bandName(g, '(no owner)')) + 12);
      if (m.rowGroups.every((g, i) => need[i] <= (g.end - g.start) * RH)) return BAND;
      return Math.min(Math.max(...need) + 2, rhw - 90);
    };
    const colBandH = (m, g) => {                                         // BAND when the name fits across
      const lv = g.id ? 18 : 0, need = textW(bandName(g, '(no context)')) + 12;
      return need + lv <= (g.end - g.start) * m.cw ? BAND : Math.min(need, MAX_UPRIGHT) + lv + 2;
    };
    const headH = (m) => m.colHeadH + Math.max(0, ...m.colGroups.map(g => colBandH(m, g) - BAND));

    const rhw = narrow ? ROW_HEAD_W_NARROW : ROW_HEAD_W;
    function place() {
      const m = S.model, chh = headH(m);
      corner.style.width = rhw + 'px'; corner.style.height = chh + 'px';
      colHead.style.left = rhw + 'px'; colHead.style.height = chh + 'px';
      rowHead.style.top = chh + 'px'; rowHead.style.width = rhw + 'px';
      body.style.left = rhw + 'px'; body.style.top = chh + 'px';
      sizer.style.width = (m.cols.length * m.cw) + 'px';
      sizer.style.height = (m.rows.length * RH) + 'px';
      colIn.style.width = (m.cols.length * m.cw + 40) + 'px'; colIn.style.height = chh + 'px';
      rowIn.style.height = (m.rows.length * RH + 40) + 'px'; rowIn.style.width = rhw + 'px';
      corner.textContent = '';
      if (m === sliceModel) {
        corner.append(ctx.html('b', null, m.rows.length + ' targets × ' + m.cols.length + ' slices'),
          document.createTextNode('Rows: write targets and read models, by owning context. Columns: slices, by context, then kind.'));
        note.textContent = 'W writes · O owns · R reads · R·via through a reader · R! cross-context without one. Tinted: crosses a context boundary.';
      } else {
        corner.append(ctx.html('b', null, m.rows.length + ' contexts'),
          document.createTextNode('Row publishes, column consumes: the number of events.'));
        note.textContent = 'Cell = events the row context publishes that the column context consumes. Off the diagonal: across a boundary.';
      }
      tabSlices.setAttribute('aria-selected', String(m === sliceModel));
      tabCtx.setAttribute('aria-selected', String(m === ctxModel));
      body.setAttribute('aria-label', (m === sliceModel ? 'Slices by targets' : 'Contexts by contexts') +
        ' matrix. Arrow keys move, Enter opens the cell.');
    }

    function switchTab(model, write) {
      if (S.model === model) return;
      S.model = model;
      S.cur = null;
      body.scrollLeft = 0; body.scrollTop = 0;
      if (write) ctx.setHash({ 'matrix.tab': model === ctxModel ? 'ctx' : null });
      place();
      schedule();
    }

    /* ---------------- rendering (virtualised) ---------------- */
    let mounted = false;
    function schedule() {
      if (S.raf || !mounted) return;
      S.raf = requestAnimationFrame(() => { S.raf = 0; if (mounted) render(); });
    }
    function box(cls, x, y, w, h, text) {
      const d = ctx.html('div', cls, text == null ? null : text);
      d.style.left = x + 'px'; d.style.top = y + 'px'; d.style.width = w + 'px'; d.style.height = h + 'px';
      return d;
    }
    const lvlDot = (lvl) => lvl ? ctx.html('span', 'mx-dot ' + LVL_CLASS[lvl]) : null;
    const isSelRow = (r) => !!S.sel && S.sel.model === S.model && S.sel.r === r;
    const isSelCol = (c) => !!S.sel && S.sel.model === S.model && S.sel.c === c;

    function render() {
      const m = S.model, cw = m.cw;
      const sl = body.scrollLeft || 0, st = body.scrollTop || 0;
      const vw = body.clientWidth || 800, vh = body.clientHeight || 600;
      const c0 = clamp(Math.floor(sl / cw) - OVERSCAN, 0, m.cols.length), c1 = clamp(Math.ceil((sl + vw) / cw) + OVERSCAN, 0, m.cols.length);
      const r0 = clamp(Math.floor(st / RH) - OVERSCAN, 0, m.rows.length), r1 = clamp(Math.ceil((st + vh) / RH) + OVERSCAN, 0, m.rows.length);
      colIn.style.transform = 'translateX(' + (-sl) + 'px)';
      rowIn.style.transform = 'translateY(' + (-st) + 'px)';
      const mk = S.mark && S.mark.model === m ? S.mark : null;
      const mt = S.match ? S.match[m.id] : null;
      const W = m.cols.length * cw, H = m.rows.length * RH;

      /* column header: context bands (label held at the left edge while the band is in view), names */
      colIn.textContent = '';
      const chh = headH(m), bandH = m.colGroups.length ? BAND : 0;
      const colBand = new Array(m.cols.length).fill(bandH);              // per column: its band's depth
      m.colGroups.forEach(g => {
        const bh = colBandH(m, g);
        for (let c = g.start; c < g.end; c++) colBand[c] = bh;
        if (g.end <= c0 || g.start >= c1) return;
        const x = g.start * cw, w = (g.end - g.start) * cw, upright = bh > BAND;
        const b = box('mx-band' + (upright ? ' upright' : ''), x, 0, w, bh - 1);
        if (mk && mk.group && mk.group.id === g.id) b.classList.add('marked');
        const lab = ctx.html('span', null, g.id || '(no context)');
        const lv = g.id ? ctx.levelOf(ctx.issuesFor({ kind: 'context', id: g.id })) : null;
        if (upright) {
          lab.style.left = Math.max(0, (w - 22) / 2) + 'px';
          lab.style.top = (lv ? 18 : 0) + 'px';
          lab.style.maxHeight = MAX_UPRIGHT + 'px';                       // past that, an ellipsis; the title has it whole
        } else {
          lab.style.left = clamp(sl - x, 0, Math.max(0, w - 80)) + 'px';   // held at the left edge while in view
        }
        b.append(lab);
        b.title = 'context ' + (g.id || '(none)');
        if (g.id) b.addEventListener('click', () => openContext(g.id));
        if (lv) {
          const d = lvlDot(lv); d.style.position = 'absolute'; d.style.top = '6px';
          if (upright) d.style.left = Math.max(0, (w - 8) / 2) + 'px'; else d.style.right = '6px';
          b.append(d);
        }
        colIn.append(b);
      });
      for (let c = c0; c < c1; c++) {
        const col = m.cols[c];
        const el = box('mx-cl', c * cw, colBand[c], cw, chh - colBand[c]);
        if (m === sliceModel) el.append(ctx.html('span', 'mx-sw ' + (KIND_CLASS[col.kind] || '')));
        el.append(ctx.html('span', 't', m === sliceModel ? col.name : col.id));
        const d = lvlDot(m.colLevel(c));
        if (d) el.append(d);
        el.title = m === sliceModel ? col.id + ' · ' + (col.kind || '?') + ' · ' + (col.bc || '?') : 'context ' + col.id + ' (consumes)';
        if (isSelCol(c)) el.classList.add('hl');
        if (mk && mk.c === c) el.classList.add('marked');
        if (mt && !mt.cols.has(c)) el.classList.add('dim');
        el.addEventListener('click', () => onCol(c));
        colIn.append(el);
      }

      /* row header */
      rowIn.textContent = '';
      const bw = m.rowGroups.length ? rowBandW(m) : 0;
      m.rowGroups.forEach(g => {
        if (g.end <= r0 || g.start >= r1) return;
        const y = g.start * RH, h = (g.end - g.start) * RH, across = bw > BAND;
        const b = box('mx-band' + (across ? ' across' : ''), 0, y, bw - 1, h);
        if (mk && mk.group && mk.group.id === g.id) b.classList.add('marked');
        const lab = ctx.html('span', null, g.id || '(no owner)');
        if (across) { lab.style.top = Math.max(0, (h - 20) / 2) + 'px'; lab.style.maxWidth = bw + 'px'; }
        else lab.style.top = clamp(st - y, 0, Math.max(0, h - 80)) + 'px';   // held at the top edge while in view
        b.append(lab);
        b.title = g.id ? 'context ' + g.id : 'targets no slice writes or owns';
        if (g.id) b.addEventListener('click', () => openContext(g.id));
        rowIn.append(b);
      });
      for (let r = r0; r < r1; r++) {
        const row = m.rows[r];
        const el = box('mx-rl', bw, r * RH, rhw - bw, RH);
        el.append(ctx.html('span', 'mx-sw k-' + (row.kind || 'context')), ctx.html('span', 't', row.id));
        const d = lvlDot(m.rowLevel(r));
        if (d) el.append(d);
        el.title = m === sliceModel ? row.id + ' · ' + row.kind + ' · ' + (row.bc || 'no writer or owner') : 'context ' + row.id + ' (publishes)';
        if (isSelRow(r)) el.classList.add('hl');
        if (mk && mk.r === r) el.classList.add('marked');
        if (mt && !mt.rows.has(r)) el.classList.add('dim');
        el.addEventListener('click', () => onRow(r));
        rowIn.append(el);
      }

      /* body: diagonal blocks, grid lines, selection band, cells, marks, cursor */
      sizer.textContent = '';
      if (m.diagCells) {
        for (let i = Math.max(r0, c0); i < Math.min(r1, c1); i++) sizer.append(box('mx-diag', i * cw, i * RH, cw, RH));
      } else {
        const cg = new Map();
        m.colGroups.forEach(g => cg.set(g.id, g));
        m.rowGroups.forEach(rg => {
          const g = cg.get(rg.id);
          if (!g || rg.id === '') return;
          if (rg.end <= r0 || rg.start >= r1 || g.end <= c0 || g.start >= c1) return;
          const b = box('mx-diag', g.start * cw, rg.start * RH, (g.end - g.start) * cw, (rg.end - rg.start) * RH);
          if (mk && mk.group && mk.group.id === rg.id) b.classList.add('marked');
          sizer.append(b);
        });
      }
      const rowEdges = new Set(m.rowGroups.map(g => g.end - 1)), colEdges = new Set(m.colGroups.map(g => g.end - 1));
      for (let r = r0; r < r1; r++) sizer.append(box('mx-gr' + (rowEdges.has(r) ? ' edge' : ''), 0, r * RH, W, RH));
      for (let c = c0; c < c1; c++) sizer.append(box('mx-gc' + (colEdges.has(c) ? ' edge' : ''), c * cw, 0, cw, H));
      if (S.sel && S.sel.model === m) {
        if (S.sel.r != null) sizer.append(box('mx-hl', 0, S.sel.r * RH, W, RH));
        if (S.sel.c != null) sizer.append(box('mx-hl', S.sel.c * cw, 0, cw, H));
      }
      for (let r = r0; r < r1; r++) {
        m.byRow[r].forEach(cell => {
          if (cell.c < c0 || cell.c >= c1) return;
          const el = box('mx-c ' + m.cellClass(cell), cell.c * cw + 2, r * RH + 2, cw - 4, RH - 4, m.cellText(cell));
          if (S.sel && S.sel.model === m && S.sel.r === r && S.sel.c === cell.c) el.classList.add('sel');
          if (mk && mk.cells && mk.cells.has(r + ',' + cell.c)) el.classList.add('marked');
          if (mt && !mt.rows.has(r) && !mt.cols.has(cell.c)) el.classList.add('dim');
          el.addEventListener('click', () => onCell(r, cell.c));
          el.addEventListener('mousemove', (ev) => ctx.tip(ev, cellTip(m, cell)));
          el.addEventListener('mouseleave', () => ctx.hideTip());
          sizer.append(el);
        });
      }
      if (mk && mk.c != null) sizer.append(box('mx-mark', mk.c * cw, 0, cw, H));
      if (mk && mk.r != null) sizer.append(box('mx-mark', 0, mk.r * RH, W, RH));
      if (S.cur && document.activeElement === body) sizer.append(box('mx-cur', S.cur.c * cw, S.cur.r * RH, cw, RH));
    }

    function cellAt(m, r, c) { return m.byRow[r] ? m.byRow[r].find(x => x.c === c) || null : null; }
    function cellLine(m, cell) {
      if (!cell) return '';
      if (m === sliceModel) return m.cols[cell.c].id + ' ' + VERB[cell.v] + ' ' + m.rows[cell.r].id;
      return m.rows[cell.r].id + ' → ' + m.cols[cell.c].id + ': ' + cell.n + ' event' + (cell.n === 1 ? '' : 's');
    }
    function cellTip(m, cell) {
      const f = document.createDocumentFragment();
      f.append(ctx.html('b', null, cellLine(m, cell)));
      if (m === sliceModel) {
        const row = m.rows[cell.r];
        f.append(document.createTextNode('\n' + row.kind + ' of ' + (row.bc || 'no context') + (cell.cross ? ' — crosses a context boundary' : '') + '\nclick to open both ends'));
      } else {
        f.append(document.createTextNode('\n' + cell.events.slice(0, 12).join('\n') + (cell.events.length > 12 ? '\n…' : '') + '\nclick to open both ends'));
      }
      return f;
    }

    /* ---------------- actions ---------------- */
    function scrollTo(r, c) {
      const m = S.model, vw = body.clientWidth || 800, vh = body.clientHeight || 600;
      if (c != null) {
        const x = c * m.cw, sl = body.scrollLeft || 0;
        if (x < sl || x + m.cw > sl + vw) body.scrollLeft = Math.max(0, x - (vw - m.cw) / 2);
      }
      if (r != null) {
        const y = r * RH, st = body.scrollTop || 0;
        if (y < st || y + RH > st + vh) body.scrollTop = Math.max(0, y - (vh - RH) / 2);
      }
    }
    function openSlice(id) {
      ctx.setHash({ focus: id, issue: null });
      ctx.openCard({ kind: 'slice', id }, { anchor: 'matrix:cell' });
    }
    function openContext(bc) {
      ctx.setHash({ focus: 'ctx:' + bc, issue: null });
      ctx.openCard({ kind: 'context', id: bc }, { anchor: 'matrix:cell' });
    }
    function onCell(r, c) {
      const m = S.model;
      S.sel = { model: m, r, c };
      S.cur = { r, c };
      schedule();
      status.textContent = cellLine(m, cellAt(m, r, c));
      if (m === sliceModel) openSlice(m.cols[c].id);
      else openContext(m.rows[r].id);
    }
    function onCol(c) {
      const m = S.model;
      S.sel = { model: m, c };
      schedule();
      if (m === sliceModel) openSlice(m.cols[c].id);
      else openContext(m.cols[c].id);
    }
    function onRow(r) {
      const m = S.model;
      S.sel = { model: m, r };
      schedule();
      if (m === ctxModel) { openContext(m.rows[r].id); return; }
      // A target has no card of its own: open its first writer or owner (else its first reader);
      // the card then lists every slice on the row.
      const cells = m.byRow[r];
      const pick = cells.find(x => x.v === 'W' || x.v === 'O') || cells[0];
      if (pick) { S.sel.c = pick.c; openSlice(m.cols[pick.c].id); }
    }

    /* ---------------- keyboard ---------------- */
    function firstCell(m) {
      const f = m.byRow.findIndex(l => l.length);
      return f < 0 ? { r: 0, c: 0 } : { r: f, c: m.byRow[f][0].c };
    }
    function onKey(e) {
      const m = S.model;
      if (!m.rows.length || !m.cols.length || e.metaKey || e.ctrlKey || e.altKey) return;
      const page = Math.max(1, Math.floor((body.clientHeight || 600) / RH) - 1);
      const cur = S.cur || firstCell(m);
      const k = e.key;
      let r = cur.r, c = cur.c;
      if (k === 'ArrowDown') r++; else if (k === 'ArrowUp') r--;
      else if (k === 'ArrowRight') c++; else if (k === 'ArrowLeft') c--;
      else if (k === 'PageDown') r += page; else if (k === 'PageUp') r -= page;
      else if (k === 'Home') { c = 0; if (e.shiftKey) r = 0; } else if (k === 'End') { c = m.cols.length - 1; if (e.shiftKey) r = m.rows.length - 1; }
      else if (k === 'Enter' || k === ' ') {
        e.preventDefault();
        if (cellAt(m, cur.r, cur.c)) onCell(cur.r, cur.c); else onCol(cur.c);
        return;
      } else return;
      e.preventDefault();
      S.cur = { r: clamp(r, 0, m.rows.length - 1), c: clamp(c, 0, m.cols.length - 1) };
      scrollTo(S.cur.r, S.cur.c);
      const cell = cellAt(m, S.cur.r, S.cur.c);
      status.textContent = cell ? cellLine(m, cell) : m.rows[S.cur.r].id + ' × ' + m.cols[S.cur.c].id + ': empty';
      schedule();
    }
    body.addEventListener('keydown', onKey);
    body.addEventListener('focus', () => { if (!S.cur) S.cur = firstCell(S.model); schedule(); });
    body.addEventListener('blur', schedule);
    body.addEventListener('scroll', () => { ctx.hideTip(); schedule(); });
    const onResize = () => schedule();
    window.addEventListener('resize', onResize);

    /* ---------------- lens surface ---------------- */
    function partRow(part) {
      const mm = part ? ROW_PART.exec(String(part)) : null;
      return mm && sliceModel.rowIdx.has(mm[1]) ? sliceModel.rowIdx.get(mm[1]) : null;
    }
    const eventName = (t) => String(t.id).startsWith('event:') ? String(t.id).slice(6) : null;
    function canFocus(t) {
      if (!t) return true;
      if (t.kind === 'slice') return sliceModel.colIdx.has(t.id);
      if (t.kind === 'context') return sliceModel.colGroups.some(g => g.id === t.id) || sliceModel.rowGroups.some(g => g.id === t.id) || ctxModel.rowIdx.has(t.id);
      if (t.kind === 'message') {
        const name = eventName(t);
        return !!name && ctxModel.byRow.some(l => l.some(x => x.events.includes(name)));
      }
      return false;
    }
    function focus(t, o) {
      o = o || {};
      if (!t) { S.sel = null; S.mark = null; schedule(); return true; }
      if (!canFocus(t)) return false;
      if (t.kind === 'slice') {
        switchTab(sliceModel, true);
        const c = sliceModel.colIdx.get(t.id);
        const part = t.part || (o.issue && o.issue.target && o.issue.target.part) || '';
        const r = partRow(part);
        S.sel = r == null ? { model: sliceModel, c } : { model: sliceModel, r, c };
        S.mark = !o.issue ? null : r == null ? { model: sliceModel, c } : { model: sliceModel, c, r, cells: new Set([r + ',' + c]) };
        scrollTo(r, c);
      } else if (t.kind === 'context') {
        const g = sliceModel.colGroups.find(x => x.id === t.id), rg = sliceModel.rowGroups.find(x => x.id === t.id);
        if (g || rg) {
          switchTab(sliceModel, true);
          S.sel = null;
          S.mark = o.issue ? { model: sliceModel, group: { id: t.id } } : null;
          scrollTo(rg ? rg.start : null, g ? g.start : null);
        } else {
          switchTab(ctxModel, true);
          const i = ctxModel.rowIdx.get(t.id);
          S.sel = { model: ctxModel, r: i, c: i };
          S.mark = o.issue ? { model: ctxModel, r: i, c: i } : null;
          scrollTo(i, i);
        }
      } else {
        switchTab(ctxModel, true);
        const name = eventName(t), cells = new Set();
        let first = null;
        ctxModel.byRow.forEach(l => l.forEach(x => { if (x.events.includes(name)) { cells.add(x.r + ',' + x.c); if (!first) first = x; } }));
        S.sel = { model: ctxModel, r: first.r, c: first.c };
        S.mark = { model: ctxModel, cells };
        scrollTo(first.r, first.c);
      }
      schedule();
      return true;
    }
    function search(q) {
      if (!q) { S.match = null; schedule(); return null; }
      const keys = new Set();
      const sm = { rows: new Set(), cols: new Set() }, cm = { rows: new Set(), cols: new Set() };
      sliceModel.cols.forEach((col, i) => {
        const s = ctx.index.slice(col.id);
        if (ctx.match(q, s ? ctx.sliceHay(s) : col.id)) { sm.cols.add(i); keys.add(col.id); }
      });
      sliceModel.rows.forEach((row, i) => { if (ctx.match(q, row.id, row.kind, row.bc || '')) sm.rows.add(i); });
      ctxModel.rows.forEach((row, i) => { if (ctx.match(q, row.id)) { cm.rows.add(i); cm.cols.add(i); keys.add('ctx:' + row.id); } });
      S.match = { slices: sm, ctx: cm };
      schedule();
      return keys;
    }

    /* ---------------- detail card: both ends of the selected cell ---------------- */
    function cardExtra(t) {
      if (!t) return null;
      const sec = document.createElement('section');
      sec.className = 'mx-extra';
      sec.dataset.anchor = 'matrix:cell';
      const lnk = (text, fn) => { const b = ctx.html('button', 'lnk', text); b.type = 'button'; b.addEventListener('click', () => { if (mounted) fn(); }); return b; };
      const li = (v, node, me) => { const l = ctx.html('li', me ? 'me' : null); l.append(ctx.html('span', 'v', v), node); return l; };
      if (t.kind === 'slice' && sliceModel.colIdx.has(t.id)) {
        const m = sliceModel, c = m.colIdx.get(t.id);
        sec.append(ctx.html('h3', null, 'Coupling matrix'));
        const r = S.sel && S.sel.model === m && S.sel.c === c && S.sel.r != null ? S.sel.r : null;
        if (r != null) {
          const row = m.rows[r], cell = cellAt(m, r, c);
          if (cell) sec.append(ctx.html('p', null, cellLine(m, cell) + (cell.cross ? ' — across a context boundary.' : '.')));
          sec.append(ctx.html('p', null, 'Every slice touching ' + row.id + ' (' + row.kind + ', ' + (row.bc || 'no owning context') + '):'));
          const ul = ctx.html('ul');
          m.byRow[r].forEach(x => ul.append(li(x.v, lnk(m.cols[x.c].id, () => {
            S.sel = { model: m, r, c: x.c }; scrollTo(r, x.c); schedule(); openSlice(m.cols[x.c].id);
          }), x.c === c)));
          sec.append(ul);
        }
        const mine = m.byCol[c];
        sec.append(ctx.html('p', null, mine.length ? t.id + ' touches:' : t.id + ' writes, owns and reads nothing.'));
        if (mine.length) {
          const ul = ctx.html('ul');
          mine.forEach(x => ul.append(li(x.v, lnk(m.rows[x.r].id + (x.cross && m.rows[x.r].bc ? '  (' + m.rows[x.r].bc + ')' : ''), () => {
            S.sel = { model: m, r: x.r, c }; scrollTo(x.r, c); schedule(); openSlice(t.id);
          }), x.r === r)));
          sec.append(ul);
        }
        return sec;
      }
      if (t.kind === 'context' && S.sel && S.sel.model === ctxModel && S.sel.r != null && S.sel.c != null) {
        const m = ctxModel, cell = cellAt(m, S.sel.r, S.sel.c);
        if (!cell) return null;
        const from = m.rows[cell.r].id, to = m.cols[cell.c].id;
        if (t.id !== from && t.id !== to) return null;
        sec.append(ctx.html('h3', null, 'Events ' + from + ' → ' + to));
        if (from !== to) {
          const other = t.id === from ? to : from;
          const p = ctx.html('p', null, (t.id === from ? 'Consumed by context ' : 'Published by context '));
          p.append(lnk(other, () => openContext(other)));
          sec.append(p);
        }
        const ul = ctx.html('ul');
        cell.events.forEach(n => ul.append(li('event', lnk(n, () => ctx.focusTarget({ kind: 'message', id: 'event:' + n })), false)));
        sec.append(ul);
        return sec;
      }
      return null;
    }

    ctx.onHash((h) => { switchTab(h['matrix.tab'] === 'ctx' ? ctxModel : sliceModel, false); });

    live = {
      focus, canFocus, search, cardExtra,
      destroy() {
        mounted = false;
        if (S.raf) cancelAnimationFrame(S.raf);
        S.raf = 0;
        window.removeEventListener('resize', onResize);
      }
    };
    mounted = true;
    place();
    render();
  }

  /* ---------------- legend ---------------- */
  function legend() {
    const f = document.createDocumentFragment();
    const h = (t) => { const e = document.createElement('h3'); e.textContent = t; f.append(e); };
    const row = (style, text, glyph) => {
      const r = document.createElement('div'); r.className = 'lg';
      const sw = document.createElement('span'); sw.className = 'sw';
      Object.assign(sw.style, style, { fontFamily: 'var(--mono)', fontSize: '11px', fontWeight: '700', textAlign: 'center', lineHeight: '11px' });
      if (glyph) sw.textContent = glyph;
      r.append(sw, document.createTextNode(text));
      f.append(r);
    };
    const dot = (token, text) => {
      const r = document.createElement('div'); r.className = 'lg';
      const d = document.createElement('span'); d.className = 'dt'; d.style.background = 'var(--' + token + ')';
      r.append(d, document.createTextNode(text));
      f.append(r);
    };
    h('Cells');
    row({ background: 'var(--cmd)', borderColor: 'var(--cmd-rule)', color: 'var(--on-sticky)' }, 'writes the aggregate', 'W');
    row({ background: 'var(--view)', borderColor: 'var(--view-rule)', color: 'var(--on-sticky)' }, 'owns the read model', 'O');
    row({ background: 'var(--panel-2)', borderColor: 'var(--rule-strong)' }, 'reads (R·via: through a reader interface)', 'R');
    row({ background: 'var(--err-bg)', borderColor: 'var(--err)', color: 'var(--err)' }, 'cross-context read without a reader interface', 'R!');
    row({ background: 'var(--warn-bg)', borderColor: 'var(--warn)', color: 'var(--warn)' }, 'tinted: the cell crosses a context boundary');
    row({ background: 'var(--ctx-fill)', borderColor: 'var(--rule-strong)' }, 'diagonal block: a context and its own targets');
    h('Rows and columns');
    row({ background: 'var(--evt)', borderColor: 'var(--evt-rule)' }, 'aggregate (a write target)');
    row({ background: 'var(--view)', borderColor: 'var(--view-rule)' }, 'read model; as a column, a view slice');
    row({ background: 'var(--cmd)', borderColor: 'var(--cmd-rule)' }, 'command slice');
    row({ background: 'var(--trg)', borderColor: 'var(--trg-rule)' }, 'automation slice');
    row({ background: 'var(--ext)', borderColor: 'var(--trg-rule)', borderStyle: 'dashed' }, 'translation slice');
    dot('err', 'error on the slice, target or context');
    dot('warn', 'warning');
    dot('info', 'info');
    const p = document.createElement('div');
    p.style.color = 'var(--text-muted)';
    p.style.marginTop = '6px';
    p.textContent = 'Second tab: events the row context publishes that the column context consumes. Click a cell to open both ends; ' +
      'in the grid, arrow keys move and Enter opens.';
    f.append(p);
    return f;
  }

  Atlas.registerLens({
    id: 'matrix', label: 'Matrix', key: '4', css: CSS,
    mount(ctx) { live = null; mount(ctx); },
    unmount() { if (live) live.destroy(); live = null; },
    focus(t, o) { return live ? live.focus(t, o) : false; },
    canFocus(t) { return live ? live.canFocus(t) : true; },
    search(q) { return live ? live.search(q) : null; },
    cardExtra(t) { return live ? live.cardExtra(t) : null; },
    legend
  });
})();
