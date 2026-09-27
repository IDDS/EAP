import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

const source = readFileSync(new URL('../src/api/substrateDeploy.ts', import.meta.url), 'utf8')
const scope = vm.createContext({ exports: {} })
vm.runInContext(ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }), scope)
const { buildDeployPayload, defaultDeployForm, validateDeployForm, deploymentProgress, actorStateLabel } = scope.exports

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
