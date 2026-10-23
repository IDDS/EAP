import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { ref, computed } from 'vue'

function screenState(getData = async () => ({})) {
  const source = readFileSync(new URL('../src/views/CommandCenterView.vue', import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const ast = ts.createSourceFile('screen.ts', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const script = ast.statements.filter(n => !ts.isImportDeclaration(n)).map(n => n.getText(ast)).join('\n')
  const context = vm.createContext({ ref, computed, getData, document: { hidden: false }, onMounted() {}, onBeforeUnmount() {} })
  vm.runInContext(ts.transpile(script + '\nglobalThis.state = { network, agentPage, agentPageCount, graph, selectedNode, selectedAgentPath, sourceUpdated, failedSources, lastUpdated, refresh, autoRefresh, toggleAutoRefresh, documentHidden, visibleRefresh, changeAgentPage, agentQuery, setAgentQuery, focusConnections, autoRotate, topologyFocused, advanceRotation, reconcileTopology, offPageConnections };', { target: ts.ScriptTarget.ES2022 }), context)
  return { ...context.state, document: context.document }
}
const node = id => ({ id: `agent:${id}`, plane: 'DATA', label: `Agent ${id}` })

test('topology pagination exposes remaining agents and only draws edges with visible endpoints', () => {
  const s = screenState()
  s.network.value = { nodes: Array.from({ length: 11 }, (_, i) => node(i + 1)), edges: [{ source: 'agent:1', target: 'agent:9' }] }
  assert.equal(s.agentPageCount.value, 2)
  const first = s.graph.value.nodes.map(n => n.id)
  assert.equal(first.length, 10)
  s.selectedNode.value = node(1)
  s.changeAgentPage(1)
  assert.equal(s.selectedNode.value, undefined)
  assert.equal(s.graph.value.nodes.length, 1)
  assert.equal(new Set([...first, s.graph.value.nodes[0].id]).size, 11)
  assert.equal(s.graph.value.edges.length, 0)
  s.changeAgentPage(1)
  assert.equal(s.agentPage.value, 1)
  s.changeAgentPage(-10)
  assert.equal(s.agentPage.value, 0)
  assert.equal(s.graph.value.edges.length, 1)
})

test('refresh clamps a removed topology page and preserves only successful source timestamps', async () => {
  const s = screenState(async url => {
    if (url.includes('physical')) throw new Error('offline')
    if (url.includes('network')) return { nodes: [node(1)], edges: [] }
    if (url.includes('jobs')) return []
    return {}
  })
  s.agentPage.value = 2
  s.sourceUpdated.value['调用遥测'] = 123
  s.selectedNode.value = node(20)
  await s.refresh()
  assert.equal(s.agentPage.value, 0)
  assert.equal(s.selectedNode.value, undefined)
  assert.equal(s.sourceUpdated.value['调用遥测'], 123)
  assert.ok(s.sourceUpdated.value['拓扑'] > 123)
  assert.equal(s.lastUpdated.value, '')
  assert.deepEqual(Array.from(s.failedSources.value), ['调用遥测'])
})

test('resuming refresh requests data immediately while hidden tabs remain idle', () => {
  let calls = 0
  const s = screenState(() => { calls++; return new Promise(() => {}) })
  s.toggleAutoRefresh()
  assert.equal(calls, 0)
  s.document.hidden = true
  s.toggleAutoRefresh()
  assert.equal(calls, 0)
  assert.equal(s.documentHidden.value, true)
  s.document.hidden = false
  s.visibleRefresh()
  assert.equal(calls, 7)
})

test('only an actual agent node produces an agent detail route', () => {
  const s = screenState()
  s.selectedNode.value = { id: 'control:eap' }
  assert.equal(s.selectedAgentPath.value, undefined)
  s.selectedNode.value = node(27)
  assert.equal(s.selectedAgentPath.value, '/agents/27')
  s.selectedNode.value = { id: 'agent:27/../../audit' }
  assert.equal(s.selectedAgentPath.value, undefined)
})


test('search finds an agent beyond the first page and clears prior selection and rotation', () => {
  const s = screenState()
  s.network.value = { nodes: Array.from({ length: 25 }, (_, i) => node(i + 1)), edges: [] }
  s.selectedNode.value = node(1)
  s.autoRotate.value = true
  s.setAgentQuery('AGENT:25')
  assert.equal(s.agentPageCount.value, 1)
  assert.equal(s.graph.value.nodes[0].id, 'agent:25')
  assert.equal(s.selectedNode.value, undefined)
  assert.equal(s.autoRotate.value, false)
  s.setAgentQuery('no match')
  assert.equal(s.graph.value.nodes.length, 0)
  s.setAgentQuery('')
  assert.equal(s.agentPageCount.value, 3)
})

test('refresh keeps a selected node visible when earlier nodes are inserted', () => {
  const s = screenState()
  s.network.value = { nodes: Array.from({ length: 10 }, (_, i) => node(i + 10)), edges: [] }
  s.selectedNode.value = node(19)
  s.network.value.nodes.unshift(node(1))
  s.reconcileTopology()
  assert.equal(s.agentPage.value, 1)
  assert.equal(s.graph.value.nodes[0].id, 'agent:19')
})

test('focused graph excludes unrelated edges and reports off-page associations', () => {
  const s = screenState()
  s.network.value = { nodes: Array.from({ length: 11 }, (_, i) => node(i + 1)), edges: [
    { source: 'agent:1', target: 'agent:2' }, { source: 'agent:2', target: 'agent:3' }, { source: 'agent:1', target: 'agent:11' },
  ] }
  s.selectedNode.value = node(1)
  s.focusConnections.value = true
  assert.equal(s.graph.value.edges.length, 1)
  assert.equal(s.offPageConnections.value, 1)
  s.focusConnections.value = false
  assert.equal(s.graph.value.edges.length, 2)
})

test('rotation wraps pages but holds during interaction, stale data and refresh pause', () => {
  const s = screenState()
  s.network.value = { nodes: Array.from({ length: 11 }, (_, i) => node(i + 1)), edges: [] }
  s.autoRotate.value = true
  s.sourceUpdated.value['拓扑'] = Date.now()
  s.advanceRotation()
  assert.equal(s.agentPage.value, 1)
  s.advanceRotation()
  assert.equal(s.agentPage.value, 0)
  for (const [state, value] of [[s.topologyFocused, true], [s.selectedNode, node(1)], [s.autoRefresh, false]]) {
    const previous = state.value
    state.value = value
    s.advanceRotation()
    assert.equal(s.agentPage.value, 0)
    state.value = previous
  }
  s.sourceUpdated.value['拓扑'] = Date.now() - 45001
  s.advanceRotation()
  assert.equal(s.agentPage.value, 0)
  s.sourceUpdated.value['拓扑'] = Date.now()
  s.document.hidden = true
  s.advanceRotation()
  assert.equal(s.agentPage.value, 0)
})
