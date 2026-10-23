import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
const vue = readFileSync(new URL('../src/components/TeamEvolutionOperations.vue', import.meta.url), 'utf8')
const source = vue.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
const availability = { modelProposalAvailable: true, contractsEnabled: true, budgetAdmissionAvailable: true, slotExecutionAvailable: true, platformScoringAvailable: true, independentHoldoutAvailable: true }
const detail = () => ({ summary: { id: 7, baselineId: 'base', candidates: [{ id: 'base', generation: 0 }], attempts: 0, maxAttempts: 4, maxCandidates: 2, maxGeneration: 1 }, roles: [{ role: 'extractor', agentId: 1 }, { role: 'classifier', agentId: 2 }], sampleCounts: { TRAIN: 1, VALIDATION: 1, HOLDOUT: 1 }, frontier: { sealed: false, selectedCandidateId: null, scores: [] }, evaluations: [], currentCosts: { pendingCalls: 0 }, budget: null, holdout: null, closure: null })
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function scope(overrides = {}) {
  const ex = {}; vm.runInNewContext(ts.transpile(readFileSync(new URL('../src/api/teamOperationSession.ts', import.meta.url), 'utf8'), { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS }), { exports: ex })
  const watchers = [], writes = [], props = { detail: detail(), loading: false }; let n = 0
  const permissions = { state: { user: {} }, can: () => true }
  const s = vm.createContext({ console, TextEncoder, Date, crypto: { randomUUID: () => `key-${++n}` }, defineProps: () => props, defineEmits: () => () => {}, usePermissions: () => permissions, reactive: v => v, ref: v => ({ value: v }), computed: f => ({ get value() { return f() } }), watch: (get, fn) => watchers.push(fn), onMounted: () => {}, onUnmounted: () => {}, ...ex,
    EapApi: { listAgents: async () => [] }, ExperimentApi: { targets: async () => ({ instances: [] }) }, TeamEvolutionApi: { content: async () => ({ candidates: [{ id: 'base', configuration: { rolePrompts: { extractor: 'original', classifier: 'unchanged' } } }] }) },
    TeamOperationApi: { proposals: async () => [], proposalContent: async () => ({ parentId: 'base', configuration: { rolePrompts: { extractor: 'generated', classifier: 'unchanged' } } }), status: async () => availability, context: async () => ({ originalSubmitter: true, maxTokens: 128, availability }), send: async i => { writes.push(i); return { id: 9 } }, receipt: async () => { throw Error('unused') } }, ...overrides })
  vm.runInContext(ts.transpile(source + '\nthis.s = { draft,budget,mode,state,context,availability,fresh,formError,readError,parentId,parentPrompts,parentLoading,changedRole,newPrompt,candidateId,evaluationId,confirmed,targets,options,session,prepare,submit,readParent,loadTargets,refreshContext,openCreate,choose,recover,clearPrivate,proposals,proposalDraft,proposalError,readProposalDraft,useProposalDraft,inspectProposal }', { target: ts.ScriptTarget.ES2022 }), s)
  s.s.context.value = { originalSubmitter: true, maxTokens: 128, availability }; s.s.availability.value = availability; s.s.fresh.value = true
  return { ...s.s, props, permissions, watchers, writes }
}
function fillCreate(s) {
  s.mode.value = 'CREATE'; s.draft.publicSamplesConfirmed = true
  s.draft.roles[0].agentId = 1; s.draft.roles[0].instanceKey = 'test/one'; s.draft.roles[1].agentId = 2; s.draft.roles[1].instanceKey = 'test/two'
  for (const sample of s.draft.samples) { sample.input = 'Public ' + sample.id; sample.expected = 'Expected ' + sample.id }
}
test('create form freezes explicit public samples and role selections without sending on review', () => {
  const s = scope(); fillCreate(s); s.prepare(); assert.equal(s.state.phase, 'REVIEW'); assert.equal(s.writes.length, 0); assert.equal(s.state.intent.body.publicSamplesConfirmed, true); assert.equal(s.state.intent.body.roles.length, 2); assert.equal(s.state.intent.body.samples.length, 3); assert.equal('localId' in s.state.intent.body.samples[0], false)
  s.draft.samples[0].input = 'later edit'; assert.equal(s.state.intent.body.samples[0].input, 'Public train-1')
})
test('invalid public confirmation role duplication and multibyte sample limits do not prepare writes', () => {
  for (const change of [s => s.draft.publicSamplesConfirmed = false, s => s.draft.roles[1].agentId = 1, s => s.draft.samples[0].input = '字'.repeat(667), s => s.draft.samples[0].split = 'HOLDOUT']) {
    const s = scope(); fillCreate(s); change(s); s.prepare(); assert.equal(s.state.phase, 'IDLE'); assert.ok(s.formError.value); assert.equal(s.writes.length, 0)
  }
})
test('candidate editing explicitly reads the selected parent and preserves the other role', async () => {
  const s = scope(); s.choose('CANDIDATE'); s.parentId.value = 'base'; await s.readParent(); s.changedRole.value = 'extractor'; s.newPrompt.value = 'changed'; s.prepare()
  assert.equal(s.state.intent.body.configuration.rolePrompts.extractor, 'changed'); assert.equal(s.state.intent.body.configuration.rolePrompts.classifier, 'unchanged'); assert.equal(s.state.intent.body.parentId, 'base'); assert.equal(s.writes.length, 0)
})
test('a late parent read or runtime target list cannot restore a previous selection', async () => {
  const p = deferred(), t = deferred(); const s = scope({ TeamEvolutionApi: { content: () => p.promise }, ExperimentApi: { targets: () => t.promise } })
  s.mode.value = 'CANDIDATE'; s.parentId.value = 'base'; const parent = s.readParent(); s.clearPrivate(); p.resolve({ candidates: [{ id: 'base', configuration: { rolePrompts: { extractor: 'private', classifier: 'private' } } }] }); await parent; assert.equal(s.parentPrompts.value, null)
  s.mode.value = 'CREATE'; s.draft.roles[0].agentId = 1; const target = s.loadTargets(0); s.clearPrivate(); t.resolve({ instances: [{ instanceKey: 'old' }] }); await target; assert.equal(s.targets.value[0].length, 0)
})
test('budget review enforces complete bounds explicit consent and a frozen absolute deadline', () => {
  const s = scope(); s.choose('BUDGET'); Object.assign(s.budget, { businessTask: 'public test', maxCalls: 8, maxOutputTokens: 1024, confirmed: true, approvalExpiresAt: new Date(Date.now() + 120000).toISOString() }); s.prepare()
  assert.equal(s.state.phase, 'REVIEW'); assert.equal(s.state.intent.body.proposalMode, 'OFFLINE_ONLY'); assert.equal(s.state.intent.body.maxCalls, 8); const deadline = s.state.intent.body.approvalExpiresAt
  s.budget.approvalExpiresAt = new Date(Date.now() + 600000).toISOString(); assert.equal(s.state.intent.body.approvalExpiresAt, deadline); assert.equal(s.writes.length, 0)
  const bad = scope(); bad.choose('BUDGET'); Object.assign(bad.budget, { businessTask: 'public test', maxCalls: 7, maxOutputTokens: 1024, confirmed: true, approvalExpiresAt: new Date(Date.now() + 120000).toISOString() }); bad.prepare(); assert.equal(bad.state.phase, 'IDLE'); assert.match(bad.formError.value, /额度不足/)
})
test('closed foreign and already failed or unknown roots expose no execution retry', () => {
  const s = scope(); s.props.detail.budget = { approvalExpiresAt: new Date(Date.now() + 600000).toISOString() }
  for (const state of ['UNKNOWN', 'FAILED', 'RUNNING', 'RECEIPTS_COMPLETE']) { s.props.detail.evaluations = [{ registration: { id: 'e', split: 'VALIDATION', state }, execution: { state }, score: null }]; assert.equal(s.options.value.find(o => o.action === 'EXECUTE').enabled, false) }
  s.props.detail.closure = {}; assert.ok(s.options.value.every(o => !o.enabled)); s.props.detail.closure = null; s.context.value.originalSubmitter = false; assert.ok(s.options.value.every(o => !o.enabled))
})
test('account or content capability changes clear forms previews and pending private state', async () => {
  const s = scope(); fillCreate(s); s.prepare(); s.permissions.state.user = {}; s.watchers[1](); assert.equal(s.state.intent, null); assert.equal(s.draft.samples[0].input, ''); assert.equal(s.parentPrompts.value, null); assert.equal(s.mode.value, null)
})
test('confirmation never extends an expired approval or submits without the second checkbox', async () => {
  const s = scope(); s.choose('BUDGET'); Object.assign(s.budget, { businessTask: 'public', maxCalls: 8, maxOutputTokens: 1024, confirmed: true, approvalExpiresAt: new Date(Date.now() + 120000).toISOString() }); s.prepare(); s.submit(); assert.equal(s.writes.length, 0)
  s.confirmed.value = true; s.state.intent.body.approvalExpiresAt = new Date(Date.now() - 1000).toISOString(); s.submit(); assert.equal(s.writes.length, 0); assert.match(s.readError.value, /期限已不足/)
})
test('operation page uses central capabilities explicit actions and no automatic adoption or persistent private drafts', () => {
  assert.match(vue, /permissions.can\('runAgentExperiments'\)/); assert.match(vue, /只读核对原请求/); assert.match(vue, /原创建账号/); assert.match(vue, /确认提交一次/); assert.doesNotMatch(vue, /setInterval|localStorage|sessionStorage|v-html|\.roles\.includes|parseJwt/)
})

const proposal = (state = 'COMPLETED') => ({ contractId: 7, requestKey: 'original-proposal', parentId: 'base', role: 'extractor', state, draftAvailable: state === 'COMPLETED', usageState: 'SETTLED', inputTokens: 2, outputTokens: 3 })
function modelBudget(s) { s.props.detail.budget = { proposalMode: 'MODEL_SINGLE_ROLE', maxProposalCalls: 1, maxProposalTokens: 512, approvalExpiresAt: new Date(Date.now() + 120000).toISOString() } }
test('model budget requires separate purpose consent and covers proposal plus evaluations', () => {
  for (const consent of [false, true]) {
    const s = scope(); s.choose('BUDGET'); Object.assign(s.budget, { businessTask: 'public', confirmed: true, proposalMode: 'MODEL_SINGLE_ROLE', proposalUseConfirmed: consent, maxProposalCalls: 1, maxProposalTokens: 512, maxCalls: 9, maxOutputTokens: 1536, approvalExpiresAt: new Date(Date.now() + 120000).toISOString() }); s.prepare()
    assert.equal(s.state.phase, consent ? 'REVIEW' : 'IDLE'); assert.equal(s.writes.length, 0)
    if (consent) { assert.equal(s.state.intent.body.maxProposalCalls, 1); assert.equal(s.state.intent.body.maxProposalTokens, 512); assert.equal(s.state.intent.body.proposalUseConfirmed, true); assert.equal(s.state.intent.body.maxCalls, 9) }
  }
  const s = scope(); s.choose('BUDGET'); Object.assign(s.budget, { businessTask: 'public', confirmed: true, proposalMode: 'MODEL_SINGLE_ROLE', proposalUseConfirmed: true, maxCalls: 9, maxOutputTokens: 1535, approvalExpiresAt: new Date(Date.now() + 120000).toISOString() }); s.prepare(); assert.equal(s.state.phase, 'IDLE'); assert.match(s.formError.value, /额度不足/)
})
test('proposal request freezes only original parent role and key without forwarding any sample content', () => {
  const s = scope(); modelBudget(s); s.choose('PROPOSE'); s.parentId.value = 'base'; s.changedRole.value = 'extractor'; s.prepare(); assert.equal(s.state.phase, 'REVIEW'); assert.deepEqual(Object.keys(s.state.intent.body).sort(), ['parentId', 'requestKey', 'role']); assert.equal(s.writes.length, 0); assert.match(s.state.intent.summary.join(' '), /最多1次模型调用/)
})
test('original unresolved proposals block new proposals and frontier while a completed allowance is not reusable', () => {
  const s = scope(); modelBudget(s)
  for (const state of ['PREPARING', 'DISPATCHED', 'UNKNOWN', 'COMPLETED', 'FAILED']) { s.proposals.value = [proposal(state)]; assert.equal(s.options.value.find(o => o.action === 'PROPOSE').enabled, false) }
  s.proposals.value = [proposal('UNKNOWN')]; assert.equal(s.options.value.find(o => o.action === 'FRONTIER').enabled, false)
  s.proposals.value = [proposal('DISPATCHED')]; assert.equal(s.options.value.find(o => o.action === 'CLOSE').enabled, false)
})
test('explicit draft read preserves other role and only fills a separately reviewed candidate form', async () => {
  const s = scope(); modelBudget(s); await s.readProposalDraft(proposal()); assert.equal(s.proposalDraft.value.prompt, 'generated'); assert.equal(s.mode.value, null); assert.equal(s.writes.length, 0)
  s.useProposalDraft(); assert.equal(s.mode.value, 'CANDIDATE'); assert.equal(s.proposalDraft.value, null); assert.equal(s.newPrompt.value, 'generated'); s.prepare(); assert.equal(s.state.phase, 'REVIEW'); assert.equal(s.state.intent.body.configuration.rolePrompts.classifier, 'unchanged'); assert.equal(s.writes.length, 0)
})
test('draft tampering private read failure and late account replies never preserve old private content', async () => {
  const s = scope({ TeamOperationApi: { proposalContent: async () => ({ parentId: 'base', configuration: { rolePrompts: { extractor: 'changed', classifier: 'tampered' } } }) } }); await s.readProposalDraft(proposal()); assert.equal(s.proposalDraft.value, null); assert.ok(s.proposalError.value)
  const pending = deferred(), late = scope({ TeamOperationApi: { proposalContent: () => pending.promise } }); const read = late.readProposalDraft(proposal()); late.clearPrivate(); pending.resolve({ parentId: 'base', configuration: { rolePrompts: { extractor: 'late', classifier: 'unchanged' } } }); await read; assert.equal(late.proposalDraft.value, null)
})
test('metadata read failure clears old proposal records and disables further writes', async () => {
  const s = scope({ TeamOperationApi: { status: async () => availability, context: async () => ({ originalSubmitter: true, maxTokens: 128, availability }), proposals: async () => { throw Error('unavailable') } } }); s.proposals.value = [proposal('UNKNOWN')]; await s.refreshContext(); assert.equal(s.proposals.value.length, 0); assert.equal(s.fresh.value, false); assert.equal(s.context.value, null)
})
