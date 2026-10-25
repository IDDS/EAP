import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import ts from 'typescript'
import { randomUUID } from 'node:crypto'

function mount(storage = new Map()) {
  const session = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
  const source = readFileSync(new URL('../src/components/substrate/SubstrateDeployPanel.vue', import.meta.url), 'utf8')
    .split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import[\s\S]*?from ['"][^'"]+['"]\s*$/gm, '')
  const calls = [], queries = [], watchers = [], permission = { state: { user: { subject: 'user-a', capabilities: {} } } }
  let unmount
  const values = {
    globalThis: { sessionStorage: session }, crypto: { randomUUID },
    ref: value => ({ value }), reactive: value => value, computed: fn => ({ get value() { return fn() } }),
    watch: (source, fn) => watchers.push(fn), onMounted: () => {}, onBeforeUnmount: fn => { unmount = fn },
    defineProps: () => ({ agentId: 7 }), usePermissions: () => permission, substrateActions: () => ({ chat: true }),
    defaultDeployForm: () => ({}), deploymentProgress: () => ({}), actorStateLabel: () => ({}),
    ACTIVE_DEPLOYMENT_STATUSES: [], ElMessage: {}, ElMessageBox: {},
    SubstrateApi: {
      instances: async () => [], deployments: async () => [],
      chat: (...args) => new Promise((resolve, reject) => calls.push({ args,
        resolve: body => resolve({ requestKey: args[4], status: 'SUCCEEDED', notice: '已完成', ...body }), reject })),
      chatReceipt: (...args) => new Promise((resolve, reject) => queries.push({ args, resolve, reject })),
    },
  }
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText
  const state = new Function(...Object.keys(values), code + ';return {openChat,sendChat,resetChat,chatText,chatMessages,chatHistory,chatting,props,pendingChatKey,queryChatReceipt,chatNotice}')(...Object.values(values))
  return { ...state, calls, queries, storage, session, watchers, permission, unmount: () => unmount() }
}
const instance = name => ({ name, instanceKey: `scope/${name}` })

test('only completed visible pairs are included and new conversation clears context', async () => {
  const x = mount(); x.openChat(instance('a')); x.chatText.value = 'first'
  const first = x.sendChat(); assert.deepEqual(x.calls[0].args[3], [])
  x.calls[0].resolve({ reply: 'answer' }); await first
  x.chatText.value = 'second'; const second = x.sendChat()
  assert.deepEqual(x.calls[1].args[3], [{ role: 'user', content: 'first' }, { role: 'assistant', content: 'answer' }])
  x.calls[1].reject(new Error('unknown')); await second
  assert.equal(x.chatHistory.value.length, 2)
  x.resetChat(); assert.equal(x.chatHistory.value.length, 0); assert.equal(x.chatMessages.value.length, 0)
})

for (const late of ['resolve', 'reject']) test(`switching instance ignores late ${late} and preserves new request state`, async () => {
  const x = mount(); x.openChat(instance('a')); x.chatText.value = 'A-private'; const a = x.sendChat()
  x.openChat(instance('b')); x.chatText.value = 'B-private'; const b = x.sendChat()
  assert.deepEqual(x.calls[1].args[3], [])
  x.calls[0][late](late === 'resolve' ? { reply: 'A-reply' } : new Error('A-error')); await a
  assert.equal(x.chatting.value, true)
  assert.equal(JSON.stringify(x.chatMessages.value).includes('A-'), false)
  x.calls[1].resolve({ reply: 'B-reply' }); await b
  assert.deepEqual(x.chatHistory.value.map(x => x.content), ['B-private', 'B-reply'])
})

for (const event of ['identity', 'agent', 'unmount']) test(`${event} change discards old reply and context`, async () => {
  const x = mount(); x.openChat(instance('a')); x.chatText.value = 'private'; const pending = x.sendChat()
  if (event === 'identity') { x.permission.state.user = { subject: 'b' }; x.watchers[0]() }
  else if (event === 'agent') { x.props.agentId = 8; x.watchers[1]() }
  else x.unmount()
  x.calls[0].resolve({ reply: 'late private reply' }); await pending
  assert.equal(x.chatMessages.value.length, 0); assert.equal(x.chatHistory.value.length, 0)
})

test('history byte cap removes complete pairs including oversized multibyte responses', async () => {
  const x = mount(); x.openChat(instance('a')); x.chatText.value = 'question'; const pending = x.sendChat()
  x.calls[0].resolve({ reply: '中'.repeat(22000) }); await pending
  assert.equal(x.chatHistory.value.length, 0)
  assert.equal(x.chatMessages.value.length, 2)
})


test('lost response keeps only the original key across refresh and query cannot send', async () => {
  const x = mount(); x.openChat(instance('a')); x.chatText.value = 'private prompt'
  const pending = x.sendChat(), key = x.calls[0].args[4]
  x.calls[0].reject(new Error('lost HTTP reply')); await pending
  assert.equal(x.pendingChatKey.value, key)
  assert.deepEqual([...x.storage.values()], [key])
  x.chatText.value = 'manual retry'; await x.sendChat(); assert.equal(x.calls.length, 1)
  x.resetChat(); x.chatText.value = 'reset retry'; await x.sendChat(); assert.equal(x.calls.length, 1)
  const refreshed = mount(x.storage); refreshed.openChat(instance('a'))
  assert.equal(refreshed.pendingChatKey.value, key)
  const query = refreshed.queryChatReceipt()
  assert.deepEqual(refreshed.queries[0].args, [7, 'a', key]); assert.equal(refreshed.calls.length, 0)
  refreshed.queries[0].resolve({ requestKey: key, status: 'SUCCEEDED', notice: '已完成，正文未保存' }); await query
  assert.equal(refreshed.pendingChatKey.value, ''); assert.equal(x.storage.size, 0)
  assert.deepEqual(refreshed.chatHistory.value, [])
})

for (const status of ['ACCEPTED', 'DISPATCHED', 'UNKNOWN']) test(`${status} only allows original receipt lookup`, async () => {
  const x = mount(); x.openChat(instance('a')); x.chatText.value = 'question'
  const sent = x.sendChat(); const key = x.calls[0].args[4]
  x.calls[0].resolve({ status, reply: null }); await sent
  const query = x.queryChatReceipt(); x.queries[0].resolve({ requestKey: key, status, notice: status }); await query
  x.chatText.value = 'retry'; await x.sendChat()
  assert.equal(x.calls.length, 1); assert.equal(x.pendingChatKey.value, key); assert.equal(x.storage.size, 1)
})

test('storage failure prevents any submission and leaves the input available', async () => {
  const x = mount(); x.openChat(instance('a')); x.session.setItem = () => { throw new Error('quota') }
  x.chatText.value = 'unsent'; await x.sendChat()
  assert.equal(x.calls.length, 0); assert.equal(x.chatText.value, 'unsent')
  assert.match(x.chatNotice.value, /未发送/)
})

test('receipt identity mismatch cannot clear a pending key or append history', async () => {
  const x = mount(); x.openChat(instance('a')); x.chatText.value = 'question'
  const sent = x.sendChat(); const key = x.calls[0].args[4]
  x.calls[0].resolve({ requestKey: randomUUID(), reply: 'wrong result' }); await sent
  assert.equal(x.pendingChatKey.value, key); assert.equal(x.chatHistory.value.length, 0)
})

test('query late response cannot touch another instance or clear the original pending key', async () => {
  const x = mount(); x.openChat(instance('a')); x.chatText.value = 'question'; const sent = x.sendChat()
  x.calls[0].reject(new Error('lost')); await sent
  const key = x.pendingChatKey.value, query = x.queryChatReceipt()
  x.openChat(instance('b'))
  x.queries[0].resolve({ requestKey: key, status: 'SUCCEEDED', notice: 'completed' }); await query
  assert.equal(x.chatMessages.value.length, 0); assert.equal(x.storage.size, 1)
  x.openChat(instance('a')); assert.equal(x.pendingChatKey.value, key)
})

test('explicit not-dispatched receipt releases the pending key without adding a completed pair', async () => {
  const x = mount(); x.openChat(instance('a')); x.chatText.value = 'question'; const sent = x.sendChat()
  x.calls[0].resolve({ status: 'NOT_DISPATCHED', reply: null }); await sent
  assert.equal(x.pendingChatKey.value, ''); assert.equal(x.storage.size, 0); assert.equal(x.chatHistory.value.length, 0)
})
