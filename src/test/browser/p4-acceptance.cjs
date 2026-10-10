// Real current Vue + HTTP + test MySQL, real Clock and @Scheduled. No mocked device execution.
const fs=require('node:fs'),path=require('node:path'),net=require('node:net'),assert=require('node:assert/strict')
const {spawn}=require('node:child_process'),CDP=require('./cdp.cjs')
const work=process.env.P4_WORK || path.resolve(__dirname,'../../../../开发资料/work/CP-P4-P5-control'),origin='http://127.0.0.1:5273'
const manifest=JSON.parse(fs.readFileSync(path.join(work,'browser-fixtures.json'),'utf8'))
assert.equal(manifest.database,'smart_community_care_test')
const report={evidence:'Real Vue, HTTP, MySQL, normal wall clock, default heartbeat/delay/expiry/offline intervals, automatic @Scheduled. No manual tick or mocked execution.',checks:[],fixtures:{},shots:[],network:[]}
const save=()=>fs.writeFileSync(path.join(work,'browser-results.json'),JSON.stringify(report,null,2))
const sleep=ms=>new Promise(r=>setTimeout(r,ms)),profile=path.join(work,'chrome-p4')
let chrome,page,browser
const dialog='Array.from(document.querySelectorAll(".el-dialog")).find(x=>x.getClientRects().length)'
async function wait(expr,label,timeout=15000){const end=Date.now()+timeout;while(Date.now()<end){if(await page.evaluate(expr))return;await sleep(150)}throw new Error('Condition not reached: '+label)}
async function navigate(route){await page.call('Page.navigate',{url:origin+route});await wait('document.readyState==="complete"',route);await sleep(200)}
async function click(text,scope='document'){assert.ok(await page.evaluate(`(()=>{const r=${scope};const x=r&&Array.from(r.querySelectorAll('button')).find(x=>x.getClientRects().length&&x.textContent.trim()===${JSON.stringify(text)}&&!x.disabled);if(!x)return false;x.click();return true})()`),'Enabled button '+text);await sleep(140)}
async function fill(selector,value){assert.ok(await page.evaluate(`(()=>{const x=document.querySelector(${JSON.stringify(selector)});if(!x)return false;Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(x,${JSON.stringify(value)});x.dispatchEvent(new Event('input',{bubbles:true}));return true})()`))}
async function fillLabel(label,value){assert.ok(await page.evaluate(`(()=>{const root=${dialog};const f=Array.from(root.querySelectorAll('.el-form-item')).find(x=>x.querySelector('label')?.textContent.trim()===${JSON.stringify(label)});const x=f?.querySelector('input');if(!x)return false;Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(x,${JSON.stringify(value)});x.dispatchEvent(new Event('input',{bubbles:true}));return true})()`))}
async function api(route,options={}){return page.evaluate(`(async()=>{const opts=${JSON.stringify(options)};const r=await fetch('/api'+${JSON.stringify(route)},{method:opts.method||'GET',headers:{'scc-token':'Bearer '+sessionStorage.getItem('scc:token'),...(opts.body?{'Content-Type':'application/json'}:{})},...(opts.body?{body:JSON.stringify(opts.body)}:{})});return {status:r.status,body:await r.json()}})()`)}
function check(name,detail={}){report.checks.push({name,pass:true,...detail});save();console.log('PASS '+name)}
async function shot(name,width=1366){await page.call('Emulation.setDeviceMetricsOverride',{width,height:width===1920?1080:768,deviceScaleFactor:1,mobile:false});await sleep(150);const s=await page.call('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(work,name+'.png'),Buffer.from(s.data,'base64'));report.shots.push(name+'.png');save()}
async function login(role){await navigate('/login');await page.evaluate("localStorage.removeItem('scc:token');localStorage.removeItem('scc:user');sessionStorage.removeItem('scc:token');sessionStorage.removeItem('scc:user');true");await navigate('/login');await fill('input[type="text"]',manifest.users[role].username);await fill('input[type="password"]',process.env[role==='admin'?'P4_ADMIN_PASSWORD':role==='staff'?'P4_STAFF_PASSWORD':'P4_ELDER_PASSWORD']);await click('登录');await wait('!!sessionStorage.getItem("scc:token")&&!location.pathname.startsWith("/login")','login '+role)}
const row='Array.from(document.querySelectorAll("tr.el-table__row")).find(x=>x.textContent.includes("IT-P4-BROWSER-01"))'
async function mode(value){assert.ok(await page.evaluate(`(()=>{const r=${dialog};const x=Array.from(r.querySelectorAll('.el-radio-button')).find(x=>x.textContent.trim()===${JSON.stringify(value)});if(!x)return false;x.click();return true})()`));await sleep(250)}
async function toggle(scope=dialog){assert.ok(await page.evaluate(`(()=>{const r=${scope};const x=r?.querySelector('.el-switch:not(.is-disabled)');if(!x)return false;x.click();return true})()`),'Enabled device switch');await sleep(250)}
async function commands(){return (await api('/devices/'+report.fixtures.deviceId+'/commands')).body.data}
async function awaitStatus(state,timeout=15000){await wait(`(async()=>{const r=await fetch('/api/devices/${report.fixtures.deviceId}/commands',{headers:{'scc-token':'Bearer '+sessionStorage.getItem('scc:token')}});return (await r.json()).data?.[0]?.status===${JSON.stringify(state)}})()`,'automatic '+state,timeout);return (await commands())[0]}
async function run(){
  await login('admin');await navigate('/admin/devices');await wait('document.body.textContent.includes("新建设备")','device page')
  await click('新建设备');await wait(dialog,'create dialog');await fillLabel('设备编号','IT-P4-BROWSER-01');await fillLabel('设备名称','P4登记客厅电灯');await fillLabel('安装位置','P4登记客厅')
  await page.evaluate(`(()=>{const r=${dialog};Array.from(r.querySelectorAll('.el-form-item')).find(x=>x.querySelector('label')?.textContent.trim()==='所属老人').querySelector('.el-select__wrapper').click();return true})()`)
  await wait('Array.from(document.querySelectorAll(".el-select-dropdown__item")).some(x=>x.getClientRects().length)','elder options')
  assert.ok(await page.evaluate('(()=>{const x=Array.from(document.querySelectorAll(".el-select-dropdown__item")).find(x=>x.getClientRects().length&&x.textContent.includes("it_p4browser_elder"));if(!x)return false;x.click();return true})()'))
  await click('创建',dialog);await wait(row,'registered device row')
  const all=(await api('/devices')).body.data,device=all.find(x=>x.deviceNo==='IT-P4-BROWSER-01');assert.ok(device);assert.equal(typeof device.id,'string');assert.equal(device.elderId,manifest.owner);assert.ok(device.powerStatus==null);assert.equal(device.simulatorRunning,false);report.fixtures.deviceId=device.id;save()
  check('Real admin page creates an idle simulated device with correct owner and string IDs')
  for(const type of ['AIRCON','CURTAIN'])assert.equal((await api('/devices',{method:'POST',body:{deviceNo:'IT-P4-BROWSER-'+type,deviceName:'P4登记'+type,deviceType:type,elderId:manifest.otherOwner}})).status,200)
  await click('启动',row);await wait(`(${row})?.textContent.includes('运行中')`,'started');await click('控制',row);await wait(dialog,'control')
  await toggle();let c=await commands();assert.equal(c[0].status,'PENDING');report.fixtures.normalId=c[0].id;check('Real switch submission is PENDING before default execution delay')
  await awaitStatus('SUCCEEDED');await wait(`(${dialog})?.querySelector('.control-tip .el-tag')?.textContent.trim()==='成功'&&(${dialog})?.querySelector('.el-switch')?.classList.contains('is-checked')`,'control panel automatically refreshes power and receipt');await shot('admin-device-normal');check('Automatic scheduler executes NORMAL and current admin dialog refreshes reported power')
  await mode('下次失败');await toggle();c=await awaitStatus('FAILED');report.fixtures.failedId=c.id;assert.equal(c.targetPowerStatus,'OFF');assert.equal((await api('/devices')).body.data.find(x=>x.id===device.id).simulatedPowerStatus,'ON');await wait(`(${dialog})?.querySelector('.control-tip .el-tag')?.textContent.trim()==='失败'`,'FAIL_NEXT receipt');await shot('admin-device-failed');check('FAIL_NEXT fails once without changing power and resets mode')
  assert.equal((await api('/devices')).body.data.find(x=>x.id===device.id).simulationMode,'NORMAL')
  await wait(`!(${dialog})?.querySelector('.el-switch')?.classList.contains('is-disabled')`,'next control');await toggle();c=await awaitStatus('SUCCEEDED');report.fixtures.secondNormalId=c.id;await wait(`!(${dialog})?.querySelector('.el-switch')?.classList.contains('is-checked')`,'reported OFF')
  await mode('不回报');await toggle();await wait(`(async()=>{const r=await fetch('/api/devices/${device.id}/commands',{headers:{'scc-token':'Bearer '+sessionStorage.getItem('scc:token')}});const x=(await r.json()).data?.[0];return x?.status==='PENDING'&&!!x.executedAt})()`,'NO_ACK actually executed')
  c=(await commands())[0];report.fixtures.noAckId=c.id;const firstExecuted=c.executedAt;await awaitStatus('TIMED_OUT',35000);c=(await commands())[0];assert.equal(c.executedAt,firstExecuted);assert.ok(c.acknowledgedAt==null);await wait(`(${dialog})?.querySelector('.control-tip .el-tag')?.textContent.trim()==='结果未确认'`,'unknown result UI');await shot('admin-device-no-ack');check('NO_ACK automatically executes once and expires as result unknown',{executedAt:firstExecuted,acknowledgedAt:null})
  await click('关闭',dialog);await click('停止',row);const stopped=(await api('/devices')).body.data.find(x=>x.id===device.id);assert.equal(stopped.simulatorRunning,false);const lastSeen=stopped.lastSeenAt
  await wait(`(${row})?.textContent.includes('离线')`,'offline after real heartbeat threshold',25000);assert.equal((await api('/devices')).body.data.find(x=>x.id===device.id).lastSeenAt,lastSeen);check('Stopping preserves last heartbeat; real offline threshold appears in admin page')
  await click('启动',row);await wait(`(${row})?.textContent.includes('运行中')`,'restart');await click('控制',row);await wait(dialog,'normal mode again');await mode('正常');await click('关闭',dialog)
  await login('elder');await navigate('/elder/my-devices');await wait('document.querySelector(".dev-card .el-switch:not(.is-disabled)")','elder live control')
  let drop=true;const submissions=[]
  page.on('Fetch.requestPaused',async e=>{
    try {
      if(e.request.method==='POST')submissions.push(JSON.parse(e.request.postData))
      if(e.request.method==='POST'&&e.responseStatusCode===200&&drop){drop=false;await sleep(1200);await page.call('Fetch.failRequest',{requestId:e.requestId,errorReason:'ConnectionClosed'})}
      else await page.call('Fetch.continueRequest',{requestId:e.requestId})
    }catch{report.interceptionError=true}
  })
  await page.call('Fetch.enable',{patterns:[{urlPattern:'*/api/devices/*/commands',requestStage:'Response'}]})
  await toggle('document.querySelector(".dev-card")')
  await wait('Array.from(document.querySelectorAll("button")).some(x=>x.textContent.trim()==="不再跟踪"&&x.disabled)','in-flight cannot forget')
  await wait('Array.from(document.querySelectorAll("button")).some(x=>x.textContent.trim()==="确认原提交"&&!x.disabled)','lost response retains original submission')
  await shot('elder-device-unconfirmed')
  await click('确认原提交');await awaitStatus('SUCCEEDED');await page.call('Fetch.disable')
  assert.equal(submissions.length,2);assert.deepEqual(submissions[0],submissions[1]);assert.equal(submissions[0].targetPowerStatus,'OFF');assert.equal((await commands()).length,5);assert.ok(!report.interceptionError);report.originalRetry=submissions;
  await wait('document.querySelector(".last-cmd .el-tag")?.textContent.trim()==="成功"&&document.querySelector(".state-value")?.textContent.includes("已关闭")','elder refreshes actual final result');check('Real elder page preserves original key and target after committed response is lost; two POSTs produce one command');await shot('elder-device-retry')
  await login('admin');await navigate('/admin/devices');await wait(row,'disable row');await click('停用',row);await wait(`(${row})?.textContent.includes('已停用')`,'disabled');await shot('admin-device-disabled');check('Real admin disable remains visible and blocks control')
  await login('elder');await navigate('/elder/my-devices');await wait('document.querySelector(".dev-card")','elder device');assert.equal(await page.evaluate('document.querySelectorAll(".dev-card").length'),1);assert.ok(await page.evaluate('document.querySelector(".dev-card .el-switch").classList.contains("is-disabled")'));await shot('elder-device-disabled');await shot('elder-device-disabled-1920',1920);check('Elder sees only owned device and disabled switch')
  await login('other');assert.equal((await api('/devices/'+device.id+'/commands')).status,404);assert.equal((await api('/devices/'+device.id+'/commands',{method:'POST',body:{targetPowerStatus:'ON'}})).status,404);check('Other elder cannot read or command the registered device through direct API')
  await login('staff');assert.equal((await api('/devices')).status,403);assert.equal((await api('/devices/'+device.id+'/simulator/start',{method:'POST'})).status,403);check('Staff is denied device APIs by backend')
  assert.equal(report.runtimeErrors||0,0);report.complete=true;save()
}
;(async()=>{
  assert.ok(!fs.existsSync(profile));const occupied=await new Promise(resolve=>{const s=net.createConnection({host:'127.0.0.1',port:19228});s.setTimeout(1000);s.once('connect',()=>{s.destroy();resolve(true)});s.once('error',()=>resolve(false));s.once('timeout',()=>{s.destroy();resolve(false)})});assert.equal(occupied,false)
  chrome=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--remote-debugging-port=19228','--remote-debugging-address=127.0.0.1','--user-data-dir='+profile,'--no-first-run','--no-default-browser-check','about:blank'],{stdio:'ignore',windowsHide:true})
  let v;for(let i=0;i<100;i++){try{v=await(await fetch('http://127.0.0.1:19228/json/version')).json();break}catch{await sleep(100)}}assert.ok(v,'Chrome startup');browser=new CDP(v.webSocketDebuggerUrl);const t=await(await fetch('http://127.0.0.1:19228/json/new?about:blank',{method:'PUT'})).json();page=new CDP(t.webSocketDebuggerUrl)
  await page.call('Page.enable');await page.call('Runtime.enable');await page.call('Network.enable');await page.call('Emulation.setDeviceMetricsOverride',{width:1366,height:768,deviceScaleFactor:1,mobile:false})
  const requests=new Map();page.on('Network.requestWillBeSent',e=>{const u=new URL(e.request.url);if(u.pathname.startsWith('/api/devices'))requests.set(e.requestId,{route:u.pathname,method:e.request.method})});page.on('Network.responseReceived',e=>{const r=requests.get(e.requestId);if(r)report.network.push({...r,status:e.response.status})});page.on('Runtime.exceptionThrown',()=>{report.runtimeErrors=(report.runtimeErrors||0)+1})
  await run()
})().catch(async e=>{report.failure={message:e.message};save();console.error('FAIL '+e.message);if(page)try{await shot('failure')}catch{};process.exitCode=1}).finally(async()=>{
  if(page)try{await page.evaluate("localStorage.removeItem('scc:token');localStorage.removeItem('scc:user');sessionStorage.removeItem('scc:token');sessionStorage.removeItem('scc:user');true")}catch{}
  if(browser)try{await browser.call('Browser.close')}catch{}
  if(chrome){await sleep(500);if(chrome.exitCode===null)chrome.kill();try{fs.rmSync(profile,{recursive:true,force:true,maxRetries:5,retryDelay:100})}catch{report.cleanupFailure=true;save();process.exitCode=1}}
  page?.socket.close();browser?.socket.close()
})
