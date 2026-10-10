const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const { SourceTextModule, createContext } = require('node:vm')

const source = readFileSync(resolve(__dirname, '../src/utils/auth.js'), 'utf8')
function storage(entries = []) {
  const values = new Map(entries)
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key)
  }
}
async function tab(shared, session = storage()) {
  const module = new SourceTextModule(source, {
    context: createContext({ localStorage: shared, sessionStorage: session })
  })
  await module.link(() => { throw new Error('Unexpected dependency') })
  await module.evaluate()
  return { auth: module.namespace, session }
}
test('three tabs retain their own account after all three logins', async () => {
  const shared = storage()
  const tabs = await Promise.all([tab(shared), tab(shared), tab(shared)])
  for (let i = 0; i < tabs.length; i++) {
    tabs[i].auth.saveLogin({ token: `fixture-token-${i}`, user: { id: String(i), role: ['COMMUNITY_ADMIN', 'ELDER', 'SERVICE_STAFF'][i] } })
  }
  for (let i = 0; i < tabs.length; i++) {
    assert.equal(tabs[i].auth.getToken(), `fixture-token-${i}`)
    assert.equal(tabs[i].auth.getStoredUser().id, String(i))
    const refreshed = await tab(shared, tabs[i].session)
    assert.equal(refreshed.auth.getToken(), `fixture-token-${i}`)
  }
  assert.equal(shared.getItem('scc:token'), null)
})
test('logging out one tab preserves another tab and unrelated storage', async () => {
  const shared = storage([['other-project:key', 'keep']])
  const a = await tab(shared), b = await tab(shared)
  a.auth.saveLogin({ token: 'fixture-a', user: { id: '1' } })
  b.auth.saveLogin({ token: 'fixture-b', user: { id: '2' } })
  a.auth.clearLogin()
  assert.equal(a.auth.getToken(), null)
  assert.equal(b.auth.getToken(), 'fixture-b')
  assert.equal(shared.getItem('other-project:key'), 'keep')
})
test('legacy shared login is removed without importing it into a tab', async () => {
  const shared = storage([['scc:token', 'legacy'], ['scc:user', '{"id":"legacy"}'], ['other-project:key', 'keep']])
  const current = await tab(shared)
  assert.equal(current.auth.getToken(), null)
  assert.equal(current.auth.getStoredUser(), null)
  assert.equal(shared.getItem('scc:token'), null)
  assert.equal(shared.getItem('scc:user'), null)
  assert.equal(shared.getItem('other-project:key'), 'keep')
})
test('corrupt tab user data does not read another account from shared storage', async () => {
  const session = storage([['scc:token', 'fixture-a'], ['scc:user', '{bad']])
  const current = await tab(storage([['scc:user', '{"id":"other"}']]), session)
  assert.equal(current.auth.getStoredUser(), null)
  assert.equal(session.getItem('scc:user'), null)
})
