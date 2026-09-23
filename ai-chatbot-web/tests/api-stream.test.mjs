import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'

const source = await readFile(new URL('../src/services/api.js', import.meta.url), 'utf8')
const testSource = source.replace('import.meta.env.VITE_API_BASE_URL', "''")
const { conversationAPI } = await import(`data:text/javascript;base64,${Buffer.from(testSource).toString('base64')}`)
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
