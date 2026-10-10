const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const { SourceTextModule, SyntheticModule, createContext } = require('node:vm')
const { parse, compileScript } = require('@vue/compiler-sfc')

async function devicePage(responder) {
  const { descriptor } = parse(readFileSync(resolve(__dirname, '../src/views/elder/MyDevicesView.vue'), 'utf8'))
  const context = createContext({ console, Date, Math, Number, String, setInterval, clearInterval })
  const calls = []
  const module = new SourceTextModule(compileScript(descriptor, { id: 'device-paging-test' }).content, { context })
  await module.link(specifier => {
    let exports
    if (specifier === 'vue') exports = { ref: value => ({ value }), computed: read => ({ get value() { return read() } }), onMounted() {}, onUnmounted() {} }
    else if (specifier === 'element-plus') exports = { ElMessage: { error() {}, success() {} } }
    else if (specifier === '@/api/device') exports = {
      listDevicesPaged: async params => { calls.push({ ...params }); return responder(params, calls.length) },
      listDeviceCommandsPaged: async () => ({ data: { records: [] } }), startSimulator: async () => {}, stopSimulator: async () => {}
    }
    else if (specifier === '@/composables/useDeviceCommand') exports = { useDeviceCommand: () => ({ pending: { value: null }, busy: { value: false }, submit() {}, retry() {}, forget() {} }) }
    else exports = { default: {} }
    return new SyntheticModule(Object.keys(exports), function () { for (const [key, value] of Object.entries(exports)) this.setExport(key, value) }, { context })
  })
  await module.evaluate()
  return { state: module.namespace.default.setup({}, { expose() {} }), calls }
}

for (const change of ['page', 'size']) {
  test(`device ${change}: an in-flight background refresh cannot discard the user's selection`, async () => {
    let release
    const oldResponse = new Promise(resolve => { release = resolve })
    const p = await devicePage(async (params, call) => call === 1 ? oldResponse : {
      data: { records: [{ id: 'selected-page' }], pageNum: params.pageNum, pageSize: params.pageSize, total: 25 }
    })
    const poll = p.state.loadAll(false, true)
    assert.equal(p.state.loading.value, false)
    const selection = change === 'page' ? p.state.changePage(2) : p.state.changeSize(10)
    release({ data: { records: [{ id: 'old-page' }], pageNum: 1, pageSize: 20, total: 25 } })
    await Promise.all([poll, selection])
    assert.equal(p.calls.length, 2)
    assert.equal(p.calls[1].pageNum, change === 'page' ? 2 : 1)
    assert.equal(p.calls[1].pageSize, change === 'size' ? 10 : 20)
    assert.equal(p.state.devices.value[0].id, 'selected-page')
    assert.equal(p.state.page.value, change === 'page' ? 2 : 1)
  })
}

test('device polling: an old error cannot end a pending user refresh or show an obsolete error', async () => {
  let rejectPoll, finishPage
  const pollResponse = new Promise((_, reject) => { rejectPoll = reject })
  const pageResponse = new Promise(resolve => { finishPage = resolve })
  const p = await devicePage(async (_, call) => call === 1 ? pollResponse : pageResponse)
  const poll = p.state.loadAll(false, true)
  const selection = p.state.changePage(2)
  rejectPoll(new Error('obsolete poll failure'))
  await poll
  assert.equal(p.state.loading.value, true)
  assert.equal(p.state.loadError.value, '')
  finishPage({ data: { records: [{ id: 'second-page' }], pageNum: 2, pageSize: 20, total: 25 } })
  await selection
  assert.equal(p.state.loading.value, false)
  assert.equal(p.state.devices.value[0].id, 'second-page')
})
