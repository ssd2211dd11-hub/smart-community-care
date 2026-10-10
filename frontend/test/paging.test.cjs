const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const { SourceTextModule, SyntheticModule, createContext } = require('node:vm')
const { parse, compileScript } = require('@vue/compiler-sfc')

async function page(filename, responder, reviewResponder = async () => ({ data: [] }), routeQuery = {}, actionResponder = async () => ({ data: [] })) {
  const text = readFileSync(resolve(__dirname, '../src/views', filename), 'utf8')
  const { descriptor } = parse(text)
  const context = createContext({ console, Date, Math, Number, String })
  const calls = [], reviewCalls = [], actionCalls = []
  const query = async params => { calls.push({ ...params }); return responder(params, calls.length) }
  const module = new SourceTextModule(compileScript(descriptor, { id: 'paging-test' }).content, { context })
  await module.link(specifier => {
    let exports
    if (specifier === 'vue') exports = {
      ref: value => ({ value }), computed: read => ({ get value() { return read() } }), onMounted: () => {}, onUnmounted: () => {}
    }
    else if (specifier === 'element-plus') exports = { ElMessage: { error() {}, warning() {}, success() {} } }
    else if (specifier === 'vue-router') exports = { useRoute: () => ({query:routeQuery}) }
    else if (specifier === '@/api/management') exports = { getWorkbench: async () => ({data:{}}) }
    else if (specifier === '@/utils/auth') exports = { getStoredUser: () => ({role:'SERVICE_STAFF'}) }
    else if (specifier === '@/utils/orderLabels') return new SourceTextModule(readFileSync(resolve(__dirname, '../src/utils/orderLabels.js'), 'utf8'), { context })
    else if (specifier === '@/api/order') exports = Object.fromEntries([
      'assignOrder','confirmAsAdmin','getOrderView','listAllOrders','terminateOrder','transferOrder','listAssignableStaff',
      'cancelOrder','confirmOrder','createReview','listMyOrders','listMyOrdersPaged','listMyReviews','listMyReviewsForOrders','returnOrder',
      'acceptOrder','listMyParticipations','listMyTasksPaged','listReceivedReviews','rejectOrder','startService','submitResult','listClaimableOrders','claimOrder'
    ].map(name => [name, name === 'listMyReviewsForOrders' ? async ids => { reviewCalls.push(Array.from(ids)); return reviewResponder(ids) }
      : ['listAllOrders','listMyOrdersPaged','listMyTasksPaged'].includes(name) ? query : async (...args) => { actionCalls.push({ name, args }); return actionResponder(name, args) }]))
    else exports = { default: {} }
    return new SyntheticModule(Object.keys(exports), function () {
      for (const [key, value] of Object.entries(exports)) this.setExport(key, value)
    }, { context })
  })
  await module.evaluate()
  const state = module.namespace.default.setup({}, { expose() {} })
  return { state, calls, reviewCalls, actionCalls }
}
test('staff detail result action opens the editable result form instead of submitting invented content', async () => {
  const p = await page('staff/MyTasksView.vue', async () => ({data:{records:[],total:0}}))
  p.state.detailCache.value = {id:'71',version:4,status:'IN_SERVICE'}
  let triggered = false
  await p.state.runDetailAction({key:'submit'}, async () => { triggered = true })
  assert.equal(triggered,false)
  assert.equal(p.state.submitVisible.value,true)
  assert.equal(p.state.submitTarget.value.version,4)
  assert.equal(p.state.submitText.value,'')
})
test('order page: review lookup contains only completed orders on the current page and preserves string IDs', async () => {
  const p = await page('elder/MyOrdersView.vue', async () => ({ data: {
    records: [{id:'9007199254741099',status:'COMPLETED'},{id:'12',status:'ASSIGNED'}],total:2
  } }), async () => ({data:[{orderId:'9007199254741099'}]}))
  await p.state.load(1)
  assert.deepEqual(p.reviewCalls,[['9007199254741099']])
  assert.equal(p.state.reviewedIds.value[0],'9007199254741099')
})
test('order page: failed review lookup cannot masquerade as an unreviewed order', async () => {
  const p = await page('elder/MyOrdersView.vue', async () => ({data:{records:[{id:'11',status:'COMPLETED'}],total:1}}), async () => {throw new Error('review read failure')})
  await p.state.load(1)
  assert.ok(p.state.reviewReadError.value)
  assert.equal(p.state.loadError.value,'')
})
test('order page: an obsolete review failure cannot overwrite the current page review state', async () => {
  let rejectOldReview
  const oldReview = new Promise((_, reject) => { rejectOldReview = reject })
  const p = await page('elder/MyOrdersView.vue', async (_, call) => ({ data: {
    records: [{ id: call === 1 ? 'old-order' : 'latest-order', status: 'COMPLETED' }], total: 25
  } }), async ids => ids[0] === 'old-order' ? oldReview : { data: [{ orderId: 'latest-order' }] })
  const oldLoad = p.state.load(1)
  while (!p.reviewCalls.length) await new Promise(resolve => setImmediate(resolve))
  await p.state.load(2)
  rejectOldReview(new Error('obsolete review request failed'))
  await oldLoad
  assert.deepEqual(Array.from(p.state.reviewedIds.value), ['latest-order'])
  assert.equal(p.state.reviewReadError.value, '')
})
test('staff today drilldown includes completed arrangements under the dashboard scope', async () => {
  const p = await page('staff/MyTasksView.vue', async () => ({data:{records:[],total:0}}), undefined,
    {scope:'ALL',fromDate:'2026-10-09',toDate:'2026-10-09',excludeEnded:'true'})
  await p.state.load(1)
  assert.equal(p.calls[0].scope,'ALL')
  assert.equal(p.calls[0].fromDate,'2026-10-09')
  assert.equal(p.calls[0].excludeEnded,true)
})
test('workbench drilldowns declare matching appointment and completion date scopes', async () => {
  const p = await page('../components/WorkbenchView.vue', async () => ({}))
  p.state.data.value = {businessDate:'2026-10-09'}
  assert.equal(p.state.orderRoute('today').query.scope,'ALL')
  const completed=p.state.orderRoute('completed').query
  assert.equal(completed.dateField,'COMPLETION')
  assert.equal(completed.fromDate,'2026-10-03')
  assert.equal(completed.toDate,'2026-10-09')
})
for (const filename of ['admin/AdminOrdersView.vue', 'elder/MyOrdersView.vue', 'staff/MyTasksView.vue']) {
  test(`${filename}: a late older response cannot replace the latest page and total`, async () => {
    let resolveOld, resolveNew
    const oldResponse = new Promise(resolve => { resolveOld = resolve })
    const newResponse = new Promise(resolve => { resolveNew = resolve })
    const p = await page(filename, async (_, call) => call === 1 ? oldResponse : newResponse)
    const oldLoad = p.state.load(1)
    const newLoad = p.state.load(2)
    resolveNew({ data: { records: [{ id: 'latest', status: 'ACCEPTED' }], total: 23 } })
    await newLoad
    resolveOld({ data: { records: [{ id: 'older', status: 'COMPLETED' }], total: 5 } })
    await oldLoad
    const rows = filename.startsWith('staff') ? p.state.tasks.value : p.state.orders.value
    assert.equal(rows[0].id, 'latest')
    assert.equal(p.state.total.value, 23)
    assert.equal(p.state.query.value.pageNum, 2)
  })
  test(`${filename}: an obsolete failure cannot clear current loading or show a false error`, async () => {
    let rejectOld, resolveNew
    const oldResponse = new Promise((_, reject) => { rejectOld = reject })
    const newResponse = new Promise(resolve => { resolveNew = resolve })
    const p = await page(filename, async (_, call) => call === 1 ? oldResponse : newResponse)
    const oldLoad = p.state.load(1)
    const newLoad = p.state.load(2)
    rejectOld(new Error('obsolete query failed'))
    await oldLoad
    assert.equal(p.state.loading.value, true)
    assert.equal(p.state.loadError.value, '')
    resolveNew({ data: { records: [{ id: 'latest', status: 'ACCEPTED' }], total: 23 } })
    await newLoad
    assert.equal(p.state.loading.value, false)
  })
  test(`${filename}: refresh does not send a mouse event as pageNum`, async () => {
    const p = await page(filename, async () => ({ data: { records: [], total: 0 } }))
    await p.state.load({ type: 'click', target: {} })
    assert.equal(p.calls[0].pageNum, 1)
  })
  test(`${filename}: changing size returns to first page with selected size`, async () => {
    const p = await page(filename, async () => ({ data: { records: [{ id: '1' }], total: 90 } }))
    await p.state.load(3)
    await p.state.changePageSize(50)
    assert.equal(p.calls.at(-1).pageNum, 1)
    assert.equal(p.calls.at(-1).pageSize, 50)
  })
  test(`${filename}: a deleted last page recovers once and keeps actual total`, async () => {
    const p = await page(filename, async (_, call) => ({ data: { records: call === 1 ? [] : [{ id: '1' }], total: 11 } }))
    await p.state.load(2)
    assert.equal(p.calls.length, 2)
    assert.equal(p.calls[1].pageNum, 1)
    assert.equal(p.state.total.value, 11)
  })
  test(`${filename}: read failure remains distinguishable from a real empty result`, async () => {
    const p = await page(filename, async () => { throw new Error('read failed') })
    await p.state.load(1)
    assert.ok(p.state.loadError.value)
    assert.equal(p.state.loading.value, false)
  })
}

test('administrator confirmation requires a real reason and keeps the displayed order version', async () => {
  const p = await page('admin/AdminOrdersView.vue', async () => ({ data: { records: [], total: 0 } }), undefined, {},
    async () => ({ data: { id: '9007199254741099', status: 'COMPLETED', version: 8 } }))
  p.state.detail.value = { id: '9007199254741099', status: 'IN_SERVICE', version: 7 }
  assert.equal(p.state.detailActions.value.some(action => action.key === 'confirmAdmin'), false)
  p.state.detail.value.status = 'PENDING_CONFIRMATION'
  assert.equal(p.state.detailActions.value.some(action => action.key === 'confirmAdmin'), true)
  await p.state.startAction('confirmAdmin')
  assert.equal(p.state.confirmVisible.value, true)
  assert.equal(p.state.confirmReason.value, '')
  p.state.confirmReason.value = '   '
  await p.state.confirmCompleteAsAdmin()
  assert.equal(p.actionCalls.filter(call => call.name === 'confirmAsAdmin').length, 0)
  p.state.confirmReason.value = ' 已电话核实老人认可实际服务结果 '
  await p.state.confirmCompleteAsAdmin()
  const calls = p.actionCalls.filter(call => call.name === 'confirmAsAdmin')
  assert.equal(calls.length, 1)
  assert.equal(calls[0].args[0], '9007199254741099')
  assert.equal(calls[0].args[1], '已电话核实老人认可实际服务结果')
  assert.equal(calls[0].args[2], 7)
})
