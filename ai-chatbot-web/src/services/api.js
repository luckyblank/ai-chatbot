export const BASE_URL = import.meta.env.VITE_API_BASE_URL || ''

const CSRF_COOKIE_NAME = 'AI_SERVICE_CSRF'
const CSRF_HEADER_NAME = 'X-CSRF-Token'
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

function cookieValue(name) {
  if (typeof document === 'undefined' || !document.cookie) return ''
  const prefix = `${encodeURIComponent(name)}=`
  const entry = document.cookie.split(';').map(item => item.trim()).find(item => item.startsWith(prefix))
  if (!entry) return ''
  try { return decodeURIComponent(entry.slice(prefix.length)) } catch { return entry.slice(prefix.length) }
}

function securedHeaders(method, headers = {}) {
  const merged = { ...headers }
  if (!SAFE_METHODS.has(String(method || 'GET').toUpperCase())) {
    const token = cookieValue(CSRF_COOKIE_NAME)
    if (token && !merged[CSRF_HEADER_NAME]) merged[CSRF_HEADER_NAME] = token
  }
  return merged
}

function withRequestId(payload, requestId) {
  const normalized = String(requestId || '').trim()
  return normalized ? { ...payload, requestId: normalized } : payload
}

export function createRequestId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  let entropy
  if (globalThis.crypto?.getRandomValues) {
    const bytes = new Uint32Array(4)
    globalThis.crypto.getRandomValues(bytes)
    entropy = Array.from(bytes, value => value.toString(36)).join('')
  } else {
    entropy = `${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}`
  }
  return `req-${Date.now().toString(36)}-${entropy}`
}

export async function request(path, options = {}) {
  const method = options.method || 'GET'
  const response = await fetch(`${BASE_URL}${path}`, {
    credentials: 'include',
    ...options,
    headers: securedHeaders(method, options.headers)
  })
  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    try {
      const payload = await response.json()
      message = payload.message || message
    } catch { /* 保留稳定的兜底信息 */ }
    const error = new Error(message)
    error.status = response.status
    if (response.status === 401 && !path.includes('/auth/login')) window.dispatchEvent(new CustomEvent('auth:required'))
    throw error
  }
  if (response.status === 204) return null
  return response.json()
}

async function streamEvents(path, payload, handlers = {}, signal) {
  const response = await fetch(`${BASE_URL}${path}`, {
    method: 'POST',
    credentials: 'include',
    headers: securedHeaders('POST', { 'Content-Type': 'application/json', Accept: 'text/event-stream' }),
    body: JSON.stringify(payload),
    signal
  })
  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    try {
      const body = await response.json()
      message = body.message || message
    } catch { /* 保留稳定的兜底信息 */ }
    const error = new Error(message)
    error.status = response.status
    if (response.status === 401) window.dispatchEvent(new CustomEvent('auth:required'))
    throw error
  }
  if (!response.body) throw new Error('当前浏览器无法读取流式响应')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let completed = null
  let finishedReading = false

  const dispatch = async (block) => {
    if (!block.trim()) return
    let eventName = 'message'
    const dataLines = []
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) eventName = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
    }
    if (!dataLines.length) return
    const raw = dataLines.join('\n')
    let data
    try { data = JSON.parse(raw) } catch { data = { delta: raw } }
    if (eventName === 'error') {
      const error = new Error(data.message || '流式回答生成失败')
      error.status = data.status
      throw error
    }
    if (eventName === 'complete') completed = data
    const handler = handlers[eventName]
    if (handler) await handler(data)
  }

  try {
    while (true) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })

      // CRLF may be split across reads. Keep a trailing CR until the next read
      // so it cannot become a false blank line before its LF arrives.
      const trailingCR = !done && buffer.endsWith('\r')
      const ready = trailingCR ? buffer.slice(0, -1) : buffer
      buffer = ready.replace(/\r\n|\r/g, '\n') + (trailingCR ? '\r' : '')

      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const block = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        await dispatch(block)
        boundary = buffer.indexOf('\n\n')
      }
      if (done) {
        finishedReading = true
        break
      }
    }
    if (buffer.trim()) await dispatch(buffer)
    if (!completed) throw new Error('流式回答意外中断，请重试')
    return completed
  } finally {
    if (!finishedReading) {
      try { await reader.cancel() } catch { /* 保留读取或事件处理时的原始错误 */ }
    }
    reader.releaseLock()
  }
}

export const authAPI = {
  login: (payload) => request('/api/v1/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  }),
  me: () => request('/api/v1/auth/me'),
  logout: () => request('/api/v1/auth/logout', { method: 'POST' })
}

export const systemAPI = {
  aiStatus: () => request('/api/v1/system/ai-status')
}

export const knowledgeAPI = {
  list: () => request('/api/v1/knowledge-bases'),
  create: (payload) => request('/api/v1/knowledge-bases', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  }),
  update: (id, payload) => request(`/api/v1/knowledge-bases/${id}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  }),
  remove: (id) => request(`/api/v1/knowledge-bases/${id}`, { method: 'DELETE' }),
  documents: (id) => request(`/api/v1/knowledge-bases/${id}/documents`),
  upload(id, file) {
    const body = new FormData(); body.append('file', file)
    return request(`/api/v1/knowledge-bases/${id}/documents`, { method: 'POST', body })
  },
  reindex: (baseId, documentId) => request(`/api/v1/knowledge-bases/${baseId}/documents/${documentId}/reindex`, { method: 'POST' }),
  removeDocument: (baseId, documentId) => request(`/api/v1/knowledge-bases/${baseId}/documents/${documentId}`, { method: 'DELETE' }),
  chunks: (baseId, documentId) => request(`/api/v1/knowledge-bases/${baseId}/documents/${documentId}/chunks`),
  previewUrl: (baseId, documentId) => `${BASE_URL}/api/v1/knowledge-bases/${baseId}/documents/${documentId}/preview`
}

export const conversationAPI = {
  widgetContext: () => request('/api/v1/conversations/widget/context'),
  createWidget: () => request('/api/v1/conversations/widget/conversations', { method: 'POST' }),
  list(knowledgeBaseId) {
    const query = knowledgeBaseId ? `?knowledgeBaseId=${encodeURIComponent(knowledgeBaseId)}` : ''
    return request(`/api/v1/conversations${query}`)
  },
  create: (knowledgeBaseId, title = '新对话', scenarioCode = 'general') => request('/api/v1/conversations', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ knowledgeBaseId, title, scenarioCode })
  }),
  get: (id) => request(`/api/v1/conversations/${id}`),
  rename: (id, title) => request(`/api/v1/conversations/${id}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title })
  }),
  remove: (id) => request(`/api/v1/conversations/${id}`, { method: 'DELETE' }),
  uploadAttachment(id, file) {
    const body = new FormData(); body.append('file', file)
    return request(`/api/v1/conversations/${id}/attachments`, { method: 'POST', body })
  },
  send: (id, message, attachmentIds = [], requestId = '') => request(`/api/v1/conversations/${id}/messages`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(withRequestId({ message, attachmentIds }, requestId))
  }),
  sendStream: (id, message, attachmentIds = [], handlers = {}, signal, requestId = '') => streamEvents(
    `/api/v1/conversations/${id}/messages/stream`, withRequestId({ message, attachmentIds }, requestId), handlers, signal
  ),
  regenerateStream: (id, messageIndex, content, attachmentIds = [], handlers = {}, signal, requestId = '') => streamEvents(
    `/api/v1/conversations/${id}/messages/${messageIndex}/regenerate/stream`, withRequestId({ content, attachmentIds }, requestId), handlers, signal
  )
}

export const pendingActionAPI = {
  list(conversationId) {
    const query = conversationId ? `?conversationId=${encodeURIComponent(conversationId)}` : ''
    return request(`/api/v1/pending-actions${query}`)
  },
  get: (actionId) => request(`/api/v1/pending-actions/${encodeURIComponent(actionId)}`),
  confirm: (actionId, expectedVersion) => request(`/api/v1/pending-actions/${encodeURIComponent(actionId)}/confirm`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ expectedVersion })
  }),
  cancel: (actionId, expectedVersion) => request(`/api/v1/pending-actions/${encodeURIComponent(actionId)}/cancel`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ expectedVersion })
  })
}

export const workflowAPI = {
  templates: () => request('/api/v1/workflows/templates'),
  list: () => request('/api/v1/workflows'),
  get: (id) => request(`/api/v1/workflows/${id}`),
  create: (payload) => request('/api/v1/workflows', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  }),
  update: (id, payload) => request(`/api/v1/workflows/${id}`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  }),
  remove: (id) => request(`/api/v1/workflows/${id}`, { method: 'DELETE' }),
  run: (id, conversationId = null) => request(`/api/v1/workflows/${id}/runs`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ conversationId })
  }),
  runStream: (id, payload = {}, handlers = {}, signal) => streamEvents(
    `/api/v1/workflows/${id}/runs/stream`, payload, handlers, signal
  ),
  resumeStream: (id, runId, payload, handlers = {}, signal) => streamEvents(
    `/api/v1/workflows/${id}/runs/${runId}/resume`, payload, handlers, signal
  ),
  runs: (id) => request(`/api/v1/workflows/${id}/runs`),
  getRun: (id, runId) => request(`/api/v1/workflows/${id}/runs/${encodeURIComponent(runId)}`)
}

export const scenarioAPI = {
  list: () => request('/api/v1/scenarios'),
  get: (code) => request(`/api/v1/scenarios/${encodeURIComponent(code)}`),
  toolCatalog: () => request('/api/v1/scenarios/tool-catalog'),
  update: (code, payload) => request(`/api/v1/scenarios/${encodeURIComponent(code)}`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
}
