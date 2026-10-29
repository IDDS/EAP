import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import {evaluationHelpers,evaluationState} from './evaluation-test-support.mjs'

// Run the real SFC functions with deferred API responses, without browser dependencies.
function functionsFrom(file, names, state) {
  if (file === 'views/EvaluationView.vue') {
    names=[...names,...evaluationHelpers]
    Object.assign(state, { ...evaluationState(), activeJob: ref(), jobs: ref([]), activeTab: ref('overview'), ...state })
  }
  if (file === 'views/WorkflowBuilderView.vue') {
    Object.assign(state, { loadError: ref(''), savedNodeConfigs: ref({}), nodeConfigError: ref(''), hasUnsavedConfig: ref(false), nodeHasTool: ref(false), permissions: { can: () => true }, workflowTab: ref('design'), selectedExecutionKey: ref(''), disposed: false, previewRequestId: 0, running: ref(false), creatingEdge: ref(false), creatingNode: ref(false), ...state })
  }
  if (file === 'views/AgentDetailView.vue' && names.includes('load')) {
    names = [...new Set([...names, 'resetHistory', 'fetchHistory', 'loadHistory'])]
    state.permissions ??= { state: { user: {} }, can: () => true }
    state.historyPageSize = 50
    state.historyKinds = ['models', 'spans', 'tools']
    state.historyRows = Object.fromEntries(state.historyKinds.map(kind => [kind, state[kind === 'models' ? 'modelEvents' : kind]]))
    state.history = Object.fromEntries(state.historyKinds.map(kind => [kind, { page: 0, requestedPage: 0, loading: false, hasNext: false, error: '', requestId: 0 }]))
  }
  const script = readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const ast = ts.createSourceFile(file, script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const source = ast.statements.filter(node => ts.isFunctionDeclaration(node) && names.includes(node.name.text)).map(node => node.getText(ast)).join('\n')
  const context = vm.createContext(state)
  vm.runInContext(ts.transpile(source, { target: ts.ScriptTarget.ES2022 }), context)
  return context
}
const ref = value => ({ value })
function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}

test('dataset switch clears old actionable rows and ignores out-of-order responses', async () => {
  const first = deferred(), second = deferred()
  const state = functionsFrom('views/EvaluationView.vue', ['loadDatasetItems', 'annotateItem'], {
    datasetItemsRequestId: 0, datasetItems: ref([{ itemKey: 'shared' }]), datasetItemsLoading: ref(false), selectedDatasetKey: ref('A'),
    EapApi: { listEvaluationDatasetItems: key => key === 'A' ? first.promise : second.promise },
  })
  const a = state.loadDatasetItems('A')
  assert.equal(state.datasetItems.value.length, 0)
  state.selectedDatasetKey.value = 'B'
  const b = state.loadDatasetItems('B')
  second.resolve([{ itemKey: 'B' }]); await b
  first.resolve([{ itemKey: 'A' }]); await a
  assert.equal(state.datasetItems.value[0].itemKey, 'B')
  assert.equal(state.datasetItemsLoading.value, false)
  // A row from the previously selected dataset must never reach annotation API.
  await state.annotateItem({ itemKey: 'shared' }, 'PASS')
})

test('opening a result without trace invalidates a pending trace response', async () => {
  const pending = deferred()
  const state = functionsFrom('views/EvaluationView.vue', ['openResult'], {
    traceRequestId: 0, traceLoading: ref(false), traceSpans: ref([]), selectedResult: ref(), detailVisible: ref(false),
    EapApi: { listTraceSpanObservations: () => pending.promise },
  })
  const first = state.openResult({ id: 1, traceId: 'old' })
  await state.openResult({ id: 2 })
  pending.resolve([{ traceId: 'old' }]); await first
  assert.equal(state.traceSpans.value.length, 0)
  assert.equal(state.traceLoading.value, false)
})

test('clearing global search resets loading while an old request is pending', async () => {
  const pending = deferred()
  const state = functionsFrom('App.vue', ['runGlobalSearch'], {
    searchNavigation: () => [], searchPageSections: () => [], searchRequestId: 0, globalSearchTerm: ref('agent'), globalSearchLoading: ref(false), globalSearchResults: ref([]),
    EapApi: { listAgents: () => pending.promise, listDiscoveredAgentAssets: async () => [], listPolicies: async () => [] },
  })
  const first = state.runGlobalSearch()
  state.globalSearchTerm.value = ''
  await state.runGlobalSearch()
  assert.equal(state.globalSearchLoading.value, false)
  pending.resolve([]); await first
  assert.equal(state.globalSearchResults.value.length, 0)
})

test('agent route switch clears old identity and rejects its late response', async () => {
  const first = deferred(), second = deferred()
  let call = 0
  const state = {
    contentAccessDenied: ref(false), loadError: ref(''), loadRequestId: 0, agentId: ref(1), context: { windowHours: 24 }, loading: ref(false), agent: ref({ id: 1 }),
    EapApi: new Proxy({}, { get: (_, name) => name === 'listAgents' ? () => ++call === 1 ? first.promise : second.promise : async () => [] }),
  }
  for (const key of ['capabilities','runtimes','modelEvents','spans','tools','content','knowledge','governance','tasks','discoveryAssets','agentOpsState','workItems','evaluationResults']) state[key] = ref([])
  const scope = functionsFrom('views/AgentDetailView.vue', ['load'], state)
  const a = scope.load()
  scope.agentId.value = 2
  const b = scope.load()
  assert.equal(scope.agent.value, undefined)
  second.resolve([{ id: 2 }]); await b
  first.resolve([{ id: 1 }]); await a
  assert.equal(scope.agent.value.id, 2)
  assert.equal(scope.loading.value, false)
})

test('workflow switch cannot save the previous workflow node or accept a stale load', async () => {
  const first = deferred(), second = deferred()
  const state = functionsFrom('views/WorkflowBuilderView.vue', ['loadWorkflow', 'saveSelectedNode'], {
    workflowRequestId: 0, selectedWorkflowId: ref(1), nodes: ref([]), edges: ref([]), selectedNode: ref({ nodeKey: 'old' }), movingNode: ref(), pendingSourceKey: ref(), lastRun: ref(),
    EapApi: { getWorkflow: id => id === 1 ? first.promise : second.promise },
  })
  const a = state.loadWorkflow()
  state.selectedWorkflowId.value = 2
  const b = state.loadWorkflow()
  assert.equal(state.selectedNode.value, undefined)
  await state.saveSelectedNode()
  second.resolve({ nodes: [{ nodeKey: 'new' }], edges: [] }); await b
  first.resolve({ nodes: [{ nodeKey: 'old' }], edges: [] }); await a
  assert.equal(state.selectedNode.value.nodeKey, 'new')
})

test('agent directory preserves latest filter response and loading state', async () => {
  const first = deferred(), second = deferred()
  let call = 0
  const state = functionsFrom('views/AgentsView.vue', ['load'], {
    loadRequestId: 0, loading: ref(false), context: { windowHours: 24, organizationScope: '' }, currentPage: ref(1), pageSize: ref(20), filters: { query: '' }, page: ref(), dashboard: ref(),
    EapApi: { listAgentOpsAgents: () => ++call === 1 ? first.promise : second.promise, getAgentOpsDashboard: async () => ({}) },
  })
  const a = state.load()
  state.filters.query = 'new'
  const b = state.load()
  first.resolve({ items: ['old'] }); await a
  assert.equal(state.loading.value, true)
  second.resolve({ items: ['new'] }); await b
  assert.equal(state.page.value.items[0], 'new')
})

test('dataset calibration cannot be replaced by the previous selection', async () => {
  const first = deferred(), second = deferred()
  const state = functionsFrom('views/EvaluationView.vue', ['loadDatasetCalibration'], {
    selectedCalibrationRunId: ref(''), calibrationDetail: ref(), calibrationRequestId: 0, calibration: ref({ datasetKey: 'previous', totalItems: 20 }), emptyCalibration: { datasetKey: '', totalItems: 0 }, selectedDatasetKey: ref('A'),
    EapApi: { getEvaluationDatasetCalibration: key => key === 'A' ? first.promise : second.promise },
  })
  const a = state.loadDatasetCalibration('A')
  state.selectedDatasetKey.value = 'B'
  const b = state.loadDatasetCalibration('B')
  assert.equal(state.calibration.value.totalItems, 0)
  second.resolve({ datasetKey: 'B', totalItems: 2 }); await b
  first.resolve({ datasetKey: 'A', totalItems: 10 }); await a
  assert.equal(state.calibration.value.datasetKey, 'B')
})

test('agent overview does not request relocated content, knowledge, governance or evaluation APIs', async () => {
  const calls = []
  const scope = agentHistoryScope(new Proxy({}, { get: (_, name) => async () => {
    calls.push(name)
    return name === 'listAgents' ? [{ id: 7 }] : []
  } }))
  await scope.load()
  assert.equal(scope.agent.value.id, 7)
  for (const name of ['listContentObservations','listKnowledgeInteractions','listGovernanceDataEvents','listEvaluationResults']) assert.ok(!calls.includes(name))
})

test('team switch clears actionable steps and invalidates pending plan detail', async () => {
  const pendingPlan = deferred(), pendingTeam = deferred()
  const state = functionsFrom('views/OrchestrationView.vue', ['loadTeamDetail', 'selectPlan'], {
    teamLoading: ref(false), planLoading: ref(false), teamError: ref(''), planError: ref(''), selectedExecution: ref(), disposed: false, teamRequestId: 0, planRequestId: 0, selectedTeamId: ref(1), selectedPlanId: ref(11), members: ref([]), plans: ref([]), planSteps: ref([{ id: 'old' }]),
    EapApi: { getPlan: () => pendingPlan.promise, listTeamMembers: async () => [], listTeamPlans: () => pendingTeam.promise },
  })
  const first = state.selectPlan(11)
  state.selectedTeamId.value = 2
  const second = state.loadTeamDetail()
  assert.equal(state.selectedPlanId.value, undefined)
  assert.equal(state.planSteps.value.length, 0)
  pendingPlan.resolve({ steps: [{ id: 'old' }] }); await first
  assert.equal(state.planSteps.value.length, 0)
  pendingTeam.resolve([]); await second
  assert.equal(state.selectedPlanId.value, undefined)
})

test('team and plan responses cannot overwrite a later selection', async () => {
  const oldTeam = deferred(), oldPlan = deferred(), planStarted = deferred()
  const state = functionsFrom('views/OrchestrationView.vue', ['loadTeamDetail', 'selectPlan'], {
    teamLoading: ref(false), planLoading: ref(false), teamError: ref(''), planError: ref(''), selectedExecution: ref(), disposed: false, teamRequestId: 0, planRequestId: 0, selectedTeamId: ref(1), selectedPlanId: ref(), members: ref([]), plans: ref([]), planSteps: ref([]),
    EapApi: { listTeamMembers: async id => [{ teamId: id }], listTeamPlans: id => id === 1 ? oldTeam.promise : Promise.resolve([{ id: 22 }]), getPlan: id => { if (id === 22) { planStarted.resolve(); return oldPlan.promise } return Promise.resolve({ steps: [{ id }] }) } },
  })
  const first = state.loadTeamDetail()
  state.selectedTeamId.value = 2
  const second = state.loadTeamDetail()
  // Allow team 2's default plan request to start before manually choosing another plan.
  await planStarted.promise
  await state.selectPlan(23)
  oldTeam.resolve([{ id: 11 }]); await first
  oldPlan.resolve({ steps: [{ id: 22 }] }); await second
  assert.equal(state.members.value[0].teamId, 2)
  assert.equal(state.selectedPlanId.value, 23)
  assert.equal(state.planSteps.value[0].id, 23)
})

test('agent history requests apply the agent filter at the API boundary', async () => {
  const calls = []
  const state = {
    contentAccessDenied: ref(false), loadError: ref(''), loadRequestId: 0, agentId: ref(7), context: { windowHours: 24 }, loading: ref(false), agent: ref(),
    EapApi: new Proxy({}, { get: (_, name) => async (...args) => {
      if (['listModelInvocationEvents','listTraceSpanObservations','listToolInvocationEvents'].includes(name)) calls.push({ name, args })
      return name === 'listAgents' ? [{ id: 7 }] : []
    } }),
  }
  for (const key of ['capabilities','runtimes','modelEvents','spans','tools','content','knowledge','governance','tasks','discoveryAssets','agentOpsState','workItems','evaluationResults']) state[key] = ref([])
  await functionsFrom('views/AgentDetailView.vue', ['load'], state).load()
  assert.equal(calls.length, 3)
  for (const call of calls) {
    assert.equal(call.args[0].limit, 50)
    assert.equal(call.args[0].agentId, 7)
  }
})

test('leaving evaluation cancels scheduled polling and settles its wait', async () => {
  let callback, cleared = false, apiCalls = 0
  const state = functionsFrom('views/EvaluationView.vue', ['waitForJob', 'stopPolling'], {
    pollingDisposed: false, pollTimer: undefined, finishPollDelay: undefined,
    window: { setTimeout: fn => { callback = fn; return 42 }, clearTimeout: id => { cleared = id === 42 } },
    EapApi: { getEvaluationJob: async () => { apiCalls++; return { status: 'RUNNING' } } },
  })
  const polling = state.waitForJob(1)
  assert.equal(typeof callback, 'function')
  state.stopPolling()
  await polling
  assert.equal(cleared, true)
  assert.equal(apiCalls, 0)
})

test('leaving evaluation while a job request is in flight prevents notifications and refresh', async () => {
  const pending = deferred(), requestStarted = deferred()
  let callback, loads = 0, notifications = 0
  const state = functionsFrom('views/EvaluationView.vue', ['synchronize', 'waitForJob', 'stopPolling'], {
    syncing: ref(false), pollingDisposed: false, pollTimer: undefined, finishPollDelay: undefined,
    window: { setTimeout: fn => { callback = fn; return 1 }, clearTimeout: () => {} },
    EapApi: { submitEvaluationJob: async () => ({ id: 1, jobKey: 'job' }), getEvaluationJob: () => { requestStarted.resolve(); return pending.promise } },
    ElMessage: { success: () => { notifications++ } }, load: async () => { loads++ },
  })
  const sync = state.synchronize()
  await new Promise(resolve => setImmediate(resolve))
  callback()
  await requestStarted.promise
  state.stopPolling()
  pending.resolve({ status: 'SUCCEEDED', scanned: 1, evaluated: 1 })
  await sync
  assert.equal(notifications, 1) // Only the initial submission notice.
  assert.equal(loads, 0)
  assert.equal(state.syncing.value, false)
})

function agentHistoryScope(api) {
  const state = {
    contentAccessDenied: ref(false), loadError: ref(''), loadRequestId: 0, agentId: ref(7), context: { windowHours: 24 }, loading: ref(false), agent: ref(), EapApi: api,
  }
  for (const key of ['capabilities','runtimes','modelEvents','spans','tools','content','knowledge','governance','tasks','discoveryAssets','agentOpsState','workItems','evaluationResults']) state[key] = ref([])
  return functionsFrom('views/AgentDetailView.vue', ['load'], state)
}

test('history paging loads only its own API and keeps the other sections unchanged', async () => {
  const calls = []
  const scope = agentHistoryScope(new Proxy({}, { get: (_, name) => async (...args) => {
    calls.push({ name, args })
    if (name === 'listAgents') return [{ id: 7 }]
    if (name === 'listModelInvocationEvents') return [{ id: args[0].page + 1 }]
    if (name === 'listToolInvocationEvents') return [{ id: 99 }]
    return []
  } }))
  await scope.load()
  calls.length = 0
  await scope.loadHistory('models', 1)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].name, 'listModelInvocationEvents')
  assert.equal(calls[0].args[0].agentId, 7)
  assert.equal(calls[0].args[0].page, 1)
  assert.equal(scope.modelEvents.value[0].id, 2)
  assert.equal(scope.history.models.page, 1)
  assert.equal(scope.tools.value[0].id, 99)
  assert.equal(scope.history.tools.page, 0)
  assert.equal(scope.loading.value, false)
})

test('failed history page preserves the visible rows and can retry the same target page', async () => {
  let failure = false
  const scope = agentHistoryScope(new Proxy({}, { get: (_, name) => async () => {
    if (name === 'listAgents') return [{ id: 7 }]
    if (name === 'listToolInvocationEvents') {
      if (failure) throw new Error('offline')
      return [{ id: 1 }]
    }
    return []
  } }))
  await scope.load()
  failure = true
  await scope.loadHistory('tools', 1)
  assert.equal(scope.tools.value[0].id, 1)
  assert.equal(scope.history.tools.page, 0)
  assert.equal(scope.history.tools.requestedPage, 1)
  assert.notEqual(scope.history.tools.error, '')
  assert.equal(scope.history.tools.loading, false)
  failure = false
  await scope.loadHistory('tools', scope.history.tools.requestedPage)
  assert.equal(scope.history.tools.page, 1)
  assert.equal(scope.history.tools.error, '')
})

test('an empty next history page marks the end without replacing the last visible page', async () => {
  const scope = agentHistoryScope(new Proxy({}, { get: (_, name) => async (...args) => {
    if (name === 'listAgents') return [{ id: 7 }]
    if (name === 'listModelInvocationEvents') return args[0].page === 0 ? [{ id: 1 }] : []
    return []
  } }))
  await scope.load()
  assert.equal(scope.history.models.hasNext, true)
  await scope.loadHistory('models', 1)
  assert.equal(scope.modelEvents.value[0].id, 1)
  assert.equal(scope.history.models.page, 0)
  assert.equal(scope.history.models.hasNext, false)
})

test('agent switch resets all history pages and ignores late history responses', async () => {
  const pending = deferred()
  const scope = agentHistoryScope(new Proxy({}, { get: (_, name) => async (...args) => {
    if (name === 'listAgents') return [{ id: 7 }, { id: 8 }]
    if (name === 'listTraceSpanObservations') {
      if (args[0].agentId === 7 && args[0].page === 1) return pending.promise
      return [{ id: args[0].agentId }]
    }
    return []
  } }))
  await scope.load()
  const oldPage = scope.loadHistory('spans', 1)
  scope.agentId.value = 8
  await scope.load()
  pending.resolve([{ id: 777 }]); await oldPage
  assert.equal(scope.spans.value[0].id, 8)
  for (const kind of scope.historyKinds) {
    assert.equal(scope.history[kind].page, 0)
    assert.equal(scope.history[kind].loading, false)
  }
})


test('a late dropped node cannot enter another workflow, even after switching back', async () => {
  const pending = deferred()
  let calls = 0
  const state = functionsFrom('views/WorkflowBuilderView.vue', ['onCanvasDrop'], {
    workflowRequestId: 1, selectedWorkflowId: ref(1), nodes: ref([]), selectedNode: ref(),
    canvasRef: ref({ getBoundingClientRect: () => ({ left: 0, top: 0 }) }),
    dragPayload: ref({ nodeType: 'AGENT', label: 'old' }), crypto: { randomUUID: () => 'uuid' },
    EapApi: { upsertWorkflowNode: () => { calls++; return pending.promise } },
  })
  const request = state.onCanvasDrop({ clientX: 100, clientY: 100 })
  await state.onCanvasDrop({ clientX: 100, clientY: 100 })
  assert.equal(calls, 1)
  state.workflowRequestId += 2 // Workflow 1 -> 2 -> 1, with a new canvas revision.
  pending.resolve({ nodeKey: 'old' }); await request
  assert.equal(state.nodes.value.length, 0)
  assert.equal(state.selectedNode.value, undefined)
  assert.equal(state.creatingNode.value, false)
})

test('duplicate connect clicks create one request and stale edges never enter a new canvas', async () => {
  const pending = deferred()
  let calls = 0
  const target = { nodeKey: 'target' }
  const state = functionsFrom('views/WorkflowBuilderView.vue', ['selectOrConnect'], {
    workflowRequestId: 1, selectedWorkflowId: ref(1), nodes: ref([target]), edges: ref([]), selectedNode: ref(),
    movedDuringDrag: ref(false), pendingSourceKey: ref('source'),
    EapApi: { addWorkflowEdge: () => { calls++; return pending.promise } },
    ElMessage: { success: () => assert.fail('stale success toast') },
  })
  const request = state.selectOrConnect(target)
  await state.selectOrConnect(target)
  assert.equal(calls, 1)
  state.selectedWorkflowId.value = 2; state.workflowRequestId++
  pending.resolve({ id: 1 }); await request
  assert.equal(state.edges.value.length, 0)
  assert.equal(state.creatingEdge.value, false)
})

test('an older preview cannot replace a newer result or clear its loading flag', async () => {
  const first = deferred(), second = deferred()
  let calls = 0
  const state = functionsFrom('views/WorkflowBuilderView.vue', ['runWorkflow'], {
    workflowRequestId: 1, selectedWorkflowId: ref(1), workflows: ref([{ id: 1, name: 'test' }]), lastRun: ref(),
    previewSeverity: ref('high'), previewApproved: ref(false),
    EapApi: { runWorkflow: () => ++calls === 1 ? first.promise : second.promise },
    ElMessage: { info: () => {} }, runStatusLabel: value => value,
  })
  const a = state.runWorkflow(), b = state.runWorkflow()
  first.resolve({ status: 'OLD' }); await a
  assert.equal(state.running.value, true)
  assert.equal(state.lastRun.value, undefined)
  second.resolve({ status: 'NEW' }); await b
  assert.equal(state.running.value, false)
  assert.equal(state.lastRun.value.status, 'NEW')
})


test('node creation works without secure-context UUID and releases its guard after failure', async () => {
  let calls = 0
  const state = functionsFrom('views/WorkflowBuilderView.vue', ['onCanvasDrop'], {
    workflowRequestId: 1, selectedWorkflowId: ref(1), nodes: ref([]), selectedNode: ref(),
    canvasRef: ref({ getBoundingClientRect: () => ({ left: 0, top: 0 }) }),
    dragPayload: ref({ nodeType: 'START', label: 'start' }), crypto: undefined,
    EapApi: { upsertWorkflowNode: async (_, node) => {
      calls++
      assert.ok(node.nodeKey.length <= 64)
      if (calls === 1) throw new Error('offline')
      return node
    } },
  })
  await assert.rejects(state.onCanvasDrop({ clientX: 100, clientY: 100 }), /offline/)
  assert.equal(state.creatingNode.value, false)
  state.dragPayload.value = { nodeType: 'START', label: 'start' }
  await state.onCanvasDrop({ clientX: 100, clientY: 100 })
  assert.equal(state.nodes.value.length, 1)
})

test('late workflow creation updates the catalog without taking over the current canvas', async () => {
  const pending = deferred()
  const state = functionsFrom('views/WorkflowBuilderView.vue', ['createWorkflow'], {
    workflowRequestId: 1, selectedWorkflowId: ref(1), workflows: ref([]), nodes: ref([{ nodeKey: 'current' }]),
    EapApi: { createWorkflow: () => pending.promise },
  })
  const request = state.createWorkflow()
  state.workflowRequestId++; state.selectedWorkflowId.value = 2
  pending.resolve({ id: 3 }); await request
  assert.equal(state.workflows.value[0].id, 3)
  assert.equal(state.selectedWorkflowId.value, 2)
  assert.equal(state.nodes.value[0].nodeKey, 'current')
})

test('an execution submission response cannot select a run on a reopened canvas', async () => {
  const pending = deferred()
  const state = functionsFrom('views/WorkflowBuilderView.vue', ['executeWorkflow'], {
    workflowRequestId: 1, selectedWorkflowId: ref(1), workflows: ref([{ id: 1, name: 'test' }]),
    executionAvailability: ref({ enabled: true }), submittingExecution: ref(false), selectedExecutionKey: ref(''),
    previewSeverity: ref('high'), previewApproved: ref(false),
    EapApi: { executeWorkflow: () => pending.promise },
    loadExecutions: () => assert.fail('stale submission refreshed the current canvas'),
  })
  const request = state.executeWorkflow()
  state.workflowRequestId += 2
  pending.resolve({ runKey: 'old-run' }); await request
  assert.equal(state.selectedExecutionKey.value, '')
  assert.equal(state.submittingExecution.value, false)
})

test('saving sends the loaded node version and preserves edits made while saving', async () => {
  const pending = deferred()
  const node = { nodeKey: 'start', label: 'first edit', version: 3 }
  const state = functionsFrom('views/WorkflowBuilderView.vue', ['saveSelectedNode'], {
    workflowRequestId: 1, selectedWorkflowId: ref(1), selectedNode: ref(node), nodes: ref([node]),
    EapApi: { upsertWorkflowNode: (_, request) => { assert.equal(request.expectedVersion, 3); return pending.promise } },
    ElMessage: { success() {} },
  })
  const request = state.saveSelectedNode()
  node.label = 'still typing'
  pending.resolve({ version: 4, label: 'first edit' }); await request
  assert.equal(node.version, 4)
  assert.equal(node.label, 'still typing')
})

test('a save conflict preserves the local draft and old version for explicit reload', async () => {
  const node = { nodeKey: 'start', label: 'unsaved draft', version: 3 }
  const state = functionsFrom('views/WorkflowBuilderView.vue', ['saveSelectedNode'], {
    workflowRequestId: 1, selectedWorkflowId: ref(1), selectedNode: ref(node), nodes: ref([node]),
    EapApi: { upsertWorkflowNode: async () => { throw new Error('409 conflict') } },
    ElMessage: { success: () => assert.fail('conflicting save must not report success') },
  })
  await assert.rejects(state.saveSelectedNode(), /409/)
  assert.equal(node.label, 'unsaved draft')
  assert.equal(node.version, 3)
})

test('evaluation polling publishes live counts and treats partial completion as warning', async () => {
  let callback
  const warnings = []
  const state = functionsFrom('views/EvaluationView.vue', ['waitForJob'], {
    pollingDisposed: false, pollTimer: undefined, finishPollDelay: undefined,
    jobs: ref([{ id: 1, status: 'RUNNING' }]),
    window: { setTimeout: fn => { callback = fn; return 1 } },
    EapApi: { getEvaluationJob: async () => ({ id: 1, status: 'PARTIAL', processed: 10, total: 10, failed: 2 }) },
    ElMessage: { warning: message => warnings.push(message) },
  })
  const polling = state.waitForJob(1)
  callback(); await polling
  assert.equal(state.activeJob.value.processed, 10)
  assert.equal(state.jobs.value[0].status, 'PARTIAL')
  assert.equal(warnings.length, 1)
})

test('lost progress connection preserves the active job and offers reconnection', async () => {
  let callback
  const original = { id: 1, status: 'RUNNING', processed: 5 }
  const state = functionsFrom('views/EvaluationView.vue', ['waitForJob'], {
    pollingDisposed: false, pollTimer: undefined, finishPollDelay: undefined,
    pageError: ref(''), activeJob: ref(original),
    window: { setTimeout: fn => { callback = fn; return 1 } },
    EapApi: { getEvaluationJob: async () => { throw new Error('offline') } },
  })
  const polling = state.waitForJob(1)
  callback(); await polling
  assert.equal(state.activeJob.value, original)
  assert.match(state.pageError.value, /继续跟踪/)
})

test('uncertain evaluation submission does not automatically send another job', async () => {
  let calls = 0
  const state = functionsFrom('views/EvaluationView.vue', ['synchronize'], {
    syncing: ref(false), pollingDisposed: false, pageError: ref(''),
    EapApi: { submitEvaluationJob: async () => { calls++; throw new Error('network lost') } },
  })
  await state.synchronize()
  assert.equal(calls, 1)
  assert.equal(state.syncing.value, false)
  assert.match(state.pageError.value, /提交状态未确认/)
})

test('evaluation load ignores older page responses', async () => {
  const first = deferred(), second = deferred()
  let calls = 0
  const state = functionsFrom('views/EvaluationView.vue', ['load'], {
    loadRequestId: 0, pollingDisposed: false, syncing: ref(false), loading: ref(false), pageError: ref(''),
    evaluators: ref([]), datasets: ref([]), results: ref([]), summary: ref({}), contentAnalysis: ref(),
    selectedDatasetKey: ref(''), selectedCalibrationRunId: ref(''), form: { evaluatorKey: '' },
    EapApi: { listEvaluators: async () => [], listEvaluationDatasets: async () => [],
      listEvaluationResults: () => (++calls === 1 ? first.promise : second.promise),
      listEvaluationJobs: async () => [], getEvaluationSummary: async () => ({}), getContentAnalysisStatus: async () => null },
  })
  const a = state.load(), b = state.load()
  second.resolve([{ id: 2 }]); await b
  first.resolve([{ id: 1 }]); await a
  assert.equal(state.results.value[0].id, 2)
  assert.equal(state.loading.value, false)
})

function commandCenterState(getData) {
  return functionsFrom('views/CommandCenterView.vue', ['refresh', 'visibleRefresh', 'number', 'rate', 'matchingAgents', 'reconcileTopology'], {
    disposed: false, loading: ref(false), failedSources: ref([]), lastUpdated: ref('previous'),
    sourceUpdated: ref({}), agentPage: ref(0), documentHidden: ref(false), agentQuery: ref(''), focusConnections: ref(false),
    ops: ref({ agentCount: 7 }), network: ref({ nodes: [] }), selectedNode: ref(), telemetry: ref(),
    evaluation: ref(), management: ref(), platform: ref(), jobs: ref([]),
    autoRefresh: ref(true), document: { hidden: false }, getData,
  })
}
test('command center retains failed source values without advancing complete-refresh timestamp', async () => {
  const state = commandCenterState(async (url, options) => {
    assert.equal(options.silentError, true)
    if (url.includes('agentops')) throw new Error('offline')
    if (url.includes('network')) return { nodes: [] }
    if (url.includes('jobs')) return []
    return { count: 12 }
  })
  await state.refresh()
  assert.equal(state.ops.value.agentCount, 7)
  assert.equal(state.telemetry.value.count, 12)
  assert.deepEqual(Array.from(state.failedSources.value), ['运行健康'])
  assert.equal(state.lastUpdated.value, 'previous')
  assert.equal(state.loading.value, false)
})
test('command center prevents overlapping refreshes and ignores results after unmount', async () => {
  const pending = deferred()
  let calls = 0
  const state = commandCenterState(() => { calls++; return pending.promise })
  const first = state.refresh()
  await state.refresh()
  assert.equal(calls, 7)
  state.disposed = true
  pending.resolve({ nodes: [], agentCount: 99 })
  await first
  assert.equal(state.ops.value.agentCount, 7)
  assert.equal(state.lastUpdated.value, 'previous')
})
test('command center suppresses background polling and distinguishes absent metrics from zero', () => {
  let calls = 0
  const state = commandCenterState(async () => { calls++; return {} })
  state.document.hidden = true
  state.visibleRefresh()
  state.document.hidden = false
  state.autoRefresh.value = false
  state.visibleRefresh()
  assert.equal(calls, 0)
  assert.equal(state.number(undefined), '—')
  assert.equal(state.number(0), '0')
  assert.equal(state.rate(0, 0), '—')
  assert.equal(state.rate(0, 10), '0.0%')
})


test('agent details skip forbidden metadata and discard a response after access is revoked', async () => {
  let requests = 0
  const pending = deferred()
  const scope = agentHistoryScope({ listModelInvocationEvents: () => { requests++; return pending.promise } })
  let allowed = false
  scope.permissions = { state: { user: {} }, can: () => allowed }
  scope.modelEvents.value = [{ content: 'previous user' }]
  await scope.loadHistory('models')
  assert.equal(requests, 0)
  assert.equal(scope.modelEvents.value.length, 0)
  allowed = true
  const request = scope.loadHistory('models')
  assert.equal(requests, 1)
  allowed = false
  scope.resetHistory()
  pending.resolve([{ content: 'late response' }])
  await request
  assert.equal(scope.modelEvents.value.length, 0)
  assert.equal(scope.history.models.loading, false)
})

test('agent details discard metadata from a previous identity even when both can view it', async () => {
  const pending = deferred()
  const scope = agentHistoryScope({ listModelInvocationEvents: () => pending.promise })
  const request = scope.loadHistory('models')
  scope.permissions.state.user = { subject: 'next-user' }
  pending.resolve([{ content: 'old identity' }])
  await request
  assert.equal(scope.modelEvents.value.length, 0)
  assert.equal(scope.history.models.hasNext, false)
})

for (const status of [401, 503]) {
  test(`workflow initial HTTP ${status} shows a recoverable error without an unhandled rejection`, async () => {
    const state = functionsFrom('views/WorkflowBuilderView.vue', ['load'], {
      agents: ref([]), workflows: ref([]), selectedWorkflowId: ref(),
      EapApi: { listAgents: async () => { throw { response: { status } } }, listWorkflows: async () => [] },
    })
    await state.load()
    assert.match(state.loadError.value, /加载失败/)
    assert.equal(state.selectedWorkflowId.value, undefined)
  })
}

test('unsaved or invalid tool drafts cannot reach the execution API', async () => {
  for (const [unsaved, error] of [[true, ''], [false, 'invalid numbers']]) {
    const state = functionsFrom('views/WorkflowBuilderView.vue', ['executeWorkflow'], {
      selectedWorkflowId: ref(1), executionAvailability: ref({ enabled: true }), submittingExecution: ref(false),
      hasUnsavedConfig: ref(unsaved), nodeConfigError: ref(error), ElMessage: { error() {} },
      EapApi: { executeWorkflow: () => assert.fail('must not dispatch') },
    })
    await state.executeWorkflow()
    assert.equal(state.submittingExecution.value, false)
  }
})

function planWorkspace(api) {
  return functionsFrom('views/OrchestrationView.vue', ['loadTeamDetail', 'selectPlan'], {
    teamLoading: ref(false), planLoading: ref(false), teamError: ref(''), planError: ref(''),
    selectedExecution: ref({ id: 'last-execution' }), disposed: false, teamRequestId: 0, planRequestId: 0,
    selectedTeamId: ref(1), selectedPlanId: ref(11), members: ref([{ id: 1 }]), plans: ref([{ id: 11 }]), planSteps: ref([{ id: 'last-step' }]), EapApi: api,
  })
}

test('failed team read ends loading and reports failure separately from an empty team', async () => {
  const state = planWorkspace({ listTeamMembers: async () => [], listTeamPlans: async () => { throw Error('503') } })
  await state.loadTeamDetail()
  assert.equal(state.teamLoading.value, false)
  assert.match(state.teamError.value, /读取失败/)
  assert.equal(state.selectedPlanId.value, undefined)
  assert.equal(state.planSteps.value.length, 0)
})

test('an older plan failure cannot end the latest loading state or replace its error', async () => {
  const first = deferred(), second = deferred()
  const state = planWorkspace({ getPlan: id => id === 11 ? first.promise.then(() => { throw Error('503') }) : second.promise })
  const old = state.selectPlan(11)
  const current = state.selectPlan(12)
  first.resolve(); await old
  assert.equal(state.planLoading.value, true)
  assert.equal(state.planError.value, '')
  second.resolve({ steps: [{ id: 'current-step' }] }); await current
  assert.equal(state.planLoading.value, false)
  assert.equal(state.planSteps.value[0].id, 'current-step')
})

test('background progress refresh retains last evidence and labels failed refreshes', async () => {
  const pending = deferred()
  const state = planWorkspace({ getPlan: () => pending.promise.then(() => { throw Error('503') }) })
  const refresh = state.selectPlan(11, true)
  assert.equal(state.planSteps.value[0].id, 'last-step')
  assert.equal(state.selectedExecution.value.id, 'last-execution')
  assert.equal(state.planLoading.value, false)
  pending.resolve(); await refresh
  assert.equal(state.planSteps.value[0].id, 'last-step')
  assert.match(state.planError.value, /上次读取/)
})
