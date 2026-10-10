/* lens-2-diff.js — the Diff lens (design §10.2): "what did this change do to the shape?"

   The before and after maps arrive matched in ATLAS.diff (C2): slices by id, edges by
   (from, to, kind) on the C2 node keys, issues by id. The layout already places removed
   slices as ghosts (C3), so before and after share one geometry, and this lens draws it with
   the Atlas lens's own board — Atlas.getLens('atlas').api.createBoard(ctx, {filter}) — which
   already paints the added / changed / removed states. On top of it this lens adds:

     - a summary bar: +slices −slices · changed · ±events ±commands · ±edges · issues new / fixed.
       Every count is a button that walks its items (the camera flies, the card opens).
     - the "changes + 1 hop" filter (key c, hash diff.only=1): only changed slices and the slices
       one message away from them — a consumer of an event a changed slice publishes, a handler
       of a command it dispatches, and the reverse.
     - a change list (key v, hash diff.list=1): every added, removed and changed slice with its
       field changes, every edge added or removed, every new issue and every fixed one. Fixed
       issues exist only in the baseline, so this list is the one place the page shows them.
     - a card section on a slice or message: its added and removed edges, and the rename hint of
       design §10.2 (no rename detection; an added and a removed slice that share a directory
       name are pointed out).

   Without ATLAS.diff the lens shows a "no baseline" state saying how to get one, and declines
   every focus so the shell keeps targets on the Atlas lens. Event and command counts are
   ATLAS.diff.messages, as slice_atlas.diff() computes them; data without it (a hand-written
   sample) gets no event or command count rather than one derived here. */
(function () {
  'use strict';

  const FOCUS_PAD = 40;
  const CHANGES_MAX_K = 1.0;      // the initial camera on the changes never zooms in past this

  const CSS = [
    '.lens-root[data-lens="diff"] { display: flex; flex-direction: column; }',
    '.diff-bar { flex: 0 0 auto; display: flex; flex-wrap: wrap; align-items: center; gap: 6px 8px; padding: 8px 64px 8px 12px;',
    '  background: var(--panel); border-bottom: 1px solid var(--rule); font-size: 13px; }',
    '.diff-bar .vs { color: var(--text-muted); font-size: 12px; margin-right: 4px; white-space: nowrap; }',
    '.diff-bar .vs b { color: var(--text); font-family: var(--mono); font-weight: 600; }',
    '.diff-bar .sep { color: var(--rule-strong); }',
    '.diff-n { border: 1px solid var(--rule-strong); background: var(--panel-2); border-radius: 999px; padding: 2px 10px;',
    '  font: inherit; font-size: 12.5px; cursor: pointer; white-space: nowrap; color: var(--text); }',
    '.diff-n:hover { border-color: var(--accent); }',
    '.diff-n:focus-visible, .diff-list button:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }',
    '.diff-n[disabled] { opacity: .45; cursor: default; border-style: dashed; }',
    '.diff-n.add { border-color: var(--ok); } .diff-n.add b { color: var(--ok); }',
    '.diff-n.rem { border-color: var(--err); } .diff-n.rem b { color: var(--err); }',
    '.diff-n.chg { border-color: var(--warn); } .diff-n.chg b { color: var(--warn); }',
    '.diff-n.new { border-color: var(--warn); background: var(--warn-bg); } .diff-n.new b { color: var(--warn); }',
    '.diff-tg { margin-left: auto; display: flex; gap: 6px; flex-wrap: wrap; }',
    '.diff-board { position: relative; flex: 1 1 auto; min-height: 0; }',
    '.diff-list { position: absolute; top: 0; left: 0; z-index: 6; width: min(420px, 100%); max-height: 100%; overflow-y: auto;',
    '  background: var(--panel-2); border-right: 1px solid var(--rule); border-bottom: 1px solid var(--rule); box-shadow: var(--shadow);',
    '  padding: 4px 14px 16px; font-size: 13px; overscroll-behavior: contain; }',
    '.diff-list[hidden] { display: none; }',
    '.diff-list h3 { font-size: 11.5px; text-transform: uppercase; letter-spacing: .05em; color: var(--text-muted); margin: 14px 0 6px; font-weight: 600; }',
    '.diff-list ul { list-style: none; margin: 0; padding: 0; }',
    '.diff-list li { margin: 0 0 6px; padding-left: 8px; border-left: 3px solid var(--rule-strong); }',
    '.diff-list li.add { border-left-color: var(--ok); } .diff-list li.rem { border-left-color: var(--err); }',
    '.diff-list li.chg { border-left-color: var(--warn); }',
    '.diff-list li.lvl-error { border-left-color: var(--err); } .diff-list li.lvl-warn { border-left-color: var(--warn); }',
    '.diff-list li.lvl-info { border-left-color: var(--info); }',
    '.diff-list .sub { display: block; font-family: var(--mono); font-size: 11.5px; color: var(--text-muted); overflow-wrap: anywhere; }',
    '.diff-list .none { color: var(--text-muted); font-size: 12px; margin: 0; }',
    '.diff-empty { padding: 28px; max-width: 64ch; }',
    '.diff-empty h2 { font-size: 16px; margin: 0 0 8px; }',
    '.diff-empty p { color: var(--text-muted); margin: 0 0 10px; }',
    '.diff-empty pre { background: var(--panel); border: 1px solid var(--rule); border-radius: 6px; padding: 8px 10px; font-family: var(--mono);',
    '  font-size: 12px; overflow-x: auto; white-space: pre; }',
    '.diff-hint { padding: 6px 10px; border-radius: 6px; background: var(--warn-bg); color: var(--warn); font-size: 13px; margin-top: 6px; }',
    '@media (max-width: 760px) {',
    '  .diff-bar { padding: 6px 56px 6px 10px; }',
    '  .diff-tg { margin-left: 0; }',
    '  .diff-list { width: 100%; max-height: 70%; border-right: 0; }',
    '}'
  ].join('\n');

  const byStr = (a, b) => a < b ? -1 : a > b ? 1 : 0;
  const plural = (n, one, many) => n + ' ' + (n === 1 ? one : (many || one + 's'));
  const edgeKey = (e) => e.from + '>' + e.to + '>' + e.kind;
  const byEdge = (a, b) => byStr(edgeKey(a), edgeKey(b));
  const nodeName = (key) => String(key).slice(String(key).indexOf(':') + 1);
  const baseDir = (p) => { const parts = String(p || '').split('/').filter(Boolean); return parts.length ? parts[parts.length - 1] : ''; };
  const splitMsg = (id) => { const i = String(id).indexOf(':'); return [String(id).slice(0, i), String(id).slice(i + 1)]; };

  /* ------------------------------------------------------------------ the change model */
  /** Everything the lens shows, derived once per mount from ATLAS.diff and the slices. */
  function changeModel(ctx) {
    const U = ctx.util, arr = U.arr, nameOf = U.nameOf;
    const D = ctx.data.diff;
    const slices = arr(ctx.data.slices);
    const removedRows = arr(D.slices && D.slices.removed).filter(r => r && r.id);
    const added = arr(D.slices && D.slices.added).slice().sort(byStr);
    const removed = removedRows.map(r => r.id).sort(byStr);
    const changed = arr(D.slices && D.slices.changed).slice().sort((a, b) => byStr(a.id, b.id));
    const eAdded = arr(D.edges && D.edges.added).slice().sort(byEdge), eRemoved = arr(D.edges && D.edges.removed).slice().sort(byEdge);

    const inbound = (s) => {
      const out = [];
      arr(s.consumes).concat(arr(s.projections).flatMap(p => arr(p && p.from))).map(nameOf).forEach(n => { if (!out.includes(n)) out.push(n); });
      return out;
    };
    const msgs = D.messages || null;
    const pair = (g) => g ? { added: arr(g.added).slice().sort(byStr), removed: arr(g.removed).slice().sort(byStr) } : null;

    /* One hop: a slice that produces a message a changed slice takes in, or takes in one it produces. */
    const prod = new Map(), cons = new Map();
    const put = (m, k, id) => { if (!m.has(k)) m.set(k, new Set()); m.get(k).add(id); };
    slices.concat(removedRows).forEach(s => {
      if (!s || s.error) return;
      arr(s.publishes).map(nameOf).forEach(e => put(prod, 'evt:' + e, s.id));
      arr(s.dispatches).map(nameOf).forEach(c => put(prod, 'cmd:' + c, s.id));
      inbound(s).forEach(e => put(cons, 'evt:' + e, s.id));
      arr(s.handles).map(nameOf).forEach(c => put(cons, 'cmd:' + c, s.id));
    });
    const core = new Set(added.concat(removed, changed.map(c => c.id)));
    const keep = new Set(core);
    core.forEach(id => {
      const s = ctx.index.slice(id);
      if (!s) return;
      const outs = arr(s.publishes).map(nameOf).map(e => 'evt:' + e).concat(arr(s.dispatches).map(nameOf).map(c => 'cmd:' + c));
      const ins = inbound(s).map(e => 'evt:' + e).concat(arr(s.handles).map(nameOf).map(c => 'cmd:' + c));
      outs.forEach(k => (cons.get(k) || new Set()).forEach(x => keep.add(x)));
      ins.forEach(k => (prod.get(k) || new Set()).forEach(x => keep.add(x)));
    });
    // An added or removed edge keeps its slice and every slice on the other side of its message.
    eAdded.concat(eRemoved).forEach(e => {
      const fromSlice = String(e.from).startsWith('slice:');
      keep.add(nodeName(fromSlice ? e.from : e.to));
      const msg = fromSlice ? e.to : e.from;
      (prod.get(msg) || new Set()).forEach(x => keep.add(x));
      (cons.get(msg) || new Set()).forEach(x => keep.add(x));
    });

    /* Rename hint: an added and a removed slice in directories of the same name. */
    const renames = new Map();
    added.forEach(a => {
      const sa = ctx.index.slice(a), da = baseDir(sa && sa.path);
      if (!da) return;
      removedRows.forEach(r => {
        if (baseDir(r.path) !== da) return;
        if (!renames.has(a)) renames.set(a, []);
        if (!renames.has(r.id)) renames.set(r.id, []);
        renames.get(a).push(r.id); renames.get(r.id).push(a);
      });
    });
    renames.forEach(list => list.sort(byStr));

    return {
      before: D.before || {}, added, removed, changed,
      edges: { added: eAdded, removed: eRemoved },
      events: pair(msgs && msgs.events), commands: pair(msgs && msgs.commands),   // null: not in the data
      issues: { known: !(D.before && D.before.issuesKnown === false),
                new: arr(D.issues && D.issues.new).slice().sort(byStr), fixed: arr(D.issues && D.issues.fixed).slice() },
      core, keep, renames,
      walk: added.concat(removed, changed.map(c => c.id)).sort(byStr)
    };
  }

  const fmtChange = (c) => {
    const parts = [];
    (c.added || []).forEach(a => parts.push('+' + a));
    (c.removed || []).forEach(r => parts.push('−' + r));
    const counts = typeof c.from === 'number' && typeof c.to === 'number';
    if (!parts.length) parts.push(String(c.from) + ' → ' + String(c.to));
    else if (counts && c.from !== c.to) parts.push('(' + c.from + ' → ' + c.to + ')');
    return c.field + ': ' + parts.join(' ');
  };
  const fmtEdge = (e) => String(e.from).startsWith('slice:')
    ? nodeName(e.from) + '  ' + e.kind + '  ' + nodeName(e.to)
    : nodeName(e.to) + '  ' + e.kind + '  ' + nodeName(e.from);
  /** The slice an edge belongs to (every C2 diff edge has exactly one slice end). */
  const edgeSlice = (e) => ({ kind: 'slice', id: nodeName(String(e.from).startsWith('slice:') ? e.from : e.to) });

  /* ------------------------------------------------------------------ the lens */
  let S = null;

  function noBaseline(ctx) {
    const html = ctx.html;
    const box = html('div', 'diff-empty');
    box.append(html('h2', null, 'No baseline'));
    box.append(html('p', null, 'This page was generated without a "before" map, so there is nothing to compare. ' +
      'On a branch other than the default one, /essentials:slice-map-html compares against the merge base by itself; ' +
      'on the default branch, or outside git, name a baseline:'));
    box.append(html('pre', null, '/essentials:slice-map-html --diff <ref>\n\nslice-index.py atlas ROOT --out FILE --before-ref <ref>\nslice-index.py atlas ROOT --out FILE --before-map <earlier map JSON>'));
    box.append(html('p', null, 'The other lenses render as usual.'));
    ctx.el.append(box);
  }

  function mount(ctx) {
    S = { ctx, board: null, model: null, only: false, list: null, pos: {}, keyFn: null, filterBtn: null, listBtn: null };
    if (!ctx.data.diff) { noBaseline(ctx); return; }
    const atlas = Atlas.getLens('atlas');
    if (!atlas || !atlas.api || typeof atlas.api.createBoard !== 'function') {
      ctx.el.append(ctx.html('p', 'lens-msg', 'The Diff lens draws on the Atlas lens\'s board, and the Atlas lens is not in this page.'));
      return;
    }
    const M = S.model = changeModel(ctx);
    const h = ctx.getHash();
    S.only = h['diff.only'] === '1';
    ctx.el.append(buildBar(ctx, M));
    const host = ctx.html('div', 'diff-board');
    ctx.el.append(host);
    S.list = buildList(ctx, M);
    host.append(S.list);
    toggleList(h['diff.list'] === '1', false);
    // The board draws into its own host below the bar; every other ctx member is the shell's.
    const boardCtx = Object.assign({}, ctx, { el: host });
    S.board = atlas.api.createBoard(boardCtx, { filter: S.only ? (id) => M.keep.has(id) : null });
    S.keyFn = (e) => onKey(e);
    document.addEventListener('keydown', S.keyFn);
    ctx.onHash((hh) => {
      const only = hh['diff.only'] === '1';
      if (only !== S.only) setOnly(only, false);
      const showList = hh['diff.list'] === '1';
      if (showList === S.list.hidden) toggleList(showList, false);
    });
    if (!h.focus && !h.issue && !h.at) showChanges(false);
  }

  function unmount() {
    if (S && S.keyFn) document.removeEventListener('keydown', S.keyFn);
    if (S && S.board) S.board.destroy();
    S = null;
  }

  /* ---------- summary bar ---------- */
  function buildBar(ctx, M) {
    const html = ctx.html;
    const bar = html('div', 'diff-bar');
    bar.setAttribute('role', 'toolbar');
    bar.setAttribute('aria-label', 'What this change did');
    const vs = html('span', 'vs');
    const b = M.before;
    vs.append(document.createTextNode('vs '), html('b', null, [b.ref, b.sha].filter(Boolean).join(' ') || 'baseline'),
      document.createTextNode(b.sliceCount != null ? ' · ' + plural(b.sliceCount, 'slice') + ' before' : ''));
    bar.append(vs);
    const count = (cls, n, strong, rest, title, onClick) => {
      const btn = html('button', 'diff-n' + (n ? ' ' + cls : ''));
      btn.type = 'button';
      btn.append(html('b', null, strong), document.createTextNode(rest));
      btn.title = title;
      btn.setAttribute('aria-label', title);
      if (!n) btn.disabled = true; else btn.addEventListener('click', onClick);
      bar.append(btn);
    };
    const sep = () => bar.append(html('span', 'sep', '·'));
    const both = (g) => (g.added.length ? '+' + g.added.join(', +') : 'none added') + '; ' + (g.removed.length ? '−' + g.removed.join(', −') : 'none removed');
    count('add', M.added.length, '+' + M.added.length, M.added.length === 1 ? ' slice' : ' slices',
      plural(M.added.length, 'slice') + ' added — select to walk them', () => walk('added', M.added));
    count('rem', M.removed.length, '−' + M.removed.length, '',
      plural(M.removed.length, 'slice') + ' removed, drawn as ghosts — select to walk them', () => walk('removed', M.removed));
    sep();
    count('chg', M.changed.length, String(M.changed.length), ' changed',
      plural(M.changed.length, 'slice') + ' changed — select to walk them', () => walk('changed', M.changed.map(c => c.id)));
    sep();
    const ev = M.events, cm = M.commands;
    if (ev) count(ev.added.length ? 'add' : 'rem', ev.added.length + ev.removed.length, '+' + ev.added.length + ' −' + ev.removed.length, ' events',
      'events: ' + both(ev), () => walkMessages('event', ev.added.concat(ev.removed)));
    if (cm && cm.added.length + cm.removed.length) {
      count(cm.added.length ? 'add' : 'rem', cm.added.length + cm.removed.length, '+' + cm.added.length + ' −' + cm.removed.length, ' commands',
        'commands: ' + both(cm), () => walkMessages('command', cm.added.concat(cm.removed)));
    }
    count('chg', M.edges.added.length + M.edges.removed.length, '+' + M.edges.added.length + ' −' + M.edges.removed.length, ' edges',
      'edges added and removed — select to list them', () => toggleList(true, true, 'edges'));
    sep();
    if (M.issues.known) {
      count('new', M.issues.new.length, '+' + M.issues.new.length, ' new issues',
        plural(M.issues.new.length, 'new issue') + ' — select to walk them', () => walkIssues());
      count('add', M.issues.fixed.length, '−' + M.issues.fixed.length, ' fixed',
        plural(M.issues.fixed.length, 'issue') + ' fixed — select to list them', () => toggleList(true, true, 'fixed'));
    } else bar.append(html('span', 'vs', 'issues not compared: the baseline predates them'));
    const tg = html('div', 'diff-tg');
    const f = S.filterBtn = html('button', 'btn', 'Changes + 1 hop');
    f.type = 'button';
    f.title = 'Only what changed and the slices one message away (c)';
    f.setAttribute('aria-pressed', String(S.only));
    f.append(html('kbd', null, 'c'));
    f.addEventListener('click', () => setOnly(!S.only, true));
    const l = S.listBtn = html('button', 'btn', 'List');
    l.type = 'button';
    l.title = 'Every change as a list (v)';
    l.setAttribute('aria-controls', 'diff-list');
    l.append(html('kbd', null, 'v'));
    l.addEventListener('click', () => toggleList(S.list.hidden, true));
    tg.append(f, l);
    bar.append(tg);
    return bar;
  }

  /* ---------- change list ---------- */
  function buildList(ctx, M) {
    const html = ctx.html, arr = ctx.util.arr;
    const box = html('div', 'diff-list');
    box.id = 'diff-list';
    box.setAttribute('aria-label', 'Changes');
    const link = (text, t) => {
      const b = html('button', 'lnk mono', text);
      b.type = 'button';
      b.addEventListener('click', () => ctx.focusTarget(t));
      return b;
    };
    const group = (title, anchor, empty) => {
      const h = html('h3', null, title);
      if (anchor) h.dataset.anchor = anchor;
      box.append(h);
      const ul = html('ul');
      box.append(ul);
      if (empty) ul.append(html('p', 'none', 'none'));
      return ul;
    };
    const sliceSub = (id, other) => {
      const s = ctx.index.slice(id);
      const r = M.renames.get(id);
      return html('span', 'sub', [s && s.kind, s && s.bc].filter(Boolean).join(' · ') + (r ? ' · same directory name as ' + other + ' ' + r.join(', ') : ''));
    };
    let ul = group('Slices added · ' + M.added.length, null, !M.added.length);
    M.added.forEach(id => { const li = html('li', 'add'); li.append(link(id, { kind: 'slice', id }), sliceSub(id, 'removed')); ul.append(li); });
    ul = group('Slices removed · ' + M.removed.length, null, !M.removed.length);
    M.removed.forEach(id => { const li = html('li', 'rem'); li.append(link(id, { kind: 'slice', id }), sliceSub(id, 'added')); ul.append(li); });
    ul = group('Slices changed · ' + M.changed.length, null, !M.changed.length);
    M.changed.forEach(c => {
      const li = html('li', 'chg');
      li.append(link(c.id, { kind: 'slice', id: c.id }));
      arr(c.fields).forEach(f => li.append(html('span', 'sub', fmtChange(f))));
      ul.append(li);
    });
    ul = group('Edges · +' + M.edges.added.length + ' −' + M.edges.removed.length, 'edges', !M.edges.added.length && !M.edges.removed.length);
    [['add', '+ ', M.edges.added], ['rem', '− ', M.edges.removed]].forEach(([cls, sign, list]) => list.forEach(e => {
      const li = html('li', cls);
      li.append(document.createTextNode(sign), link(fmtEdge(e), edgeSlice(e)));
      ul.append(li);
    }));
    if (!M.issues.known) {
      box.append(html('h3', null, 'Issues'));
      box.append(html('p', 'none', 'Not compared: the baseline map predates issues.'));
      return box;
    }
    ul = group('New issues · ' + M.issues.new.length, 'new', !M.issues.new.length);
    M.issues.new.forEach(id => {
      const i = ctx.index.issue(id);
      const li = html('li', 'lvl-' + (i ? i.level : 'info'));
      const b = html('button', 'lnk', i ? i.text : id);
      b.type = 'button';
      b.addEventListener('click', () => ctx.markIssue(id));
      li.append(b, html('span', 'sub', id));
      ul.append(li);
    });
    ul = group('Fixed issues · ' + M.issues.fixed.length, 'fixed', !M.issues.fixed.length);
    M.issues.fixed.forEach(i => {
      const li = html('li', 'lvl-' + (i.level || 'info'));
      li.append(html('span', null, i.text || i.id));
      const t = i.target || {};
      const sub = html('span', 'sub', (i.code || '') + ' · ');
      // The fix may have taken the target with it (a removed slice is still drawn, as a ghost).
      const exists = t.kind === 'slice' ? !!ctx.index.slice(t.id) : t.kind === 'process' ? !!ctx.index.process(t.id)
        : t.kind === 'context' ? !!ctx.index.context(t.id) : false;
      if (exists) sub.append(link(Atlas.targetKey(t), { kind: t.kind, id: t.id }));
      else sub.append(document.createTextNode(Atlas.targetKey(t) || '—'));
      if (t.part) sub.append(document.createTextNode(' · ' + t.part));
      li.append(sub);
      ul.append(li);
    });
    return box;
  }
  function toggleList(show, write, anchor) {
    if (!S || !S.list) return;
    S.list.hidden = !show;
    S.listBtn.setAttribute('aria-pressed', String(show));
    if (write) S.ctx.setHash({ 'diff.list': show ? '1' : null });
    if (show && anchor) {
      const h = S.list.querySelector('[data-anchor="' + anchor + '"]');
      if (h && h.scrollIntoView) h.scrollIntoView({ block: 'start' });
    }
  }

  /* ---------- filter, walking, camera ---------- */
  function setOnly(on, write) {
    if (!S || !S.board) return;
    S.only = on;
    S.filterBtn.setAttribute('aria-pressed', String(on));
    S.board.setFilter(on ? (id) => S.model.keep.has(id) : null);
    if (write) S.ctx.setHash({ 'diff.only': on ? '1' : null });
  }
  /** The camera on every changed slice's stickies, never zoomed in past CHANGES_MAX_K. */
  function showChanges(animate) {
    const L = S.ctx.layout && S.ctx.layout.atlas;
    const vp = S.board && S.board.viewport;
    if (!L || !vp) return;
    const rs = S.ctx.util.arr(L.cards).filter(c => !c.portal && S.model.core.has(c.slice));
    if (!rs.length) return;
    const x0 = Math.min.apply(null, rs.map(r => r.x)), y0 = Math.min.apply(null, rs.map(r => r.y));
    const x1 = Math.max.apply(null, rs.map(r => r.x + r.w)), y1 = Math.max.apply(null, rs.map(r => r.y + r.h));
    vp.flyTo({ x: x0, y: y0, w: x1 - x0, h: y1 - y0 }, { pad: FOCUS_PAD, maxScale: CHANGES_MAX_K, animate });
  }
  const step = (name, n, dir) => {
    const cur = S.pos[name] == null ? (dir > 0 ? -1 : 0) : S.pos[name];
    return (S.pos[name] = (cur + dir + n) % n);
  };
  function walk(name, ids) {
    if (!S || !ids.length) return;
    const i = step(name, ids.length, 1);
    S.ctx.focusTarget({ kind: 'slice', id: ids[i] });
    S.ctx.toast(name + ' ' + (i + 1) + ' / ' + ids.length + ': ' + ids[i]);
  }
  function walkMessages(type, names) {
    if (!S || !names.length) return;
    const list = names.slice().sort(byStr);
    const i = step(type, list.length, 1);
    const t = { kind: 'message', id: type + ':' + list[i] };
    if (S.board.canFocus(t)) { S.ctx.focusTarget(t); return; }
    // Gone with the change: nothing draws it any more, so point at the edges that named it.
    toggleList(true, true, 'edges');
    S.ctx.toast(type + ' ' + list[i] + ' is gone from the map — its removed edges are in the list');
  }
  function walkIssues() {
    if (!S) return;
    // Folded per-slice ids resolve to their context issue: walk each issue of the map once.
    const ids = [...new Set(S.model.issues.new.map(id => S.ctx.index.issue(id)).filter(Boolean).map(i => i.id))];
    if (ids.length) S.ctx.markIssue(ids[step('issue', ids.length, 1)]);
  }
  function onKey(e) {
    if (!S || !S.board || e.defaultPrevented || e.metaKey || e.ctrlKey || e.altKey) return;
    const tag = (e.target && e.target.tagName) || '';
    if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return;
    if (e.key === 'c') { e.preventDefault(); setOnly(!S.only, true); }
    else if (e.key === 'v') { e.preventDefault(); toggleList(S.list.hidden, true); }
    else if ((e.key === 'n' || e.key === 'N') && S.model.walk.length) {
      e.preventDefault();
      S.ctx.focusTarget({ kind: 'slice', id: S.model.walk[step('all', S.model.walk.length, e.key === 'n' ? 1 : -1)] });
    }
  }

  /* ---------- the lens surface ---------- */
  function focus(t, o) {
    if (!S || !S.board) return false;
    if (S.board.focus(t, o)) return true;
    // Hidden by the filter: show everything rather than send the reader to another lens.
    if (t && S.only) {
      setOnly(false, true);
      if (S.board.focus(t, o)) { S.ctx.toast('Showing everything: ' + Atlas.targetKey(t) + ' is outside the changes'); return true; }
    }
    return false;
  }
  function canFocus(t) {
    // Unmounted, this lens can show nothing the Atlas lens (always tried first) cannot.
    if (!S || !S.board) return false;
    return S.board.canFocus(t) || !!(t && S.only);
  }
  function cardExtra(t) {
    if (!S || !S.model || !t || (t.kind !== 'slice' && t.kind !== 'message')) return null;
    const ctx = S.ctx, html = ctx.html, M = S.model;
    let key, msgState = null;
    if (t.kind === 'slice') key = 'slice:' + t.id;
    else {
      const [ty, n] = splitMsg(t.id);
      key = (ty === 'command' ? 'cmd:' : 'evt:') + n;
      const g = (ty === 'command' ? M.commands : M.events) || { added: [], removed: [] };
      msgState = g.added.includes(n) ? 'new in this change' : g.removed.includes(n) ? 'gone after this change' : null;
    }
    const mine = (list) => list.filter(e => e.from === key || e.to === key);
    const add = mine(M.edges.added), rem = mine(M.edges.removed);
    const renames = t.kind === 'slice' ? (M.renames.get(t.id) || []) : [];
    if (!add.length && !rem.length && !renames.length && !msgState) return null;
    const sec = html('section');
    sec.append(html('h3', null, 'Diff'));
    if (msgState) sec.append(html('p', 'sum', 'This ' + splitMsg(t.id)[0] + ' is ' + msgState + '.'));
    if (add.length || rem.length) {
      const ul = html('ul', 'changes');
      add.forEach(e => ul.append(html('li', null, '+ ' + fmtEdge(e))));
      rem.forEach(e => ul.append(html('li', null, '− ' + fmtEdge(e))));
      sec.append(ul);
    }
    if (renames.length) {
      const d = html('div', 'diff-hint');
      d.append(document.createTextNode('Shares its directory name with the ' + (ctx.diff.state(t.id) === 'added' ? 'removed' : 'added') + ' slice '));
      renames.forEach((id, i) => {
        if (i) d.append(document.createTextNode(', '));
        const b = html('button', 'lnk mono', id);
        b.type = 'button';
        b.addEventListener('click', () => ctx.focusTarget({ kind: 'slice', id }));
        d.append(b);
      });
      d.append(document.createTextNode(' — perhaps a move. The map does not detect renames: a moved slice is a remove plus an add.'));
      sec.append(d);
    }
    return sec;
  }
  function legend() {
    const f = document.createDocumentFragment();
    const note = (text) => {
      const p = document.createElement('div');
      p.style.color = 'var(--text-muted)';
      p.style.marginTop = '6px';
      p.textContent = text;
      f.append(p);
    };
    if (!S || !S.model) { note('No baseline: this page carries no diff.'); return f; }
    const atlas = Atlas.getLens('atlas');
    if (atlas && atlas.api && atlas.api.legend) f.append(atlas.api.legend(S.ctx));
    const h = document.createElement('h3');
    h.textContent = 'Edges in this change';
    f.append(h);
    const row = (style, text) => {
      const r = document.createElement('div'); r.className = 'lg';
      const sw = document.createElement('span'); sw.className = 'ln';
      Object.assign(sw.style, style);
      r.append(sw, document.createTextNode(text));
      f.append(r);
    };
    row({ borderColor: 'var(--ok)', borderTopWidth: '3px' }, 'edge added');
    row({ borderColor: 'var(--err)', borderTopStyle: 'dashed' }, 'edge removed');
    note('c: changes + 1 hop, a slice one message away from a change · v: the change list · n / N: next / previous changed slice.');
    return f;
  }

  Atlas.registerLens({
    id: 'diff', label: 'Diff', key: '2', css: CSS,
    mount, unmount, focus, canFocus, cardExtra, legend,
    search(q) { return S && S.board ? S.board.search(q) : null; },
    minimap() { return S && S.board ? S.board.minimap() : []; },
    api: { changeModel }
  });
})();
