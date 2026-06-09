/**
 * to_d3hw.mjs - Adapter: socless design.json -> d3-hwschematic ELK graph JSON
 *
 * Usage:
 *   node to_d3hw.mjs <path/to/design.json> <ModuleName> > graph.json
 *
 * Schema reference (from d3-hwschematic README + Cdc.json example):
 *   Root node: { id, hwMeta:{name,cls,maxId}, properties, ports, children, edges }
 *   Port: { id, direction:"INPUT"|"OUTPUT", hwMeta:{name,level,connectedAsParent}, properties:{side,index} }
 *   Edge (simple): { id, source, sourcePort, target, targetPort, hwMeta:{name} }
 *   Child node: { id, hwMeta:{name,cls,maxId,isExternalPort?}, properties, ports }
 *
 * ASSUMPTIONS / UNCERTAINTIES:
 *   - wire.from = SINK endpoint, wire.to = SOURCE endpoint (per task spec)
 *   - Edges flow source -> target (source drives, target receives)
 *   - Bundle ports are represented as single aggregated ports (no sub-port expansion)
 *     because d3-hwschematic handles bundle expansion internally
 *   - Clock/Reset/AsyncReset bundles are skipped to match Track A
 *   - Boundary ports (no dot in name) map to a virtual "boundary" node (id="__boundary__")
 *     because d3-hwschematic edges must reference LNode ids
 *   - Port direction on child instance is inferred from wire role:
 *     if the child instance is SOURCE (wire.to), port direction = OUTPUT;
 *     if the child instance is SINK (wire.from), port direction = INPUT
 *   - Boundary port direction: if boundary is SOURCE (wire.to), direction = OUTPUT (produces signal);
 *     if boundary is SINK (wire.from), direction = INPUT (consumes signal = module output)
 *   - ID counter is global to avoid collisions across all nodes/ports/edges
 */

import { readFileSync } from 'fs';

const SKIP_BUNDLES = new Set(['Clock', 'Reset', 'AsyncReset', 'clock', 'reset']);

function main() {
  const [, , designPath, moduleName] = process.argv;
  if (!designPath || !moduleName) {
    process.stderr.write('Usage: node to_d3hw.mjs <design.json> <ModuleName>\n');
    process.exit(1);
  }

  const design = JSON.parse(readFileSync(designPath, 'utf8'));
  const mod = design.modules.find(m => m.name === moduleName);
  if (!mod) {
    process.stderr.write(`Module "${moduleName}" not found. Available: ${design.modules.map(m => m.name).join(', ')}\n`);
    process.exit(1);
  }

  const graph = buildGraph(mod, design.bundles || {});
  process.stdout.write(JSON.stringify(graph, null, 2));
}

/** Auto-incrementing ID counter shared across all nodes/ports/edges */
let _nextId = 1;
function nextId() { return String(_nextId++); }

function buildGraph(mod, bundles) {
  // Reset counter for each graph build
  _nextId = 1;

  // Non-clock wires only
  const allWires = (mod.wires || []).filter(w => !SKIP_BUNDLES.has(w.bundle));

  // Deduplicate bidirectional wires: some bundles (e.g. Decoupled with flip, AXI io)
  // appear twice — once as (from=A, to=B) and again as (from=B, to=A). Keep only one
  // edge per unordered pair of endpoints (prefer the one where wire.to is an inst port
  // i.e. the source is an instance, not boundary, when there's a choice).
  const wiresSeen = new Set();
  const wires = [];
  for (const w of allWires) {
    const key = [w.from, w.to].sort().join('||');
    const revKey = [w.to, w.from].sort().join('||');
    if (!wiresSeen.has(key) && !wiresSeen.has(revKey)) {
      wiresSeen.add(key);
      wires.push(w);
    }
  }

  // Build known instance name set for endpoint disambiguation
  const instNames = new Set((mod.children || []).map(c => c.name));

  // ----- Collect all ports needed per instance -----
  // instPorts: Map<instName, Map<portPath, { direction: 'INPUT'|'OUTPUT', bundle }>>
  const instPorts = new Map(); // instName -> Map<portKey, {direction, bundle}>

  // Also collect boundary ports
  const boundaryPorts = new Map(); // portName -> {direction: 'INPUT'|'OUTPUT', bundle}

  for (const w of wires) {
    const src = parseEndpoint(w.to, instNames);   // SOURCE drives signal
    const snk = parseEndpoint(w.from, instNames); // SINK receives signal

    // Source side
    if (src.kind === 'inst') {
      if (!instPorts.has(src.inst)) instPorts.set(src.inst, new Map());
      instPorts.get(src.inst).set(src.port, { direction: 'OUTPUT', bundle: w.bundle });
    } else {
      // boundary - this boundary port is a source (drives signal into module) = input port of module
      // Collapse sub-paths: 'io.imem' -> top-level key is 'io.imem' kept as-is for the boundary;
      // boundary port lookup uses the full path since the boundary node will have a port for it
      if (!boundaryPorts.has(src.port)) {
        boundaryPorts.set(src.port, { direction: 'INPUT', bundle: w.bundle });
      }
    }

    // Sink side
    if (snk.kind === 'inst') {
      if (!instPorts.has(snk.inst)) instPorts.set(snk.inst, new Map());
      // Only set if not already set as OUTPUT (don't overwrite with INPUT)
      if (!instPorts.get(snk.inst).has(snk.port)) {
        instPorts.get(snk.inst).set(snk.port, { direction: 'INPUT', bundle: w.bundle });
      }
    } else {
      // boundary port as sink = module output port
      if (!boundaryPorts.has(snk.port)) {
        boundaryPorts.set(snk.port, { direction: 'OUTPUT', bundle: w.bundle });
      }
    }
  }

  // Also add boundary ports from module port declarations (even if no wires connect to them)
  for (const p of (mod.ports || [])) {
    if (SKIP_BUNDLES.has(p.bundle) || SKIP_BUNDLES.has(p.name)) continue;
    if (!boundaryPorts.has(p.name)) {
      // dir: "input" means the module receives it from outside -> INPUT to module = OUTPUT from boundary perspective
      // For the boundary node: if module port is "input", the boundary drives it -> boundary port direction = OUTPUT
      // If module port is "output", the boundary receives it -> boundary port direction = INPUT
      const dir = p.dir === 'input' ? 'OUTPUT' : 'INPUT';
      boundaryPorts.set(p.name, { direction: dir, bundle: p.bundle });
    }
  }

  // ----- Assign IDs -----
  const rootId = nextId(); // "1" - root/top-level node

  // Boundary node
  const boundaryId = nextId();
  const boundaryPortIds = new Map(); // portName -> id
  for (const [pname] of boundaryPorts) {
    boundaryPortIds.set(pname, nextId());
  }

  // Instance nodes
  const instIds = new Map(); // instName -> nodeId
  const instPortIds = new Map(); // instName -> Map<portKey, portId>
  for (const child of (mod.children || [])) {
    instIds.set(child.name, nextId());
    const pm = new Map();
    const ports = instPorts.get(child.name) || new Map();
    for (const [pk] of ports) {
      pm.set(pk, nextId());
    }
    instPortIds.set(child.name, pm);
  }

  // Also handle instances that appear in wires but not in mod.children (shouldn't happen but be safe)
  for (const [instName] of instPorts) {
    if (!instIds.has(instName)) {
      instIds.set(instName, nextId());
      const pm = new Map();
      for (const [pk] of instPorts.get(instName)) {
        pm.set(pk, nextId());
      }
      instPortIds.set(instName, pm);
    }
  }

  // ----- Build port list for boundary node -----
  const bndPortNodes = [];
  let bndIdx = 0;
  for (const [pname, { direction, bundle }] of boundaryPorts) {
    const portId = boundaryPortIds.get(pname);
    const side = direction === 'OUTPUT' ? 'EAST' : 'WEST';
    bndPortNodes.push({
      id: portId,
      direction,
      hwMeta: { name: portLabel(pname, bundle), level: 0, connectedAsParent: false },
      properties: { side, index: bndIdx++ },
      children: [],
    });
  }

  // ----- Build child nodes for instances -----
  const childNodes = [];
  for (const child of (mod.children || [])) {
    const instName = child.name;
    const nodeId = instIds.get(instName);
    const ports = instPorts.get(instName) || new Map();
    const portIdMap = instPortIds.get(instName) || new Map();

    const portNodes = [];
    let inIdx = 0, outIdx = 0;
    for (const [portKey, { direction, bundle }] of ports) {
      const portId = portIdMap.get(portKey);
      const side = direction === 'INPUT' ? 'WEST' : 'EAST';
      const idx = direction === 'INPUT' ? inIdx++ : outIdx++;
      portNodes.push({
        id: portId,
        direction,
        hwMeta: { name: portLabel(portKey, bundle), level: 0, connectedAsParent: false },
        properties: { side, index: idx },
        children: [],
      });
    }

    const maxPortId = portNodes.length > 0
      ? Math.max(...portNodes.map(p => parseInt(p.id)))
      : parseInt(nodeId);

    childNodes.push({
      id: nodeId,
      hwMeta: {
        name: instName,
        cls: child.type || instName,
        maxId: maxPortId,
      },
      properties: {
        'org.eclipse.elk.portConstraints': 'FIXED_ORDER',
        'org.eclipse.elk.layered.mergeEdges': 1,
      },
      ports: portNodes,
    });
  }

  // ----- Build edges -----
  const edges = [];
  for (const w of wires) {
    const src = parseEndpoint(w.to, instNames);
    const snk = parseEndpoint(w.from, instNames);

    let sourceNode, sourcePort, targetNode, targetPort;

    // Resolve source (the node that drives the signal)
    if (src.kind === 'inst') {
      sourceNode = instIds.get(src.inst);
      sourcePort = instPortIds.get(src.inst)?.get(src.port);
    } else {
      sourceNode = boundaryId;
      sourcePort = boundaryPortIds.get(src.port);
    }

    // Resolve target (the node that receives the signal)
    if (snk.kind === 'inst') {
      targetNode = instIds.get(snk.inst);
      targetPort = instPortIds.get(snk.inst)?.get(snk.port);
    } else {
      targetNode = boundaryId;
      targetPort = boundaryPortIds.get(snk.port);
    }

    if (!sourceNode || !sourcePort || !targetNode || !targetPort) {
      // Unresolved endpoint - skip with warning to stderr
      process.stderr.write(`WARN: unresolved endpoint for wire ${w.from} -> ${w.to} (bundle: ${w.bundle})\n`);
      continue;
    }

    const label = w.label || w.bundle || '';
    edges.push({
      id: nextId(),
      source: sourceNode,
      sourcePort,
      target: targetNode,
      targetPort,
      hwMeta: { name: label },
    });
  }

  // ----- Boundary node -----
  const boundaryMaxId = bndPortNodes.length > 0
    ? Math.max(...bndPortNodes.map(p => parseInt(p.id)))
    : parseInt(boundaryId);

  const boundaryNode = {
    id: boundaryId,
    hwMeta: {
      name: mod.name,
      cls: null,
      isExternalPort: true,
      maxId: boundaryMaxId,
    },
    properties: {
      'org.eclipse.elk.portConstraints': 'FIXED_ORDER',
      'org.eclipse.elk.layered.mergeEdges': 1,
    },
    ports: bndPortNodes,
  };

  // All children: boundary node + instance nodes
  const allChildren = [boundaryNode, ...childNodes];

  const rootMaxId = _nextId - 1;

  // ----- Root graph node -----
  return {
    id: rootId,
    hwMeta: {
      name: mod.name,
      cls: mod.type || mod.name,
      maxId: rootMaxId,
    },
    properties: {
      'org.eclipse.elk.portConstraints': 'FREE',
      'org.eclipse.elk.layered.mergeEdges': 1,
      'org.eclipse.elk.direction': 'RIGHT',
    },
    ports: [],
    children: allChildren,
    edges,
  };
}

/**
 * Parse a wire endpoint string, using the known instance name set to
 * distinguish boundary sub-fields from instance ports.
 *
 * Examples (instNames = {'cpu','dma','xbar','mem','periph'}):
 *   "cpu.io"         -> { kind:'inst', inst:'cpu', port:'io' }
 *   "xbar.io.in[0]"  -> { kind:'inst', inst:'xbar', port:'io.in[0]' }
 *   "clock"          -> { kind:'boundary', port:'clock' }
 *   "io"             -> { kind:'boundary', port:'io' }
 *   "io.imem"        -> { kind:'boundary', port:'io.imem' }  ('io' not an instance)
 */
function parseEndpoint(ep, instNames) {
  const dot = ep.indexOf('.');
  if (dot === -1) {
    return { kind: 'boundary', port: ep };
  }
  const prefix = ep.slice(0, dot);
  if (instNames && instNames.has(prefix)) {
    return { kind: 'inst', inst: prefix, port: ep.slice(dot + 1) };
  }
  // prefix is not a known instance — treat the whole path as a boundary port
  return { kind: 'boundary', port: ep };
}

/** Produce a human-readable port label from the port key and bundle type */
function portLabel(portKey, bundle) {
  // Strip array indices for display, keep the key readable
  const base = portKey.replace(/\[\d+\]/g, '[]');
  if (bundle && bundle !== base) {
    return `${base}: ${bundle}`;
  }
  return base;
}

main();
