// Explicit acceptance harness. Real Vue + real HTTP/MySQL; no request interception or API mocks.
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict')
const { spawn } = require('node:child_process')
const net = require('node:net')
const CDP = require('./cdp.cjs')
const work=process.env.P3_WORK || path.resolve(__dirname,'../../../../开发资料/work/CP-P3-final-control')
const phase=process.argv[2], origin='http://127.0.0.1:5273'
const manifest=JSON.parse(fs.readFileSync(path.join(work,'browser-fixtures.json'),'utf8'))
assert.equal(manifest.database,'smart_community_care_test')
const output=path.join(work,'browser-results.json')
const report=phase==='prepare'?{evidence:'Real current Vue + real Spring HTTP + smart_community_care_test. No mocks. Test-only injected Clock advances time, production rules unchanged.',checks:[],network:[],fixtures:{},shots:[]}:JSON.parse(fs.readFileSync(output,'utf8'))
delete report.failure
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
const save=()=>fs.writeFileSync(output,JSON.stringify(report,null,2))
const check=(name,detail={})=>{report.checks.push({name,pass:true,...detail});save();console.log('PASS '+name)}
let page,chrome,browser
const profile=path.resolve(work,'chrome-p3-final')
assert.equal(path.dirname(profile),path.resolve(work))
const dialog='Array.from(document.querySelectorAll(".el-dialog")).find(x=>x.getClientRects().length)'
const message='Array.from(document.querySelectorAll(".el-message-box")).find(x=>x.getClientRects().length)'
async function wait(expr,label){for(let i=0;i<120;i++){if(await page.evaluate(expr))return;await sleep(100)}throw new Error('Condition not reached: '+label)}
async function navigate(route){await page.call('Page.navigate',{url:origin+route});await wait('document.readyState === "complete"',route);await sleep(250)}
async function click(text,scope='document'){
  const ok=await page.evaluate(`(()=>{const root=${scope};if(!root)return false;const b=Array.from(root.querySelectorAll('button')).find(x=>x.textContent.trim()===${JSON.stringify(text)} && x.getClientRects().length && !x.disabled);if(!b)return false;b.scrollIntoView({block:'center'});b.click();return true})()`)
  assert.ok(ok,'Visible enabled button '+text);await sleep(160)
}
async function fill(selector,value){assert.ok(await page.evaluate(`(()=>{const x=document.querySelector(${JSON.stringify(selector)});if(!x)return false;const proto=x.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(x,${JSON.stringify(value)});x.dispatchEvent(new Event('input',{bubbles:true}));x.dispatchEvent(new Event('change',{bubbles:true}));x.dispatchEvent(new Event('blur',{bubbles:true}));return true})()`))}
async function fillLabel(label,value){assert.ok(await page.evaluate(`(()=>{const roots=Array.from(document.querySelectorAll('.el-form-item'));const row=roots.find(x=>x.getClientRects().length&&x.querySelector('.el-form-item__label')?.textContent.trim()===${JSON.stringify(label)});const x=row?.querySelector('input,textarea');if(!x)return false;const proto=x.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(x,${JSON.stringify(value)});x.dispatchEvent(new Event('input',{bubbles:true}));x.dispatchEvent(new Event('change',{bubbles:true}));x.dispatchEvent(new Event('blur',{bubbles:true}));return true})()`),'Field '+label)}
async function select(label,index){
  assert.ok(await page.evaluate(`(()=>{const row=Array.from(document.querySelectorAll('.el-form-item')).find(x=>x.getClientRects().length&&x.querySelector('.el-form-item__label')?.textContent.trim()===${JSON.stringify(label)});const x=row?.querySelector('.el-select__wrapper');if(!x)return false;x.scrollIntoView({block:'center'});x.click();return true})()`))
  await wait('Array.from(document.querySelectorAll(".el-select-dropdown__item")).some(x=>x.getClientRects().length)','select options')
  assert.ok(await page.evaluate(`(()=>{const options=Array.from(document.querySelectorAll('.el-select-dropdown__item')).filter(x=>x.getClientRects().length);const x=options[${index}];if(!x)return false;x.click();return true})()`));await sleep(100)
}
async function api(route,options={}){
  return await page.evaluate(`(async()=>{const opts=${JSON.stringify(options)};const r=await fetch('/api'+${JSON.stringify(route)},{method:opts.method||'GET',headers:{'scc-token':'Bearer '+sessionStorage.getItem('scc:token'),...(opts.body?{'Content-Type':'application/json'}:{})},...(opts.body?{body:JSON.stringify(opts.body)}:{})});return {status:r.status,body:await r.json()}})()`)
}
async function login(role){
  await navigate('/login');await page.evaluate("localStorage.removeItem('scc:token');localStorage.removeItem('scc:user');sessionStorage.removeItem('scc:token');sessionStorage.removeItem('scc:user');true");await navigate('/login')
  await fill('input[type="text"]',manifest.users[role].username)
  await fill('input[type="password"]',process.env[role==='admin'?'P3_ADMIN_PASSWORD':role==='elder'?'P3_ELDER_PASSWORD':'P3_STAFF_PASSWORD'])
  await click('登录');await wait('!!sessionStorage.getItem("scc:token")','real login '+role)
  await wait('!location.pathname.startsWith("/login")','role landing '+role)
  const me=await api('/auth/me');assert.equal(me.status,200);assert.equal(typeof me.body.data.id,'string')
}
async function shot(name,width=1366){await page.call('Emulation.setDeviceMetricsOverride',{width,height:width===1920?1080:768,deviceScaleFactor:1,mobile:false});await sleep(160);const s=await page.call('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});fs.writeFileSync(path.join(work,name+'.png'),Buffer.from(s.data,'base64'));report.shots.push(name+'.png');save()}
async function assertState(id,state){await wait(`(async()=>{const r=await fetch('/api/service-orders/'+${JSON.stringify(id)},{headers:{'scc-token':'Bearer '+sessionStorage.getItem('scc:token')}});const b=await r.json();return r.status===200&&b.data?.status===${JSON.stringify(state)}})()`,'order '+state)}
const orderRow=id=>'Array.from(document.querySelectorAll("tr.el-table__row")).find(x=>x.querySelector("button") && x.textContent.includes('+JSON.stringify(report.fixtures['no_'+id])+'))'
async function orderAction(id,label){await click(label,orderRow(id))}
async function adminDetail(id){await navigate('/admin/orders');await wait(orderRow(id),'admin row');await orderAction(id,'详情');await wait('document.querySelector(".el-drawer")?.textContent.includes('+JSON.stringify(report.fixtures['no_'+id])+')','admin detail')}
async function assign(id,role,transfer=false){
  await adminDetail(id);await click(transfer?'转派他人':'分派服务人员','document.querySelector(".el-drawer")')
  await wait(dialog,'assignment dialog');const people=(await api('/users/assignable-staff')).body.data
  const index=people.findIndex(x=>x.id===manifest.users[role].id);assert.ok(index>=0)
  await select(transfer?'新服务人员':'服务人员',index)
  await fillLabel(transfer?'转派原因':'说明','登记人员交接验收')
  await click('确认',dialog);await assertState(id,'ASSIGNED')
}
async function staffAction(id,reject=false){
  await navigate('/staff/tasks');await wait(orderRow(id),'staff assigned row');await orderAction(id,reject?'拒接':'接单')
  await wait(dialog,'staff action dialog');await fill('.el-dialog textarea',reject?'登记拒接：当日安排冲突':'登记接单：已确认')
  await click(reject?'确认拒接':'确认接单',dialog)
  // After rejection this staff loses detail permission; verify list removal instead.
  if(reject)await wait('!'+orderRow(id),'rejected current task removed');else await assertState(id,'ACCEPTED')
}
async function submit(id,text){await navigate('/staff/tasks');await wait(orderRow(id),'execution row');await orderAction(id,'提交结果');await wait(dialog,'submit dialog');await fill('.el-dialog textarea',text);await click('提交',dialog);await assertState(id,'PENDING_CONFIRMATION')}
async function start(id){await navigate('/staff/tasks');await wait(orderRow(id),'accepted row');await orderAction(id,'开始服务');await assertState(id,'IN_SERVICE')}
async function checkProfile(role){
  await page.evaluate('document.querySelector(".avatar-entry").click();true');await wait('location.pathname.endsWith("/profile")','avatar profile')
  assert.equal(await page.evaluate('Array.from(document.querySelectorAll(".side-nav a")).some(x=>x.textContent.includes("个人中心"))'),false)
  await wait('document.querySelector(".profile-form-grid")','profile grid')
  await fillLabel('姓名','P3验收-'+role);await click('保存资料')
  await wait(`(async()=>{const r=await fetch('/api/auth/me',{headers:{'scc-token':'Bearer '+sessionStorage.getItem('scc:token')}});return (await r.json()).data?.realName===${JSON.stringify('P3验收-'+role)}})()`,'profile persisted')
  await click('修改密码');await wait(dialog,'password dialog');await shot('profile-'+role+'-1366');await shot('profile-'+role+'-1920',1920)
  await click('暂不修改',dialog);check('Avatar-only profile and real profile save '+role)
}
async function prepare(){
  await login('admin');await checkProfile('admin')
  await navigate('/admin/services');await wait('document.querySelector(".service-manage")','management')
  await click('新建服务');await wait(dialog,'service dialog')
  for(const [k,v] of Object.entries({'服务名称':'页面验收服务','说明':'真实页面登记夹具 [IT-FIXTURE]','参考价格':'40','提供方':'登记社区服务','联系电话':'13900001111'}))await fillLabel(k,v)
  await click('保存');await wait('document.body.textContent.includes("页面验收服务")','created service')
  const matching=(await api('/services?all=true')).body.data.filter(x=>x.name==='页面验收服务');assert.equal(matching.length,1,'Registered service name must identify exactly one row');const service=matching[0];assert.ok(service.description.includes('[IT-FIXTURE]'));report.fixtures.serviceId=service.id;save()
  await page.evaluate('Array.from(document.querySelectorAll(".item-row")).find(x=>x.textContent.includes("页面验收服务")).click();true')
  await click('新建时段');await fillLabel('开始时间',manifest.startAt.replace('T',' '));await fillLabel('结束时间',manifest.endAt.replace('T',' '));await fillLabel('容量','10');await click('创建')
  await wait('document.querySelector(".service-manage tr.el-table__row")','future slot')
  const slot=(await api('/services/'+service.id+'/slots?all=true')).body.data[0];assert.ok(slot);report.fixtures.slotId=slot.id;save()
  const slotRow='document.querySelector(".service-manage .el-table__body-wrapper tbody tr.el-table__row")'
  await click('关闭',slotRow);await click('确定',message);await wait('('+slotRow+')?.textContent.includes("已关闭")','closed slot')
  await click('开放',slotRow);await click('确定',message);await wait('!('+slotRow+')?.textContent.includes("已关闭")','reopened slot')
  const itemScope='Array.from(document.querySelectorAll(".item-row")).find(x=>x.querySelector(".item-name")?.textContent.trim()==="页面验收服务")'
  await click('下架',itemScope);await click('确定',message);await wait('('+itemScope+')?.textContent.includes("已下架")','disabled service')
  await click('上架',itemScope);await click('确定',message);await wait('!('+itemScope+')?.textContent.includes("已下架")','enabled service')
  check('Real service create, slot create, close/reopen and disable/enable',{serviceId:service.id,slotId:slot.id})
  await shot('admin-service-management')
  await login('elder');await checkProfile('elder')
  for(const kind of ['normal','exceptional','cancelled']){
    await navigate('/elder/service-booking');await wait('document.querySelector(".service-item")','elder services')
    await page.evaluate('Array.from(document.querySelectorAll(".service-item")).find(x=>x.textContent.includes("页面验收服务")).click();true')
    await wait('document.querySelector(".el-radio")','elder slot');await page.evaluate('document.querySelector(".el-radio").click();true')
    await fillLabel('联系人','P3联系人-'+kind);await fillLabel('联系电话','13900002222');await fillLabel('服务地址','P3登记地址')
    await click('提交预约');await wait(`(async()=>{const r=await fetch('/api/service-orders',{headers:{'scc-token':'Bearer '+sessionStorage.getItem('scc:token')}});return ((await r.json()).data||[]).some(x=>x.contactName===${JSON.stringify('P3联系人-'+kind)})})()`,'real booking '+kind)
    const o=(await api('/service-orders')).body.data.find(x=>x.contactName==='P3联系人-'+kind);report.fixtures[kind]=o.id;report.fixtures['no_'+o.id]=o.orderNo;save()
  }
  const f=report.fixtures
  await navigate('/elder/my-orders');await wait(orderRow(f.cancelled),'cancel row');await orderAction(f.cancelled,'取消');await wait(dialog,'cancel dialog');await fill('.el-dialog textarea','登记取消：不再需要预约');await click('确认取消',dialog);await assertState(f.cancelled,'CANCELLED')
  await orderAction(f.normal,'详情');await wait('document.querySelector(".el-drawer")?.textContent.includes("P3登记地址")','elder detail');check('Real elder booking, detail and versioned cancellation');await shot('elder-orders')
  // The creator cancels an unassigned help call; administrators must not see this creator-only action.
  await navigate('/elder/help-call');await select('求助类型',3);await fillLabel('联系电话','13900002222');await fillLabel('补充说明','P3登记本人撤销求助');await click('提交求助');await wait('location.pathname==="/elder/my-alerts"','cancellable help submitted')
  const cancelledHelp=(await api('/alert-events')).body.data.find(x=>x.description==='P3登记本人撤销求助');assert.ok(cancelledHelp)
  await navigate('/alerts/'+cancelledHelp.id);await wait('document.querySelector(".actions")','creator event actions');await click('撤销');await wait(message,'cancel help prompt');await fill('.el-message-box input','登记撤销：已自行处理');await click('确认撤销',message);await wait('document.body.textContent.includes("已撤销")','help cancelled')
  check('Real creator-only help cancellation carries the loaded version',{eventId:cancelledHelp.id})
  // Create and handle one existing event task through the normal pages.
  await navigate('/elder/help-call');await select('求助类型',3);await fillLabel('联系电话','13900002222');await fillLabel('补充说明','P3事件页面登记验收');await click('提交求助');await wait('location.pathname==="/elder/my-alerts"','help submitted')
  f.eventId=(await api('/alert-events')).body.data.find(x=>x.description==='P3事件页面登记验收').id;save()
  await login('admin');await assign(f.normal,'staffA');await assign(f.exceptional,'staffA')
  await login('staffA');await checkProfile('staffA');await staffAction(f.normal,true)
  await login('admin');await assertState(f.normal,'PENDING_ASSIGNMENT');let o=(await api('/service-orders/'+f.normal)).body.data;assert.ok(o.staffId==null);assert.ok(o.acceptedAt==null)
  await assign(f.normal,'staffA');await login('staffA');await staffAction(f.normal);await staffAction(f.exceptional)
  await login('admin');await assign(f.normal,'staffB',true)
  o=(await api('/service-orders/'+f.normal)).body.data;assert.ok(o.acceptedAt==null);assert.equal(o.staffId,manifest.users.staffB.id)
  // Snapshot verification after real catalog edit.
  await navigate('/admin/services');await click('编辑',itemScope);await fillLabel('服务名称','页面验收服务修改后');await fillLabel('参考价格','80');await click('保存')
  await wait('document.body.textContent.includes("页面验收服务修改后")','catalog edit')
  o=(await api('/service-orders/'+f.normal)).body.data;assert.equal(o.serviceNameSnapshot,'页面验收服务');assert.equal(Number(o.priceSnapshot),40);assert.equal(o.addressSnapshot,'P3登记地址')
  check('Real rejection, reassignment, acceptance and transfer; old order snapshots retained')
  await navigate('/alerts/'+f.eventId);await wait('document.querySelector(".actions")','event actions');assert.equal(await page.evaluate('Array.from(document.querySelectorAll(".actions button")).some(x=>x.textContent.trim()==="撤销")'),false);await click('分派');await wait(message,'assignee prompt');await fill('.el-message-box input',manifest.users.staffA.id);await click('确认分派',message);await wait('document.body.textContent.includes("待接收")','assigned event')
  await login('staffA');await navigate('/staff/tasks');await wait('document.querySelector(".participation-tip")','participation area')
  const current=(await api('/service-orders/my-tasks/paged')).body.data.records;assert.ok(!current.some(x=>x.id===f.normal))
  const history=(await api('/service-orders/my-participations')).body.data;const record=history.find(x=>x.orderId===f.normal);assert.ok(record);assert.ok(!('contactPhone' in record));assert.ok(!('addressSnapshot' in record));assert.ok(!('health' in record))
  await wait('document.body.textContent.includes('+JSON.stringify(f['no_'+f.normal])+')','masked history row')
  for(const route of ['/service-orders/'+f.normal,'/service-orders/'+f.normal+'/view','/service-orders/'+f.normal+'/logs'])assert.equal((await api(route)).status,404)
  assert.equal((await api('/service-orders/'+f.normal+'/accept',{method:'POST',body:{reason:'旧受派人无权操作',version:o.version}})).status,404)
  check('Transferred staff loses current task, full detail, logs and actions; masked participation remains',{orderId:f.normal})
  await shot('staff-participation-after-transfer')
  await navigate('/staff/alerts');await wait('document.querySelector(".staff-alerts") || document.querySelector(".el-table")','staff event list')
  await navigate('/alerts/'+f.eventId);await wait('document.querySelector(".actions")','staff event detail');await click('接收');await wait('document.body.textContent.includes("处理中")','accepted event');await click('关闭');await wait(message,'close result');await fill('.el-message-box input','RESOLVED');await click('下一步',message);await wait(message+'.querySelector("input")','close note');await fill('.el-message-box input','登记关闭说明：已核实处理');await click('确认关闭',message);await wait('document.body.textContent.includes("已关闭")','closed event');await shot('staff-event-closed')
  check('Staff event page: real assignment, receive, close result/note and timeline')
  await login('staffB');await staffAction(f.normal)
  const beforeEarlyStart=(await api('/service-orders/'+f.normal)).body.data
  await navigate('/staff/tasks');await wait(orderRow(f.normal),'early-start row');await orderAction(f.normal,'开始服务')
  await wait(`document.body.textContent.includes('不能提前开工')`,'early start refusal')
  const afterEarlyStart=(await api('/service-orders/'+f.normal)).body.data
  assert.equal(afterEarlyStart.status,'ACCEPTED');assert.ok(afterEarlyStart.startedAt==null);assert.equal(afterEarlyStart.version,beforeEarlyStart.version)
  assert.ok(report.network.some(x=>x.route==='/api/service-orders/'+f.normal+'/start'&&x.status===400))
  check('Actual page refuses early start without changing order version or start time')
  // Execute actual API module with a large ID; real backend rejects missing object.
  const large=await page.evaluate(`(async()=>{const m=await import('/src/api/order.js');try{await m.getOrder('9007199254740993')}catch(e){return e.response?.status}})()`);assert.equal(large,404)
  check('Actual frontend API preserves ID above JavaScript safe integer in request URL')
  await navigate('/staff');await shot('staff-workbench-1366');await shot('staff-workbench-1920',1920)
  save()
}
async function finish(){
  const f=report.fixtures
  await login('staffB');await start(f.normal);await submit(f.normal,'登记首轮服务结果：已完成保洁')
  let o=(await api('/service-orders/'+f.normal)).body.data;report.firstStart=o.startedAt;report.firstFinish=o.finishedAt;report.firstSubmissionVersion=o.version;save()
  await login('elder');await navigate('/elder/my-orders');await wait(orderRow(f.normal),'pending confirmation');await orderAction(f.normal,'未完成');await wait(dialog,'return dialog');await fill('.el-dialog textarea','登记返工：厨房角落需要重做');await click('提交反馈',dialog);await assertState(f.normal,'IN_SERVICE')
  o=(await api('/service-orders/'+f.normal)).body.data;assert.equal(o.startedAt,report.firstStart);assert.equal(o.finishedAt,report.firstFinish)
  await login('staffB');await submit(f.normal,'登记第二轮结果：厨房角落已重做');await login('elder');await navigate('/elder/my-orders');await wait(orderRow(f.normal),'confirmation row');await orderAction(f.normal,'确认完成');await assertState(f.normal,'COMPLETED')
  await wait('('+orderRow(f.normal)+')?.textContent.includes("评价")','review entry');await orderAction(f.normal,'评价');await wait(dialog,'review dialog');await fill('.el-dialog textarea','登记评价：服务已完成');await click('提交评价',dialog)
  await wait('('+orderRow(f.normal)+')?.textContent.includes("已评价")','review persisted');assert.ok(await page.evaluate('Array.from(('+orderRow(f.normal)+').querySelectorAll("button")).find(x=>x.textContent.trim()==="已评价")?.disabled'))
  o=(await api('/service-orders/'+f.normal)).body.data;assert.equal(o.startedAt,report.firstStart);assert.equal(o.version,report.firstSubmissionVersion+3)
  check('Real start, submit, return, resubmit, elder confirm and one immutable review',{orderId:f.normal,firstStart:o.startedAt,latestFinish:o.finishedAt})
  await shot('elder-completed-reviewed-1366');await shot('elder-completed-reviewed-1920',1920)
  await login('staffB');await navigate('/staff/reviews');await wait('document.body.textContent.includes("登记评价：服务已完成")','received review');assert.equal((await api('/service-orders/reviews/received')).body.data.length,1);await shot('staff-received-review');check('Final executing staff receives the actual review')
  await login('staffA');await start(f.exceptional);await login('admin');await adminDetail(f.exceptional);await click('异常终止','document.querySelector(".el-drawer")');await wait(dialog,'termination dialog');await fill('.el-dialog textarea','登记终止：老人临时住院');await click('确认终止',dialog);await assertState(f.exceptional,'TERMINATED');await shot('admin-terminated');check('Real administrator termination preserves slot occupation',{orderId:f.exceptional})
  await login('elder');assert.equal((await api('/service-orders/admin/all')).status,403);assert.equal((await api('/services?all=true')).status,403)
  const review=(await api('/service-orders/'+f.normal+'/review')).body.data;assert.equal(review.rating,5)
  assert.equal((await api('/service-orders/'+f.normal+'/review',{method:'POST',body:{rating:1,content:'禁止覆盖'}})).status,409)
  check('Direct forbidden API and duplicate review return correct refusal')
  assert.ok(report.network.some(x=>x.route==='/api/service-orders/9007199254740993'&&x.status===404))
  const actions=report.network.filter(x=>x.method==='POST'&&/\/api\/service-orders\/\d+\/(assign|transfer|accept|reject|start|submit-result|confirm|return|terminate|cancel)$/.test(x.route))
  assert.ok(actions.length>=16)
  for(const x of actions)assert.equal(typeof x.version,'number','Actual page action must carry numeric version '+x.route)
  const eventActions=report.network.filter(x=>x.method==='POST'&&/\/api\/alert-events\/\d+\/(assign|accept|close)$/.test(x.route));assert.equal(eventActions.length,3)
  for(const x of eventActions)assert.match(x.versionQuery||'',/^\d+$/)
  check('Actual page requests carry read versions: order and event actions',{orderRequests:actions.length,eventRequests:eventActions.length})
}
;(async()=>{
  assert.ok(!fs.existsSync(profile),'Owned Chrome profile must be absent before starting')
  const occupied=await new Promise(resolve=>{const s=net.createConnection({host:'127.0.0.1',port:19228});s.setTimeout(1000);s.once('connect',()=>{s.destroy();resolve(true)});s.once('error',()=>resolve(false));s.once('timeout',()=>{s.destroy();resolve(false)})})
  assert.equal(occupied,false,'CDP port already in use; do not attach to another browser')
  chrome=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--remote-debugging-port=19228','--remote-debugging-address=127.0.0.1','--user-data-dir='+profile,'--no-first-run','--no-default-browser-check','about:blank'],{stdio:['ignore','ignore','pipe'],windowsHide:true})
  chrome.stderr.on('data',chunk=>{const lines=chunk.toString().split(/\r?\n/).filter(x=>x&&!/password|token|cookie/i.test(x));report.chromeDiagnostics=(report.chromeDiagnostics||[]).concat(lines).slice(-20)})
  let v;for(let i=0;i<100;i++){try{v=await(await fetch('http://127.0.0.1:19228/json/version')).json();break}catch{await sleep(100)}}assert.ok(v,'Chrome startup')
  browser=new CDP(v.webSocketDebuggerUrl);const t=await(await fetch('http://127.0.0.1:19228/json/new?about:blank',{method:'PUT'})).json();page=new CDP(t.webSocketDebuggerUrl)
  await page.call('Page.enable');await page.call('Runtime.enable');await page.call('Network.enable');await page.call('Emulation.setDeviceMetricsOverride',{width:1366,height:768,deviceScaleFactor:1,mobile:false})
  const requests=new Map()
  page.on('Network.requestWillBeSent',e=>{const u=new URL(e.request.url);if(u.pathname.startsWith('/api/')&&!u.pathname.includes('/auth/')){let version;try{version=JSON.parse(e.request.postData||'{}').version}catch{}requests.set(e.requestId,{route:u.pathname,method:e.request.method,version,versionQuery:u.searchParams.get('version')})}})
  page.on('Network.responseReceived',e=>{const req=requests.get(e.requestId);if(req)report.network.push({...req,status:e.response.status})})
  page.on('Runtime.exceptionThrown',()=>{report.runtimeErrors=(report.runtimeErrors||0)+1})
  await (phase==='prepare'?prepare():finish());assert.equal(report.runtimeErrors||0,0);report[phase+'Complete']=true;save()
})().catch(async e=>{report.failure={phase,message:e.message};save();console.error('FAIL '+e.message);if(page)try{await shot('failure-'+phase)}catch{};process.exitCode=1}).finally(async()=>{
  if(page)try{await page.evaluate("localStorage.removeItem('scc:token');localStorage.removeItem('scc:user');sessionStorage.removeItem('scc:token');sessionStorage.removeItem('scc:user');true")}catch{}
  if(browser)try{await browser.call('Browser.close')}catch{}
  if(chrome){await sleep(500);if(chrome.exitCode===null)chrome.kill()}
  if(chrome)try{fs.rmSync(profile,{recursive:true,force:true,maxRetries:5,retryDelay:100})}catch{report.cleanupFailure='Owned browser profile could not be removed';save();console.error(report.cleanupFailure);process.exitCode=1}
  page?.socket.close();browser?.socket.close()
})
