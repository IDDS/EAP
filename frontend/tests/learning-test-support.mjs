import {readFileSync} from 'node:fs'
import ts from 'typescript'

const helperSource=readFileSync(new URL('../src/navigation/learningLinks.ts',import.meta.url),'utf8')
const helperCode=ts.transpileModule(helperSource,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText
const exports={}
new Function('exports',helperCode)(exports)
export const {learningLocation,learningRecordPath}=exports

export function routeStubs(query={}){
 const route={query},guards=[],leaveGuards=[],watchers=[]
 const navigate=async query=>{
  if(guards.some(guard=>guard()===false))return false
  route.query=query
  await Promise.all(watchers.map(fn=>fn()))
  return true
 }
 return {route,guards,leaveGuards,watchers,navigate,values:{
  useRoute:()=>route,useRouter:()=>({push:({query})=>navigate(query)}),
  onBeforeRouteUpdate:fn=>guards.push(fn),onBeforeRouteLeave:fn=>leaveGuards.push(fn),defineExpose:()=>{},learningLocation,learningRecordPath,
 }}
}

export function mountLearning(file,expose,options={}){
 const source=readFileSync(new URL(`../src/${file}`,import.meta.url),'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm,'')
 const code=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.None}}).outputText
 const routing=routeStubs(options.query),permission={state:{user:{subject:'a'}},can:()=>true},permissionWatchers=[],messages=[]
 let unmount
 const values={
  ref:value=>({value}),computed:fn=>({get value(){return fn()}}),
  watch:(sources,fn)=>(Array.isArray(sources)?permissionWatchers:routing.watchers).push(fn),
  onMounted:()=>{},onBeforeUnmount:fn=>{unmount=fn},usePermissions:()=>permission,
  Plus:null,MagicStick:null,ElMessage:{warning:message=>messages.push(message),error:message=>messages.push(message),success:message=>messages.push(message)},
  EapApi:{listLearningFeedbacks:async()=>[],listAgents:async()=>[],...options.EapApi},
  ExperimentApi:{list:async()=>[],...options.ExperimentApi},
  OptimizationApi:{status:async()=>({enabled:true}),list:async()=>[],...options.OptimizationApi},
  ...routing.values,
 }
 const scope=new Function(...Object.keys(values),code+`;return {${expose}}`)(...Object.values(values))
 return {...scope,permission,messages,routing,reset:()=>permissionWatchers.forEach(fn=>fn()),unmount:()=>unmount?.()}
}

export function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
export const settle=()=>new Promise(resolve=>setImmediate(resolve))
