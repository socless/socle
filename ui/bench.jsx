// Bench — VSCode/Linear-style Chisel design explorer.
// Multi-panel layout: hierarchy (left), schematic (center), inspector (right),
// tabbed bottom drawer (source / waveform / ports). Designed for 1440×900.

const { useState, useEffect, useMemo, useRef, useCallback } = React;
const B_MODULES  = window.DESIGN.MODULES;
const B_BUNDLES  = window.DESIGN.BUNDLES;
const B_TOP_LAYOUT = window.DESIGN.TOP_LAYOUT;
const B_WAVES    = window.DESIGN.WAVES;
const B_WAVE_CYCLES = window.DESIGN.WAVE_CYCLES;
const B_CHAN     = window.DESIGN.CHANNEL_COLOR;
const bBundleFields = window.DESIGN.bundleFields;
const bTotalWidth   = window.DESIGN.totalWidth;

// ─── Tokens ──────────────────────────────────────────────────────────────────
const benchTokens = {
  bg:        '#fbfbfa',
  panel:     '#ffffff',
  panelAlt:  '#f5f5f4',
  border:    '#e5e5e3',
  borderSoft:'#ececea',
  ink:       '#1a1a18',
  ink2:      '#525250',
  ink3:      '#8a8a86',
  ink4:      '#b8b8b3',
  accent:    'oklch(0.55 0.16 250)',
  accentBg:  'oklch(0.96 0.02 250)',
  accentLine:'oklch(0.55 0.16 250 / 0.18)',
  hi:        'oklch(0.95 0.05 90)',  // selection wash
  ok:        'oklch(0.60 0.13 150)',
  warn:      'oklch(0.65 0.16 50)',
  mono:      "'JetBrains Mono', 'SF Mono', ui-monospace, monospace",
  sans:      "Inter, -apple-system, system-ui, sans-serif",
};

// Density multipliers
const DENSITY = {
  compact:    { pad: 6, row: 22, font: 12 },
  comfortable:{ pad: 10, row: 28, font: 13 },
};

// ─── Module catalog helper ───────────────────────────────────────────────────
function getModuleByPath(path) {
  // path = ['AXISystem'] or ['AXISystem', 'xbar'] (instance names after top)
  // For depth>1 we look up the instance's type to find its module def.
  let mod = B_MODULES[path[0]];
  for (let i = 1; i < path.length; i++) {
    const child = mod.children.find((c) => c.name === path[i]);
    if (!child) return mod;
    mod = B_MODULES[child.type] || mod;
  }
  return mod;
}

// Resolve a wire endpoint string ('cpu.io.ar' → {inst:'cpu', port:'io', chan:'ar'})
function parseEndpoint(s) {
  // Handle 'in[0].ar' style indices
  const parts = s.split('.');
  return { inst: parts[0], port: parts[1] || null, chan: parts[2] || null, full: s };
}

// ─── App root ────────────────────────────────────────────────────────────────
function BenchApp({ tweaks }) {
  const [path, setPath] = useState(['AXISystem']);
  const [selectedSig, setSelectedSig] = useState(null); // 'cpu.io.ar' or 'cpu.io.ar.bits.addr'
  const [hoveredSig, setHoveredSig] = useState(null);
  const [expandedBundles, setExpandedBundles] = useState(new Set()); // bundle paths expanded
  const [bottomTab, setBottomTab] = useState('source');
  const [drawerHeight, setDrawerHeight] = useState(240);

  const mod = getModuleByPath(path);
  const density = DENSITY[tweaks.density] || DENSITY.comfortable;

  const drillInto = useCallback((inst) => {
    const child = mod.children.find((c) => c.name === inst);
    if (!child) return;
    if (!B_MODULES[child.type]) return; // leaf module — no internals
    setPath((p) => [...p, inst]);
    setSelectedSig(null);
  }, [mod]);

  const navTo = useCallback((idx) => {
    setPath((p) => p.slice(0, idx + 1));
    setSelectedSig(null);
  }, []);

  const toggleBundle = useCallback((bpath) => {
    setExpandedBundles((s) => {
      const n = new Set(s);
      if (n.has(bpath)) n.delete(bpath); else n.add(bpath);
      return n;
    });
  }, []);

  return (
    <div style={{
      width: '100%', height: '100%', background: benchTokens.bg,
      color: benchTokens.ink, fontFamily: benchTokens.sans, fontSize: density.font,
      display: 'flex', flexDirection: 'column', overflow: 'hidden',
    }}>
      <BenchTopBar path={path} navTo={navTo} mod={mod} />
      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
        <BenchHierarchy path={path} setPath={setPath} density={density} />
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0, borderLeft: `1px solid ${benchTokens.border}`, borderRight: `1px solid ${benchTokens.border}` }}>
          <BenchSchematic
            mod={mod} path={path}
            selectedSig={selectedSig} setSelectedSig={setSelectedSig}
            hoveredSig={hoveredSig} setHoveredSig={setHoveredSig}
            drillInto={drillInto}
            expandedBundles={expandedBundles} toggleBundle={toggleBundle}
            tweaks={tweaks}
          />
          <BenchBottomPanel
            mod={mod} tab={bottomTab} setTab={setBottomTab}
            selectedSig={selectedSig} setSelectedSig={setSelectedSig}
            hoveredSig={hoveredSig} setHoveredSig={setHoveredSig}
            height={drawerHeight} setHeight={setDrawerHeight}
            density={density}
          />
        </div>
        <BenchInspector
          mod={mod}
          selectedSig={selectedSig}
          hoveredSig={hoveredSig}
          setSelectedSig={setSelectedSig}
          expandedBundles={expandedBundles}
          toggleBundle={toggleBundle}
          density={density}
        />
      </div>
    </div>
  );
}

// ─── Top bar ─────────────────────────────────────────────────────────────────
function BenchTopBar({ path, navTo, mod }) {
  return (
    <div style={{
      height: 38, flexShrink: 0,
      borderBottom: `1px solid ${benchTokens.border}`, background: benchTokens.panel,
      display: 'flex', alignItems: 'center', padding: '0 12px', gap: 12,
      fontSize: 12,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
        <div style={{ width: 18, height: 18, borderRadius: 4, background: benchTokens.ink, color: benchTokens.bg, display: 'grid', placeItems: 'center', fontFamily: benchTokens.mono, fontSize: 10, fontWeight: 700, letterSpacing: -0.5 }}>S</div>
        <span style={{ fontWeight: 600, letterSpacing: -0.1 }}>socless</span>
        <span style={{ color: benchTokens.ink4 }}>·</span>
        <span style={{ color: benchTokens.ink2, fontFamily: benchTokens.mono, fontSize: 11 }}>AXISystem.fir</span>
      </div>
      {/* breadcrumb */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, flex: 1, marginLeft: 8, color: benchTokens.ink2 }}>
        {path.map((p, i) => (
          <React.Fragment key={i}>
            {i > 0 && <span style={{ color: benchTokens.ink4 }}>›</span>}
            <button
              onClick={() => navTo(i)}
              style={{
                border: 'none', background: 'transparent', cursor: 'pointer',
                color: i === path.length - 1 ? benchTokens.ink : benchTokens.ink2,
                fontFamily: benchTokens.mono, fontSize: 11, padding: '3px 6px',
                borderRadius: 4, fontWeight: i === path.length - 1 ? 600 : 400,
              }}
              onMouseEnter={(e) => e.currentTarget.style.background = benchTokens.panelAlt}
              onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}
            >{p}</button>
          </React.Fragment>
        ))}
      </div>
      {/* search */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, background: benchTokens.panelAlt, padding: '5px 10px', borderRadius: 5, color: benchTokens.ink3, width: 220 }}>
        <svg width="11" height="11" viewBox="0 0 11 11" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="4.5" cy="4.5" r="3"/><path d="M7 7l2.5 2.5"/></svg>
        <span style={{ fontSize: 11.5 }}>Search signals…</span>
        <span style={{ marginLeft: 'auto', fontFamily: benchTokens.mono, fontSize: 10, padding: '1px 4px', background: benchTokens.panel, border: `1px solid ${benchTokens.border}`, borderRadius: 3 }}>⌘K</span>
      </div>
      {/* synth metrics */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 14, fontFamily: benchTokens.mono, fontSize: 11, color: benchTokens.ink3 }}>
        {mod.metrics.gates != null && <span><span style={{ color: benchTokens.ink2 }}>{mod.metrics.gates.toLocaleString()}</span> gates</span>}
        <span><span style={{ color: benchTokens.ink2 }}>{mod.metrics.registers}</span> reg</span>
        <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <span style={{ width: 6, height: 6, borderRadius: 3, background: benchTokens.ok }} />
          {window.DESIGN.LOADED_FROM ? 'live' : 'demo'}
        </span>
      </div>
    </div>
  );
}

// ─── Hierarchy tree (left) ───────────────────────────────────────────────────
function BenchHierarchy({ path, setPath, density }) {
  const root = B_MODULES.AXISystem;
  const [expanded, setExpanded] = useState(new Set(['AXISystem', 'xbar']));

  // Build flat list with indent levels
  const rows = useMemo(() => {
    const out = [];
    const walk = (instName, modName, p, depth) => {
      const mod = B_MODULES[modName];
      const isLeaf = !mod || mod.children.length === 0;
      const id = p.join('/');
      out.push({ id, label: instName, type: modName, depth, isLeaf, path: p });
      if (mod && expanded.has(id)) {
        for (const c of mod.children) walk(c.name, c.type, [...p, c.name], depth + 1);
      }
    };
    walk('AXISystem', 'AXISystem', ['AXISystem'], 0);
    return out;
  }, [expanded]);

  const toggle = (id) => setExpanded((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const activeId = path.join('/');

  return (
    <div style={{ width: 240, flexShrink: 0, background: benchTokens.panel, overflowY: 'auto', display: 'flex', flexDirection: 'column' }}>
      <div style={{ padding: '10px 12px 6px', fontSize: 10.5, fontWeight: 600, color: benchTokens.ink3, letterSpacing: 0.6, textTransform: 'uppercase', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span>Hierarchy</span>
        <span style={{ fontFamily: benchTokens.mono, color: benchTokens.ink4, textTransform: 'none', letterSpacing: 0 }}>{rows.length}</span>
      </div>
      <div style={{ padding: '0 4px 8px', flex: 1 }}>
        {rows.map((r) => {
          const isActive = r.id === activeId;
          const isAncestor = activeId.startsWith(r.id + '/');
          const hasKids = !r.isLeaf;
          const isOpen = expanded.has(r.id);
          return (
            <div
              key={r.id}
              onClick={() => setPath(r.path)}
              style={{
                display: 'flex', alignItems: 'center', gap: 4,
                height: density.row, padding: '0 4px 0 0',
                paddingLeft: 4 + r.depth * 14,
                cursor: 'pointer',
                background: isActive ? benchTokens.accentBg : 'transparent',
                borderLeft: `2px solid ${isActive ? benchTokens.accent : 'transparent'}`,
                color: isActive ? benchTokens.accent : (isAncestor ? benchTokens.ink : benchTokens.ink2),
                fontWeight: isActive ? 600 : 400,
                fontSize: density.font - 0.5,
              }}
              onMouseEnter={(e) => { if (!isActive) e.currentTarget.style.background = benchTokens.panelAlt; }}
              onMouseLeave={(e) => { if (!isActive) e.currentTarget.style.background = 'transparent'; }}
            >
              <span
                onClick={(e) => { if (hasKids) { e.stopPropagation(); toggle(r.id); } }}
                style={{ width: 12, display: 'inline-flex', justifyContent: 'center', color: benchTokens.ink4 }}
              >
                {hasKids ? (
                  <svg width="9" height="9" viewBox="0 0 9 9" fill="currentColor" style={{ transform: isOpen ? 'rotate(90deg)' : 'none', transition: 'transform .12s' }}>
                    <path d="M2 1.5 L6 4.5 L2 7.5 Z"/>
                  </svg>
                ) : null}
              </span>
              <ModuleGlyph type={r.type} active={isActive} />
              <span style={{ fontFamily: benchTokens.mono, fontSize: density.font - 1 }}>{r.label}</span>
              <span style={{ marginLeft: 'auto', color: benchTokens.ink4, fontSize: 10.5, fontFamily: benchTokens.mono }}>{r.type}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function ModuleGlyph({ type, active }) {
  // Tiny shape indicating module kind
  const shape = type === 'AXICrossbar' ? '⬢' :
                type === 'AXIMaster'   ? '◆' :
                type === 'AXISlave'    ? '■' :
                type === 'AXISystem'   ? '◉' : '·';
  return (
    <span style={{
      width: 12, display: 'inline-flex', justifyContent: 'center',
      color: active ? benchTokens.accent : benchTokens.ink3,
      fontSize: 10,
    }}>{shape}</span>
  );
}

// ─── Schematic (center) ──────────────────────────────────────────────────────
// Block diagram of the current module's children + their wires. Three layout
// modes (hier / orth / dataflow) selectable via the Tweaks panel; bundleMode
// either lets users toggle bundles individually (collapsed) or forces all
// expanded (channel ribbons always on).

const NODE_W = 220, NODE_H = 84;

// Build forward/reverse adjacency on instance names from a module's wires.
function bBuildAdj(mod) {
  const fwd = {}, rev = {};
  for (const c of mod.children) { fwd[c.name] = new Set(); rev[c.name] = new Set(); }
  for (const w of mod.wires) {
    const a = (w.from || '').split('.')[0];
    const b = (w.to   || '').split('.')[0];
    if (!fwd[a] || !fwd[b]) continue;
    fwd[a].add(b); rev[b].add(a);
  }
  return { fwd, rev };
}

// Longest-path topological rank — ground-truth source for column placement.
function bTopoRanks(mod) {
  const { fwd, rev } = bBuildAdj(mod);
  const ranks = {};
  const insts = mod.children.map((c) => c.name);
  // Start from sources (no predecessors)
  const q = [];
  for (const n of insts) if (rev[n].size === 0) { ranks[n] = 0; q.push(n); }
  while (q.length) {
    const n = q.shift();
    for (const m of fwd[n] || []) {
      const r = (ranks[n] || 0) + 1;
      if (ranks[m] == null || ranks[m] < r) { ranks[m] = r; q.push(m); }
    }
  }
  // Cyclic / orphaned nodes: pin them to rank 0
  for (const n of insts) if (ranks[n] == null) ranks[n] = 0;
  return ranks;
}

// Place a column list into {boxes, totalW, totalH}.
function bPlaceColumns(cols, opts) {
  const { colW = NODE_W, colGap = 80, rowGap = 28, modH = NODE_H } = opts || {};
  const boxes = {};
  cols.forEach((col, ci) => {
    const x = ci * (colW + colGap);
    col.instances.forEach((inst, ri) => {
      boxes[inst] = { x, y: ri * (modH + rowGap), w: colW, h: modH };
    });
  });
  const totalW = Math.max(1, cols.length) * colW + Math.max(0, cols.length - 1) * colGap;
  const maxRows = Math.max(1, ...cols.map((c) => c.instances.length));
  const totalH = maxRows * modH + (maxRows - 1) * rowGap;
  return { boxes, totalW, totalH, columnLabels: cols.map((c) => c.label) };
}

// Layout 1: hier — TOP_LAYOUT at root (Masters | Fabric | Slaves), topo at depth.
function bLayoutHier(mod, path) {
  let cols;
  if (path.length === 1) {
    cols = B_TOP_LAYOUT.columns.map((col) => ({
      label: col.label,
      instances: col.modules.filter((m) => mod.children.find((c) => c.name === m)),
    }));
    // Append anything TOP_LAYOUT doesn't cover (e.g. user-added instances)
    const covered = new Set(cols.flatMap((c) => c.instances));
    const rest = mod.children.map((c) => c.name).filter((n) => !covered.has(n));
    if (rest.length) cols.push({ label: 'Other', instances: rest });
  } else {
    const ranks = bTopoRanks(mod);
    const maxR = Math.max(0, ...Object.values(ranks));
    cols = [];
    for (let r = 0; r <= maxR; r++) {
      const insts = mod.children.map((c) => c.name).filter((n) => ranks[n] === r);
      if (insts.length) cols.push({ label: `Layer ${r}`, instances: insts });
    }
  }
  return { ...bPlaceColumns(cols), routingStyle: 'orth' };
}

// Layout 2: orth — pure topo rank, tight spacing, no tier labels.
function bLayoutOrth(mod) {
  const ranks = bTopoRanks(mod);
  const maxR = Math.max(0, ...Object.values(ranks));
  const cols = [];
  for (let r = 0; r <= maxR; r++) {
    const insts = mod.children.map((c) => c.name).filter((n) => ranks[n] === r);
    if (insts.length) cols.push({ label: '', instances: insts });
  }
  const placed = bPlaceColumns(cols, { colW: 200, colGap: 56, modH: 80, rowGap: 22 });
  return { ...placed, columnLabels: [], routingStyle: 'orth' };
}

// Layout 3: dataflow — force-directed simulation. Deterministic seed so the
// layout is stable across rerenders (no jiggle as React re-renders).
function bLayoutForce(mod) {
  const insts = mod.children.map((c) => c.name);
  const N = insts.length;
  if (N === 0) return { boxes: {}, totalW: 1, totalH: 1, columnLabels: [], routingStyle: 'curve' };

  // Seeded RNG (mulberry32) so identical input → identical layout
  const seed = insts.join('').split('').reduce((a, c) => (a * 31 + c.charCodeAt(0)) | 0, 7);
  let s = seed >>> 0;
  const rand = () => { s |= 0; s = (s + 0x6D2B79F5) | 0; let t = Math.imul(s ^ (s >>> 15), 1 | s); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };

  // Place in a circle to start
  const pos = {}, vel = {};
  insts.forEach((inst, i) => {
    const a = (i / N) * Math.PI * 2 + rand() * 0.4;
    pos[inst] = { x: 400 + Math.cos(a) * (180 + rand() * 60), y: 280 + Math.sin(a) * (140 + rand() * 40) };
    vel[inst] = { x: 0, y: 0 };
  });

  const edges = mod.wires.map((w) => [w.from.split('.')[0], w.to.split('.')[0]])
    .filter(([a, b]) => pos[a] && pos[b] && a !== b);

  const ITER = 320;
  const REP = 60000;
  const SPR_K = 0.04;
  const SPR_L = 260;
  const DAMP = 0.82;
  const DT = 0.12;
  const GRAVITY = 0.0025; // pull toward center to keep cluster compact

  for (let iter = 0; iter < ITER; iter++) {
    // Anneal: stronger forces early, gentler later
    const cool = 1 - iter / ITER;
    // Repulsion
    for (let i = 0; i < N; i++) {
      for (let j = i + 1; j < N; j++) {
        const a = insts[i], b = insts[j];
        const dx = pos[b].x - pos[a].x;
        const dy = pos[b].y - pos[a].y;
        const d2 = Math.max(dx * dx + dy * dy, 400);
        const d = Math.sqrt(d2);
        const f = REP / d2;
        const fx = (dx / d) * f;
        const fy = (dy / d) * f;
        vel[a].x -= fx; vel[a].y -= fy;
        vel[b].x += fx; vel[b].y += fy;
      }
    }
    // Springs
    for (const [a, b] of edges) {
      const dx = pos[b].x - pos[a].x;
      const dy = pos[b].y - pos[a].y;
      const d = Math.sqrt(dx * dx + dy * dy) || 1;
      const f = SPR_K * (d - SPR_L);
      const fx = (dx / d) * f;
      const fy = (dy / d) * f;
      vel[a].x += fx; vel[a].y += fy;
      vel[b].x -= fx; vel[b].y -= fy;
    }
    // Gravity to center (400, 280)
    for (const n of insts) {
      vel[n].x += (400 - pos[n].x) * GRAVITY;
      vel[n].y += (280 - pos[n].y) * GRAVITY;
    }
    // Integrate
    for (const n of insts) {
      pos[n].x += vel[n].x * DT * cool;
      pos[n].y += vel[n].y * DT * cool;
      vel[n].x *= DAMP; vel[n].y *= DAMP;
    }
  }

  // Normalize: translate to origin + padding
  const PAD = 20;
  const xs = insts.map((n) => pos[n].x), ys = insts.map((n) => pos[n].y);
  const minX = Math.min(...xs), minY = Math.min(...ys);
  const boxes = {};
  for (const n of insts) {
    boxes[n] = { x: pos[n].x - minX + PAD, y: pos[n].y - minY + PAD, w: NODE_W, h: NODE_H };
  }
  const totalW = Math.max(...insts.map((n) => boxes[n].x + boxes[n].w)) + PAD;
  const totalH = Math.max(...insts.map((n) => boxes[n].y + boxes[n].h)) + PAD;
  return { boxes, totalW, totalH, columnLabels: [], routingStyle: 'curve' };
}

function bComputeLayout(mod, path, mode) {
  if (mode === 'orth')     return bLayoutOrth(mod);
  if (mode === 'dataflow') return bLayoutForce(mod);
  return bLayoutHier(mod, path);
}

// Pick which side of each box a wire enters from based on the relative box
// positions of the two endpoints (closest face). Used for force layouts where
// boxes can be in any relative position.
function bWireEndpoints(ba, bb, fromIdx, toIdx, total) {
  const cax = ba.x + ba.w / 2, cay = ba.y + ba.h / 2;
  const cbx = bb.x + bb.w / 2, cby = bb.y + bb.h / 2;
  const dx = cbx - cax, dy = cby - cay;
  // Stagger when multiple wires share the same face
  const off = total > 1 ? (fromIdx - (total - 1) / 2) * 10 : 0;
  let ax, ay, bx, by;
  if (Math.abs(dx) >= Math.abs(dy)) {
    ax = dx > 0 ? ba.x + ba.w : ba.x;
    ay = cay + off;
    bx = dx > 0 ? bb.x : bb.x + bb.w;
    by = cby + off;
  } else {
    ax = cax + off;
    ay = dy > 0 ? ba.y + ba.h : ba.y;
    bx = cbx + off;
    by = dy > 0 ? bb.y : bb.y + bb.h;
  }
  return { x1: ax, y1: ay, x2: bx, y2: by };
}

function bRoutePath(w, routingStyle) {
  if (routingStyle === 'curve') {
    const dx = w.x2 - w.x1, dy = w.y2 - w.y1;
    const horiz = Math.abs(dx) >= Math.abs(dy);
    const cp = Math.max(40, Math.min(180, Math.sqrt(dx * dx + dy * dy) * 0.4));
    if (horiz) {
      return `M ${w.x1} ${w.y1} C ${w.x1 + Math.sign(dx) * cp} ${w.y1}, ${w.x2 - Math.sign(dx) * cp} ${w.y2}, ${w.x2} ${w.y2}`;
    }
    return `M ${w.x1} ${w.y1} C ${w.x1} ${w.y1 + Math.sign(dy) * cp}, ${w.x2} ${w.y2 - Math.sign(dy) * cp}, ${w.x2} ${w.y2}`;
  }
  // orth: out-segment, vertical bend, in-segment
  const mx = (w.x1 + w.x2) / 2;
  return `M ${w.x1} ${w.y1} L ${mx} ${w.y1} L ${mx} ${w.y2} L ${w.x2} ${w.y2}`;
}

function bRouteMidpoint(w, routingStyle) {
  // Where to anchor labels / expand toggles on a routed wire.
  if (routingStyle === 'curve') {
    return { x: (w.x1 + w.x2) / 2, y: (w.y1 + w.y2) / 2 };
  }
  return { x: (w.x1 + w.x2) / 2, y: (w.y1 + w.y2) / 2 };
}

function BenchSchematic({ mod, path, selectedSig, setSelectedSig, hoveredSig, setHoveredSig, drillInto, expandedBundles, toggleBundle, tweaks }) {
  const layoutResult = useMemo(
    () => bComputeLayout(mod, path, tweaks.layout),
    [mod, path, tweaks.layout],
  );
  const { boxes: layoutBoxes, totalW, totalH, columnLabels, routingStyle } = layoutResult;
  const containerH = totalH + 40;

  // Wires with absolute endpoints. We compute per-instance fan-out indexing
  // so wires sharing a face are staggered (avoids overlap on dense modules).
  const wires = useMemo(() => {
    // Count wires per (instance, role) to compute stagger offsets.
    const fanOut = {}, fanIn = {};
    mod.wires.forEach((w) => {
      const a = w.from.split('.')[0], b = w.to.split('.')[0];
      fanOut[a] = (fanOut[a] || 0) + 1;
      fanIn[b]  = (fanIn[b]  || 0) + 1;
    });
    const idxOut = {}, idxIn = {};
    return mod.wires.map((w, i) => {
      const fromInst = w.from.split('.')[0];
      const toInst   = w.to.split('.')[0];
      const ba = layoutBoxes[fromInst];
      const bb = layoutBoxes[toInst];
      if (!ba || !bb) return null;
      const fIdx = (idxOut[fromInst] = (idxOut[fromInst] || 0) + 1) - 1;
      const tIdx = (idxIn[toInst]   = (idxIn[toInst]   || 0) + 1) - 1;
      const pts = bWireEndpoints(ba, bb, fIdx, fanOut[fromInst]);
      return {
        id: `w${i}`, ...w,
        fromInst, toInst,
        fromPath: w.from, toPath: w.to,
        ...pts,
      };
    }).filter(Boolean);
  }, [mod, layoutBoxes]);

  const isHighlighted = (wire) => {
    if (!selectedSig) return false;
    return wire.fromPath.startsWith(selectedSig) || wire.toPath.startsWith(selectedSig) ||
           selectedSig.startsWith(wire.fromPath) || selectedSig.startsWith(wire.toPath);
  };
  const isHovered = (wire) => {
    if (!hoveredSig) return false;
    return wire.fromPath.startsWith(hoveredSig) || wire.toPath.startsWith(hoveredSig);
  };

  // Global bundle-mode: when "expanded", every wire renders as channel ribbons
  // regardless of individual toggles; the per-wire expand pill is hidden.
  const globalExpanded = tweaks.bundleMode === 'expanded';

  return (
    <div style={{ flex: 1, position: 'relative', overflow: 'hidden', background: `radial-gradient(circle at 1px 1px, ${benchTokens.border} 1px, transparent 0) 0 0/20px 20px, ${benchTokens.bg}` }}>
      {/* Canvas chrome */}
      <div style={{ position: 'absolute', top: 12, left: 12, right: 12, display: 'flex', alignItems: 'center', justifyContent: 'space-between', zIndex: 2, pointerEvents: 'none' }}>
        <div style={{ display: 'flex', gap: 6, pointerEvents: 'auto' }}>
          <div style={{ background: benchTokens.panel, border: `1px solid ${benchTokens.border}`, borderRadius: 5, padding: '4px 10px', fontSize: 11, color: benchTokens.ink2, display: 'flex', alignItems: 'center', gap: 6, fontFamily: benchTokens.mono }}>
            <span style={{ color: benchTokens.ink4 }}>module</span>
            <span style={{ color: benchTokens.ink }}>{mod.type}</span>
          </div>
          <div style={{ background: benchTokens.panel, border: `1px solid ${benchTokens.border}`, borderRadius: 5, padding: '4px 10px', fontSize: 11, color: benchTokens.ink3, fontFamily: benchTokens.mono }}>
            {mod.children.length} inst · {mod.wires.length} wires
          </div>
        </div>
        <div style={{ display: 'flex', gap: 0, pointerEvents: 'auto', background: benchTokens.panel, border: `1px solid ${benchTokens.border}`, borderRadius: 5, padding: 2, fontFamily: benchTokens.mono, fontSize: 10.5 }}>
          <span style={{ padding: '3px 8px', color: benchTokens.ink4 }}>layout</span>
          <span style={{ padding: '3px 8px 3px 2px', color: benchTokens.ink, fontWeight: 600 }}>{tweaks.layout}</span>
        </div>
      </div>

      <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 40, overflow: 'auto' }}>
        <div style={{ position: 'relative', width: totalW, height: containerH, transition: 'width .3s, height .3s' }}>
          {/* Column labels (hier only) */}
          {columnLabels.length > 0 && columnLabels.map((label, ci) => (
            label ? (
              <div key={ci} style={{
                position: 'absolute', left: ci * (NODE_W + 80), top: -28, width: NODE_W,
                fontSize: 10, fontFamily: benchTokens.mono, color: benchTokens.ink3,
                textTransform: 'uppercase', letterSpacing: 0.8, fontWeight: 600,
              }}>{label}</div>
            ) : null
          ))}

          {/* Wires */}
          <svg style={{ position: 'absolute', top: 0, left: 0, width: totalW, height: containerH, pointerEvents: 'none', overflow: 'visible' }}>
            <defs>
              {Object.entries(B_CHAN).map(([k, v]) => (
                <marker key={k} id={`arr-${k}`} viewBox="0 0 8 8" refX="4" refY="4" markerWidth="6" markerHeight="6" orient="auto">
                  <path d="M0 0 L8 4 L0 8 Z" fill={v} />
                </marker>
              ))}
              <marker id="arr-default" viewBox="0 0 8 8" refX="4" refY="4" markerWidth="6" markerHeight="6" orient="auto">
                <path d="M0 0 L8 4 L0 8 Z" fill={benchTokens.ink3} />
              </marker>
            </defs>
            {wires.map((w) => {
              const hl = isHighlighted(w);
              const hov = isHovered(w);
              const dim = selectedSig && !hl;
              const expanded = globalExpanded || expandedBundles.has(w.fromPath);
              const stroke = dim ? benchTokens.ink4 : (hl || hov ? benchTokens.accent : benchTokens.ink2);
              const sw = expanded ? 1.4 : (hl ? 4 : 3);
              const d = bRoutePath(w, routingStyle);
              const mid = bRouteMidpoint(w, routingStyle);
              const channels = (B_BUNDLES[w.bundle] && B_BUNDLES[w.bundle].channels) || [];
              return (
                <g key={w.id} style={{ opacity: dim ? 0.32 : 1, transition: 'opacity .15s' }}>
                  {!expanded && (
                    <path d={d} stroke={stroke} strokeWidth={sw + 4} fill="none" strokeLinecap="round" strokeLinejoin="round" opacity={0.12} />
                  )}
                  {expanded && channels.length > 0 && (
                    <g>
                      {channels.map((ch, ci) => {
                        // For curve routing, fan channels by offsetting their control points.
                        // For orth, offset the entire bend line.
                        if (routingStyle === 'curve') {
                          const dxw = w.x2 - w.x1, dyw = w.y2 - w.y1;
                          const horiz = Math.abs(dxw) >= Math.abs(dyw);
                          const off = (ci - (channels.length - 1) / 2) * 4;
                          let dd;
                          if (horiz) {
                            const cp = Math.max(40, Math.min(180, Math.sqrt(dxw * dxw + dyw * dyw) * 0.4));
                            dd = `M ${w.x1} ${w.y1 + off} C ${w.x1 + Math.sign(dxw) * cp} ${w.y1 + off}, ${w.x2 - Math.sign(dxw) * cp} ${w.y2 + off}, ${w.x2} ${w.y2 + off}`;
                          } else {
                            const cp = Math.max(40, Math.min(180, Math.sqrt(dxw * dxw + dyw * dyw) * 0.4));
                            dd = `M ${w.x1 + off} ${w.y1} C ${w.x1 + off} ${w.y1 + Math.sign(dyw) * cp}, ${w.x2 + off} ${w.y2 - Math.sign(dyw) * cp}, ${w.x2 + off} ${w.y2}`;
                          }
                          return <path key={ch.name} d={dd} stroke={dim ? benchTokens.ink4 : B_CHAN[ch.name]} strokeWidth={1.8} fill="none" strokeLinecap="round" opacity={dim ? 0.3 : 1} markerEnd={`url(#arr-${ch.name})`} />;
                        }
                        const offset = (ci - (channels.length - 1) / 2) * 5;
                        const yo1 = w.y1 + offset;
                        const yo2 = w.y2 + offset;
                        const mx = (w.x1 + w.x2) / 2 + offset * 2;
                        const dd = `M ${w.x1} ${yo1} L ${mx} ${yo1} L ${mx} ${yo2} L ${w.x2} ${yo2}`;
                        return <path key={ch.name} d={dd} stroke={dim ? benchTokens.ink4 : B_CHAN[ch.name]} strokeWidth={1.8} fill="none" strokeLinecap="round" strokeLinejoin="round" opacity={dim ? 0.3 : 1} markerEnd={`url(#arr-${ch.name})`} />;
                      })}
                    </g>
                  )}
                  {!expanded && (
                    <path d={d} stroke={stroke} strokeWidth={sw} fill="none"
                      strokeLinecap="round" strokeLinejoin="round"
                      markerEnd="url(#arr-default)"
                    />
                  )}
                  {/* Click target */}
                  <path d={d} stroke="transparent" strokeWidth={14} fill="none"
                    style={{ cursor: 'pointer', pointerEvents: 'stroke' }}
                    onClick={() => setSelectedSig(w.fromPath)}
                    onMouseEnter={() => setHoveredSig(w.fromPath)}
                    onMouseLeave={() => setHoveredSig(null)}
                  />
                  {tweaks.showWidths && (
                    <g transform={`translate(${mid.x}, ${mid.y - 8})`}>
                      <rect x={-30} y={-9} width={60} height={18} rx={3}
                        fill={benchTokens.panel} stroke={hl ? benchTokens.accent : benchTokens.border} strokeWidth={1} />
                      <text x={0} y={3} textAnchor="middle"
                        fontFamily={benchTokens.mono} fontSize={9}
                        fill={hl ? benchTokens.accent : benchTokens.ink2}>
                        {w.bundle} · {bTotalWidth(w.bundle)}b
                      </text>
                    </g>
                  )}
                  {!globalExpanded && (
                    <g transform={`translate(${mid.x - 10}, ${mid.y + 14})`}
                       style={{ cursor: 'pointer' }}
                       onClick={() => toggleBundle(w.fromPath)}>
                      <rect x={-2} y={-7} width={24} height={14} rx={7} fill={benchTokens.panel} stroke={benchTokens.border} />
                      <text x={10} y={3} textAnchor="middle" fontFamily={benchTokens.mono} fontSize={9} fill={benchTokens.ink3}>
                        {expanded ? '−' : '+' + (channels.length || 5)}
                      </text>
                    </g>
                  )}
                </g>
              );
            })}
          </svg>

          {/* Module boxes */}
          {Object.entries(layoutBoxes).map(([inst, b]) => {
            const child = mod.children.find((c) => c.name === inst);
            if (!child) return null;
            const childMod = B_MODULES[child.type];
            const drillable = !!childMod && childMod.children.length > 0;
            const wiresOnMe = wires.filter((w) => w.fromInst === inst || w.toInst === inst);
            const selectedHere = selectedSig && (selectedSig.startsWith(inst + '.') || selectedSig === inst);
            return (
              <div key={inst}
                onDoubleClick={() => drillable && drillInto(inst)}
                style={{
                  position: 'absolute', left: b.x, top: b.y, width: b.w, height: b.h,
                  background: benchTokens.panel,
                  border: `1px solid ${selectedHere ? benchTokens.accent : benchTokens.border}`,
                  borderRadius: 6,
                  boxShadow: selectedHere ? `0 0 0 3px ${benchTokens.accentBg}` : '0 1px 2px rgba(0,0,0,.04)',
                  cursor: drillable ? 'pointer' : 'default',
                  display: 'flex', flexDirection: 'column',
                  overflow: 'hidden',
                  transition: 'left .35s cubic-bezier(.4,.2,.2,1), top .35s cubic-bezier(.4,.2,.2,1), box-shadow .12s, border-color .12s',
                }}
              >
                <div style={{ padding: '7px 10px', borderBottom: `1px solid ${benchTokens.borderSoft}`, display: 'flex', alignItems: 'center', gap: 6, background: benchTokens.panelAlt }}>
                  <ModuleGlyph type={child.type} active={selectedHere} />
                  <span style={{ fontFamily: benchTokens.mono, fontSize: 11.5, fontWeight: 600, color: benchTokens.ink }}>{inst}</span>
                  <span style={{ marginLeft: 'auto', fontFamily: benchTokens.mono, fontSize: 10, color: benchTokens.ink3 }}>{child.type}</span>
                </div>
                <div style={{ flex: 1, padding: '6px 10px', fontSize: 10.5, color: benchTokens.ink3, lineHeight: 1.45 }}>
                  {child.doc}
                </div>
                <div style={{ padding: '4px 10px', borderTop: `1px solid ${benchTokens.borderSoft}`, display: 'flex', justifyContent: 'space-between', fontFamily: benchTokens.mono, fontSize: 9.5, color: benchTokens.ink4 }}>
                  <span>{wiresOnMe.length} port{wiresOnMe.length !== 1 ? 's' : ''}</span>
                  {drillable && <span style={{ color: benchTokens.accent }}>↵ open</span>}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

// ─── Inspector (right) ───────────────────────────────────────────────────────
function BenchInspector({ mod, selectedSig, hoveredSig, setSelectedSig, expandedBundles, toggleBundle, density }) {
  const sig = selectedSig || hoveredSig;
  const wire = sig ? mod.wires.find((w) => w.from === sig || w.to === sig || sig.startsWith(w.from + '.') || sig.startsWith(w.to + '.')) : null;

  return (
    <div style={{ width: 304, flexShrink: 0, background: benchTokens.panel, overflowY: 'auto', display: 'flex', flexDirection: 'column' }}>
      <div style={{ padding: '10px 14px 8px', borderBottom: `1px solid ${benchTokens.borderSoft}`, fontSize: 10.5, fontWeight: 600, color: benchTokens.ink3, letterSpacing: 0.6, textTransform: 'uppercase', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span>Inspector</span>
        {sig && <button onClick={() => setSelectedSig(null)} style={{ border: 'none', background: 'transparent', color: benchTokens.ink3, fontSize: 11, cursor: 'pointer', textTransform: 'none', letterSpacing: 0 }}>clear</button>}
      </div>
      {!sig && (
        <div style={{ padding: '24px 16px', color: benchTokens.ink3, fontSize: 12, lineHeight: 1.55 }}>
          <div style={{ marginBottom: 12, fontWeight: 500, color: benchTokens.ink2 }}>{mod.type}</div>
          <div style={{ marginBottom: 16, fontSize: 11.5 }}>{mod.doc}</div>
          <InspectorMeta mod={mod} />
          <div style={{ marginTop: 14, color: benchTokens.ink4, fontSize: 11 }}>Click a wire or module to inspect.</div>
        </div>
      )}
      {sig && wire && (
        <InspectorBundle wire={wire} sig={sig} expandedBundles={expandedBundles} toggleBundle={toggleBundle} setSelectedSig={setSelectedSig} />
      )}
    </div>
  );
}

function InspectorMeta({ mod }) {
  const items = [
    ['Type',      mod.type],
    ['Instances', mod.children.length],
    ['Wires',     mod.wires.length],
    ...(mod.metrics.gates != null ? [['Gates', mod.metrics.gates.toLocaleString()]] : []),
    ['Registers', mod.metrics.registers],
    ['LOC',       mod.metrics.lines],
  ];
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '90px 1fr', rowGap: 5, columnGap: 8, fontFamily: benchTokens.mono, fontSize: 11 }}>
      {items.map(([k, v]) => (
        <React.Fragment key={k}>
          <span style={{ color: benchTokens.ink4 }}>{k}</span>
          <span style={{ color: benchTokens.ink2 }}>{v}</span>
        </React.Fragment>
      ))}
    </div>
  );
}

function InspectorBundle({ wire, sig, expandedBundles, toggleBundle, setSelectedSig }) {
  const expanded = expandedBundles.has(wire.from);
  const fields = bBundleFields(wire.bundle);
  // Group by channel for AXI4
  const channels = useMemo(() => {
    const m = {};
    for (const f of fields) {
      const ch = f.channel || '_';
      if (!m[ch]) m[ch] = [];
      m[ch].push(f);
    }
    return m;
  }, [fields]);

  return (
    <div style={{ padding: '12px 14px', fontSize: 12, overflow: 'auto' }}>
      <div style={{ marginBottom: 10 }}>
        <div style={{ fontFamily: benchTokens.mono, fontSize: 12, fontWeight: 600, color: benchTokens.ink, marginBottom: 2 }}>{wire.from}</div>
        <div style={{ fontFamily: benchTokens.mono, fontSize: 10.5, color: benchTokens.ink3, display: 'flex', alignItems: 'center', gap: 6 }}>
          <span>↓</span><span>{wire.to}</span>
        </div>
      </div>
      <div style={{ background: benchTokens.panelAlt, padding: '8px 10px', borderRadius: 5, marginBottom: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontFamily: benchTokens.mono, fontSize: 11 }}>
        <span style={{ color: benchTokens.ink3 }}>{wire.bundle}</span>
        <span style={{ color: benchTokens.ink2 }}>{bTotalWidth(wire.bundle)} bits</span>
      </div>

      <div style={{ fontSize: 10.5, fontWeight: 600, color: benchTokens.ink3, letterSpacing: 0.6, textTransform: 'uppercase', marginBottom: 6 }}>Channels</div>
      {Object.entries(channels).map(([ch, fs]) => (
        <BundleChannel key={ch} ch={ch} fields={fs} wireFrom={wire.from} setSelectedSig={setSelectedSig} sig={sig} />
      ))}

      <div style={{ marginTop: 14, fontSize: 10.5, fontWeight: 600, color: benchTokens.ink3, letterSpacing: 0.6, textTransform: 'uppercase', marginBottom: 6 }}>Source</div>
      <div style={{ background: benchTokens.panelAlt, padding: '6px 10px', borderRadius: 5, fontFamily: benchTokens.mono, fontSize: 11, color: benchTokens.ink2 }}>
        {wire.label || wire.bundle}<span style={{ color: benchTokens.ink4 }}> · L24</span>
      </div>
    </div>
  );
}

function BundleChannel({ ch, fields, wireFrom, setSelectedSig, sig }) {
  const [open, setOpen] = useState(true);
  const color = B_CHAN[ch] || benchTokens.ink3;
  return (
    <div style={{ marginBottom: 6, border: `1px solid ${benchTokens.borderSoft}`, borderRadius: 5, overflow: 'hidden' }}>
      <div onClick={() => setOpen((o) => !o)}
        style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '5px 8px', background: benchTokens.panelAlt, cursor: 'pointer', fontFamily: benchTokens.mono, fontSize: 11 }}>
        <span style={{ width: 8, height: 8, borderRadius: 4, background: color }} />
        <span style={{ fontWeight: 600 }}>{ch}</span>
        <span style={{ marginLeft: 'auto', color: benchTokens.ink4, fontSize: 10 }}>{fields.length} signals</span>
        <span style={{ color: benchTokens.ink4, fontSize: 9, transform: open ? 'rotate(90deg)' : 'none', transition: 'transform .12s', display: 'inline-block' }}>▶</span>
      </div>
      {open && (
        <div>
          {fields.map((f) => {
            const fullPath = `${wireFrom}.${ch}.${f.name}`;
            const isSel = sig === fullPath;
            return (
              <div key={f.name}
                onClick={() => setSelectedSig(fullPath)}
                style={{
                  display: 'flex', alignItems: 'center', gap: 8,
                  padding: '3px 8px 3px 22px', fontFamily: benchTokens.mono, fontSize: 10.5,
                  cursor: 'pointer',
                  background: isSel ? benchTokens.accentBg : 'transparent',
                  color: isSel ? benchTokens.accent : benchTokens.ink2,
                }}
                onMouseEnter={(e) => { if (!isSel) e.currentTarget.style.background = benchTokens.panelAlt; }}
                onMouseLeave={(e) => { if (!isSel) e.currentTarget.style.background = 'transparent'; }}
              >
                <span style={{ width: 12, color: benchTokens.ink4, fontSize: 9 }}>{f.dir === 'out' ? '→' : '←'}</span>
                <span>{f.name}</span>
                <span style={{ marginLeft: 'auto', color: benchTokens.ink4 }}>{f.width}b</span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ─── Bottom drawer: source / waveform / ports ────────────────────────────────
function BenchBottomPanel({ mod, tab, setTab, selectedSig, setSelectedSig, hoveredSig, setHoveredSig, height, setHeight, density }) {
  const tabs = [
    { id: 'source', label: 'Source',  icon: '⌥' },
    { id: 'wave',   label: 'Waveform', icon: '∿' },
    { id: 'ports',  label: 'Ports',   icon: '⊕' },
  ];

  return (
    <div style={{ height, flexShrink: 0, borderTop: `1px solid ${benchTokens.border}`, background: benchTokens.panel, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      <div style={{ display: 'flex', borderBottom: `1px solid ${benchTokens.borderSoft}`, height: 30, alignItems: 'stretch' }}>
        {tabs.map((t) => (
          <button key={t.id} onClick={() => setTab(t.id)}
            style={{
              border: 'none', background: 'transparent', cursor: 'pointer',
              padding: '0 14px', fontSize: 11.5, fontFamily: 'inherit',
              color: tab === t.id ? benchTokens.ink : benchTokens.ink3,
              fontWeight: tab === t.id ? 600 : 500,
              borderBottom: `2px solid ${tab === t.id ? benchTokens.accent : 'transparent'}`,
              display: 'flex', alignItems: 'center', gap: 6,
            }}>
            <span style={{ fontFamily: benchTokens.mono, color: benchTokens.ink4 }}>{t.icon}</span>
            {t.label}
          </button>
        ))}
        <div style={{ flex: 1 }} />
        <div style={{ display: 'flex', alignItems: 'center', padding: '0 12px', fontSize: 11, fontFamily: benchTokens.mono, color: benchTokens.ink4 }}>
          {tab === 'source' && mod.source.file}
          {tab === 'wave' && `${B_WAVE_CYCLES} cycles · 100 MHz`}
          {tab === 'ports' && `${mod.children.length} instances`}
        </div>
      </div>
      <div style={{ flex: 1, overflow: 'auto', position: 'relative' }}>
        {tab === 'source' && <SourceView mod={mod} selectedSig={selectedSig} setSelectedSig={setSelectedSig} hoveredSig={hoveredSig} />}
        {tab === 'wave'   && <WaveformView selectedSig={selectedSig} setSelectedSig={setSelectedSig} setHoveredSig={setHoveredSig} />}
        {tab === 'ports'  && <PortsView mod={mod} setSelectedSig={setSelectedSig} />}
      </div>
    </div>
  );
}

// — Source view: code + syntax highlighting + cross-ref highlight.
// If the source code text wasn't embedded (i.e. the design was loaded from a
// real exporter that only knows file:line:col, not the full text), fall back
// to a locator-only view that points at the originating .scala file.
function SourceView({ mod, selectedSig, setSelectedSig, hoveredSig }) {
  if (!mod.source.code) {
    return (
      <div style={{ padding: '20px 22px', fontFamily: benchTokens.sans, fontSize: 12, color: benchTokens.ink2, lineHeight: 1.55, maxWidth: 520 }}>
        <div style={{ fontFamily: benchTokens.mono, fontSize: 11, color: benchTokens.ink, marginBottom: 6 }}>
          {mod.source.file}{mod.source.line ? `:${mod.source.line}` : ''}{mod.source.col ? `:${mod.source.col}` : ''}
        </div>
        <div style={{ color: benchTokens.ink3 }}>
          Source text was not embedded in <code style={{ fontFamily: benchTokens.mono, fontSize: 11 }}>design.json</code>.
          The SoCLess exporter only records the originating <code style={{ fontFamily: benchTokens.mono, fontSize: 11 }}>file:line:col</code> — open the file in your editor to view it.
        </div>
      </div>
    );
  }
  const code = mod.source.code.split('\n');
  // Highlight lines that mention the selected wire's instance / port
  const selInst = selectedSig ? selectedSig.split('.')[0] : null;
  const selPort = selectedSig ? selectedSig.split('.').slice(0, 2).join('.') : null;

  return (
    <div style={{ fontFamily: benchTokens.mono, fontSize: 11.5, lineHeight: 1.6, padding: '8px 0', display: 'grid', gridTemplateColumns: 'auto 1fr', columnGap: 12 }}>
      {code.map((line, i) => {
        const matchInst = selInst && line.indexOf(selInst) >= 0;
        const matchPort = selPort && line.indexOf(selPort) >= 0;
        const isHighlight = matchInst || matchPort;
        return (
          <React.Fragment key={i}>
            <div style={{
              textAlign: 'right', padding: '0 4px', userSelect: 'none',
              color: isHighlight ? benchTokens.accent : benchTokens.ink4,
              background: isHighlight ? benchTokens.accentBg : 'transparent',
              fontVariantNumeric: 'tabular-nums',
            }}>{i + 1}</div>
            <div style={{
              padding: '0 14px 0 0',
              background: isHighlight ? benchTokens.accentBg : 'transparent',
              borderLeft: isHighlight ? `2px solid ${benchTokens.accent}` : '2px solid transparent',
              paddingLeft: 6,
              whiteSpace: 'pre',
              color: benchTokens.ink2,
            }}>{tokenizeScala(line, selInst, selPort, setSelectedSig)}</div>
          </React.Fragment>
        );
      })}
    </div>
  );
}

function tokenizeScala(line, selInst, selPort, setSelectedSig) {
  // Very lightweight: highlight Chisel keywords + types + instance refs
  const KW = /\b(class|extends|val|def|Module|IO|new|Bundle|Vec|Flipped|for|until|true|false)\b/g;
  const NUM = /\b(\d+)\b/g;
  const STR = /"[^"]*"/g;
  const out = [];
  let rest = line;
  let i = 0;
  // simple pass — split by whitespace boundaries to preserve spacing
  const parts = line.split(/(\s+|[(){}.,;])/);
  return parts.map((p, idx) => {
    if (!p) return null;
    if (/^\s+$/.test(p) || /^[(){}.,;]$/.test(p)) return <span key={idx}>{p}</span>;
    if (/^(class|extends|val|def|new|for|until)$/.test(p))
      return <span key={idx} style={{ color: 'oklch(0.5 0.15 290)', fontWeight: 500 }}>{p}</span>;
    if (/^(Module|IO|Bundle|Vec|Flipped|Queue|RRArbiter|AddrDec|RRouter|Backend|IdAllocator|AXI4|AXIMaster|AXISlave|AXISystem|AXICrossbar|TxReq|AR|R)$/.test(p))
      return <span key={idx} style={{ color: 'oklch(0.5 0.13 200)' }}>{p}</span>;
    if (/^(true|false)$/.test(p))
      return <span key={idx} style={{ color: 'oklch(0.55 0.15 30)' }}>{p}</span>;
    if (/^\d+$/.test(p))
      return <span key={idx} style={{ color: 'oklch(0.55 0.15 30)' }}>{p}</span>;
    if (selInst && p === selInst)
      return <span key={idx} style={{ background: 'oklch(0.95 0.1 90)', borderRadius: 2, padding: '0 1px', cursor: 'pointer', color: benchTokens.ink }} onClick={() => setSelectedSig(p)}>{p}</span>;
    // comments
    if (/^\/\//.test(p))
      return <span key={idx} style={{ color: benchTokens.ink4, fontStyle: 'italic' }}>{p}</span>;
    return <span key={idx}>{p}</span>;
  });
}

// — Waveform view: track names + per-cycle cells
function WaveformView({ selectedSig, setSelectedSig, setHoveredSig }) {
  const cellW = 28;
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '200px 1fr', fontFamily: benchTokens.mono, fontSize: 10.5 }}>
      <div style={{ borderRight: `1px solid ${benchTokens.borderSoft}` }}>
        <div style={{ padding: '6px 10px', height: 26, background: benchTokens.panelAlt, borderBottom: `1px solid ${benchTokens.borderSoft}`, fontSize: 10, color: benchTokens.ink3, fontWeight: 600, textTransform: 'uppercase', letterSpacing: 0.6, display: 'flex', alignItems: 'center' }}>Signal</div>
        {B_WAVES.map((tr) => {
          const sel = selectedSig === tr.path || (selectedSig && tr.path.startsWith(selectedSig));
          return (
            <div key={tr.path}
              onClick={() => setSelectedSig(tr.path)}
              onMouseEnter={() => setHoveredSig(tr.path)}
              onMouseLeave={() => setHoveredSig(null)}
              style={{
                padding: '6px 10px', height: 26, lineHeight: '14px',
                cursor: 'pointer',
                background: sel ? benchTokens.accentBg : 'transparent',
                color: sel ? benchTokens.accent : benchTokens.ink2,
                borderBottom: `1px solid ${benchTokens.borderSoft}`,
                display: 'flex', alignItems: 'center', gap: 8,
              }}>
              <span style={{ color: benchTokens.ink4, fontSize: 9 }}>{tr.type}</span>
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>{tr.path}</span>
            </div>
          );
        })}
      </div>
      <div style={{ overflowX: 'auto' }}>
        {/* cycle ruler */}
        <div style={{ display: 'flex', height: 26, background: benchTokens.panelAlt, borderBottom: `1px solid ${benchTokens.borderSoft}`, fontSize: 9.5, color: benchTokens.ink4 }}>
          {Array.from({ length: B_WAVE_CYCLES }, (_, i) => (
            <div key={i} style={{ width: cellW, minWidth: cellW, textAlign: 'center', borderRight: `1px solid ${benchTokens.borderSoft}`, padding: '6px 0', display: 'flex', alignItems: 'center', justifyContent: 'center', fontVariantNumeric: 'tabular-nums' }}>{i}</div>
          ))}
        </div>
        {B_WAVES.map((tr) => {
          const sel = selectedSig === tr.path;
          return (
            <div key={tr.path} style={{ display: 'flex', height: 26, borderBottom: `1px solid ${benchTokens.borderSoft}`, background: sel ? benchTokens.accentBg : 'transparent' }}>
              {tr.values.map((v, i) => {
                const isBool = tr.type === 'Bool';
                return (
                  <div key={i} style={{ width: cellW, minWidth: cellW, padding: 2, borderRight: `1px solid ${benchTokens.borderSoft}` }}>
                    {isBool ? (
                      <div style={{
                        height: '100%', background: v === '1' ? benchTokens.accent : (v === '0' ? benchTokens.panel : benchTokens.panelAlt),
                        border: v === '0' ? `1px solid ${benchTokens.borderSoft}` : 'none',
                        borderRadius: 1,
                      }} />
                    ) : (
                      <div style={{
                        height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center',
                        fontSize: 9, color: v === '–' ? benchTokens.ink4 : benchTokens.ink2,
                        background: v !== '–' ? 'oklch(0.96 0.02 250)' : 'transparent',
                        borderRadius: 1, overflow: 'hidden', whiteSpace: 'nowrap',
                      }}>{v}</div>
                    )}
                  </div>
                );
              })}
            </div>
          );
        })}
      </div>
    </div>
  );
}

// — Ports table
function PortsView({ mod, setSelectedSig }) {
  const rows = [];
  mod.children.forEach((c) => {
    const cm = B_MODULES[c.type];
    if (!cm) return;
    cm.ports.forEach((p) => {
      rows.push({ inst: c.name, type: c.type, port: p.name, dir: p.dir, bundle: p.bundle, doc: p.doc });
    });
  });
  return (
    <table style={{ width: '100%', borderCollapse: 'collapse', fontFamily: benchTokens.mono, fontSize: 11 }}>
      <thead>
        <tr style={{ background: benchTokens.panelAlt }}>
          {['Instance', 'Type', 'Port', 'Dir', 'Bundle', 'Width', 'Notes'].map((h) => (
            <th key={h} style={{ textAlign: 'left', padding: '7px 12px', fontSize: 10, color: benchTokens.ink3, fontWeight: 600, textTransform: 'uppercase', letterSpacing: 0.6, borderBottom: `1px solid ${benchTokens.borderSoft}` }}>{h}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((r, i) => (
          <tr key={i}
            onClick={() => setSelectedSig(`${r.inst}.${r.port}`)}
            style={{ cursor: 'pointer', borderBottom: `1px solid ${benchTokens.borderSoft}` }}
            onMouseEnter={(e) => e.currentTarget.style.background = benchTokens.panelAlt}
            onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}
          >
            <td style={{ padding: '6px 12px', fontWeight: 600, color: benchTokens.ink }}>{r.inst}</td>
            <td style={{ padding: '6px 12px', color: benchTokens.ink3 }}>{r.type}</td>
            <td style={{ padding: '6px 12px', color: benchTokens.ink2 }}>{r.port}</td>
            <td style={{ padding: '6px 12px' }}>
              <span style={{ padding: '1px 6px', borderRadius: 2, background: r.dir === 'master' ? 'oklch(0.96 0.04 250)' : 'oklch(0.96 0.04 50)', color: r.dir === 'master' ? 'oklch(0.4 0.15 250)' : 'oklch(0.4 0.15 50)', fontSize: 10 }}>{r.dir}</span>
            </td>
            <td style={{ padding: '6px 12px', color: benchTokens.ink2 }}>{r.bundle}</td>
            <td style={{ padding: '6px 12px', color: benchTokens.ink3 }}>{bTotalWidth(r.bundle)}b</td>
            <td style={{ padding: '6px 12px', color: benchTokens.ink4, fontFamily: benchTokens.sans, fontSize: 11 }}>{r.doc}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

window.BenchApp = BenchApp;
