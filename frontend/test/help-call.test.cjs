const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const { SourceTextModule, SyntheticModule, createContext } = require('node:vm')
const { parse, compileScript } = require('@vue/compiler-sfc')
async function helpPage(send) {
  const { descriptor } = parse(readFileSync(resolve(__dirname, '../src/views/elder/HelpCallView.vue'), 'utf8'))
  let id = 0
  const context = createContext({ console, Date, Math, Object, window: { crypto: { randomUUID: () => `fixture-${++id}` } } })
  const calls = []
  const module = new SourceTextModule(compileScript(descriptor, { id: 'help-test' }).content, { context })
  const stubs = {
    vue: { ref: value => ({ value }), reactive: value => value },
    'vue-router': { useRouter: () => ({ push: async () => {} }) },
    'element-plus': { ElMessage: { error() {}, warning() {}, success() {}, info() {} } },
    '@/api/alert': { submitHelpCall: async body => { calls.push({ ...body }); return send(calls.length) } }
  }
  await module.link(specifier => new SyntheticModule(Object.keys(stubs[specifier]), function () {
    for (const [key, value] of Object.entries(stubs[specifier])) this.setExport(key, value)
  }, { context }))
  await module.evaluate()
  const state = module.namespace.default.setup({}, { expose() {} })
  state.formRef.value = { validate: async () => true }
  Object.assign(state.form, { callType: 'OTHER', contactPhone: '13900001234', description: '原求助内容' })
  return { state, calls }
}
test('response loss followed by form edits confirms the exact original help request', async () => {
  const p = await helpPage(async call => { if (call === 1) throw new Error('lost response'); return { data: { id: '1' } } })
  await p.state.handleSubmit()
  p.state.form.contactPhone = '13900005678'
  p.state.form.description = '强制改动的内容'
  await p.state.handleSubmit()
  assert.equal(p.calls.length, 2)
  assert.deepEqual(p.calls[1], p.calls[0])
  assert.equal(p.state.pending.value, null)
})
test('a 503 then a rejected retry cannot prove the first request failed', async () => {
  const p = await helpPage(async call => { throw { response: { status: call === 1 ? 503 : 409 } } })
  await p.state.handleSubmit()
  await p.state.handleSubmit()
  assert.deepEqual(p.calls[1], p.calls[0])
  assert.ok(p.state.pending.value)
  p.state.abandonPending()
  assert.equal(p.state.pending.value, null)
})
test('in-flight help cannot be forgotten or submitted a second time', async () => {
  let finish
  const p = await helpPage(() => new Promise(resolve => { finish = resolve }))
  const first = p.state.handleSubmit()
  await new Promise(resolve => setImmediate(resolve))
  p.state.abandonPending()
  await p.state.handleSubmit()
  assert.ok(p.state.pending.value)
  assert.equal(p.calls.length, 1)
  finish({ data: { id: '1' } })
  await first
})
test('a definite first rejection unlocks a genuinely new submission', async () => {
  const p = await helpPage(async () => { throw { response: { status: 400 } } })
  await p.state.handleSubmit()
  assert.equal(p.state.pending.value, null)
  await p.state.handleSubmit()
  assert.notEqual(p.calls[1].requestId, p.calls[0].requestId)
})
