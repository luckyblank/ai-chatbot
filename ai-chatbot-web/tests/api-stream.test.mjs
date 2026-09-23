import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'

const source = await readFile(new URL('../src/services/api.js', import.meta.url), 'utf8')
const testSource = source.replace('import.meta.env.VITE_API_BASE_URL', "''")
const { conversationAPI, pendingActionAPI, workflowAPI } = await import(`data:text/javascript;base64,${Buffer.from(testSource).toString('base64')}`)
const encoder = new TextEncoder()

async function withResponse(chunks, callback, onCancel = () => {}) {
  const previousFetch = globalThis.fetch
  globalThis.fetch = async () => ({
    ok: true,
    body: new ReadableStream({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(typeof chunk === 'string' ? encoder.encode(chunk) : chunk)
        if (onCancel === null) controller.close()
      },
      cancel: onCancel || undefined
    })
  })
  try { return await callback() }
  finally { globalThis.fetch = previousFetch }
}

test('CRLF split between reads keeps the delta event and completes', async () => {
  const deltas = []
  const completed = { answer: '你好' }
  await withResponse([
    'event: delta\r',
    '\ndata: {"delta":"你好"}\r',
    '\n\r',
    '\nevent: complete\r\n',
    'data: {"answer":"你好"}\r\n\r',
    '\n'
  ], async () => {
    const result = await conversationAPI.sendStream('1', 'test', [], {
      delta: ({ delta }) => deltas.push(delta)
    })
    assert.deepEqual(result, completed)
    assert.deepEqual(deltas, ['你好'])
  }, null)
})

test('server error event rejects and cancels the stream', async () => {
  let cancelled = false
  await withResponse(['event: error\r\n', 'data: {"message":"模型请求失败"}\r\n\r\n'], async () => {
    await assert.rejects(conversationAPI.sendStream('1', 'test'), /模型请求失败/)
  }, () => { cancelled = true })
  assert.equal(cancelled, true)
})

test('EOF without a complete event reports interruption', async () => {
  await withResponse(['event: delta\n', 'data: {"delta":"partial"}\n\n'], async () => {
    await assert.rejects(conversationAPI.sendStream('1', 'test'), /流式回答意外中断/)
  }, null)
})

test('conversation stream carries a stable request id and CSRF header', async () => {
  const previousFetch = globalThis.fetch
  const previousDocument = globalThis.document
  let captured
  globalThis.document = { cookie: 'AI_SERVICE_CSRF=csrf%20token' }
  globalThis.fetch = async (url, options) => {
    captured = { url, options }
    return {
      ok: true,
      body: new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode('event: complete\ndata: {"conversationId":"conversation-1","answer":"ok","citations":[],"traces":[]}\n\n'))
          controller.close()
        }
      })
    }
  }
  try {
    const result = await conversationAPI.sendStream('conversation-1', 'test', [], {}, undefined, 'req-stable')
    assert.equal(result.conversationId, 'conversation-1')
    assert.equal('requestId' in result, false)
    assert.equal('pendingActions' in result, false)
    assert.equal(captured.url, '/api/v1/conversations/conversation-1/messages/stream')
    assert.equal(captured.options.headers['X-CSRF-Token'], 'csrf token')
    assert.deepEqual(JSON.parse(captured.options.body), { message: 'test', attachmentIds: [], requestId: 'req-stable' })
  } finally {
    globalThis.fetch = previousFetch
    if (previousDocument === undefined) delete globalThis.document
    else globalThis.document = previousDocument
  }
})

test('pending action confirmation sends only the expected version control field', async () => {
  const previousFetch = globalThis.fetch
  const previousDocument = globalThis.document
  let captured
  globalThis.document = { cookie: 'other=value; AI_SERVICE_CSRF=csrf-token' }
  globalThis.fetch = async (url, options) => {
    captured = { url, options }
    return { ok: true, status: 200, json: async () => ({ actionId: 'action/1', version: 7, status: 'SUCCEEDED' }) }
  }
  try {
    const result = await pendingActionAPI.confirm('action/1', 7)
    assert.equal(result.status, 'SUCCEEDED')
    assert.equal(captured.url, '/api/v1/pending-actions/action%2F1/confirm')
    assert.equal(captured.options.credentials, 'include')
    assert.equal(captured.options.headers['X-CSRF-Token'], 'csrf-token')
    assert.deepEqual(JSON.parse(captured.options.body), { expectedVersion: 7 })
  } finally {
    globalThis.fetch = previousFetch
    if (previousDocument === undefined) delete globalThis.document
    else globalThis.document = previousDocument
  }
})

test('workflow stream exposes run id before a waiting terminal record', async () => {
  const starts = []
  await withResponse([
    'event: run-start\ndata: {"runId":"run-1","totalNodes":3}\n\n',
    'event: complete\ndata: {"id":"run-1","status":"waiting","waitingNodeId":"approval-1"}\n\n'
  ], async () => {
    const result = await workflowAPI.runStream('workflow-1', { input: { orderNo: 'SO-001' } }, {
      'run-start': event => starts.push(event)
    })
    assert.equal(starts[0].runId, 'run-1')
    assert.equal(result.status, 'waiting')
    assert.equal(result.waitingNodeId, 'approval-1')
  }, null)
})

test('workflow run status can be queried by the run id', async () => {
  const previousFetch = globalThis.fetch
  let capturedUrl
  globalThis.fetch = async (url) => {
    capturedUrl = url
    return { ok: true, status: 200, json: async () => ({ id: 'run/1', status: 'interrupted' }) }
  }
  try {
    const result = await workflowAPI.getRun('workflow-1', 'run/1')
    assert.equal(result.status, 'interrupted')
    assert.equal(capturedUrl, '/api/v1/workflows/workflow-1/runs/run%2F1')
  } finally {
    globalThis.fetch = previousFetch
  }
})
