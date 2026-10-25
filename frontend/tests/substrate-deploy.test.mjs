import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

const source = readFileSync(new URL('../src/api/substrateDeploy.ts', import.meta.url), 'utf8')
const scope = vm.createContext({ exports: {} })
vm.runInContext(ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }), scope)
const { buildDeployPayload, defaultDeployForm, validateDeployForm, deploymentProgress, actorStateLabel, deployStages, failedStageIndex } = scope.exports

test('runner payload carries only runner fields, drops blank env rows and normalizes hosts', () => {
  const form = { ...defaultDeployForm(), systemPrompt: '  你是助手  ', image: 'ignored', env: [{ name: 'TONE', value: 'formal' }, { name: ' ', value: 'x' }],
    egressHosts: 'API.Example.com\n\n docs.example.com, ' }
  const payload = buildDeployPayload(form, 'k1')
  assert.equal(payload.sourceType, 'RUNNER')
  assert.equal(payload.systemPrompt, '你是助手')
  assert.equal(payload.image, undefined)
  assert.deepEqual({ ...payload.env }, { TONE: 'formal' })
  assert.deepEqual([...payload.egressHosts], ['api.example.com', 'docs.example.com'])
  assert.equal(payload.requestKey, 'k1')
  assert.equal(payload.maxTokens, 1024)
  assert.equal(payload.thinkingMode, 'PROVIDER_DEFAULT')
})

test('image payload splits command lines and defaults the readiness path', () => {
  const form = { ...defaultDeployForm(), sourceType: 'IMAGE', image: ' localhost:5001/a:1 ', readinessPath: '', command: '/app\n--port=80\n' }
  const payload = buildDeployPayload(form, 'k2')
  assert.equal(payload.image, 'localhost:5001/a:1')
  assert.equal(payload.readinessPath, '/')
  assert.deepEqual([...payload.command], ['/app', '--port=80'])
  assert.equal(payload.systemPrompt, undefined)
  assert.equal(payload.maxTokens, undefined)
  assert.equal(payload.thinkingMode, undefined)
})

test('framework selection changes publication identity while native keeps its legacy payload', () => {
  const native = { ...defaultDeployForm(), systemPrompt: 'text task' }
  assert.equal(buildDeployPayload(native, 'original').runnerFramework, undefined)
  const kagent = { ...native, runnerFramework: 'KAGENT_ADK' }
  assert.equal(validateDeployForm(kagent), null)
  assert.equal(buildDeployPayload(kagent, 'original').runnerFramework, 'KAGENT_ADK')
  assert.notEqual(JSON.stringify(buildDeployPayload(native, 'original')), JSON.stringify(buildDeployPayload(kagent, 'original')))
  assert.equal(buildDeployPayload({ ...kagent, sourceType: 'IMAGE', image: 'test/image' }, 'image').runnerFramework, undefined)
  assert.match(validateDeployForm({ ...kagent, runnerFramework: 'unverified-engine' }), /运行框架/)
})

test('validation blocks secrets and reserved names before submission', () => {
  const base = { ...defaultDeployForm(), systemPrompt: 'x' }
  assert.equal(validateDeployForm(base), null)
  assert.match(validateDeployForm({ ...base, env: [{ name: 'OPENAI_API_KEY', value: 'sk' }] }), /密钥/)
  assert.match(validateDeployForm({ ...base, env: [{ name: 'EAP_TASK_TOKEN', value: 'x' }] }), /保留/)
  assert.match(validateDeployForm({ ...base, systemPrompt: ' ' }), /提示词/)
  assert.match(validateDeployForm({ ...defaultDeployForm(), sourceType: 'IMAGE', image: 'a', readinessPath: 'readyz' }), /\//)
})

test('status labels', () => {
  assert.equal(deploymentProgress('READY').active, 5)
  assert.equal(deploymentProgress('BUILDING_GOLDEN').label, '生成快照')
  assert.equal(deploymentProgress('FAILED').state, 'error')
  assert.equal(actorStateLabel('SUSPENDED').label, '休眠')
  assert.equal(actorStateLabel('weird').label, '未知')
})

test('source payload carries git fields with defaults and no image', () => {
  const form = { ...defaultDeployForm(), sourceType: 'SOURCE', gitUrl: ' https://gitee.com/org/agent.git ', gitRef: ' ', dockerfile: '', port: 8080 }
  const payload = buildDeployPayload(form, 'k3')
  assert.equal(payload.gitUrl, 'https://gitee.com/org/agent.git')
  assert.equal(payload.gitRef, 'main')
  assert.equal(payload.dockerfile, 'Dockerfile')
  assert.equal(payload.image, undefined)
  assert.equal(payload.port, 8080)
  assert.equal(payload.maxTokens, undefined)
  assert.equal(payload.thinkingMode, undefined)
})

test('model controls validate bounds and supported overrides before publication', () => {
  const base = { ...defaultDeployForm(), systemPrompt: 'x', maxTokens: 4096, thinkingMode: 'DISABLED' }
  assert.equal(validateDeployForm(base), null)
  assert.equal(buildDeployPayload(base, 'configured').maxTokens, 4096)
  assert.equal(buildDeployPayload(base, 'configured').thinkingMode, 'DISABLED')
  for (const maxTokens of [0, -1, 8193, 1.5, NaN, undefined]) assert.match(validateDeployForm({ ...base, maxTokens }), /输出上限/)
  for (const maxTokens of [1, 8192]) assert.equal(validateDeployForm({ ...base, maxTokens }), null)
  assert.match(validateDeployForm({ ...base, model: 'other-model' }), /关闭思考/)
  assert.match(validateDeployForm({ ...base, thinkingMode: 'INVALID' }), /思考模式/)
  assert.equal(validateDeployForm({ ...base, model: 'other-model', thinkingMode: 'PROVIDER_DEFAULT' }), null)
})

test('source validation rejects credentials, queries and path traversal', () => {
  const base = { ...defaultDeployForm(), sourceType: 'SOURCE', gitUrl: 'https://gitee.com/org/agent.git' }
  assert.equal(validateDeployForm(base), null)
  assert.match(validateDeployForm({ ...base, gitUrl: 'https://u:p@gitee.com/a/b' }), /https/)
  assert.equal(validateDeployForm({ ...base, gitUrl: 'http://git.internal/a/b' }), null)
  assert.match(validateDeployForm({ ...base, gitUrl: 'ftp://gitee.com/a/b' }), /https/)
  assert.match(validateDeployForm({ ...base, contextPath: '../x' }), /\.\./)
})

test('source deployments show a build stage before the template', () => {
  assert.equal(deployStages('SOURCE').length, 6)
  assert.equal(deployStages('RUNNER').length, 5)
  assert.equal(deploymentProgress('BUILDING_IMAGE', 'SOURCE').active, 1)
  assert.equal(deploymentProgress('QUEUED', 'SOURCE').active, 2)
  assert.equal(deploymentProgress('READY', 'SOURCE').active, 6)
  assert.equal(deploymentProgress('READY').active, 5)
})

test('a failed deployment marks the stage that actually failed', () => {
  const golden = [{ step: 'VALIDATE', status: 'DONE' }, { step: 'BUILD_IMAGE', status: 'DONE' }, { step: 'CREATE_TEMPLATE', status: 'DONE' },
    { step: 'GOLDEN_SNAPSHOT', status: 'FAILED' }]
  assert.equal(failedStageIndex(golden, 'SOURCE'), 3)
  assert.equal(failedStageIndex([{ step: 'BUILD_IMAGE', status: 'FAILED' }], 'SOURCE'), 1)
  assert.equal(failedStageIndex([{ step: 'CREATING_INSTANCE', status: 'FAILED' }], 'RUNNER'), 3)
  assert.equal(failedStageIndex([{ step: 'PREFLIGHT', status: 'DENY' }], 'RUNNER'), 0)
})

test('dedicated identity is a separate durable stage while legacy progress stays unchanged', () => {
  assert.equal(deploymentProgress('CONNECTING_IDENTITY').label, '专属身份接入')
  assert.equal(deployStages('RUNNER', 'PER_INSTANCE_V1').length, 6)
  assert.equal(deploymentProgress('READY', 'RUNNER', 'PER_INSTANCE_V1').active, 6)
  assert.equal(deploymentProgress('READY', 'RUNNER').active, 5)
  assert.equal(failedStageIndex([{ step: 'CONNECTING_IDENTITY', status: 'FAILED' }], 'RUNNER', 'PER_INSTANCE_V1'), 4)
})

test('custom programs must listen on the identity router port before new publication', () => {
  for (const sourceType of ['IMAGE', 'SOURCE']) {
    const form = { ...defaultDeployForm(), sourceType, image: 'registry/a@sha256:a', gitUrl: 'https://git.example/a.git' }
    assert.equal(validateDeployForm(form), null)
    for (const port of [8080, 0, 65536, 80.5]) assert.match(validateDeployForm({ ...form, port }), /端口 80/)
  }
})

test('secure source progress preserves image building and places identity after instance creation', () => {
  const stages = deployStages('SOURCE', 'PER_INSTANCE_V1')
  assert.deepEqual([...stages], ['校验与治理预检', '构建镜像', '创建模板', '生成快照', '创建实例', '专属身份接入', '可运行'])
  assert.equal(deploymentProgress('CONNECTING_IDENTITY', 'SOURCE', 'PER_INSTANCE_V1').active, 5)
  assert.equal(deploymentProgress('READY', 'SOURCE', 'PER_INSTANCE_V1').active, 7)
  assert.equal(failedStageIndex([{ step: 'CONNECTING_IDENTITY', status: 'FAILED' }], 'SOURCE', 'PER_INSTANCE_V1'), 5)
  assert.equal(failedStageIndex([{ step: 'BUILD_IMAGE', status: 'FAILED' }], 'SOURCE', 'PER_INSTANCE_V1'), 1)
  assert.equal(deployStages('SOURCE').length, 6)
})


test('Kagent tool selection is explicit and never leaks into native or image publications', () => {
  const form = { ...defaultDeployForm(), runnerFramework: 'KAGENT_ADK', systemPrompt: 'test' }
  assert.equal(buildDeployPayload(form, 'same').runnerToolMode, undefined)
  const selected = { ...form, runnerToolMode: 'CALCULATE_SUM_V1' }
  assert.equal(validateDeployForm(selected), null)
  assert.equal(buildDeployPayload(selected, 'same').runnerToolMode, 'CALCULATE_SUM_V1')
  assert.notDeepEqual(buildDeployPayload(form, 'same'), buildDeployPayload(selected, 'same'))
  assert.equal(buildDeployPayload({ ...selected, runnerFramework: 'EAP_NATIVE' }, 'native').runnerToolMode, undefined)
  assert.equal(buildDeployPayload({ ...selected, sourceType: 'IMAGE' }, 'image').runnerToolMode, undefined)
  assert.match(validateDeployForm({ ...selected, runnerToolMode: 'MCP_ALL' }), /工具模式/)
})


test('fixed samples discard unrelated drafts and freeze deployment fields', () => {
  const form = { ...defaultDeployForm(), sourceType: 'TEMPLATE', frameworkSample: 'LANGGRAPH_SUM_V1',
    image: 'caller/image', command: '/bin/sh', systemPrompt: 'old prompt', model: 'old-model',
    port: 3000, sandboxClass: 'MICROVM', env: [{ name: 'PATH', value: '/tmp' }], egressHosts: 'example.com' }
  assert.equal(validateDeployForm(form), null)
  const payload = JSON.parse(JSON.stringify(buildDeployPayload(form, 'original')))
  assert.deepEqual(payload, { sourceType: 'TEMPLATE', runnerFramework: 'LANGGRAPH_SUM_V1',
    sandboxClass: 'GVISOR', size: 'SMALL', env: {}, egressHosts: [], requestKey: 'original' })
})

test('changing sample identity changes the immutable submission payload', () => {
  const form = { ...defaultDeployForm(), sourceType: 'TEMPLATE' }
  const first = buildDeployPayload(form, 'original')
  const second = buildDeployPayload({ ...form, frameworkSample: 'AUTOGEN_SUM_V1' }, 'original')
  assert.notEqual(first.runnerFramework, second.runnerFramework)
  assert.equal(second.runnerFramework, 'AUTOGEN_SUM_V1')
  assert.equal(validateDeployForm({ ...form, frameworkSample: 'CREWAI' }).includes('样例'), true)
  assert.equal(validateDeployForm({ ...form, sourceType: 'UNKNOWN' }).includes('部署方式'), true)
})

test('sample selection does not leak into native, image or source publication', () => {
  const form = { ...defaultDeployForm(), frameworkSample: 'AUTOGEN_SUM_V1', systemPrompt: 'native' }
  assert.equal(buildDeployPayload(form, 'native').runnerFramework, undefined)
  assert.equal(buildDeployPayload({ ...form, sourceType: 'IMAGE', image: 'example/image' }, 'image').runnerFramework, undefined)
  assert.equal(buildDeployPayload({ ...form, sourceType: 'SOURCE', gitUrl: 'https://example.com/code' }, 'source').runnerFramework, undefined)
  assert.equal(deployStages('TEMPLATE', 'PER_INSTANCE_V1').length, deployStages('IMAGE', 'PER_INSTANCE_V1').length)
})
