// Atlas — bold editorial "interactive datasheet" view of a Chisel design.
// The metaphor: hardware exploration as a *document*. Vertical-flow netlist
// (masters above, slaves below) with bundles rendered as colored channels;
// surrounding marginalia replace traditional sidebar panels. 1440×900.

const { useState: aUseState, useMemo: aUseMemo, useRef: aUseRef, useEffect: aUseEffect, useCallback: aUseCallback } = React;
const { MODULES: A_MODULES, BUNDLES: A_BUNDLES, WAVES: A_WAVES, WAVE_CYCLES: A_WAVE_CYCLES, CHANNEL_COLOR: A_CHAN, bundleFields: aBundleFields, totalWidth: aTotalWidth } = window.DESIGN;

const atlasTokens = {
  paper:    '#f3ede1',
  paperDk:  '#ebe4d4',
  ink:      '#1c1816',
  ink2:     '#3a342c',
  ink3:     '#6a6258',
  ink4:     '#9c9384',
  rule:     '#cdc5b3',
  ruleSoft: '#dcd5c2',
  accent:   'oklch(0.62 0.18 50)',    // signal-orange
  accentDk: 'oklch(0.45 0.15 45)',
  accentBg: 'oklch(0.92 0.06 70)',
  shadow:   'rgba(60, 45, 25, 0.08)',

  serif:    "'Newsreader', 'Charter', 'Iowan Old Style', Georgia, serif",
  sans:     "Inter, -apple-system, system-ui, sans-serif",
  mono:     "'JetBrains Mono', 'IBM Plex Mono', ui-monospace, monospace",
};

// Roman numerals for chapter / section labels
const ROMAN = ['Ⅰ','Ⅱ','Ⅲ','Ⅳ','Ⅴ','Ⅵ','Ⅶ','Ⅷ','Ⅸ','Ⅹ'];

function aGetModuleByPath(path) {
  let mod = A_MODULES[path[0]];
  for (let i = 1; i < path.length; i++) {
    const child = mod.children.find((c) => c.name === path[i]);
    if (!child) return mod;
    mod = A_MODULES[child.type] || mod;
  }
  return mod;
}

// ─── App root ────────────────────────────────────────────────────────────────
function AtlasApp({ tweaks }) {
  const [path, setPath] = aUseState(['AXISystem']);
  const [selectedSig, setSelectedSig] = aUseState(null);
  const [hoveredSig, setHoveredSig] = aUseState(null);
  const [expandedBundles, setExpandedBundles] = aUseState(new Set());
  const [rightTab, setRightTab] = aUseState('bundle'); // bundle | source | wave | ports

  const mod = aGetModuleByPath(path);

  const drillInto = aUseCallback((inst) => {
    const child = mod.children.find((c) => c.name === inst);
    if (!child) return;
    if (!A_MODULES[child.type]) return;
    setPath((p) => [...p, inst]);
    setSelectedSig(null);
  }, [mod]);

  const toggleBundle = aUseCallback((bpath) => {
    setExpandedBundles((s) => { const n = new Set(s); if (n.has(bpath)) n.delete(bpath); else n.add(bpath); return n; });
  }, []);

  // When a wire is selected, default the right column to "bundle"
  aUseEffect(() => { if (selectedSig) setRightTab('bundle'); }, [selectedSig]);

  return (
    <div style={{
      width: '100%', height: '100%', background: atlasTokens.paper,
      color: atlasTokens.ink, fontFamily: atlasTokens.serif,
      display: 'flex', overflow: 'hidden', position: 'relative',
    }}>
      {/* paper texture — subtle horizontal grain */}
      <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none', opacity: 0.4, mixBlendMode: 'multiply',
        backgroundImage: `repeating-linear-gradient(0deg, transparent 0 23px, rgba(60,45,25,0.025) 23px 24px)` }} />

      <AtlasChapterRail path={path} setPath={setPath} />

      <div style={{ flex: 1, display: 'flex', minWidth: 0, position: 'relative', zIndex: 1 }}>
        <div style={{ flex: '1 1 auto', minWidth: 0, padding: '24px 0 24px 0', overflow: 'auto', display: 'flex', flexDirection: 'column' }}>
          <AtlasHeader mod={mod} path={path} setPath={setPath} />
          <AtlasNetlist
            mod={mod} path={path}
            selectedSig={selectedSig} setSelectedSig={setSelectedSig}
            hoveredSig={hoveredSig} setHoveredSig={setHoveredSig}
            expandedBundles={expandedBundles} toggleBundle={toggleBundle}
            drillInto={drillInto}
            tweaks={tweaks}
          />
          <AtlasFooter mod={mod} />
        </div>

        <AtlasRightColumn
          mod={mod} tab={rightTab} setTab={setRightTab}
          selectedSig={selectedSig} setSelectedSig={setSelectedSig}
          expandedBundles={expandedBundles} toggleBundle={toggleBundle}
        />
      </div>
    </div>
  );
}

// ─── Chapter rail (far left) ─────────────────────────────────────────────────
// Numbered vertical strip. Roman numerals as chapter labels. Active chapter
// gets a heavy black square. This is the hierarchy tree, reimagined.
function AtlasChapterRail({ path, setPath }) {
  const root = A_MODULES.AXISystem;
  const chapters = [
    { id: ['AXISystem'], label: 'System', type: 'AXISystem' },
    ...root.children.map((c) => ({ id: ['AXISystem', c.name], label: c.name, type: c.type })),
  ];
  const activeKey = path.join('/');
  return (
    <div style={{
      width: 72, flexShrink: 0,
      borderRight: `1px solid ${atlasTokens.rule}`,
      padding: '22px 0', display: 'flex', flexDirection: 'column',
      background: atlasTokens.paperDk, position: 'relative', zIndex: 2,
    }}>
      <div style={{
        writingMode: 'vertical-rl', transform: 'rotate(180deg)',
        fontFamily: atlasTokens.serif, fontStyle: 'italic',
        fontSize: 11, color: atlasTokens.ink4, letterSpacing: 2,
        padding: '0 0 16px', textAlign: 'center', textTransform: 'uppercase',
      }}>Chapters · Modules</div>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
        {chapters.map((ch, i) => {
          const key = ch.id.join('/');
          const active = key === activeKey;
          return (
            <button key={key} onClick={() => setPath(ch.id)}
              style={{
                width: '100%', border: 'none', background: 'transparent', cursor: 'pointer',
                padding: '8px 0', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4,
                position: 'relative',
              }}>
              <span style={{
                fontFamily: atlasTokens.serif, fontSize: 14, fontWeight: 500,
                color: active ? atlasTokens.ink : atlasTokens.ink4,
              }}>{ROMAN[i]}</span>
              <span style={{
                fontFamily: atlasTokens.mono, fontSize: 9, letterSpacing: 0.4,
                color: active ? atlasTokens.ink : atlasTokens.ink3,
                textTransform: 'uppercase', maxWidth: 60, overflow: 'hidden', textOverflow: 'ellipsis',
              }}>{ch.label}</span>
              {active && <span style={{ position: 'absolute', right: -1, top: 14, bottom: 14, width: 3, background: atlasTokens.ink }} />}
            </button>
          );
        })}
      </div>
      <div style={{ padding: '12px 0 0', textAlign: 'center', fontFamily: atlasTokens.mono, fontSize: 9, color: atlasTokens.ink4, letterSpacing: 0.6 }}>
        S/{chapters.length.toString().padStart(2,'0')}
      </div>
    </div>
  );
}

// ─── Top header (editorial title block) ──────────────────────────────────────
function AtlasHeader({ mod, path, setPath }) {
  return (
    <div style={{ padding: '0 56px 16px', position: 'relative' }}>
      {/* Eyebrow: file + breadcrumb */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, fontFamily: atlasTokens.mono, fontSize: 10.5, color: atlasTokens.ink3, letterSpacing: 0.6, marginBottom: 14, textTransform: 'uppercase' }}>
        <span style={{ color: atlasTokens.accent, fontWeight: 600 }}>SoCLess Atlas</span>
        <span>·</span>
        <span>{mod.source.file}</span>
        <span>·</span>
        <span>Rev 04 / 26</span>
        <span style={{ marginLeft: 'auto', display: 'flex', gap: 6, color: atlasTokens.ink4 }}>
          {path.map((p, i) => (
            <span key={i}>
              {i > 0 && <span style={{ margin: '0 6px' }}>↳</span>}
              <button onClick={() => setPath(path.slice(0, i + 1))} style={{ border: 'none', background: 'transparent', color: i === path.length - 1 ? atlasTokens.ink : atlasTokens.ink3, fontFamily: 'inherit', fontSize: 'inherit', letterSpacing: 'inherit', cursor: 'pointer', padding: 0, textTransform: 'inherit' }}>{p}</button>
            </span>
          ))}
        </span>
      </div>
      {/* Rule */}
      <div style={{ height: 3, background: atlasTokens.ink, marginBottom: 18 }} />
      {/* Title row */}
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 28 }}>
        <div style={{
          fontFamily: atlasTokens.serif, fontWeight: 600, fontSize: 64, lineHeight: 0.95,
          letterSpacing: -1.5, color: atlasTokens.ink,
        }}>{mod.type}</div>
        <div style={{ flex: 1, paddingBottom: 6 }}>
          <div style={{ fontFamily: atlasTokens.serif, fontStyle: 'italic', fontSize: 16, color: atlasTokens.ink2, maxWidth: 460, lineHeight: 1.35 }}>
            {mod.doc}
          </div>
        </div>
        {/* Stat block */}
        <div style={{ display: 'flex', gap: 26, paddingBottom: 6 }}>
          <AtlasStat label="Gates" value={mod.metrics.gates.toLocaleString()} />
          <AtlasStat label="Reg" value={mod.metrics.registers} />
          <AtlasStat label="Inst" value={mod.children.length} />
          <AtlasStat label="Wires" value={mod.wires.length} />
        </div>
      </div>
    </div>
  );
}

function AtlasStat({ label, value }) {
  return (
    <div style={{ textAlign: 'right' }}>
      <div style={{ fontFamily: atlasTokens.serif, fontSize: 28, fontWeight: 500, color: atlasTokens.ink, lineHeight: 1, fontVariantNumeric: 'tabular-nums' }}>{value}</div>
      <div style={{ fontFamily: atlasTokens.mono, fontSize: 9.5, color: atlasTokens.ink3, letterSpacing: 0.6, textTransform: 'uppercase', marginTop: 4 }}>{label}</div>
    </div>
  );
}

// ─── Netlist plate — vertical flow diagram ───────────────────────────────────
// Top tier: master modules. Middle: fabric. Bottom: slave modules. Bundles are
// rendered as fat vertical colored ribbons between tiers, with hand-set
// labels in marginalia style.
function AtlasNetlist({ mod, path, selectedSig, setSelectedSig, hoveredSig, setHoveredSig, expandedBundles, toggleBundle, drillInto, tweaks }) {
  // Tier assignment for top-level AXI system
  const tiers = aUseMemo(() => {
    if (path.length === 1) {
      return [
        { label: 'Masters', insts: ['cpu', 'dma'] },
        { label: 'Fabric',  insts: ['xbar'] },
        { label: 'Slaves',  insts: ['mem', 'periph'] },
      ].map((t) => ({ ...t, insts: t.insts.filter((n) => mod.children.find((c) => c.name === n)) }));
    }
    // generic: pack into one tier
    return [{ label: 'Submodules', insts: mod.children.map((c) => c.name) }];
  }, [mod, path]);

  // Layout constants
  const stationW = 220;
  const stationH = 76;
  const tierGap = 72; // vertical gap between tiers (where bundles flow)
  const stationGap = 56;
  const padding = 56;

  // Compute station positions
  const positions = aUseMemo(() => {
    const out = {}; let y = 0;
    const tierLayouts = tiers.map((tier) => {
      const tierW = tier.insts.length * stationW + (tier.insts.length - 1) * stationGap;
      const tierStart = -tierW / 2;
      tier.insts.forEach((inst, i) => {
        out[inst] = { x: tierStart + i * (stationW + stationGap), y, w: stationW, h: stationH };
      });
      const cur = { y, tierW };
      y += stationH + tierGap;
      return cur;
    });
    const totalH = y - tierGap;
    return { out, totalH };
  }, [tiers]);

  // Wires, with start/end station coords (bottom-center to top-center)
  const wires = aUseMemo(() => {
    return mod.wires.map((w, i) => {
      const fromInst = w.from.split('.')[0];
      const toInst   = w.to.split('.')[0];
      const a = positions.out[fromInst];
      const b = positions.out[toInst];
      if (!a || !b) return null;
      // Spread connection X within station based on wire index relative to siblings
      const ax = a.x + a.w / 2;
      const bx = b.x + b.w / 2;
      // Connect bottom of upper station to top of lower station
      const upper = a.y < b.y ? a : b;
      const lower = a.y < b.y ? b : a;
      const upperInst = a.y < b.y ? fromInst : toInst;
      const lowerInst = a.y < b.y ? toInst : fromInst;
      return {
        id: `w${i}`, ...w,
        fromInst, toInst,
        upperInst, lowerInst,
        x1: a.y < b.y ? ax : bx, y1: upper.y + upper.h,
        x2: a.y < b.y ? bx : ax, y2: lower.y,
        bundle: w.bundle,
      };
    }).filter(Boolean);
  }, [mod, positions]);

  const isHighlighted = (w) => selectedSig && (w.from.startsWith(selectedSig) || w.to.startsWith(selectedSig) || selectedSig.startsWith(w.from) || selectedSig.startsWith(w.to));
  const isHovered = (w) => hoveredSig && (w.from.startsWith(hoveredSig) || w.to.startsWith(hoveredSig));

  const plateW = 980;
  const offsetX = plateW / 2;

  return (
    <div style={{ padding: '0 56px 24px', flex: 1, display: 'flex', flexDirection: 'column' }}>
      {/* Plate caption */}
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 12 }}>
        <span style={{ fontFamily: atlasTokens.serif, fontStyle: 'italic', fontSize: 13, color: atlasTokens.ink3 }}>Plate Ⅰ</span>
        <span style={{ fontFamily: atlasTokens.serif, fontSize: 15, color: atlasTokens.ink, fontWeight: 500 }}>Netlist — top-level connectivity.</span>
        <span style={{ marginLeft: 'auto', fontFamily: atlasTokens.mono, fontSize: 10, color: atlasTokens.ink3, letterSpacing: 0.4 }}>
          { tweaks.showWidths ? 'widths: shown' : 'widths: hidden' } · {wires.length} bundles
        </span>
      </div>
      <div style={{
        background: '#fdfaf2', border: `1px solid ${atlasTokens.rule}`,
        padding: '36px 24px', position: 'relative',
        boxShadow: `0 2px 12px ${atlasTokens.shadow}`,
        flex: '0 0 auto',
      }}>
        <div style={{ position: 'relative', width: plateW, height: positions.totalH, margin: '0 auto' }}>
          {/* tier labels (left side, vertical) */}
          {tiers.map((tier) => {
            const firstInst = tier.insts[0];
            const pos = positions.out[firstInst];
            if (!pos) return null;
            return (
              <div key={tier.label} style={{
                position: 'absolute', left: -38, top: pos.y + stationH / 2 - 8,
                fontFamily: atlasTokens.serif, fontStyle: 'italic',
                fontSize: 13, color: atlasTokens.ink3,
                writingMode: 'vertical-rl', transform: 'rotate(180deg)', letterSpacing: 1,
              }}>{tier.label}</div>
            );
          })}

          {/* Bundles */}
          <svg style={{ position: 'absolute', inset: 0, width: '100%', height: positions.totalH, pointerEvents: 'none', overflow: 'visible' }}>
            <defs>
              {Object.entries(A_CHAN).map(([k, v]) => (
                <linearGradient key={k} id={`atlas-grad-${k}`} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={v} stopOpacity="0.95" />
                  <stop offset="100%" stopColor={v} stopOpacity="0.7" />
                </linearGradient>
              ))}
            </defs>
            {wires.map((w) => {
              const hl = isHighlighted(w);
              const hov = isHovered(w);
              const dim = selectedSig && !hl;
              const expanded = expandedBundles.has(w.from);
              const ribbonW = expanded ? 38 : 20;
              const mx = (w.x1 + w.x2) / 2 + offsetX;
              const x1 = w.x1 + offsetX;
              const x2 = w.x2 + offsetX;
              // S-curve from (x1, y1) to (x2, y2)
              const cy1 = w.y1 + (w.y2 - w.y1) * 0.4;
              const cy2 = w.y1 + (w.y2 - w.y1) * 0.6;

              return (
                <g key={w.id} style={{ opacity: dim ? 0.28 : 1, transition: 'opacity .2s' }}>
                  {!expanded ? (
                    <>
                      {/* Channel ribbon — single thick stroke per bundle */}
                      <path
                        d={`M ${x1} ${w.y1} C ${x1} ${cy1}, ${x2} ${cy2}, ${x2} ${w.y2}`}
                        stroke={hl || hov ? atlasTokens.accent : atlasTokens.ink}
                        strokeWidth={ribbonW + 6}
                        fill="none" strokeLinecap="round"
                        opacity={0.06}
                      />
                      <path
                        d={`M ${x1} ${w.y1} C ${x1} ${cy1}, ${x2} ${cy2}, ${x2} ${w.y2}`}
                        stroke={hl || hov ? atlasTokens.accent : atlasTokens.ink}
                        strokeWidth={ribbonW}
                        fill="none" strokeLinecap="round"
                      />
                      {/* tick marks along the ribbon */}
                      {[0.25, 0.5, 0.75].map((t) => {
                        const ty = w.y1 + (w.y2 - w.y1) * t;
                        const tx = x1 + (x2 - x1) * t;
                        return <circle key={t} cx={tx} cy={ty} r={1.8} fill={atlasTokens.paper} />;
                      })}
                    </>
                  ) : (
                    A_BUNDLES.AXI4.channels.map((ch, ci) => {
                      const off = (ci - 2) * 7;
                      const cx1 = x1 + off;
                      const cx2 = x2 + off;
                      return (
                        <path key={ch.name}
                          d={`M ${cx1} ${w.y1} C ${cx1} ${cy1}, ${cx2} ${cy2}, ${cx2} ${w.y2}`}
                          stroke={dim ? atlasTokens.ink4 : A_CHAN[ch.name]}
                          strokeWidth={4} fill="none" strokeLinecap="round"
                          opacity={dim ? 0.4 : 0.9}
                        />
                      );
                    })
                  )}

                  {/* hit target */}
                  <path d={`M ${x1} ${w.y1} C ${x1} ${cy1}, ${x2} ${cy2}, ${x2} ${w.y2}`}
                    stroke="transparent" strokeWidth={28} fill="none"
                    style={{ pointerEvents: 'stroke', cursor: 'pointer' }}
                    onClick={() => setSelectedSig(w.from)}
                    onMouseEnter={() => setHoveredSig(w.from)}
                    onMouseLeave={() => setHoveredSig(null)}
                  />

                  {/* Marginalia label — italic serif w/ hand-set position */}
                  <g transform={`translate(${mx + 22}, ${(w.y1 + w.y2) / 2})`}>
                    <line x1={-22} y1={0} x2={-6} y2={0} stroke={atlasTokens.ink4} strokeWidth={0.6} />
                    <text x={0} y={-2} fontFamily={atlasTokens.serif} fontStyle="italic" fontSize={11.5} fill={atlasTokens.ink2}>{w.label}</text>
                    {tweaks.showWidths && (
                      <text x={0} y={12} fontFamily={atlasTokens.mono} fontSize={9.5} fill={atlasTokens.ink3} letterSpacing={0.4}>
                        {w.bundle}·{aTotalWidth(w.bundle)}b
                      </text>
                    )}
                  </g>

                  {/* Bundle expand toggle */}
                  <g transform={`translate(${mx - 36}, ${(w.y1 + w.y2) / 2 - 8})`}
                     onClick={() => toggleBundle(w.from)}
                     style={{ cursor: 'pointer', pointerEvents: 'all' }}>
                    <rect x={-16} y={-8} width={32} height={16} fill={atlasTokens.paper} stroke={atlasTokens.ink2} strokeWidth={0.8} />
                    <text x={0} y={4} textAnchor="middle" fontFamily={atlasTokens.mono} fontSize={9} fill={atlasTokens.ink}>
                      {expanded ? '◁ ⌐' : '5 ch ▷'}
                    </text>
                  </g>
                </g>
              );
            })}
          </svg>

          {/* Station blocks */}
          {Object.entries(positions.out).map(([inst, p]) => {
            const child = mod.children.find((c) => c.name === inst);
            if (!child) return null;
            const childMod = A_MODULES[child.type];
            const drillable = !!childMod && childMod.children.length > 0;
            const isSel = selectedSig && (selectedSig.startsWith(inst + '.') || selectedSig === inst);
            return (
              <div key={inst}
                onDoubleClick={() => drillable && drillInto(inst)}
                style={{
                  position: 'absolute',
                  left: p.x + offsetX, top: p.y, width: p.w, height: p.h,
                  background: isSel ? atlasTokens.ink : atlasTokens.paper,
                  color: isSel ? atlasTokens.paper : atlasTokens.ink,
                  border: `2px solid ${atlasTokens.ink}`,
                  cursor: drillable ? 'pointer' : 'default',
                  display: 'flex', flexDirection: 'column',
                  fontFamily: atlasTokens.serif,
                  position: 'absolute',
                  boxShadow: isSel ? `0 4px 0 ${atlasTokens.accent}` : 'none',
                  transition: 'background .12s, color .12s, box-shadow .12s',
                }}
              >
                <div style={{
                  padding: '5px 10px',
                  background: isSel ? atlasTokens.ink : atlasTokens.ink,
                  color: atlasTokens.paper,
                  display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                  fontFamily: atlasTokens.mono, fontSize: 9.5, letterSpacing: 0.8, textTransform: 'uppercase',
                }}>
                  <span>{child.type}</span>
                  <span style={{ opacity: 0.6 }}>{drillable ? `${childMod.children.length} inner` : 'leaf'}</span>
                </div>
                <div style={{ flex: 1, padding: '8px 12px', display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
                  <div style={{ fontFamily: atlasTokens.serif, fontWeight: 600, fontSize: 22, lineHeight: 1, letterSpacing: -0.4 }}>{inst}</div>
                  <div style={{ fontFamily: atlasTokens.serif, fontStyle: 'italic', fontSize: 11, color: isSel ? 'rgba(243,237,225,0.7)' : atlasTokens.ink3, lineHeight: 1.3 }}>{child.doc}</div>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

// ─── Footer — small print + ports summary ─────────────────────────────────────
function AtlasFooter({ mod }) {
  const portRows = [];
  mod.children.forEach((c) => {
    const cm = A_MODULES[c.type];
    if (!cm) return;
    cm.ports.forEach((p) => portRows.push({ inst: c.name, type: c.type, port: p.name, dir: p.dir, bundle: p.bundle }));
  });
  return (
    <div style={{ padding: '0 56px 32px' }}>
      <div style={{ marginTop: 12, display: 'flex', alignItems: 'baseline', gap: 12, paddingBottom: 8, borderBottom: `1.5px solid ${atlasTokens.ink}` }}>
        <span style={{ fontFamily: atlasTokens.serif, fontStyle: 'italic', fontSize: 13, color: atlasTokens.ink3 }}>Table Ⅰ</span>
        <span style={{ fontFamily: atlasTokens.serif, fontSize: 15, fontWeight: 500 }}>Instance ports.</span>
        <span style={{ marginLeft: 'auto', fontFamily: atlasTokens.mono, fontSize: 10, color: atlasTokens.ink3 }}>{portRows.length} entries</span>
      </div>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontFamily: atlasTokens.serif, fontSize: 13, marginTop: 6 }}>
        <tbody>
          {portRows.map((r, i) => (
            <tr key={i} style={{ borderBottom: `1px solid ${atlasTokens.ruleSoft}` }}>
              <td style={{ padding: '7px 0', width: 28, fontFamily: atlasTokens.mono, fontSize: 10, color: atlasTokens.ink4, fontVariantNumeric: 'tabular-nums' }}>{String(i + 1).padStart(2, '0')}</td>
              <td style={{ padding: '7px 14px 7px 0', fontWeight: 600, color: atlasTokens.ink }}>{r.inst}</td>
              <td style={{ padding: '7px 14px 7px 0', fontStyle: 'italic', color: atlasTokens.ink3 }}>{r.type}</td>
              <td style={{ padding: '7px 14px 7px 0', fontFamily: atlasTokens.mono, fontSize: 11, color: atlasTokens.ink2 }}>{r.port}</td>
              <td style={{ padding: '7px 14px 7px 0', fontFamily: atlasTokens.mono, fontSize: 10, color: atlasTokens.ink3, textTransform: 'uppercase', letterSpacing: 0.6 }}>{r.dir}</td>
              <td style={{ padding: '7px 14px 7px 0', fontFamily: atlasTokens.mono, fontSize: 11 }}>
                <span style={{ background: atlasTokens.accentBg, padding: '1px 6px', color: atlasTokens.accentDk }}>{r.bundle}</span>
              </td>
              <td style={{ padding: '7px 0', fontFamily: atlasTokens.mono, fontSize: 10, color: atlasTokens.ink3, textAlign: 'right' }}>{aTotalWidth(r.bundle)} b</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div style={{ marginTop: 18, fontFamily: atlasTokens.serif, fontStyle: 'italic', fontSize: 11, color: atlasTokens.ink4, textAlign: 'center' }}>
        — End of plate Ⅰ — turn page for §Ⅱ Source &amp; §Ⅲ Timing —
      </div>
    </div>
  );
}

// ─── Right column ────────────────────────────────────────────────────────────
function AtlasRightColumn({ mod, tab, setTab, selectedSig, setSelectedSig, expandedBundles, toggleBundle }) {
  const tabs = [
    { id: 'bundle', label: 'Bundle',   roman: 'Ⅱ' },
    { id: 'source', label: 'Source',   roman: 'Ⅲ' },
    { id: 'wave',   label: 'Timing',   roman: 'Ⅳ' },
  ];
  return (
    <aside style={{
      width: 420, flexShrink: 0,
      borderLeft: `1.5px solid ${atlasTokens.ink}`,
      background: atlasTokens.paperDk,
      display: 'flex', flexDirection: 'column',
      overflow: 'hidden',
    }}>
      <div style={{ display: 'flex', borderBottom: `1px solid ${atlasTokens.rule}` }}>
        {tabs.map((t) => (
          <button key={t.id} onClick={() => setTab(t.id)}
            style={{
              flex: 1, border: 'none', cursor: 'pointer', padding: '14px 8px 12px',
              background: tab === t.id ? atlasTokens.ink : 'transparent',
              color: tab === t.id ? atlasTokens.paper : atlasTokens.ink,
              fontFamily: atlasTokens.serif, fontSize: 14, fontWeight: 500,
              display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3,
              borderRight: `1px solid ${atlasTokens.rule}`,
            }}>
            <span style={{ fontStyle: 'italic', fontSize: 11, opacity: 0.7 }}>{t.roman}</span>
            <span>{t.label}</span>
          </button>
        ))}
      </div>
      <div style={{ flex: 1, overflow: 'auto' }}>
        {tab === 'bundle' && <AtlasBundleSheet mod={mod} selectedSig={selectedSig} setSelectedSig={setSelectedSig} expandedBundles={expandedBundles} toggleBundle={toggleBundle} />}
        {tab === 'source' && <AtlasSourceSheet mod={mod} selectedSig={selectedSig} />}
        {tab === 'wave'   && <AtlasTimingSheet selectedSig={selectedSig} setSelectedSig={setSelectedSig} />}
      </div>
    </aside>
  );
}

function AtlasBundleSheet({ mod, selectedSig, setSelectedSig, expandedBundles, toggleBundle }) {
  // If a signal is selected, focus on that wire's bundle. Otherwise, list all.
  const focusWire = selectedSig ? mod.wires.find((w) => w.from === selectedSig || w.to === selectedSig || selectedSig.startsWith(w.from + '.') || selectedSig.startsWith(w.to + '.')) : null;

  if (focusWire) {
    return <AtlasBundleDetail wire={focusWire} setSelectedSig={setSelectedSig} sig={selectedSig} />;
  }

  return (
    <div style={{ padding: '20px 22px' }}>
      <div style={{ fontFamily: atlasTokens.serif, fontStyle: 'italic', fontSize: 12, color: atlasTokens.ink3, marginBottom: 4 }}>§ Ⅱ</div>
      <div style={{ fontFamily: atlasTokens.serif, fontSize: 22, fontWeight: 500, color: atlasTokens.ink, lineHeight: 1.1, marginBottom: 12, letterSpacing: -0.3 }}>
        Bundle catalogue
      </div>
      <div style={{ fontFamily: atlasTokens.serif, fontSize: 12, color: atlasTokens.ink3, fontStyle: 'italic', marginBottom: 18, lineHeight: 1.5 }}>
        Select a channel in <em>Plate Ⅰ</em> to inspect its fields; or browse the catalogue of bundles defined in this scope.
      </div>
      {Object.entries(A_BUNDLES).map(([name, b]) => (
        <div key={name} style={{ marginBottom: 14, paddingBottom: 14, borderBottom: `1px solid ${atlasTokens.ruleSoft}` }}>
          <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between' }}>
            <span style={{ fontFamily: atlasTokens.mono, fontSize: 13, fontWeight: 600, color: atlasTokens.ink }}>{name}</span>
            <span style={{ fontFamily: atlasTokens.serif, fontStyle: 'italic', fontSize: 11, color: atlasTokens.ink3 }}>{b.kind}</span>
          </div>
          <div style={{ marginTop: 6, fontFamily: atlasTokens.mono, fontSize: 10, color: atlasTokens.ink3, lineHeight: 1.6 }}>
            {b.kind === 'Decoupled' && b.fields.map(([n, w, d]) => (
              <div key={n} style={{ display: 'flex', justifyContent: 'space-between' }}><span>{n}</span><span>{w}b</span></div>
            ))}
            {b.kind === 'Bundle' && b.channels.map((ch) => (
              <div key={ch.name} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <span style={{ width: 6, height: 6, background: A_CHAN[ch.name] }} />
                  {ch.flip ? 'Flipped(' : ''}{ch.name}{ch.flip ? ')' : ''}
                </span>
                <span>{ch.type}</span>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

function AtlasBundleDetail({ wire, setSelectedSig, sig }) {
  const fields = aBundleFields(wire.bundle);
  const channels = aUseMemo(() => {
    const m = {}; for (const f of fields) { const ch = f.channel || '_'; (m[ch] = m[ch] || []).push(f); } return m;
  }, [fields]);
  return (
    <div style={{ padding: '20px 22px' }}>
      <div style={{ fontFamily: atlasTokens.serif, fontStyle: 'italic', fontSize: 12, color: atlasTokens.ink3, marginBottom: 4 }}>§ Ⅱ · selected channel</div>
      <div style={{ fontFamily: atlasTokens.serif, fontSize: 26, fontWeight: 500, color: atlasTokens.ink, lineHeight: 1.05, marginBottom: 4, letterSpacing: -0.4 }}>
        {wire.bundle}
      </div>
      <div style={{ fontFamily: atlasTokens.mono, fontSize: 11, color: atlasTokens.ink3, marginBottom: 18 }}>
        {wire.from} <span style={{ color: atlasTokens.ink4 }}>↦</span> {wire.to}
      </div>
      {Object.entries(channels).map(([ch, fs]) => (
        <div key={ch} style={{ marginBottom: 14 }}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 6, paddingBottom: 4, borderBottom: `1px solid ${atlasTokens.ink}` }}>
            <span style={{ width: 8, height: 8, background: A_CHAN[ch] || atlasTokens.ink, marginTop: 4 }} />
            <span style={{ fontFamily: atlasTokens.serif, fontSize: 17, fontWeight: 600, color: atlasTokens.ink }}>{ch}</span>
            <span style={{ fontFamily: atlasTokens.serif, fontStyle: 'italic', fontSize: 11, color: atlasTokens.ink3 }}>
              {ch === 'ar' ? 'address-read' : ch === 'aw' ? 'address-write' : ch === 'w' ? 'write-data' : ch === 'r' ? 'read-data' : ch === 'b' ? 'write-resp' : 'channel'}
            </span>
            <span style={{ marginLeft: 'auto', fontFamily: atlasTokens.mono, fontSize: 10, color: atlasTokens.ink3 }}>{fs.length} sig</span>
          </div>
          <div style={{ fontFamily: atlasTokens.mono, fontSize: 11, lineHeight: 1.7 }}>
            {fs.map((f) => {
              const full = `${wire.from}.${ch}.${f.name}`;
              const isSel = sig === full;
              return (
                <div key={f.name}
                  onClick={() => setSelectedSig(full)}
                  style={{
                    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                    padding: '2px 6px', cursor: 'pointer',
                    background: isSel ? atlasTokens.accentBg : 'transparent',
                    color: isSel ? atlasTokens.accentDk : atlasTokens.ink2,
                  }}>
                  <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <span style={{ color: atlasTokens.ink4, width: 10, display: 'inline-block', textAlign: 'center' }}>{f.dir === 'out' ? '↓' : '↑'}</span>
                    {f.name}
                  </span>
                  <span style={{ color: atlasTokens.ink3, fontVariantNumeric: 'tabular-nums' }}>
                    {f.width === 1 ? 'Bool' : `UInt⟨${f.width}⟩`}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
}

function AtlasSourceSheet({ mod, selectedSig }) {
  const code = mod.source.code.split('\n');
  const selInst = selectedSig ? selectedSig.split('.')[0] : null;
  return (
    <div style={{ padding: '20px 22px' }}>
      <div style={{ fontFamily: atlasTokens.serif, fontStyle: 'italic', fontSize: 12, color: atlasTokens.ink3, marginBottom: 4 }}>§ Ⅲ · transcription</div>
      <div style={{ fontFamily: atlasTokens.serif, fontSize: 22, fontWeight: 500, color: atlasTokens.ink, lineHeight: 1.1, marginBottom: 4, letterSpacing: -0.3 }}>{mod.source.file}</div>
      <div style={{ fontFamily: atlasTokens.serif, fontStyle: 'italic', fontSize: 12, color: atlasTokens.ink3, marginBottom: 16 }}>{code.length} lines · Scala / Chisel</div>
      <div style={{ fontFamily: atlasTokens.mono, fontSize: 11.5, lineHeight: 1.65, color: atlasTokens.ink2, background: '#fdfaf2', padding: '14px 16px', border: `1px solid ${atlasTokens.rule}` }}>
        {code.map((line, i) => {
          const isMatch = selInst && line.indexOf(selInst) >= 0;
          return (
            <div key={i} style={{ display: 'flex', gap: 14, background: isMatch ? atlasTokens.accentBg : 'transparent', marginLeft: -16, marginRight: -16, padding: '0 16px' }}>
              <span style={{ width: 22, color: atlasTokens.ink4, textAlign: 'right', fontVariantNumeric: 'tabular-nums', fontSize: 10, userSelect: 'none' }}>{i + 1}</span>
              <span style={{ whiteSpace: 'pre', flex: 1 }}>{atlasTokenize(line)}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function atlasTokenize(line) {
  const parts = line.split(/(\s+|[(){}.,;])/);
  return parts.map((p, i) => {
    if (!p) return null;
    if (/^\s+$/.test(p) || /^[(){}.,;]$/.test(p)) return <span key={i}>{p}</span>;
    if (/^(class|extends|val|def|new|for|until|true|false)$/.test(p))
      return <span key={i} style={{ color: atlasTokens.accentDk, fontWeight: 600 }}>{p}</span>;
    if (/^(Module|IO|Bundle|Vec|Flipped|Queue|RRArbiter|AddrDec|RRouter|Backend|IdAllocator|AXI4|AXIMaster|AXISlave|AXISystem|AXICrossbar|TxReq|AR|R)$/.test(p))
      return <span key={i} style={{ color: atlasTokens.ink, fontWeight: 600 }}>{p}</span>;
    if (/^\d+$/.test(p))
      return <span key={i} style={{ color: atlasTokens.accent }}>{p}</span>;
    if (/^\/\//.test(p))
      return <span key={i} style={{ color: atlasTokens.ink4, fontStyle: 'italic', fontFamily: atlasTokens.serif }}>{p}</span>;
    return <span key={i}>{p}</span>;
  });
}

function AtlasTimingSheet({ selectedSig, setSelectedSig }) {
  const cellW = 14;
  return (
    <div style={{ padding: '20px 22px' }}>
      <div style={{ fontFamily: atlasTokens.serif, fontStyle: 'italic', fontSize: 12, color: atlasTokens.ink3, marginBottom: 4 }}>§ Ⅳ · figure</div>
      <div style={{ fontFamily: atlasTokens.serif, fontSize: 22, fontWeight: 500, color: atlasTokens.ink, lineHeight: 1.1, marginBottom: 4, letterSpacing: -0.3 }}>Timing impressions</div>
      <div style={{ fontFamily: atlasTokens.serif, fontStyle: 'italic', fontSize: 12, color: atlasTokens.ink3, marginBottom: 14 }}>
        {A_WAVE_CYCLES} cycles · 10 ns each · simulation trace ‘cpu-burst.vcd’
      </div>
      <div style={{ background: '#fdfaf2', border: `1px solid ${atlasTokens.rule}`, padding: '10px 0' }}>
        {A_WAVES.map((tr) => {
          const sel = selectedSig === tr.path;
          return (
            <div key={tr.path} style={{ padding: '4px 12px', background: sel ? atlasTokens.accentBg : 'transparent', cursor: 'pointer' }}
              onClick={() => setSelectedSig(tr.path)}>
              <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, marginBottom: 2 }}>
                <span style={{ fontFamily: atlasTokens.serif, fontStyle: 'italic', fontSize: 10, color: atlasTokens.ink3, width: 60 }}>{tr.type}</span>
                <span style={{ fontFamily: atlasTokens.mono, fontSize: 10.5, color: sel ? atlasTokens.accentDk : atlasTokens.ink2, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{tr.path}</span>
              </div>
              <div style={{ display: 'flex', height: 14 }}>
                {tr.values.map((v, i) => {
                  const isBool = tr.type === 'Bool';
                  if (isBool) {
                    const on = v === '1';
                    return <div key={i} style={{ width: cellW, height: '100%', background: on ? atlasTokens.ink : 'transparent', borderBottom: `1px solid ${on ? atlasTokens.ink : atlasTokens.ink3}` }} />;
                  } else {
                    return <div key={i} style={{ width: cellW, height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: atlasTokens.mono, fontSize: 7.5, color: v === '–' ? atlasTokens.ink4 : atlasTokens.ink, borderBottom: `1px solid ${atlasTokens.ruleSoft}`, overflow: 'hidden' }}>{v === '–' ? '·' : v.slice(-2)}</div>;
                  }
                })}
              </div>
            </div>
          );
        })}
      </div>
      <div style={{ marginTop: 8, display: 'flex', justifyContent: 'space-between', fontFamily: atlasTokens.mono, fontSize: 9, color: atlasTokens.ink4 }}>
        <span>t=0 ns</span>
        <span>t={A_WAVE_CYCLES * 10} ns</span>
      </div>
    </div>
  );
}

window.AtlasApp = AtlasApp;
