import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import vm from 'node:vm'
const source=readFileSync(new URL('../src/navigation/catalog.ts',import.meta.url),'utf8')
const context=vm.createContext({exports:{}})
vm.runInContext(ts.transpile(source,{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}),context)
const {navigationItems,navigationGroups,navigationForPath,searchNavigation}=context.exports

test('every navigable route remains reachable exactly once in the new catalog',()=>{
 const router=readFileSync(new URL('../src/router/index.ts',import.meta.url),'utf8')
 const routes=[...router.matchAll(/path: '([^']+)'/g)].map(m=>m[1]).filter(p=>p!='/auth/callback'&&!p.includes(':'))
 assert.equal(new Set(navigationItems.map(i=>i.path)).size,navigationItems.length)
 assert.deepEqual([...navigationItems.map(i=>i.path)].sort(),routes.sort())
 assert.equal(navigationGroups.find(g=>g.id==='workspace-execution').items.length,6)
})
test('agent detail highlights its parent and route changes choose the correct workspace',()=>{
 assert.equal(navigationForPath('/agents/15').activePath,'/agents')
 assert.equal(navigationForPath('/agents/15').group.id,'workspace-assets')
 assert.equal(navigationForPath('/ax-tasks').group.id,'workspace-execution')
 assert.equal(navigationForPath('/substrate-monitor').group.id,'workspace-observation')
 assert.equal(navigationForPath('/learning').group.id,'workspace-quality')
 assert.equal(navigationForPath('/management').openGroups.length,0)
 assert.equal(navigationForPath('/agents-other').item,undefined)
})
test('old labels and system names remain discoverable in page search',()=>{
 assert.equal(searchNavigation('Substrate')[0].path,'/substrate-monitor')
 assert.ok(searchNavigation('自适应学习').some(i=>i.path==='/learning'))
 assert.ok(searchNavigation('AX').some(i=>i.path==='/ax-tasks'))
 assert.equal(searchNavigation('   ').length,0)
 assert.equal(searchNavigation('no-such-page').length,0)
})
test('navigation icons are defined for each group and leaf and never used without a text label',()=>{
 const icons=readFileSync(new URL('../src/navigation/icons.ts',import.meta.url),'utf8')
 for(const item of [...navigationItems,...navigationGroups]){assert.ok(icons.includes(item.icon));assert.ok(item.label.length>0)}
 const menu=readFileSync(new URL('../src/components/PlatformNavigation.vue',import.meta.url),'utf8')
 assert.match(menu,/aria-current/);assert.match(menu,/aria-hidden="true"/);assert.doesNotMatch(menu,/@click.*openWorkspace/)
})
