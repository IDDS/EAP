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
})

test('image payload splits command lines and defaults the readiness path', () => {
  const form = { ...defaultDeployForm(), sourceType: 'IMAGE', image: ' localhost:5001/a:1 ', readinessPath: '', command: '/app\n--port=80\n' }
  const payload = buildDeployPayload(form, 'k2')
  assert.equal(payload.image, 'localhost:5001/a:1')
  assert.equal(payload.readinessPath, '/')
  assert.deepEqual([...payload.command], ['/app', '--port=80'])
  assert.equal(payload.systemPrompt, undefined)
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
