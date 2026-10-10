const fs = require('node:fs'), path = require('node:path'), net = require('node:net')
const assert = require('node:assert/strict'), { spawn } = require('node:child_process')
const CDP = require('./cdp.cjs')
const work = process.env.V13_WORK || path.resolve(__dirname, '../../../../开发资料/work/CP-P3-V1.3-control')
const origin = 'http://127.0.0.1:5273', debugPort = 19229
const manifest = JSON.parse(fs.readFileSync(path.join(work, 'browser-fixtures.json'), 'utf8'))
assert.equal(manifest.database, 'smart_community_care_test')
const report = { evidence: 'Real Vue, HTTP, dedicated MySQL test database and automatic scheduler. No mocked API results.', checks: [], shots: [], runtimeErrors: 0 }
const save = () => fs.writeFileSync(path.join(work, 'browser-results.json'), JSON.stringify(report, null, 2))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const profile = path.join(work, 'chrome-v13')
const tabs = {}, results = [], targets = []
let chrome, browser, page
async function wait(read, label, timeout = 15000) {
  const end = Date.now() + timeout
  while (Date.now() < end) { if (await read()) return; await sleep(120) }
  throw new Error('Condition not reached: ' + label)
}
const dom = (expression, label) => wait(() => page.evaluate(expression), label)
function check(name, detail = {}) { report.checks.push({ name, pass: true, ...detail }); save(); console.log('PASS ' + name) }
async function navigate(route) {
  await page.call('Page.bringToFront')
  await page.call('Page.navigate', { url: origin + route })
  await dom('document.readyState==="complete"', route); await sleep(200)
}
async function click(text, scope = 'document') {
  assert.ok(await page.evaluate(`(()=>{const root=${scope};const x=Array.from(root.querySelectorAll('button')).find(x=>x.getClientRects().length&&!x.disabled&&x.textContent.trim()===${JSON.stringify(text)});if(!x)return false;x.scrollIntoView({block:'center'});x.click();return true})()`), 'Enabled button ' + text)
}
async function fill(selector, value) {
  assert.ok(await page.evaluate(`(()=>{const x=document.querySelector(${JSON.stringify(selector)});if(!x)return false;const p=x.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(p,'value').set.call(x,${JSON.stringify(value)});x.dispatchEvent(new Event('input',{bubbles:true}));x.dispatchEvent(new Event('change',{bubbles:true}));return true})()`), 'Field ' + selector)
}
async function fillLabel(label, value) {
  assert.ok(await page.evaluate(`(()=>{const row=Array.from(document.querySelectorAll('.el-form-item')).find(x=>x.getClientRects().length&&x.querySelector('label')?.textContent.trim()===${JSON.stringify(label)});const x=row?.querySelector('input,textarea');if(!x)return false;const p=x.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(p,'value').set.call(x,${JSON.stringify(value)});x.dispatchEvent(new Event('input',{bubbles:true}));return true})()`), 'Field ' + label)
}
async function api(route, options = {}) {
  return page.evaluate(`(async()=>{const o=${JSON.stringify(options)};const r=await fetch('/api'+${JSON.stringify(route)},{method:o.method||'GET',headers:{'scc-token':'Bearer '+sessionStorage.getItem('scc:token'),...(o.body?{'Content-Type':'application/json'}:{})},...(o.body?{body:JSON.stringify(o.body)}:{})});return {status:r.status,body:await r.json()}})()`)
}
async function selectField(label, option, scope = 'document') {
  assert.ok(await page.evaluate(`(()=>{const row=Array.from((${scope}).querySelectorAll('.el-form-item')).find(x=>x.getClientRects().length&&x.querySelector('label')?.textContent.trim()===${JSON.stringify(label)});const x=row?.querySelector('.el-select__wrapper');if(!x)return false;x.click();return true})()`))
  await selectOption(option)
}
async function selectOption(option) {
  await dom(`Array.from(document.querySelectorAll('.el-select-dropdown__item')).some(x=>x.getClientRects().length&&x.textContent.trim()===${JSON.stringify(option)})`, 'option ' + option)
  await page.evaluate(`Array.from(document.querySelectorAll('.el-select-dropdown__item')).find(x=>x.getClientRects().length&&x.textContent.trim()===${JSON.stringify(option)}).click();true`)
}
async function shot(name, width = 1366) {
  await page.call('Page.bringToFront')
  await page.call('Emulation.setDeviceMetricsOverride', { width, height: width === 1920 ? 1080 : 768, deviceScaleFactor: 1, mobile: false })
  if (name !== 'failure') {
    await wait(() => page.evaluate('!Array.from(document.querySelectorAll(".el-message")).some(x=>x.getClientRects().length)'), 'transient messages settle', 7000)
  }
  await sleep(180)
  assert.ok(await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1'), 'No page horizontal overflow')
  const capture = await page.call('Page.captureScreenshot', { format: 'png' })
  fs.writeFileSync(path.join(work, name + '.png'), Buffer.from(capture.data, 'base64')); report.shots.push(name + '.png'); save()
}
async function createTab(role) {
  const target = await (await fetch(`http://127.0.0.1:${debugPort}/json/new?about:blank`, { method: 'PUT' })).json()
  targets.push(target.id)
  const tab = new CDP(target.webSocketDebuggerUrl); tabs[role] = tab; page = tab
  await tab.call('Page.enable'); await tab.call('Runtime.enable'); await tab.call('Network.enable')
  await tab.call('Emulation.setDeviceMetricsOverride', { width: 1366, height: 768, deviceScaleFactor: 1, mobile: false })
  tab.on('Runtime.exceptionThrown', () => { report.runtimeErrors++ })
  const requests = new Map()
  tab.on('Network.requestWillBeSent', event => {
    const url = new URL(event.request.url)
    if (['/api/service-orders/admin/all', '/api/service-orders/mine/paged', '/api/service-orders/my-tasks/paged'].includes(url.pathname)) requests.set(event.requestId, { role, path: url.pathname, pageNum: Number(url.searchParams.get('pageNum')), pageSize: Number(url.searchParams.get('pageSize')) })
  })
  tab.on('Network.loadingFinished', async event => {
    const request = requests.get(event.requestId)
    if (!request) return
    try {
      const response = await tab.call('Network.getResponseBody', { requestId: event.requestId })
      const body = JSON.parse(response.body)
      if (body.code === 200) results.push({ ...request, total: body.data.total, records: body.data.records.length })
    } catch { report.networkReadErrors = (report.networkReadErrors || 0) + 1 }
  })
  await navigate('/login')
  await fill('input[type="text"]', manifest.users[role].username)
  await fill('input[type="password"]', process.env[`V13_${role.toUpperCase()}_PASSWORD`])
  await click('登录')
  await dom('!!sessionStorage.getItem("scc:token")&&!location.pathname.startsWith("/login")', 'login ' + role)
}
async function paging(role, route) {
  page = tabs[role]; const from = results.length
  await navigate(route)
  await wait(() => results.slice(from).some(x => x.role === role && x.pageSize === 20), 'default page ' + role)
  await dom('document.querySelector(".el-pagination__sizes .el-select__wrapper")&&!document.querySelector(".el-pagination").classList.contains("is-disabled")', 'pager ready')
  await page.evaluate('document.querySelector(".el-pagination__sizes .el-select__wrapper").click();true')
  let index = results.length
  await selectOption('10条/页')
  await wait(() => results.slice(index).some(x => x.role === role && x.pageSize === 10 && x.pageNum === 1), 'size 10 resets page ' + role)
  index = results.length
  await fill('.el-pagination__jump input', '2')
  await page.evaluate('const x=document.querySelector(".el-pagination__jump input");x.dispatchEvent(new KeyboardEvent("keyup",{key:"Enter",code:"Enter",bubbles:true}));x.blur();true')
  await wait(() => results.slice(index).some(x => x.role === role && x.pageSize === 10 && x.pageNum === 2 && x.records === 10), 'jump page 2 ' + role)
  index = results.length
  await click('刷新')
  await wait(() => results.slice(index).some(x => x.role === role && x.pageSize === 10 && x.pageNum >= 1), 'refresh uses a numeric page ' + role)
  await page.evaluate('document.querySelector(".el-pagination__sizes .el-select__wrapper").click();true')
  index = results.length
  await selectOption('50条/页')
  const expected = role === 'admin' ? manifest.adminTotal : manifest.ownTotal
  await wait(() => results.slice(index).some(x => x.role === role && x.pageSize === 50 && x.pageNum === 1 && x.total === expected && x.records === Math.min(expected, 50)), 'size 50 and real count ' + role)
  await dom('!document.querySelector(".el-pagination").classList.contains("is-disabled")', 'finished loading')
  if (role === 'staff') {
    assert.ok(await page.evaluate(`(()=>{const root=document.querySelector('.my-tasks');const tables=Array.from(root.querySelectorAll('.current-tasks-table')).filter(x=>x.getClientRects().length);const pager=root.querySelector('.el-pagination');return tables.length>=2&&tables.every(table=>!!(table.compareDocumentPosition(pager)&Node.DOCUMENT_POSITION_FOLLOWING))})()`), 'Pager follows all task groups')
  }
  await page.evaluate('document.querySelector(".el-pagination").scrollIntoView({block:"center"});true')
  await shot(role + '-paging-1366'); await shot(role + '-paging-1920', 1920)
  check('Real page size, jump, refresh and complete total ' + role, { expectedTotal: expected })
}
async function helpRetry() {
  page = tabs.elder; await navigate('/elder/help-call')
  await selectField('求助类型', '需要帮助')
  await fillLabel('联系电话', '13900001234'); await fillLabel('补充说明', 'V1.3登记求助响应丢失')
  let drop = true; const posts = []
  page.on('Fetch.requestPaused', async event => {
    try {
      if (event.request.method === 'POST') posts.push(JSON.parse(event.request.postData))
      if (event.request.method === 'POST' && event.responseStatusCode === 200 && drop) {
        drop = false; await sleep(1200); await page.call('Fetch.failRequest', { requestId: event.requestId, errorReason: 'ConnectionClosed' })
      } else await page.call('Fetch.continueRequest', { requestId: event.requestId })
    } catch { report.interceptionError = true }
  })
  await page.call('Fetch.enable', { patterns: [{ urlPattern: '*/api/alert-events/help-calls', requestStage: 'Response' }] })
  await click('提交求助')
  await dom('Array.from(document.querySelectorAll("button")).some(x=>x.textContent.trim()==="不再跟踪"&&x.disabled)', 'in-flight protection')
  await dom('Array.from(document.querySelectorAll("button")).some(x=>x.textContent.trim()==="确认原提交"&&!x.disabled)', 'unknown request retained')
  await fillLabel('联系电话', '13900005678'); await fillLabel('补充说明', '被强制改动的表单')
  await click('确认原提交'); await dom('location.pathname==="/elder/my-alerts"', 'original help confirmed')
  await page.call('Fetch.disable')
  assert.equal(posts.length, 2); assert.deepEqual(posts[1], posts[0]); assert.ok(!report.interceptionError)
  report.helpRetry = { requestId: posts[0].requestId, postCount: 2, sameOriginalPayload: true }
  assert.equal((await api('/alert-events')).body.data.filter(x => x.sourceType === 'HELP_CALL' && x.description === posts[0].description).length, 1)
  check('Committed help response loss retries original content and produces one event')
}
async function device() {
  page = tabs.admin; await navigate('/admin/devices')
  await dom('document.querySelector(".simulator-state")?.textContent.includes("模拟引擎已开启")', 'real engine state')
  assert.equal((await api('/devices/simulator-status')).body.data.globalEnabled, true)
  await click('新建设备'); await fillLabel('设备编号', 'IT-V13-USAGE-01'); await fillLabel('设备名称', 'V1.3登记验收灯')
  await selectField('所属老人', '测试-it_v13_usage_elder'); await click('创建')
  const row = 'Array.from(document.querySelectorAll("tr.el-table__row")).find(x=>x.textContent.includes("IT-V13-USAGE-01"))'
  await dom(row, 'registered device'); await click('启动', row)
  await dom(`(${row})?.textContent.includes('在线')`, 'automatic heartbeat')
  await click('控制', row)
  const dialog = 'Array.from(document.querySelectorAll(".el-dialog")).find(x=>x.getClientRects().length&&x.querySelector(".el-dialog__title")?.textContent.trim()==="设备控制")'
  report.deviceProbe = (await api('/devices')).body.data.find(x => x.deviceNo === 'IT-V13-USAGE-01')
  report.controlProbe = await page.evaluate(`(()=>{const root=(${dialog});const toggle=root?.querySelector('.el-switch');return {visibleDialogTitle:root?.querySelector('.el-dialog__title')?.textContent,switchClass:toggle?.className,switchDisabled:toggle?.querySelector('input')?.disabled}})()`)
  save()
  await dom(`!!(${dialog})?.querySelector('.el-switch:not(.is-disabled)')`, 'live switch')
  await page.evaluate(`(${dialog}).querySelector('.el-switch').click();true`)
  await dom(`(${dialog})?.querySelector('.control-tip .el-tag')?.textContent.trim()==='成功'`, 'automatic command execution')
  report.deviceId = (await api('/devices')).body.data.find(x => x.deviceNo === 'IT-V13-USAGE-01').id
  await click('关闭', dialog); await click('停止', row)
  await shot('admin-device-engine'); check('Global engine, owned device start, automatic heartbeat and command result')
}
async function run() {
  for (const role of ['admin', 'elder', 'staff']) await createTab(role)
  for (const role of ['admin', 'elder', 'staff']) {
    page = tabs[role]; await page.call('Page.reload'); await dom('document.readyState==="complete"', 'reload'); await sleep(250)
    const me = await api('/auth/me'); assert.equal(me.status, 200); assert.equal(me.body.data.id, manifest.users[role].id)
    assert.equal(await page.evaluate('localStorage.getItem("scc:token")'), null)
  }
  check('One browser keeps all three identities after all logins and refreshes')
  await paging('admin', '/admin/orders'); await paging('elder', '/elder/my-orders'); await paging('staff', '/staff/tasks')
  await helpRetry(); await device()
  await management(); await workbenches(); await historyPages()
  page = tabs.admin; await click('退出'); await dom('location.pathname==="/login"', 'admin logout')
  for (const role of ['elder', 'staff']) { page = tabs[role]; assert.equal((await api('/auth/me')).status, 200) }
  check('Logging out administrator preserves elder and staff sessions')
  assert.equal(report.runtimeErrors, 0); assert.equal(report.networkReadErrors || 0, 0)
  report.pageResponses = results; report.complete = true; save()
}
async function management() {
  page=tabs.admin;await navigate('/admin/users')
  await dom('document.querySelector(".el-table")&&!document.querySelector(".el-loading-mask")', 'account list')
  await click('创建账号');await fillLabel('账号','it_v13_usage_created_elder');await fillLabel('初始密码',process.env.V13_ELDER_PASSWORD);await fillLabel('姓名','登记新老人');await click('创建')
  await dom('document.querySelector(".el-table")?.textContent.includes("it_v13_usage_created_elder")', 'created elder visible')
  const users=(await api('/users/paged',{method:'GET'})).body.data
  const created=users.records.find(x=>x.username==='it_v13_usage_created_elder')
  assert.ok(created);assert.equal(typeof created.id,'string');assert.ok(!('passwordHash' in created))
  await click('创建账号');await selectField('角色','服务人员','document.querySelector(".el-dialog")');await fillLabel('账号','it_v13_usage_created_staff');await fillLabel('初始密码',process.env.V13_STAFF_PASSWORD);await fillLabel('姓名','登记新人员');await click('创建')
  await dom('document.querySelector(".el-table")?.textContent.includes("it_v13_usage_created_staff")', 'created staff visible')
  const row='Array.from(document.querySelectorAll("tr.el-table__row")).find(x=>x.textContent.includes("it_v13_usage_created_staff"))'
  await click('维护',row);await fillLabel('姓名','维护后的人员');await fillLabel('联系电话','13900008888');await click('保存')
  await dom(`(${row})?.textContent.includes('维护后的人员')`, 'account profile saved')
  await click('停用',row);await dom('document.querySelector(".el-message-box")?.textContent.includes("停用后")','disable confirmation');await click('确定','document.querySelector(".el-message-box")')
  await dom(`(${row})?.querySelector('.el-tag')?.textContent.trim()==='停用'`, 'account disabled')
  await click('启用',row);await dom('document.querySelector(".el-message-box")?.textContent.includes("启用后")','enable confirmation');await click('确定','document.querySelector(".el-message-box")')
  await dom(`(${row})?.querySelector('.el-tag')?.textContent.trim()==='启用'`, 'account enabled')
  await shot('b-account-management-1366');await shot('b-account-management-1920',1920)
  check('Administrator creates both roles and maintains, disables and enables a staff account')
  await navigate('/admin/elders');await dom('document.querySelector(".el-table")?.textContent.includes("登记新老人")','new elder archive')
  const elderRow='Array.from(document.querySelectorAll("tr.el-table__row")).find(x=>x.textContent.includes("it_v13_usage_created_elder"))'
  await click('查看档案',elderRow);await dom('document.querySelector("h1")?.textContent.trim()==="登记新老人"','archive detail')
  await fillLabel('住址','登记档案地址');await fillLabel('紧急联系人','登记联系人');await fillLabel('联系人电话','13900005555');await fillLabel('照护备注','仅管理可维护的登记照护备注');await click('保存资料')
  await dom('Array.from(document.querySelectorAll(".el-message")).some(x=>x.textContent.includes("档案已保存"))&&!document.querySelector(".el-loading-mask")&&document.querySelector(".archive-form input")','archive saved')
  const found=(await api('/elder-profiles/paged')).body.data.records.find(x=>x.username==='it_v13_usage_created_elder')
  assert.ok(found);assert.equal(typeof found.id,'string');assert.equal(found.address,'登记档案地址');report.createdElderId=found.id
  const detail=(await api('/elder-profiles/'+found.id)).body.data;assert.equal(detail.profile.careNote,'仅管理可维护的登记照护备注');assert.notEqual(detail.profile.id,detail.profile.userId)
  await shot('b-elder-archive-1366');await shot('b-elder-archive-1920',1920)
  await navigate('/admin/elders/'+manifest.elderId)
  for(const [tab,label] of [['health','健康记录'],['alerts','事项'],['orders','订单'],['devices','设备']]) {
    assert.ok(await page.evaluate(`(()=>{const x=Array.from(document.querySelectorAll('[role="tab"]')).find(x=>x.textContent.trim()===${JSON.stringify(label)});if(!x)return false;x.click();return true})()`))
    await dom('document.querySelector(".related-heading")?.textContent.includes('+JSON.stringify(label)+')&&document.querySelector(".related-heading button:not(:disabled)")&&!document.querySelector(".el-loading-mask")', 'archive '+tab)
    assert.equal(await page.evaluate('Array.from(document.querySelectorAll(".el-alert")).some(x=>x.textContent.includes("读取失败"))'),false)
    if(tab==='orders')assert.ok(await page.evaluate('document.querySelector(".el-pagination__total")?.textContent.includes("31")'))
  }
  check('Elder archive persists allowed fields and all four related business tabs load')
}
async function workbenches() {
  for(const role of ['admin','staff']) {
    page=tabs[role];await navigate('/'+role);await dom('document.querySelectorAll(".summary-entry strong").length===3','workbench '+role)
    const response=(await api('/workbench')).body.data
    const shown=await page.evaluate('Array.from(document.querySelectorAll(".summary-entry strong")).map(x=>Number(x.textContent))')
    assert.deepEqual(shown,[response.counts.pendingEvents,response.counts.pendingOrders,response.counts.todayOrders]);assert.equal(response.businessZone,'Asia/Shanghai')
    if(role==='staff')assert.equal(response.counts.pendingOrders,30)
    await shot('b-'+role+'-workbench-1366');await shot('b-'+role+'-workbench-1920',1920)
  }
  check('Both workbenches render server full counts and the declared business date')
}
async function historyPages() {
  page=tabs.elder;await navigate('/elder/health-input');await dom('document.querySelector("h1")?.textContent.trim()==="健康记录"','health history')
  await dom('document.querySelector(".el-pagination")&&!document.querySelector(".el-loading-mask")','health history pager')
  await click('录入记录');await dom('document.querySelector(".el-drawer")?.textContent.includes("历史补录")','health entry drawer')
  await page.evaluate('document.querySelector(".el-drawer__close-btn").click();true')
  await shot('b-health-history-1366')
  page=tabs.staff;await navigate('/staff/alerts');await dom('document.querySelectorAll(".el-pagination").length===2','event and participation pagers')
  assert.equal(await page.evaluate('document.querySelector(".participation-section")?.textContent.includes("当前联系资料")'),true)
  await navigate('/staff/reviews');await dom('document.querySelector(".el-pagination")','reviews pager')
  assert.equal((await api('/service-orders/reviews/received/paged')).body.data.ratedCount,0)
  page=tabs.elder;await navigate('/elder/service-booking');await dom('document.querySelector(".el-pagination")','service catalog pager')
  assert.ok(await page.evaluate('(()=>{const x=Array.from(document.querySelectorAll(".service-item")).find(x=>x.textContent.includes("V1.3分页验收服务"));if(!x)return false;x.click();return true})()'))
  await dom('document.querySelectorAll(".el-pagination").length===2','slot pager')
  check('Health entry, service slots, event participation and reviews have real query and paging entry points')
}
;(async () => {
  assert.ok(!fs.existsSync(profile), 'Do not reuse an existing browser profile')
  const occupied = await new Promise(resolve => { const connection = net.createConnection({ host: '127.0.0.1', port: debugPort }); connection.setTimeout(1000); connection.once('connect', () => { connection.destroy(); resolve(true) }); connection.once('error', () => resolve(false)); connection.once('timeout', () => { connection.destroy(); resolve(false) }) })
  assert.equal(occupied, false)
  chrome = spawn('C:/Program Files/Google/Chrome/Application/chrome.exe', ['--headless=new', `--remote-debugging-port=${debugPort}`, '--remote-debugging-address=127.0.0.1', '--user-data-dir=' + profile, '--no-first-run', '--no-default-browser-check', 'about:blank'], { stdio: 'ignore', windowsHide: true })
  let version
  await wait(async () => { try { version = await (await fetch(`http://127.0.0.1:${debugPort}/json/version`)).json(); return true } catch { return false } }, 'Chrome startup')
  browser = new CDP(version.webSocketDebuggerUrl)
  await run()
})().catch(async error => { report.failure = { message: error.message }; save(); console.error('FAIL ' + error.message); if (page) try { await shot('failure') } catch {} process.exitCode = 1 }).finally(async () => {
  if (browser) try { await browser.call('Browser.close') } catch {}
  if (chrome) { await sleep(500); if (chrome.exitCode === null) chrome.kill() }
  for (const tab of Object.values(tabs)) tab.socket.close()
  browser?.socket.close()
  // 本脚本创建的固定项目内目录；拒绝对其他路径作递归清理。
  assert.equal(path.dirname(profile), work); assert.equal(path.basename(profile), 'chrome-v13')
  if (chrome) try { fs.rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 }) } catch { report.cleanupFailure = true; save(); process.exitCode = 1 }
})
