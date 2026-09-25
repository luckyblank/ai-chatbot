import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { describeAiStatus } from '../src/services/aiStatusDisplay.js'

test('AI display distinguishes disabled, missing key, ready, and unknown states', () => {
  assert.equal(describeAiStatus({ enabled: false, configured: false }).title, 'AI 服务未启用')
  assert.equal(describeAiStatus({ enabled: true, configured: false }).title, 'AI 密钥未配置')
  assert.deepEqual(describeAiStatus({ enabled: true, configured: true, model: 'model-a', provider: 'provider-a' }), {
    title: 'AI 参数已配置', detail: 'model-a · provider-a', badge: '参数已配置', kind: 'configured'
  })
  assert.equal(describeAiStatus(null).title, 'AI 状态未知')
  assert.equal(describeAiStatus({ enabled: true }).title, 'AI 状态未知')
  assert.equal(describeAiStatus({ enabled: true, configured: true }, true).title, 'AI 状态读取中')
})

test('AI status request uses the authenticated same-origin API', async () => {
  const source = await readFile(new URL('../src/services/api.js', import.meta.url), 'utf8')
  const testSource = source.replace('import.meta.env.VITE_API_BASE_URL', "''")
  const { systemAPI } = await import(`data:text/javascript;base64,${Buffer.from(testSource).toString('base64')}`)
  const previousFetch = globalThis.fetch
  let captured
  globalThis.fetch = async (url, options) => {
    captured = { url, options }
    return { ok: true, status: 200, json: async () => ({ enabled: false, configured: false }) }
  }
  try {
    const result = await systemAPI.aiStatus()
    assert.deepEqual(result, { enabled: false, configured: false })
    assert.equal(captured.url, '/api/v1/system/ai-status')
    assert.equal(captured.options.credentials, 'include')
    assert.equal(captured.options.method, undefined)
  } finally {
    globalThis.fetch = previousFetch
  }
})

test('sidebar and workbench services use the server-backed status after merging system management', async () => {
  const sidebar = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  const systemPage = await readFile(new URL('../src/components/WorkbenchServices.vue', import.meta.url), 'utf8')
  assert.match(sidebar, /\{\{ aiStatusDisplay\.title \}\}/)
  assert.match(systemPage, /\{\{ ai\.badge \}\}/)
  assert.match(systemPage, /describeAiStatus\(aiStatusState\.status/)
  const router = await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8')
  assert.match(router, /path: '\/system'.*redirect: \{ path: '\/', hash: '#services' \}/)
  assert.doesNotMatch(sidebar, /label: '系统管理'/)
  assert.doesNotMatch(sidebar, />AI 服务已配置</)
  assert.doesNotMatch(systemPage, /<label>模型服务<\/label><strong>qwen-plus<\/strong>/)
})
