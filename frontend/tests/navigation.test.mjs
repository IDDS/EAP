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
 assert.equal(navigationGroups.find(g=>g.id==='workspace-execution').items.length,4)
 assert.equal(navigationGroups.find(g=>g.id==='workspace-design').items.length,2)
})
test('agent detail highlights its parent and route changes choose the correct workspace',()=>{
 assert.equal(navigationForPath('/agents/15').activePath,'/agents')
 assert.equal(navigationForPath('/agents/15').group.id,'workspace-assets')
 assert.equal(navigationForPath('/ax-tasks').group.id,'workspace-execution')
 assert.equal(navigationForPath('/substrate-monitor').group.id,'workspace-observation')
 assert.equal(navigationForPath('/learning').group.id,'workspace-quality')
 assert.equal(navigationForPath('/management').group.id,'workspace-quality')
 assert.equal(navigationForPath('/orchestration').group.id,'workspace-design')
 assert.equal(navigationForPath('/workflow-builder').group.id,'workspace-design')
 assert.equal(navigationForPath('/task-workspace').group.id,'workspace-execution')
 assert.equal(navigationForPath('/').group,undefined)
 assert.equal(navigationForPath('/agents-other').item,undefined)
})
test('old labels and system names remain discoverable in page search',()=>{
 assert.equal(searchNavigation('Substrate')[0].path,'/substrate-monitor')
 assert.ok(searchNavigation('自适应学习').some(i=>i.path==='/learning'))
 assert.ok(searchNavigation('AX').some(i=>i.path==='/ax-tasks'))
 for(const [label,path] of [['任务工作台','/task-workspace'],['运行与遥测','/physical'],['业务任务记录','/operations'],['调度与画像','/scheduling'],['团队优化结果','/team-evolutions']]) assert.ok(searchNavigation(label).some(item=>item.path===path))
 assert.equal(searchNavigation('   ').length,0)
 assert.equal(searchNavigation('no-such-page').length,0)
})
test('navigation icons are defined for each group and leaf and never used without a text label',()=>{
 const icons=readFileSync(new URL('../src/navigation/icons.ts',import.meta.url),'utf8')
 for(const item of [...navigationItems,...navigationGroups]){assert.ok(icons.includes(item.icon));assert.ok(item.label.length>0)}
 const menu=readFileSync(new URL('../src/components/PlatformNavigation.vue',import.meta.url),'utf8')
 assert.match(menu,/aria-current/);assert.match(menu,/aria-hidden="true"/);assert.doesNotMatch(menu,/@click.*openWorkspace/)
})

const {pageNavigationForPath,legacyAgentDestination,searchPageSections,pageSections}=context.exports

const {workspaceDestinations}=context.exports
test('every routed section has a matching readable page heading and task description',()=>{
 const {pageHeadingForPath}=context.exports
 for(const [path,sections] of Object.entries(pageSections)) for(const section of sections) {
  const actual=path.replace(':id','122'),parameter=path.includes(':id')?'tab':'section'
  const heading=pageHeadingForPath(actual,{[parameter]:section.value})
  assert.equal(heading.title,path==='/'&&section.value==='start'?'工作台':section.label)
  assert.ok(heading.description.length>10)
 }
 assert.equal(pageHeadingForPath('/workflow-builder').title,'工作流编排')
 assert.equal(pageHeadingForPath('/physical',{section:'tools'}).title,'工具与 MCP 调用')
})
test('workspace destinations directly reach every page section without losing routes',()=>{
 for(const group of navigationGroups) {
  const destinations=workspaceDestinations(group,'/')
  assert.equal(new Set(destinations.map(item=>item.key)).size,destinations.length)
  for(const item of group.items) {
   const actual=destinations.filter(destination=>destination.path===item.path)
   const sections=pageSections[item.path]
   if(sections) assert.deepEqual(Array.from(actual,destination=>destination.query.section),Array.from(sections,section=>section.value))
   else assert.equal(actual.length,1)
  }
 }
})
test('section links preserve current evidence scope without leaking it to another page',()=>{
 const group=navigationGroups.find(group=>group.id==='workspace-observation')
 const destinations=workspaceDestinations(group,'/physical',{agentId:'122',traceId:'existing',section:'trace'})
 const tools=destinations.find(item=>item.section==='tools')
 assert.equal(tools.query.agentId,'122');assert.equal(tools.query.traceId,'existing');assert.equal(tools.query.section,'tools')
 for(const item of destinations.filter(item=>item.path!=='/physical')) {
  assert.equal(item.query?.agentId,undefined);assert.equal(item.query?.traceId,undefined)
 }
})
test('agent identity sections retain the concrete agent while the directory stays reachable',()=>{
 const group=navigationGroups.find(group=>group.id==='workspace-assets')
 const destinations=workspaceDestinations(group,'/agents/122',{tab:'deploy'})
 assert.equal(destinations.filter(item=>item.path==='/agents').length,1)
 const detail=destinations.filter(item=>item.path==='/agents/122')
 assert.equal(detail.length,4)
 for(const item of detail) {
  assert.equal(item.context,'当前智能体 #122')
  assert.equal(item.query.tab,item.section)
  assert.ok(pageSections['/agents/:id'].some(section=>section.value===item.query.tab))
 }
 assert.ok(!destinations.some(item=>item.path==='/physical'))
})

test('every functional section can be bookmarked with a validated selection and fallback',()=>{
 for(const [path,sections] of Object.entries(pageSections)) {
  assert.equal(new Set(sections.map(s=>s.value)).size,sections.length)
  const actual=path.replace(':id','81'),parameter=actual.startsWith('/agents/')?'tab':'section'
  for(const section of sections) assert.equal(pageNavigationForPath(actual,{[parameter]:section.value}).active,section.value)
  assert.equal(pageNavigationForPath(actual,{[parameter]:'invalid'}).active,sections[0].value)
  assert.equal(pageNavigationForPath(actual,{[parameter]:[sections[1].value]}).active,sections[0].value)
 }
 assert.equal(pageNavigationForPath('/unknown').items.length,0)
})
test('contextual evidence links open the appropriate page and explicit section wins',()=>{
 assert.equal(pageNavigationForPath('/physical',{traceId:'t'}).active,'trace')
 assert.equal(pageNavigationForPath('/physical',{traceId:'t',section:'tools'}).active,'tools')
 assert.equal(pageNavigationForPath('/governance',{agentId:'81'}).active,'audit')
 assert.equal(pageNavigationForPath('/governance',{policyId:'2'}).active,'policies')
 assert.equal(pageNavigationForPath('/evaluation',{agentId:'81'}).active,'results')
})
test('old agent observation links route to outer workspaces with their agent scope',()=>{
 for(const tab of ['trace','models','tools','quality','governance']) {
  const destination=legacyAgentDestination('81',tab)
  assert.equal(destination.query.agentId,'81')
  assert.ok(pageSections[destination.path].some(s=>s.value===destination.query.section))
 }
 for(const tab of ['deploy','identity','runtime','unknown',undefined,['trace']]) assert.equal(legacyAgentDestination('81',tab),undefined)
})
test('search reaches tools, model usage and budget without first opening an agent',()=>{
 for(const term of ['MCP','模型调用','预算','数据集']) {
  const matches=searchPageSections(term)
  assert.ok(matches.length>0)
  for(const match of matches) assert.ok(pageSections[match.path].some(s=>s.value===match.query.section))
 }
 assert.equal(searchPageSections(' ').length,0)
})
