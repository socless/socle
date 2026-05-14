// AXI4 interconnect example — module hierarchy, bundles, connections, source.
// Shape mirrors what a Chisel elaboration would expose: every module has typed
// ports (each a Bundle with named fields), submodule instances, and a list of
// connections at this scope. Used by both Bench and Atlas variations.

// ─── Bundle definitions ──────────────────────────────────────────────────────
// Each entry is a flat field list: [name, width, dir]. dir is from the
// producer's perspective (out = driven by this side, in = consumed).
// `flip` reverses every dir when used in a port (Chisel Flipped semantics).

const BUNDLES = {
  // AXI4 channels — each Decoupled[Channel] becomes valid/ready/bits.
  AXI4_AR: {
    kind: 'Decoupled',
    fields: [
      ['id',    4, 'out'],
      ['addr', 32, 'out'],
      ['len',   8, 'out'],
      ['size',  3, 'out'],
      ['burst', 2, 'out'],
      ['cache', 4, 'out'],
      ['prot',  3, 'out'],
    ],
  },
  AXI4_AW: {
    kind: 'Decoupled',
    fields: [
      ['id',    4, 'out'],
      ['addr', 32, 'out'],
      ['len',   8, 'out'],
      ['size',  3, 'out'],
      ['burst', 2, 'out'],
      ['cache', 4, 'out'],
      ['prot',  3, 'out'],
    ],
  },
  AXI4_W: {
    kind: 'Decoupled',
    fields: [
      ['data', 64, 'out'],
      ['strb',  8, 'out'],
      ['last',  1, 'out'],
    ],
  },
  AXI4_R: {
    kind: 'Decoupled',
    fields: [
      ['id',    4, 'out'],
      ['data', 64, 'out'],
      ['resp',  2, 'out'],
      ['last',  1, 'out'],
    ],
  },
  AXI4_B: {
    kind: 'Decoupled',
    fields: [
      ['id',   4, 'out'],
      ['resp', 2, 'out'],
    ],
  },
  // AXI4 = aggregate of all five channels. `flip` is applied per-channel
  // since R/B flow slave→master while AR/AW/W flow master→slave.
  AXI4: {
    kind: 'Bundle',
    channels: [
      { name: 'ar', type: 'AXI4_AR', flip: false },
      { name: 'aw', type: 'AXI4_AW', flip: false },
      { name: 'w',  type: 'AXI4_W',  flip: false },
      { name: 'r',  type: 'AXI4_R',  flip: true },
      { name: 'b',  type: 'AXI4_B',  flip: true },
    ],
  },
};

// ─── Module hierarchy ────────────────────────────────────────────────────────
// Each module:
//   type     — Chisel class name
//   doc      — one-line description (shown in inspector / breadcrumb)
//   ports    — IO bundle list [{name, dir, bundle, doc?}]
//   children — instance list [{name, type, doc?}]
//   wires    — internal connections [{from:'inst.port[.chan]', to:'...', label?}]
//   source   — { file, code } — Chisel source text, lines split on \n
//   metrics  — { gates, registers, lines } — synth-style stats for the bench

const MODULES = {
  AXISystem: {
    type: 'AXISystem',
    doc: 'Top-level interconnect — 2 masters, 1 crossbar, 2 slaves.',
    ports: [],
    children: [
      { name: 'cpu',    type: 'AXIMaster',    doc: 'RV32 fetch + LSU master' },
      { name: 'dma',    type: 'AXIMaster',    doc: 'Scatter-gather DMA engine' },
      { name: 'xbar',   type: 'AXICrossbar',  doc: '2×2 round-robin crossbar' },
      { name: 'mem',    type: 'AXISlave',     doc: 'DDR4 memory controller' },
      { name: 'periph', type: 'AXISlave',     doc: 'APB peripheral bridge' },
    ],
    wires: [
      { from: 'cpu.io',       to: 'xbar.in[0]',  bundle: 'AXI4', label: 'cpu↔xbar' },
      { from: 'dma.io',       to: 'xbar.in[1]',  bundle: 'AXI4', label: 'dma↔xbar' },
      { from: 'xbar.out[0]',  to: 'mem.io',      bundle: 'AXI4', label: 'xbar↔mem' },
      { from: 'xbar.out[1]',  to: 'periph.io',   bundle: 'AXI4', label: 'xbar↔periph' },
    ],
    source: {
      file: 'AXISystem.scala',
      code:
`class AXISystem extends Module {
  val io = IO(new Bundle {})

  val cpu    = Module(new AXIMaster(role = "cpu"))
  val dma    = Module(new AXIMaster(role = "dma"))
  val xbar   = Module(new AXICrossbar(nMasters = 2, nSlaves = 2))
  val mem    = Module(new AXISlave(base = 0x80000000L, size = 0x10000000L))
  val periph = Module(new AXISlave(base = 0x40000000L, size = 0x00100000L))

  // Masters drive crossbar inputs
  xbar.io.in(0) <> cpu.io
  xbar.io.in(1) <> dma.io

  // Crossbar outputs drive slaves
  mem.io    <> xbar.io.out(0)
  periph.io <> xbar.io.out(1)
}`,
    },
    metrics: { gates: 18204, registers: 612, lines: 14 },
  },

  AXIMaster: {
    type: 'AXIMaster',
    doc: 'Issues read/write transactions on a single AXI4 port.',
    ports: [
      { name: 'io', dir: 'master', bundle: 'AXI4', doc: 'AXI4 master interface' },
    ],
    children: [
      { name: 'txQ', type: 'Queue',       doc: 'Outstanding transaction queue (depth 8)' },
      { name: 'idGen', type: 'IdAllocator', doc: 'Issues unique AXI transaction IDs' },
    ],
    wires: [
      { from: 'txQ.deq', to: 'io.ar', bundle: 'AXI4_AR', label: 'txQ → ar' },
      { from: 'idGen.out', to: 'txQ.enq', bundle: 'TxReq', label: 'next id' },
    ],
    source: {
      file: 'AXIMaster.scala',
      code:
`class AXIMaster(role: String) extends Module {
  val io = IO(new AXI4)

  val txQ   = Module(new Queue(new TxReq, entries = 8))
  val idGen = Module(new IdAllocator(idWidth = 4))

  // Allocate a fresh id for each new request
  txQ.io.enq.bits.id := idGen.io.out
  io.ar.valid := txQ.io.deq.valid
  io.ar.bits  := txQ.io.deq.bits.asARChannel
  txQ.io.deq.ready := io.ar.ready

  // Drop responses; we just count them for now
  io.r.ready := true.B
  io.b.ready := true.B

  // (write path omitted for brevity)
  io.aw.valid := false.B
  io.w.valid  := false.B
}`,
    },
    metrics: { gates: 1840, registers: 96, lines: 18 },
  },

  AXICrossbar: {
    type: 'AXICrossbar',
    doc: '2×2 round-robin crossbar — address-decoded routing.',
    ports: [
      { name: 'in[0]',  dir: 'slave',  bundle: 'AXI4', doc: 'From CPU master' },
      { name: 'in[1]',  dir: 'slave',  bundle: 'AXI4', doc: 'From DMA master' },
      { name: 'out[0]', dir: 'master', bundle: 'AXI4', doc: 'To memory slave' },
      { name: 'out[1]', dir: 'master', bundle: 'AXI4', doc: 'To peripheral slave' },
    ],
    children: [
      { name: 'arbAR',   type: 'RRArbiter', doc: 'Round-robin AR arbiter' },
      { name: 'arbAW',   type: 'RRArbiter', doc: 'Round-robin AW arbiter' },
      { name: 'routerR', type: 'RRouter',   doc: 'R response router (id-based)' },
      { name: 'routerB', type: 'RRouter',   doc: 'B response router (id-based)' },
      { name: 'decAR',   type: 'AddrDec',   doc: 'AR address decoder' },
      { name: 'decAW',   type: 'AddrDec',   doc: 'AW address decoder' },
    ],
    wires: [
      { from: 'in[0].ar', to: 'arbAR.in[0]', bundle: 'AXI4_AR', label: 'cpu AR' },
      { from: 'in[1].ar', to: 'arbAR.in[1]', bundle: 'AXI4_AR', label: 'dma AR' },
      { from: 'arbAR.out', to: 'decAR.in',   bundle: 'AXI4_AR', label: 'arbitrated AR' },
      { from: 'decAR.out[0]', to: 'out[0].ar', bundle: 'AXI4_AR', label: 'mem AR' },
      { from: 'decAR.out[1]', to: 'out[1].ar', bundle: 'AXI4_AR', label: 'periph AR' },
      { from: 'out[0].r', to: 'routerR.in[0]', bundle: 'AXI4_R', label: 'mem R' },
      { from: 'out[1].r', to: 'routerR.in[1]', bundle: 'AXI4_R', label: 'periph R' },
      { from: 'routerR.out[0]', to: 'in[0].r', bundle: 'AXI4_R', label: 'cpu R' },
      { from: 'routerR.out[1]', to: 'in[1].r', bundle: 'AXI4_R', label: 'dma R' },
    ],
    source: {
      file: 'AXICrossbar.scala',
      code:
`class AXICrossbar(nMasters: Int, nSlaves: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Vec(nMasters, Flipped(new AXI4))
    val out = Vec(nSlaves,  new AXI4)
  })

  // Read-address arbitration: round-robin across masters
  val arbAR = Module(new RRArbiter(new AR, nMasters))
  for (i <- 0 until nMasters) arbAR.io.in(i) <> io.in(i).ar

  // Decode the arbitrated AR to a slave port
  val decAR = Module(new AddrDec(nSlaves))
  decAR.io.in <> arbAR.io.out
  for (s <- 0 until nSlaves) io.out(s).ar <> decAR.io.out(s)

  // Read-response routing by id high bits
  val routerR = Module(new RRouter(new R, nSlaves, nMasters))
  for (s <- 0 until nSlaves) routerR.io.in(s) <> io.out(s).r
  for (m <- 0 until nMasters) io.in(m).r <> routerR.io.out(m)

  // (write path: AW / W / B mirrors the above)
}`,
    },
    metrics: { gates: 6420, registers: 248, lines: 22 },
  },

  AXISlave: {
    type: 'AXISlave',
    doc: 'AXI4 slave wrapper with response-pipelined backend.',
    ports: [
      { name: 'io', dir: 'slave', bundle: 'AXI4', doc: 'AXI4 slave interface' },
    ],
    children: [
      { name: 'respQ', type: 'Queue', doc: 'Response queue (depth 4)' },
      { name: 'beReadback', type: 'Backend', doc: 'Backend storage / fabric stub' },
    ],
    wires: [
      { from: 'io.ar', to: 'beReadback.req', bundle: 'AXI4_AR', label: 'addr req' },
      { from: 'beReadback.resp', to: 'respQ.enq', bundle: 'AXI4_R', label: 'data resp' },
      { from: 'respQ.deq', to: 'io.r', bundle: 'AXI4_R', label: 'pipelined R' },
    ],
    source: {
      file: 'AXISlave.scala',
      code:
`class AXISlave(base: BigInt, size: BigInt) extends Module {
  val io = IO(Flipped(new AXI4))

  val respQ      = Module(new Queue(new R, entries = 4))
  val beReadback = Module(new Backend(base, size))

  // Forward incoming AR to the backend; pipe its response through respQ
  beReadback.io.req  <> io.ar
  respQ.io.enq       <> beReadback.io.resp
  io.r               <> respQ.io.deq

  // Stub the write path
  io.aw.ready := true.B
  io.w.ready  := true.B
  io.b.valid  := false.B
  io.b.bits   := DontCare
}`,
    },
    metrics: { gates: 3210, registers: 134, lines: 16 },
  },
};

// Display order at top level for the schematic layout (left → right columns).
const TOP_LAYOUT = {
  columns: [
    { id: 'masters',     label: 'Masters',      modules: ['cpu', 'dma'] },
    { id: 'fabric',      label: 'Fabric',       modules: ['xbar'] },
    { id: 'slaves',      label: 'Slaves',       modules: ['mem', 'periph'] },
  ],
};

// ─── Synthetic waveform — 32 cycles of activity on the AR channel ────────────
// Each track is a signal path; values are per-cycle. 'x' = unknown, '–' = idle.
const WAVE_CYCLES = 32;
const WAVES = [
  { path: 'cpu.io.ar.valid', type: 'Bool', values: '0011110001111110011000111111000110'.slice(0, WAVE_CYCLES).split('') },
  { path: 'cpu.io.ar.ready', type: 'Bool', values: '0011100011110010011100011110001100'.slice(0, WAVE_CYCLES).split('') },
  { path: 'cpu.io.ar.bits.addr', type: 'UInt<32>',
    values: ['–','–','80000000','80000008','80000010','80000018','–','–','80000020','80000028','80000030','80000038','80000040','80000048','–','–','40000000','40000004','40000008','4000000C','–','–','80000050','80000058','80000060','80000068','80000070','80000078','–','–','–','–'] },
  { path: 'cpu.io.ar.bits.id',   type: 'UInt<4>',
    values: ['–','–','0','1','2','3','–','–','4','5','6','7','0','1','–','–','2','3','4','5','–','–','6','7','0','1','2','3','–','–','–','–'] },
  { path: 'cpu.io.ar.bits.len',  type: 'UInt<8>',
    values: ['–','–','3','3','3','3','–','–','7','7','7','7','3','3','–','–','0','0','0','0','–','–','3','3','3','3','3','3','–','–','–','–'] },
  { path: 'xbar.arbAR.chosen',   type: 'UInt<1>',
    values: ['0','0','0','0','1','1','0','0','0','0','1','1','0','0','0','0','0','0','1','1','0','0','0','0','1','1','0','0','0','0','0','0'] },
  { path: 'mem.io.r.valid',      type: 'Bool', values: '0000001111100001111111000001111110'.slice(0, WAVE_CYCLES).split('') },
  { path: 'mem.io.r.bits.last',  type: 'Bool', values: '0000000010000000100000010000000010'.slice(0, WAVE_CYCLES).split('') },
];

// ─── Helpers ─────────────────────────────────────────────────────────────────

function totalWidth(bundleName) {
  const b = BUNDLES[bundleName];
  if (!b) return 0;
  if (b.kind === 'Bundle') {
    return b.channels.reduce((acc, ch) => acc + totalWidth(ch.type) + 2 /* valid+ready */, 0);
  }
  if (b.kind === 'Decoupled') {
    return b.fields.reduce((acc, f) => acc + f[1], 0) + 2 /* valid + ready */;
  }
  return 0;
}

function bundleFields(bundleName) {
  // Flat field list for inspectors. AXI4 → [ar.valid, ar.ready, ar.bits.id, ...]
  const b = BUNDLES[bundleName];
  if (!b) return [];
  if (b.kind === 'Decoupled') {
    return [
      { name: 'valid', width: 1, dir: 'out', kind: 'handshake' },
      { name: 'ready', width: 1, dir: 'in',  kind: 'handshake' },
      ...b.fields.map(([n, w, d]) => ({ name: `bits.${n}`, width: w, dir: d, kind: 'data' })),
    ];
  }
  if (b.kind === 'Bundle') {
    const out = [];
    for (const ch of b.channels) {
      for (const f of bundleFields(ch.type)) {
        out.push({
          ...f,
          name: `${ch.name}.${f.name}`,
          dir: ch.flip ? (f.dir === 'out' ? 'in' : 'out') : f.dir,
          channel: ch.name,
        });
      }
    }
    return out;
  }
  return [];
}

// Channel color palette — used by both variations so colors are consistent.
const CHANNEL_COLOR = {
  ar: 'oklch(0.60 0.16 250)',   // blue
  aw: 'oklch(0.55 0.17 290)',   // violet
  w:  'oklch(0.62 0.17 170)',   // teal
  r:  'oklch(0.65 0.16 50)',    // amber
  b:  'oklch(0.58 0.18 25)',    // red
};

window.DESIGN = { BUNDLES, MODULES, TOP_LAYOUT, WAVES, WAVE_CYCLES, CHANNEL_COLOR, totalWidth, bundleFields };
