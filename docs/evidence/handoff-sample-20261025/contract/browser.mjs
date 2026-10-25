import {createRequire} from 'node:module'
import fs from 'node:fs/promises'
import net from 'node:net'
import {hostname} from 'node:os'
const {chromium}=createRequire('/work/package.json')('playwright-core')
if(hostname()!=='eap-handoff-browser')throw Error('remote only')
const input=JSON.parse(await new Promise(resolve=>{let s='';process.stdin.on('data',x=>s+=x);process.stdin.on('end',()=>resolve(s))}))
await fs.mkdir('/out/screenshots',{recursive:true})
const origin='http://localhost:5173',proxies=[],report={cases:[],displayFixtures:[],unexpectedWrites:[],pageErrors:[],serverErrors:[]}
let browser,stage='setup',activePage,fixtureActive=false
function check(ok,label){if(!ok){const e=Error(label);e.safeLabel=label;throw e}}
async function proxy(port,host,destination){const s=net.createServer(c=>{const u=net.connect(destination,host);c.pipe(u).pipe(c);c.on('error',()=>u.destroy());u.on('error',()=>c.destroy())});await new Promise(resolve=>s.listen(port,'127.0.0.1',resolve));proxies.push(s)}
try {
 await proxy(5173,'frontend.eap.svc.cluster.local',80);await proxy(8180,'keycloak.eap-auth.svc.cluster.local',8080)
 browser=await chromium.launch({executablePath:'/usr/bin/chromium',args:['--no-sandbox','--disable-dev-shm-usage','--no-proxy-server']})
 const [source,target]=input.live.streams
 for(const user of ['viewer','operator','governance','admin'])for(const width of [1440,390]) {
  stage=`${user}:${width}:login`
  const context=await browser.newContext({viewport:{width,height:width===390?844:1050},locale:'zh-CN',reducedMotion:'reduce'}),page=await context.newPage();activePage=page;page.setDefaultTimeout(30000)
  await context.route('**/*',async route=>{const req=route.request(),u=new URL(req.url());if(![origin,'http://localhost:8180'].includes(u.origin))return route.abort();if(u.origin===origin&&u.pathname.startsWith('/api/')&&!['GET','HEAD','OPTIONS'].includes(req.method())){report.unexpectedWrites.push({user,path:u.pathname,method:req.method()});return route.abort()};return route.continue()})
  page.on('pageerror',e=>report.pageErrors.push({user,name:e.name}))
  page.on('response',r=>{if(r.url().startsWith(origin+'/api/')&&r.status()>=500&&!fixtureActive)report.serverErrors.push({user,status:r.status(),path:new URL(r.url()).pathname})})
  await page.goto(origin+'/',{waitUntil:'domcontentloaded'});await page.getByRole('button',{name:'企业账号登录',exact:true}).click();await page.locator('#username').waitFor()
  check(new URL(page.url()).searchParams.get('code_challenge_method')==='S256','PKCE S256')
  await page.locator('#username').fill(user);await page.locator('#password').fill(Buffer.from(input.data[user],'base64').toString());await page.locator('#kc-login').click();await page.waitForURL(u=>u.origin===origin&&u.pathname==='/',{timeout:45000})
  const state=await page.evaluate(async()=>({me:(await(await fetch('/api/platform/me',{headers:{Authorization:'Bearer '+sessionStorage.getItem('eap.access_token')}})).json()).data,version:await(await fetch('/version.json')).json()}))
  check(state.me.mode==='JWT'&&state.version.buildId===input.buildId&&state.me.capabilities.viewConsole,'JWT capability and fixed build')
  stage=`${user}:${width}:real-confirmed-links`
  await page.goto(origin+'/physical',{waitUntil:'networkidle'});await page.getByRole('button',{name:'执行关系',exact:true}).click()
  await page.getByRole('textbox',{name:'执行观测智能体编号',exact:true}).fill(String(source.agentId));await page.getByRole('button',{name:'查询执行',exact:true}).click()
  await page.locator('.stream-list button').waitFor();check(await page.locator('.stream-list button').count()===1,'one actual source stream')
  check((await page.locator('.stream-list').innerText()).includes(source.streamKey),'expected source stream')
  await page.locator('.stream-list button').click()
  const panel=page.getByRole('region',{name:'跨实例执行关联',exact:true})
  await panel.getByText('代码交接',{exact:true}).waitFor()
  check(await panel.getByText('双方已确认',{exact:true}).count()===2,'two actual confirmed relations')
  check(await page.locator('.run-choice').count()===source.runs,'actual source logical runs')
  const code=input.live.links.items.find(x=>x.relation==='CODE_HANDOFF')
  check((await panel.innerText()).includes(code.artifactDigest),'actual code digest visible')
  check((await panel.innerText()).includes('未保存源码正文'),'observation storage boundary')
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'source viewport fit')
  if(user==='operator'){await panel.scrollIntoViewIfNeeded();await page.screenshot({path:`/out/screenshots/real-source-${width}.png`,fullPage:false,animations:'disabled'})}
  await panel.getByRole('button',{name:'查看接收方事件',exact:true}).first().click()
  await panel.getByRole('button',{name:'查看来源事件',exact:true}).first().waitFor()
  check(await page.locator('.run-choice').count()===target.runs,'actual receiving ephemeral executor')
  check(await panel.getByText('双方已确认',{exact:true}).count()===2,'target same confirmed relations')
  check((await panel.innerText()).includes(code.artifactDigest),'target exact same digest')
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'target viewport fit')
  if(user==='operator'){await panel.scrollIntoViewIfNeeded();await page.screenshot({path:`/out/screenshots/real-target-${width}.png`,fullPage:false,animations:'disabled'})}
  await panel.getByRole('button',{name:'查看来源事件',exact:true}).first().click()
  await panel.getByRole('button',{name:'查看接收方事件',exact:true}).first().waitFor()
  check(await page.locator('.run-choice').count()===source.runs,'bidirectional source navigation')
  report.cases.push({user,width,passed:true,pkceS256:true,sourceRuns:source.runs,targetRuns:target.runs,confirmedLinks:2,bidirectionalNavigation:true,viewportFit:true,synthetic:false})
  await context.close();activePage=null
 }
 check(!report.unexpectedWrites.length&&!report.pageErrors.length&&!report.serverErrors.length,'no business writes or unexpected errors');report.passed=true
} catch(e) {
 report.passed=false;report.failure={stage,label:e.safeLabel||e.name||'BROWSER_FAILURE'}
 if(activePage)await activePage.screenshot({path:'/out/screenshots/failure.png',fullPage:true,animations:'disabled'}).catch(()=>{})
} finally {
 if(browser)await browser.close();for(const server of proxies)server.close();await fs.writeFile('/out/ui-report.json',JSON.stringify(report,null,2)+'\n')
}
console.log(JSON.stringify({passed:report.passed,cases:report.cases.length,failure:report.failure}));if(!report.passed)process.exitCode=1
