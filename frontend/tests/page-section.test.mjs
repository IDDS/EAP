import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import ts from 'typescript'
import vm from 'node:vm'
import {computed,reactive} from 'vue'
const context=vm.createContext({exports:{}})
vm.runInContext(ts.transpile(readFileSync(new URL('../src/navigation/catalog.ts',import.meta.url),'utf8'),{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}),context)
const code=ts.transpile(readFileSync(new URL('../src/composables/usePageSection.ts',import.meta.url),'utf8').replace(/^import .*$/gm,'').replace('export function','function'),{target:ts.ScriptTarget.ES2022})

test('home view follows bookmarked sections and history while preserving organization scope',()=>{
 const route=reactive({path:'/',query:{section:'queue',organizationId:'7'}}),writes=[]
 const useSection=new Function('computed','useRoute','useRouter','pageNavigationForPath',code+';return usePageSection')(computed,()=>route,()=>({push:target=>writes.push(target)}),context.exports.pageNavigationForPath)
 const section=useSection()
 assert.equal(section.value,'queue');section.value='metrics'
 assert.deepEqual(writes[0],{path:'/',query:{section:'metrics',organizationId:'7'}})
 route.query={section:'start'};assert.equal(section.value,'start')
 route.query={section:'queue'};assert.equal(section.value,'queue')
 route.query={};assert.equal(section.value,'start')
 section.value='invalid';assert.equal(writes.length,1)
})

test('section selection preserves filters, follows history, and ignores departed page writes',()=>{
 const route=reactive({path:'/physical',query:{section:'trace',agentId:'81',traceId:'existing'}}),writes=[]
 const useSection=new Function('computed','useRoute','useRouter','pageNavigationForPath',code+';return usePageSection')(computed,()=>route,()=>({push:target=>{writes.push(target)}}),context.exports.pageNavigationForPath)
 const section=useSection()
 assert.equal(section.value,'trace');section.value='tools'
 assert.deepEqual(writes[0],{path:'/physical',query:{section:'tools',agentId:'81',traceId:'existing'}})
 route.query={section:'models',agentId:'81'};assert.equal(section.value,'models')
 route.query={agentId:'81'};assert.equal(section.value,'overview')
 section.value='invalid';assert.equal(writes.length,1)
 route.path='/evaluation';section.value='tools';assert.equal(writes.length,1)
})
