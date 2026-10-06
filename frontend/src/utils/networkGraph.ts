import type { NetworkEdge, NetworkNode } from '@/types/domain'

export const NODE_W = 196
export const NODE_H = 92

// Collapse parallel declarations without losing their individual labels or counts.
export function bundleEdges(edges: NetworkEdge[]) {
  const groups = new Map<string, NetworkEdge[]>()
  for (const edge of edges) {
    const key = JSON.stringify([edge.source, edge.target, edge.plane])
    groups.set(key, [...(groups.get(key) || []), edge])
  }
  return [...groups.entries()].map(([id, relations]) => ({ ...relations[0], id, relations, count: relations.length }))
}

export function graphScene(nodes: NetworkNode[], edges: NetworkEdge[], selected: string, focus: boolean, maxColumns = 4) {
  const known = new Set(nodes.map(n => n.id))
  const links = edges.filter(e => known.has(e.source) && known.has(e.target)
    && (!focus || !selected || e.source === selected || e.target === selected))
  const visible = new Set([selected, ...links.flatMap(e => [e.source, e.target])])
  const rows = nodes.filter(n => !focus || !selected || visible.has(n.id))
  const controls = rows.filter(n => n.plane === 'CONTROL')
  const agents = rows.filter(n => n.plane !== 'CONTROL')
  const cols = Math.min(maxColumns, Math.max(1, controls.length, agents.length))
  const width = 96 + cols * 248
  const arrange = (list: NetworkNode[], top: number) => list.map((n, i) => ({ ...n,
    x: 48 + (i % cols) * 248 + (Math.min(cols, list.length) < cols ? (cols - list.length) * 124 : 0),
    y: top + Math.floor(i / cols) * 196,
  }))
  const positioned = [...arrange(controls, 48), ...arrange(agents, controls.length ? 264 : 48)]
  return { nodes: positioned, edges: bundleEdges(links), width,
    height: Math.max(260, ...positioned.map(n => n.y + NODE_H + 76)) }
}

export function edgePath(source: NetworkNode, target: NetworkNode, reverse = false) {
  if (source.id === target.id) return `M ${source.x + NODE_W} ${source.y + 24} C ${source.x + NODE_W + 64} ${source.y - 64}, ${source.x - 64} ${source.y - 64}, ${source.x} ${source.y + 24}`
  if (Math.abs(source.y - target.y) < NODE_H) {
    const right = target.x > source.x
    const sx = source.x + (right ? NODE_W : 0), tx = target.x + (right ? 0 : NODE_W)
    const y = source.y + NODE_H / 2, bend = y + (reverse ? 70 : -70)
    return `M ${sx} ${y} C ${sx} ${bend}, ${tx} ${bend}, ${tx} ${target.y + NODE_H / 2}`
  }
  const down = target.y > source.y
  const sx = source.x + NODE_W / 2, sy = source.y + (down ? NODE_H : 0)
  const tx = target.x + NODE_W / 2, ty = target.y + (down ? 0 : NODE_H)
  return `M ${sx} ${sy} C ${sx + (reverse ? 24 : 0)} ${(sy + ty) / 2}, ${tx + (reverse ? 24 : 0)} ${(sy + ty) / 2}, ${tx} ${ty}`
}

// In the overview, fan-out travels along shared trunks in the gutters, never through cards.
export function controlBusPath(source: NetworkNode, target: NetworkNode, lane: number) {
  const busX = 12 + lane * 10, departureY = source.y + NODE_H + 20 + lane * 12
  const arrivalY = target.y - 48 + lane * 12
  return `M ${source.x + NODE_W / 2} ${source.y + NODE_H} V ${departureY} H ${busX} V ${arrivalY} H ${target.x + NODE_W / 2} V ${target.y}`
}

// Draw shared vertical and row trunks once. Repeating long dashed SVG paths per
// target makes browser painting grow quadratically even when lines overlap exactly.
export function controlBusPaths(source: NetworkNode, targets: NetworkNode[], lane: number) {
  const busX = 12 + lane * 10, departureY = source.y + NODE_H + 20 + lane * 12
  const rows = new Map<number, number>(), branches = new Map<string, string>()
  for (const target of targets) {
    const arrivalY = target.y - 48 + lane * 12, centerX = target.x + NODE_W / 2
    rows.set(arrivalY, Math.max(rows.get(arrivalY) || 0, centerX))
    branches.set(target.id, `M ${centerX} ${arrivalY} V ${target.y}`)
  }
  const bottom = Math.max(departureY, ...rows.keys())
  const trunk = `M ${source.x + NODE_W / 2} ${source.y + NODE_H} V ${departureY} H ${busX} V ${bottom}`
    + [...rows].map(([y, x]) => ` M ${busX} ${y} H ${x}`).join('')
  return { trunk, branches }
}

export function fitCamera(width: number, height: number, sceneWidth: number, sceneHeight: number) {
  const scale = Math.min(1, Math.max(1, width - 32) / Math.max(1, sceneWidth), Math.max(1, height - 32) / Math.max(1, sceneHeight))
  return { scale, x: (width - sceneWidth * scale) / 2, y: (height - sceneHeight * scale) / 2 }
}
export function zoomCamera(camera: { x: number; y: number; scale: number }, factor: number, x: number, y: number) {
  const scale = Math.min(2.5, Math.max(Math.min(.05, camera.scale), camera.scale * factor)), ratio = scale / camera.scale
  return { scale, x: x - (x - camera.x) * ratio, y: y - (y - camera.y) * ratio }
}
