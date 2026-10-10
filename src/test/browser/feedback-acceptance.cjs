const fs = require('node:fs'), path = require('node:path'), net = require('node:net')
const assert = require('node:assert/strict'), { spawn } = require('node:child_process')
const CDP = require('./cdp.cjs')
const work = process.env.FEEDBACK_WORK || path.resolve(__dirname, '../../../../开发资料/work/使用反馈整改')
const fixture = JSON.parse(fs.readFileSync(path.join(work, 'browser-fixtures.json'), 'utf8'))
assert.equal(fixture.database, 'smart_community_care_test')
const origin = 'http://127.0.0.1:5273', port = 19329
const report = { evidence: 'Real browser, Vue forms, HTTP, dedicated MySQL and normal automatic device scheduler. No mocked API.', checks: [], shots: [], runtimeErrors: 0 }
const save = () => fs.writeFileSync(path.join(work, 'browser-results.json'), JSON.stringify(report, null, 2))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
let chrome, browser, page
const tabs = {}, slotPosts = [], resultPosts = [], confirmPosts = []
async function wait(read, label, timeout = 15000) {
  const until = Date.now() + timeout
  while (Date.now() < until) { if (await read()) return; await sleep(150) }
  throw new Error('Condition not reached: ' + label)
}
const dom = (expr, label) => wait(() => page.evaluate(expr), label)
async function nav(route) {
  await page.call('Page.bringToFront'); await page.call('Page.navigate', { url: origin + route })
  await dom('document.readyState === "complete"', route); await sleep(250)
}
async function click(text, scope = 'document') {
  assert.ok(await page.evaluate(`(()=>{const r=${scope};const x=r&&Array.from(r.querySelectorAll('button')).find(x=>x.getClientRects().length&&!x.disabled&&x.textContent.trim()===${JSON.stringify(text)});if(!x)return false;x.click();return true})()`), 'Button ' + text)
}
async function fill(selector, value) {
  assert.ok(await page.evaluate(`(()=>{const x=document.querySelector(${JSON.stringify(selector)});if(!x)return false;const p=x.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(p,'value').set.call(x,${JSON.stringify(value)});x.dispatchEvent(new Event('input',{bubbles:true}));x.dispatchEvent(new Event('change',{bubbles:true}));x.blur();return true})()`), 'Input ' + selector)
}
const row = no => `Array.from(document.querySelectorAll('.el-table__row')).find(x=>x.getClientRects().length&&x.textContent.includes(${JSON.stringify(no)}))`
async function api(route) {
  return page.evaluate(`(async()=>{const r=await fetch('/api'+${JSON.stringify(route)},{headers:{'scc-token':'Bearer '+sessionStorage.getItem('scc:token')}});return {status:r.status,body:await r.json()}})()`)
}
async function ownDevice() {
  const response=await api('/devices/paged')
  assert.equal(response.status,200)
  return response.body.data.records.find(x=>x.id===fixture.deviceId)
}
function check(name, evidence = {}) { report.checks.push({ name, pass: true, ...evidence }); save(); console.log('PASS ' + name) }
async function shot(name, width = 1366) {
  await page.call('Page.bringToFront')
  await page.call('Emulation.setDeviceMetricsOverride', {width,height:width===1920?1080:768,deviceScaleFactor:1,mobile:false})
  await sleep(200)
  assert.ok(await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1'), 'No page horizontal overflow')
  const image = await page.call('Page.captureScreenshot', {format:'png'})
  fs.writeFileSync(path.join(work,name+'.png'),Buffer.from(image.data,'base64'));report.shots.push(name+'.png');save()
}
async function login(role, username = 'it_feedback_'+role, passwordRole = role) {
  const target = await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, {method:'PUT'})).json()
  page = tabs[role] = new CDP(target.webSocketDebuggerUrl)
  await page.call('Page.enable');await page.call('Runtime.enable');await page.call('Network.enable')
  await page.call('Emulation.setDeviceMetricsOverride',{width:1366,height:768,deviceScaleFactor:1,mobile:false})
  page.on('Runtime.exceptionThrown',()=>report.runtimeErrors++)
  page.on('Network.requestWillBeSent', e => {
    const url = new URL(e.request.url)
    if(e.request.method==='POST'&&url.pathname.endsWith('/slots'))slotPosts.push(url.search)
    if(e.request.method==='POST'&&url.pathname.endsWith('/submit-result'))resultPosts.push(JSON.parse(e.request.postData))
    if(e.request.method==='POST'&&url.pathname.endsWith('/confirm-as-admin'))confirmPosts.push(JSON.parse(e.request.postData))
  })
  await nav('/login');await fill('input[type="text"]',username)
  await fill('input[type="password"]',process.env['FEEDBACK_'+passwordRole.toUpperCase()+'_PASSWORD']);await click('登录')
  await dom('!!sessionStorage.getItem("scc:token")&&!location.pathname.startsWith("/login")','login '+role)
}
async function slotForm(start, end) {
  await click('新建时段');await dom('document.querySelector(".el-dialog input")','slot dialog')
  await fill('.el-dialog .el-form-item:nth-child(1) input',start.replace('T',' '))
  await fill('.el-dialog .el-form-item:nth-child(2) input',end.replace('T',' '))
  await click('创建')
}
(async()=>{
  const available = await new Promise(resolve=>{const server=net.createServer();server.once('error',()=>resolve(false));server.listen(port,'127.0.0.1',()=>server.close(()=>resolve(true)))})
  assert.ok(available,'Debug port belongs to no other browser')
  chrome=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check',`--remote-debugging-port=${port}`,`--user-data-dir=${path.join(work,'chrome-feedback')}`,'about:blank'],{windowsHide:true,stdio:'ignore'})
  await wait(async()=>{try{return(await fetch(`http://127.0.0.1:${port}/json/version`)).ok}catch{return false}},'Chrome')
  browser=new CDP((await(await fetch(`http://127.0.0.1:${port}/json/version`)).json()).webSocketDebuggerUrl)
  await login('admin');await nav('/admin/services')
  await dom('Array.from(document.querySelectorAll(".item-name")).some(x=>x.textContent==="使用反馈验收服务")','fixture service')
  await page.evaluate('Array.from(document.querySelectorAll(".item-name")).find(x=>x.textContent==="使用反馈验收服务").click();true')
  await sleep(200)
  await slotForm('2020-01-01T09:00:00','2020-01-01T10:00:00')
  await dom('document.body.textContent.includes("开始时间必须晚于当前时间")','useful past time error')
  assert.equal(slotPosts.length,0,'Rejected form sends no request')
  await fill('.el-dialog .el-form-item:nth-child(1) input',fixture.newSlotStart.replace('T',' '))
  await fill('.el-dialog .el-form-item:nth-child(2) input',fixture.newSlotEnd.replace('T',' '))
  await click('创建');await dom('document.body.textContent.includes("时段已创建")','slot saved')
  assert.equal(slotPosts.length,1);const params=new URLSearchParams(slotPosts[0])
  assert.equal(params.get('capacity'),'1');assert.equal(params.get('startAt'),fixture.newSlotStart.length===16?fixture.newSlotStart+':00':fixture.newSlotStart)
  await slotForm(fixture.newSlotStart,fixture.newSlotEnd)
  await dom('document.body.textContent.includes("该时段与已有时段重叠")','overlap reason')
  check('管理员真实表单创建未来时段，过去时间和重叠明确拒绝',{post:slotPosts[0]})
  await click('取消');await shot('管理员时段-1366')

  await login('elder');await nav('/elder/my-devices')
  await dom('document.body.textContent.includes("验收电灯")','own device');await click('启动设备')
  await wait(async()=>{const device=await ownDevice();return device?.simulatorRunning&&device?.controlable},'own start with heartbeat')
  await dom('document.querySelector(".el-switch")&&!document.querySelector(".el-switch").classList.contains("is-disabled")','control switch')
  await page.evaluate('document.querySelector(".el-switch").click();true')
  await wait(async()=>(await ownDevice())?.powerStatus==='ON','automatic command execution')
  await dom('document.body.textContent.includes("成功")','page command result')
  await click('停止设备');await wait(async()=>(await ownDevice())?.simulatorRunning===false,'stop own device')
  await dom('Array.from(document.querySelectorAll("button")).some(x=>x.textContent.trim()==="启动设备")','restart button');await click('启动设备')
  await shot('老人设备-1366');await shot('老人设备-1920',1920)
  check('老人自行启动、控制、停止自己的设备，正常调度确认开关',{deviceId:fixture.deviceId})

  await login('staff');await nav('/staff/tasks')
  await dom(`${row(fixture.orderNo)}&&${row(fixture.futureOrderNo)}`,'claimable and future task')
  assert.ok(await page.evaluate(`(()=>{const r=${row(fixture.futureOrderNo)};return Array.from(r.querySelectorAll('button')).some(x=>x.disabled&&x.textContent.trim()==='预约时间未到')})()`))
  await shot('人员待领取-1366');await shot('人员待领取-1920',1920)
  await click('领取服务',row(fixture.orderNo))
  await dom(`!!${row(fixture.orderNo)}?.textContent.includes('开始服务')`,'claimed task can start')
  await click('开始服务',row(fixture.orderNo))
  await dom(`!!${row(fixture.orderNo)}?.textContent.includes('服务中')`,'in service')
  await click('详情',row(fixture.orderNo));await dom('document.querySelector(".el-drawer")&&document.body.textContent.includes("登记验收地址")','details')
  await click('提交服务结果','document.querySelector(".el-drawer")')
  await dom('document.querySelector(".el-dialog textarea")','real result form')
  assert.equal(resultPosts.length,0);assert.equal(await page.evaluate('document.querySelector(".el-dialog textarea").value'),'')
  await fill('.el-dialog textarea','实际完成居室整理，物品归位，老人确认无遗漏。')
  await click('提交','document.querySelector(".el-dialog")')
  await dom(`!!${row(fixture.orderNo)}?.textContent.includes('待确认')`,'pending elder confirmation')
  assert.equal(resultPosts.length,1);assert.equal(resultPosts[0].result,'实际完成居室整理，物品归位，老人确认无遗漏。')
  check('人员自主领取、按时间开工、从详情填写实际结果并提交',{result:resultPosts[0]})

  page=tabs.elder;await nav('/elder/my-orders');await dom(`${row(fixture.orderNo)}?.textContent.includes('确认完成')`,'elder order')
  await click('确认完成',row(fixture.orderNo));await dom(`${row(fixture.orderNo)}?.textContent.includes('评价')`,'completed order')
  await click('评价',row(fixture.orderNo));await dom('document.querySelector(".el-dialog .el-rate")','review dialog')
  await page.evaluate('document.querySelector(".el-dialog .el-rate__item:nth-child(4)").click();true')
  await fill('.el-dialog textarea','服务认真，整理细致，感谢上门帮助。');await click('提交评价')
  await dom('document.body.textContent.includes("评价已提交")','rating submitted')
  await shot('老人订单评价-1366');page=tabs.staff;await nav('/staff/reviews')
  await dom('document.body.textContent.includes("服务认真，整理细致，感谢上门帮助。")','staff received review')
  await shot('人员收到评价-1366')
  check('订单所属老人确认完成后给本单人员评价，人员收到评价')

  page=tabs.admin;await nav('/admin/orders?status=PENDING_CONFIRMATION')
  await dom(`!!${row(fixture.adminConfirmOrderNo)}`,'administrator pending confirmation')
  await click('详情',row(fixture.adminConfirmOrderNo));await dom('document.querySelector(".el-drawer")?.textContent.includes("代确认完成")','confirmation entry')
  await click('代确认完成','document.querySelector(".el-drawer")')
  await dom('document.querySelector(".el-dialog textarea")','confirmation reason form')
  await click('确认完成','document.querySelector(".el-dialog")')
  await dom('document.body.textContent.includes("请填写代确认原因")','required reason')
  assert.equal(confirmPosts.length,0)
  await fill('.el-dialog textarea','已电话核实服务完成，老人同意社区代确认。')
  await click('确认完成','document.querySelector(".el-dialog")')
  await dom('document.querySelector(".el-drawer")?.textContent.includes("已完成")','administrator completed')
  assert.equal(confirmPosts.length,1);assert.equal(confirmPosts[0].version,3)
  assert.equal(confirmPosts[0].reason,'已电话核实服务完成，老人同意社区代确认。')
  assert.equal(await page.evaluate('/CONFIRM|SUBMIT|PENDING_CONFIRMATION|COMPLETED/.test(document.querySelector(".el-drawer .el-timeline").textContent)'),false)
  await shot('管理员代确认与中文时间线-1366')
  check('管理员填写实际原因代确认完成，携带原版本且时间线显示中文',{request:confirmPosts[0]})

  page=tabs.staff;await nav('/staff/tasks')
  await dom('document.querySelector(".my-tasks")&&!document.querySelector(".el-loading-mask")','task page ready')
  const staffPage=page, delay={held:null,once:true}
  staffPage.on('Fetch.requestPaused',async event=>{
    if(delay.once&&event.request.url.includes('scope=HISTORY')){delay.once=false;delay.held=event.requestId}
    else await staffPage.call('Fetch.continueRequest',{requestId:event.requestId})
  })
  await staffPage.call('Fetch.enable',{patterns:[{urlPattern:'*service-orders/my-tasks/paged*',requestStage:'Response'}]})
  await page.evaluate('document.querySelector(".my-tasks .el-form .el-select__wrapper").click();true')
  await dom('Array.from(document.querySelectorAll(".el-select-dropdown__item")).some(e=>e.getClientRects().length&&e.textContent.trim()==="历史任务")','history option')
  await page.evaluate('Array.from(document.querySelectorAll(".el-select-dropdown__item")).find(e=>e.getClientRects().length&&e.textContent.trim()==="历史任务").click();true')
  await wait(()=>delay.held,'held history response')
  for(let i=0;i<2;i++){
    await staffPage.call('Input.dispatchKeyEvent',{type:'keyDown',key:'ArrowDown',code:'ArrowDown',windowsVirtualKeyCode:40})
    await staffPage.call('Input.dispatchKeyEvent',{type:'keyUp',key:'ArrowDown',code:'ArrowDown',windowsVirtualKeyCode:40})
  }
  await staffPage.call('Input.dispatchKeyEvent',{type:'keyDown',key:'Enter',code:'Enter',windowsVirtualKeyCode:13})
  await staffPage.call('Input.dispatchKeyEvent',{type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13})
  const state='document.querySelector(".my-tasks").__vueParentComponent.setupState'
  await dom(`${state}.query.scope==="ALL"&&!${state}.loading`,'latest all query completed')
  const before=await page.evaluate(`({scope:${state}.query.scope,total:${state}.total,ids:${state}.tasks.map(e=>e.id)})`)
  assert.equal(before.total,3)
  await staffPage.call('Fetch.continueRequest',{requestId:delay.held});delay.held=null;await sleep(300)
  const after=await page.evaluate(`({scope:${state}.query.scope,total:${state}.total,ids:${state}.tasks.map(e=>e.id)})`)
  assert.deepEqual(after,before)
  assert.equal(await page.evaluate('/CLAIM|ACCEPT|START|SUBMIT/.test(document.querySelector(".my-tasks").innerText)'),false)
  await staffPage.call('Fetch.disable');await shot('人员快速切换与中文历史-1366')
  check('历史任务晚响应不覆盖全部任务，参与历史动作显示中文',{before,after})

  await login('paging','it_feedback_paging','elder');await nav('/elder/my-devices')
  await dom('document.querySelectorAll(".dev-card").length===20&&!document.querySelector(".el-loading-mask")','twenty registered devices')
  const devicePage=page, deviceDelay={held:null,once:true}
  devicePage.on('Fetch.requestPaused',async event=>{
    if(deviceDelay.once){deviceDelay.once=false;deviceDelay.held=event.requestId}
    else await devicePage.call('Fetch.continueRequest',{requestId:event.requestId})
  })
  const deviceState='document.querySelector(".page").__vueParentComponent.setupState'
  await devicePage.call('Fetch.enable',{patterns:[{urlPattern:'*devices/paged*',requestStage:'Response'}]})
  await wait(()=>deviceDelay.held,'held background device refresh',8000)
  assert.ok(await page.evaluate('(()=>{const e=document.querySelector(".el-pagination .btn-next");if(!e||e.disabled)return false;e.click();return true})()'))
  await dom(`${deviceState}.page===2&&${deviceState}.devices.length===5&&!${deviceState}.loading`,'second page wins background refresh')
  const second=await page.evaluate(`({page:${deviceState}.page,total:${deviceState}.total,ids:${deviceState}.devices.map(e=>e.id)})`)
  assert.equal(second.total,25)
  await devicePage.call('Fetch.continueRequest',{requestId:deviceDelay.held});deviceDelay.held=null;await sleep(250)
  assert.deepEqual(await page.evaluate(`({page:${deviceState}.page,total:${deviceState}.total,ids:${deviceState}.devices.map(e=>e.id)})`),second)
  deviceDelay.once=true;await wait(()=>deviceDelay.held,'held background refresh before size change',8000)
  await page.evaluate('document.querySelector(".el-pagination__sizes .el-select__wrapper").click();true')
  await dom('Array.from(document.querySelectorAll(".el-select-dropdown__item")).some(e=>e.getClientRects().length&&e.textContent.replace(/\\s/g,"")==="10条/页")','ten per page option')
  await page.evaluate('Array.from(document.querySelectorAll(".el-select-dropdown__item")).find(e=>e.getClientRects().length&&e.textContent.replace(/\\s/g,"")==="10条/页").click();true')
  await dom(`${deviceState}.size===10&&${deviceState}.page===1&&${deviceState}.devices.length===10&&!${deviceState}.loading`,'new size wins background refresh')
  await devicePage.call('Fetch.continueRequest',{requestId:deviceDelay.held});deviceDelay.held=null;await sleep(250)
  assert.ok(await page.evaluate(`${deviceState}.size===10&&${deviceState}.page===1&&${deviceState}.devices.length===10&&${deviceState}.total===25`))
  await devicePage.call('Fetch.disable');await shot('设备后台刷新与真实翻页-1366');await shot('设备后台刷新与真实翻页-1920',1920)
  check('25台登记设备的翻页和每页条数在后台响应延迟时均不被覆盖',{second,newSize:10,finalPage:1,total:25})
  assert.equal(report.runtimeErrors,0);report.complete=true;save()
})().catch(async error=>{
  report.error=error.message;save();console.error(error.stack)
  try {if(page)await shot('failure')}catch{}
  process.exitCode=1
}).finally(async()=>{
  try {if(browser)await browser.call('Browser.close')}catch{}
  if(chrome&&chrome.exitCode===null)chrome.kill()
  for(const tab of Object.values(tabs))tab.socket.close()
  if(browser)browser.socket.close()
})
