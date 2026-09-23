<script setup>
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  ArrowPathIcon,
  ArrowsPointingInIcon,
  ArrowsPointingOutIcon,
  ChatBubbleLeftRightIcon,
  ClockIcon,
  DocumentIcon,
  PaperClipIcon,
  PaperAirplaneIcon,
  PlusIcon,
  SparklesIcon,
  XMarkIcon
} from '@heroicons/vue/24/outline'
import PendingActionCard from './PendingActionCard.vue'
import { BASE_URL, conversationAPI, createRequestId, pendingActionAPI } from '../services/api'
import { authState } from '../services/auth'

const ChatMarkdown = defineAsyncComponent(() => import('./ChatMarkdown.vue'))

const props = defineProps({
  conversationPage: { type: Boolean, default: false }
})

const open = ref(false)
const isFullscreen = ref(false)
const showHistory = ref(false)
const currentConversationId = ref('')
const messages = ref([])
const pendingActions = ref([])
const actionBusy = reactive({})
const actionErrors = reactive({})
const draft = ref('')
const attachments = ref([])
const sending = ref(false)
const uploadingAttachments = ref(false)
const initialized = ref(false)
const loadingSession = ref(false)
const recentSessions = ref([])
const loadingHistory = ref(false)
const historyError = ref('')
const notice = ref('')
const panel = ref(null)
const launcher = ref(null)
const messageArea = ref(null)
const composer = ref(null)
const fileInput = ref(null)
const draggingFiles = ref(false)
const panelPosition = ref(null)
const launcherPosition = ref(null)
const draggingPanel = ref(false)
const draggingLauncher = ref(false)
const panelManuallyDragged = ref(false)

const conversationStorageKey = computed(() => 'enterprise-support-conversation:' + (authState.user?.username || ''))
const launcherStorageKey = computed(() => 'enterprise-support-launcher:' + (authState.user?.username || ''))
const panelStyle = computed(() => !isFullscreen.value && panelPosition.value
  ? { left: panelPosition.value.left + 'px', top: panelPosition.value.top + 'px', right: 'auto', bottom: 'auto' }
  : undefined)
const launcherStyle = computed(() => launcherPosition.value
  ? { left: launcherPosition.value.left + 'px', top: launcherPosition.value.top + 'px', right: 'auto', bottom: 'auto' }
  : undefined)
const supportSessions = computed(() => recentSessions.value.filter(item => item.scenarioCode === 'commerce-support').slice(0, 8))

let panelDrag = null
let launcherDrag = null
let launcherAnchor = null
let launcherSize = { width: 54, height: 54 }
let suppressLauncherClick = false
let activeStreamController = null
let scrollQueued = false
let sessionLoadVersion = 0
let pendingActionPollTimer = 0
let pendingActionRequestVersion = 0
let savedPageOverflow = null
let attachmentDragDepth = 0

const ATTACHMENT_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'webp', 'gif', 'pdf', 'txt', 'md', 'markdown'])
const CLIPBOARD_MIME_EXTENSIONS = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp', 'image/gif': 'gif', 'application/pdf': 'pdf', 'text/plain': 'txt' }
const messageTimeFormatter = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
const messageDateTimeFormatter = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })

function validMessageDate(value) {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}
function formatMessageTime(value) { const date = validMessageDate(value); return date ? messageTimeFormatter.format(date) : '' }
function formatMessageDateTime(value) { const date = validMessageDate(value); return date ? messageDateTimeFormatter.format(date) : '' }
function formatSize(value) { return value < 1024 * 1024 ? `${Math.max(1, Math.round(value / 1024))} KB` : `${(value / 1024 / 1024).toFixed(1)} MB` }
function fileIsImage(file) { return Boolean(file.image ?? file.isImage ?? /\.(png|jpe?g|webp|gif)$/i.test(file.name || '')) }
function fileUrl(file) { const url = file.contentUrl || file.url || ''; return url.startsWith('/') ? `${BASE_URL}${url}` : url }

function revokeAttachmentPreview(item) {
  if (item?.url?.startsWith('blob:')) URL.revokeObjectURL(item.url)
}
function clearAttachments() {
  attachments.value.forEach(revokeAttachmentPreview)
  attachments.value = []
}
function removeAttachment(index) {
  const [item] = attachments.value.splice(index, 1)
  if (item) revokeAttachmentPreview(item)
}
function addAttachments(files) {
  if (sending.value || !files.length) return
  if (attachments.value.length + files.length > 10) {
    notice.value = '单次最多添加 10 个附件。'
    return
  }
  const prepared = files.map(file => {
    const hasExtension = /\.[^.]+$/.test(file.name)
    const extension = hasExtension ? file.name.split('.').pop().toLowerCase() : CLIPBOARD_MIME_EXTENSIONS[file.type]
    if (!ATTACHMENT_EXTENSIONS.has(extension)) return null
    return hasExtension ? file : new File([file], `${file.name || '粘贴文件'}.${extension}`, { type: file.type, lastModified: file.lastModified })
  })
  if (prepared.some(file => !file)) {
    notice.value = '仅支持 PNG、JPG、WEBP、GIF、PDF、TXT 和 Markdown 文件。'
    return
  }
  if (prepared.some(file => !file.size || file.size > 20 * 1024 * 1024)) {
    notice.value = '附件不能为空，且单个文件不能超过 20 MB。'
    return
  }
  attachments.value.push(...prepared.map(file => ({
    file, name: file.name, size: file.size,
    isImage: /\.(png|jpe?g|webp|gif)$/i.test(file.name),
    url: /\.(png|jpe?g|webp|gif)$/i.test(file.name) ? URL.createObjectURL(file) : ''
  })))
  notice.value = ''
}
function selectAttachments(event) {
  addAttachments(Array.from(event.target.files || []))
  event.target.value = ''
  composer.value?.focus()
}
function onAttachmentPaste(event) {
  const data = event.clipboardData
  const itemFiles = Array.from(data?.items || []).filter(item => item.kind === 'file').map(item => item.getAsFile()).filter(Boolean)
  const files = itemFiles.length ? itemFiles : Array.from(data?.files || [])
  if (!files.length) return
  event.preventDefault()
  addAttachments(files)
}
function isFileDrag(event) { return Array.from(event.dataTransfer?.types || []).includes('Files') }
function onAttachmentDragEnter(event) {
  if (!isFileDrag(event)) return
  event.preventDefault()
  attachmentDragDepth += 1
  draggingFiles.value = true
}
function onAttachmentDragOver(event) {
  if (!isFileDrag(event)) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
}
function onAttachmentDragLeave(event) {
  if (!isFileDrag(event)) return
  attachmentDragDepth = Math.max(0, attachmentDragDepth - 1)
  if (!attachmentDragDepth) draggingFiles.value = false
}
function onAttachmentDrop(event) {
  if (!isFileDrag(event)) return
  event.preventDefault()
  attachmentDragDepth = 0
  draggingFiles.value = false
  addAttachments(Array.from(event.dataTransfer?.files || []))
}

watch(() => authState.user?.username, username => {
  clearAttachments()
  sessionLoadVersion += 1
  resetPendingActionState()
  if (!username) {
    currentConversationId.value = ''
    messages.value = []
    initialized.value = false
    recentSessions.value = []
    return
  }
  currentConversationId.value = localStorage.getItem(conversationStorageKey.value) || ''
  messages.value = []
  initialized.value = false
  recentSessions.value = []
  panelPosition.value = null
  panelManuallyDragged.value = false
  try {
    const saved = JSON.parse(localStorage.getItem(launcherStorageKey.value) || 'null')
    launcherPosition.value = Number.isFinite(saved?.left) && Number.isFinite(saved?.top) ? saved : null
  } catch {
    launcherPosition.value = null
  }
}, { immediate: true })

function clamp(value, min, max) {
  return Math.max(min, Math.min(value, Math.max(min, max)))
}

function setPageScrollLocked(locked) {
  if (locked && !savedPageOverflow) {
    savedPageOverflow = {
      html: document.documentElement.style.overflow,
      body: document.body.style.overflow
    }
    document.documentElement.style.overflow = 'hidden'
    document.body.style.overflow = 'hidden'
  } else if (!locked && savedPageOverflow) {
    document.documentElement.style.overflow = savedPageOverflow.html
    document.body.style.overflow = savedPageOverflow.body
    savedPageOverflow = null
  }
}

function setConversationId(id) {
  currentConversationId.value = id || ''
  if (id) localStorage.setItem(conversationStorageKey.value, id)
  else localStorage.removeItem(conversationStorageKey.value)
}

function scrollToBottom() {
  if (scrollQueued) return
  scrollQueued = true
  requestAnimationFrame(() => {
    scrollQueued = false
    if (messageArea.value) messageArea.value.scrollTop = messageArea.value.scrollHeight
  })
}

function movePanelTo(left, top) {
  const width = panel.value?.offsetWidth || 380
  const height = panel.value?.offsetHeight || 540
  panelPosition.value = {
    left: clamp(left, 8, window.innerWidth - width - 8),
    top: clamp(top, 8, window.innerHeight - height - 8)
  }
}

function moveLauncherTo(left, top) {
  const width = launcher.value?.offsetWidth || launcherSize.width
  const height = launcher.value?.offsetHeight || launcherSize.height
  launcherPosition.value = {
    left: clamp(left, 8, window.innerWidth - width - 8),
    top: clamp(top, 8, window.innerHeight - height - 8)
  }
}

function placePanel() {
  if (!panel.value || isFullscreen.value) return
  if (panelPosition.value) {
    movePanelTo(panelPosition.value.left, panelPosition.value.top)
    return
  }
  const anchor = launcherAnchor
  if (!anchor) return
  const width = panel.value.offsetWidth
  const height = panel.value.offsetHeight
  const above = anchor.top - height - 12
  const top = above >= 8 ? above : anchor.bottom + 12
  movePanelTo(anchor.right - width, top)
}

function handleViewportResize() {
  if (launcherPosition.value) moveLauncherTo(launcherPosition.value.left, launcherPosition.value.top)
  if (isFullscreen.value) return
  if (!open.value && !panelManuallyDragged.value) {
    panelPosition.value = null
    return
  }
  if (open.value && !panelManuallyDragged.value) {
    panelPosition.value = null
    nextTick(placePanel)
  } else if (panelPosition.value) movePanelTo(panelPosition.value.left, panelPosition.value.top)
}

watch(() => props.conversationPage, async () => {
  if (!open.value || panelManuallyDragged.value) return
  panelPosition.value = null
  await nextTick()
  placePanel()
})

async function loadHistory() {
  loadingHistory.value = true
  historyError.value = ''
  try {
    recentSessions.value = await conversationAPI.list()
  } catch (error) {
    historyError.value = error.message || '历史会话加载失败'
  } finally {
    loadingHistory.value = false
  }
}

function actionsFromPayload(payload) {
  if (Array.isArray(payload)) return payload
  if (Array.isArray(payload?.items)) return payload.items
  if (Array.isArray(payload?.pendingActions)) return payload.pendingActions
  return []
}

function sortPendingActions(items) {
  return [...items].sort((first, second) => {
    const firstTime = new Date(first.updatedAt || first.createdAt || 0).getTime() || 0
    const secondTime = new Date(second.updatedAt || second.createdAt || 0).getTime() || 0
    return secondTime - firstTime
  })
}

function mergePendingActions(payload, replace = false) {
  const incoming = actionsFromPayload(payload).filter(item => item?.actionId)
  if (replace) pendingActions.value = sortPendingActions(incoming)
  else if (incoming.length) {
    const merged = new Map(pendingActions.value.map(item => [item.actionId, item]))
    incoming.forEach(item => merged.set(item.actionId, { ...merged.get(item.actionId), ...item }))
    pendingActions.value = sortPendingActions([...merged.values()])
  }
  schedulePendingActionPolling()
}

function upsertPendingAction(action) {
  if (!action?.actionId || (action.conversationId && action.conversationId !== currentConversationId.value)) return
  mergePendingActions([action])
}

function clearPendingActionPolling() {
  if (pendingActionPollTimer) window.clearTimeout(pendingActionPollTimer)
  pendingActionPollTimer = 0
}

function resetPendingActionState() {
  pendingActionRequestVersion += 1
  clearPendingActionPolling()
  pendingActions.value = []
  Object.keys(actionBusy).forEach(key => delete actionBusy[key])
  Object.keys(actionErrors).forEach(key => delete actionErrors[key])
}

function schedulePendingActionPolling() {
  clearPendingActionPolling()
  const conversationId = currentConversationId.value
  const hasPending = pendingActions.value.some(action => ['PENDING', 'PROCESSING', 'RUNNING'].includes(String(action.status || '').toUpperCase()))
  if (!conversationId || !hasPending) return
  pendingActionPollTimer = window.setTimeout(() => {
    pendingActionPollTimer = 0
    void refreshPendingActions(conversationId, true)
  }, 6000)
}

async function refreshPendingActions(conversationId = currentConversationId.value, silent = false) {
  const targetId = String(conversationId || '').trim()
  if (!targetId) return []
  const requestVersion = ++pendingActionRequestVersion
  try {
    const payload = await pendingActionAPI.list(targetId)
    if (requestVersion !== pendingActionRequestVersion || targetId !== currentConversationId.value) return []
    mergePendingActions(payload, true)
    return actionsFromPayload(payload)
  } catch (error) {
    if (!silent && requestVersion === pendingActionRequestVersion && targetId === currentConversationId.value) {
      notice.value = `业务动作状态加载失败：${error.message}`
    }
    return []
  } finally {
    if (requestVersion === pendingActionRequestVersion && targetId === currentConversationId.value) schedulePendingActionPolling()
  }
}

async function refreshPendingActionStatus(action, silent = false) {
  const actionId = action?.actionId
  if (!actionId || actionBusy[actionId]) return null
  pendingActionRequestVersion += 1
  actionBusy[actionId] = 'refresh'
  try {
    const updated = await pendingActionAPI.get(actionId)
    upsertPendingAction(updated)
    delete actionErrors[actionId]
    return updated
  } catch (error) {
    if (!silent) actionErrors[actionId] = error.message
    return null
  } finally {
    delete actionBusy[actionId]
    schedulePendingActionPolling()
  }
}

async function recoverPendingAction(actionId) {
  try {
    const updated = await pendingActionAPI.get(actionId)
    upsertPendingAction(updated)
    return updated
  } catch {
    return null
  }
}

async function confirmPendingAction(action) {
  const actionId = action?.actionId
  if (!actionId || actionBusy[actionId] || String(action.status).toUpperCase() !== 'PENDING') return
  pendingActionRequestVersion += 1
  actionBusy[actionId] = 'confirm'
  delete actionErrors[actionId]
  try {
    const updated = await pendingActionAPI.confirm(actionId, action.version)
    upsertPendingAction(updated)
    if (String(updated?.status || '').toUpperCase() !== 'SUCCEEDED') {
      actionErrors[actionId] = '服务端尚未返回正式工单成功结果，请刷新状态。'
    }
  } catch (error) {
    const recovered = await recoverPendingAction(actionId)
    if (String(recovered?.status || '').toUpperCase() !== 'SUCCEEDED') actionErrors[actionId] = error.message
  } finally {
    delete actionBusy[actionId]
    schedulePendingActionPolling()
  }
}

async function cancelPendingAction(action) {
  const actionId = action?.actionId
  if (!actionId || actionBusy[actionId] || String(action.status).toUpperCase() !== 'PENDING') return
  pendingActionRequestVersion += 1
  actionBusy[actionId] = 'cancel'
  delete actionErrors[actionId]
  try {
    const updated = await pendingActionAPI.cancel(actionId, action.version)
    upsertPendingAction(updated)
    if (String(updated?.status || '').toUpperCase() !== 'CANCELLED') {
      actionErrors[actionId] = '服务端尚未返回取消结果，请刷新状态。'
    }
  } catch (error) {
    const recovered = await recoverPendingAction(actionId)
    if (String(recovered?.status || '').toUpperCase() !== 'CANCELLED') actionErrors[actionId] = error.message
  } finally {
    delete actionBusy[actionId]
    schedulePendingActionPolling()
  }
}

async function restoreSession() {
  if (!currentConversationId.value) {
    initialized.value = true
    return
  }
  const requestedId = currentConversationId.value
  const requestVersion = ++sessionLoadVersion
  loadingSession.value = true
  notice.value = ''
  try {
    const session = await conversationAPI.get(requestedId)
    if (requestVersion !== sessionLoadVersion || currentConversationId.value !== requestedId) return
    messages.value = session.messages || []
    mergePendingActions(session.pendingActions)
    initialized.value = true
    await refreshPendingActions(requestedId)
  } catch (error) {
    if (requestVersion !== sessionLoadVersion) return
    if (error.status === 404 || error.status === 400) {
      setConversationId('')
      messages.value = []
      initialized.value = true
      notice.value = '上次咨询已不可用，可开始新的咨询。'
    } else {
      notice.value = '上次咨询加载失败：' + (error.message || '请重试')
    }
  } finally {
    if (requestVersion === sessionLoadVersion) {
      loadingSession.value = false
      await nextTick()
      scrollToBottom()
    }
  }
}

async function openWidget() {
  if (launcher.value) launcherSize = { width: launcher.value.offsetWidth, height: launcher.value.offsetHeight }
  launcherAnchor = launcher.value?.getBoundingClientRect() || null
  if (!panelManuallyDragged.value) panelPosition.value = null
  open.value = true
  showHistory.value = false
  await nextTick()
  placePanel()
  if (!initialized.value) await restoreSession()
  void loadHistory()
  if (!open.value) return
  await nextTick()
  composer.value?.focus()
  scrollToBottom()
}

async function closeWidget(restoreFocus = false) {
  open.value = false
  isFullscreen.value = false
  setPageScrollLocked(false)
  showHistory.value = false
  draggingPanel.value = false
  if (restoreFocus) {
    await nextTick()
    launcher.value?.focus()
  }
}

async function toggleFullscreen() {
  isFullscreen.value = !isFullscreen.value
  setPageScrollLocked(isFullscreen.value)
  if (!isFullscreen.value) {
    await nextTick()
    if (panelPosition.value) movePanelTo(panelPosition.value.left, panelPosition.value.top)
    else placePanel()
  }
  scrollToBottom()
}

function handleEscape(event) {
  if (open.value && event.key === 'Escape') closeWidget(true)
}

function handleLauncherClick(event) {
  if (suppressLauncherClick) {
    event.preventDefault()
    suppressLauncherClick = false
    return
  }
  if (open.value) closeWidget()
  else void openWidget()
}

async function toggleHistory() {
  if (sending.value) return
  showHistory.value = !showHistory.value
  if (showHistory.value) void loadHistory()
  else {
    await nextTick()
    composer.value?.focus()
    scrollToBottom()
  }
}

async function newConversation() {
  if (sending.value) return
  sessionLoadVersion += 1
  loadingSession.value = false
  setConversationId('')
  messages.value = []
  resetPendingActionState()
  draft.value = ''
  clearAttachments()
  notice.value = ''
  initialized.value = true
  showHistory.value = false
  await nextTick()
  composer.value?.focus()
}

async function selectConversation(id) {
  if (sending.value || loadingSession.value) return
  const requestVersion = ++sessionLoadVersion
  loadingSession.value = true
  notice.value = ''
  try {
    const session = await conversationAPI.get(id)
    if (requestVersion !== sessionLoadVersion) return
    setConversationId(session.id)
    messages.value = session.messages || []
    mergePendingActions(session.pendingActions)
    clearAttachments()
    draft.value = ''
    initialized.value = true
    showHistory.value = false
    await refreshPendingActions(session.id)
    await nextTick()
    composer.value?.focus()
    scrollToBottom()
  } catch (error) {
    if (requestVersion !== sessionLoadVersion) return
    notice.value = '打开会话失败：' + (error.message || '请重试')
  } finally {
    if (requestVersion === sessionLoadVersion) loadingSession.value = false
  }
}

async function streamAnswer(conversationId, content, attachmentIds, assistant, retry) {
  const controller = new AbortController()
  const requestId = retry?.requestId || assistant.requestId || createRequestId()
  assistant.requestId = requestId
  activeStreamController = controller
  let completed = false
  try {
    const result = await conversationAPI.sendStream(conversationId, content, attachmentIds, {
      delta: data => {
        assistant.content += data.delta || ''
        scrollToBottom()
      },
      complete: data => {
        completed = true
        assistant.content = data.answer || assistant.content
        assistant.citations = data.citations || []
        assistant.requestId = requestId
        assistant.streaming = false
        assistant.interrupted = false
        assistant.retry = null
        scrollToBottom()
      }
    }, controller.signal, requestId)
    assistant.content = result?.answer || assistant.content
    assistant.citations = result?.citations || assistant.citations
    assistant.requestId = requestId
    assistant.streaming = false
    assistant.interrupted = false
    assistant.retry = null
    void loadHistory()
    return true
  } catch (error) {
    if (completed || error.name === 'AbortError') return completed
    assistant.streaming = false
    assistant.interrupted = true
    assistant.interruptionMessage = error.message || '连接已断开'
    assistant.retry = retry
    scrollToBottom()
    return false
  } finally {
    if (activeStreamController === controller) activeStreamController = null
    await refreshPendingActions(conversationId, true)
  }
}

async function sendMessage() {
  const content = draft.value.trim()
  if (!content || sending.value || loadingSession.value || (!initialized.value && currentConversationId.value)) return
  if (messages.value.some(item => item.interrupted)) {
    notice.value = '请先重试中断的回答，或新建咨询。'
    return
  }
  sending.value = true
  notice.value = ''
  try {
    if (!currentConversationId.value) {
      const session = await conversationAPI.create(null, '在线客服 · 新对话', 'commerce-support')
      setConversationId(session.id)
      initialized.value = true
    }
    const uploaded = []
    uploadingAttachments.value = attachments.value.length > 0
    for (const item of attachments.value) {
      uploaded.push(await conversationAPI.uploadAttachment(currentConversationId.value, item.file))
    }
    uploadingAttachments.value = false
    const userIndex = messages.value.length
    const createdAt = new Date().toISOString()
    const requestId = createRequestId()
    const assistant = reactive({
      role: 'assistant', content: '', citations: [], streaming: true,
      interrupted: false, retry: null, requestId, createdAt
    })
    messages.value.push({ role: 'user', content, attachments: uploaded, requestId, createdAt }, assistant)
    draft.value = ''
    clearAttachments()
    await nextTick()
    scrollToBottom()
    const attachmentIds = uploaded.map(item => item.id)
    await streamAnswer(currentConversationId.value, content, attachmentIds, assistant, { userIndex, content, attachmentIds, requestId })
  } catch (error) {
    notice.value = '发送失败：' + (error.message || '请稍后重试')
  } finally {
    uploadingAttachments.value = false
    sending.value = false
  }
}

async function retryInterruptedAnswer() {
  const assistant = messages.value.at(-1)
  const retry = assistant?.retry
  if (!assistant?.interrupted || !retry || !currentConversationId.value || sending.value) return
  sending.value = true
  notice.value = ''
  try {
    const session = await conversationAPI.get(currentConversationId.value)
    if ((session.messages || []).length > retry.userIndex) {
      messages.value = session.messages || []
      mergePendingActions(session.pendingActions)
      await refreshPendingActions(currentConversationId.value, true)
      notice.value = '已从服务端恢复这轮回答。'
      await nextTick()
      scrollToBottom()
      return
    }
    assistant.content = ''
    assistant.citations = []
    assistant.interrupted = false
    assistant.streaming = true
    await streamAnswer(currentConversationId.value, retry.content, retry.attachmentIds || [], assistant, retry)
  } catch (error) {
    notice.value = '重试前核对会话失败：' + (error.message || '请稍后重试')
  } finally {
    sending.value = false
  }
}

async function usePrompt(prompt) {
  draft.value = prompt
  await nextTick()
  composer.value?.focus()
}

function handleComposerEnter(event) {
  if (event.isComposing) return
  event.preventDefault()
  void sendMessage()
}

function startPanelDrag(event) {
  if (isFullscreen.value || event.button !== 0 || event.target.closest('button')) return
  const rect = panel.value?.getBoundingClientRect()
  if (!rect) return
  panelDrag = { pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, left: rect.left, top: rect.top, capture: event.currentTarget }
  event.currentTarget.setPointerCapture(event.pointerId)
  draggingPanel.value = true
  event.preventDefault()
}

function dragPanel(event) {
  if (!panelDrag || event.pointerId !== panelDrag.pointerId) return
  if (Math.hypot(event.clientX - panelDrag.startX, event.clientY - panelDrag.startY) > 2) panelManuallyDragged.value = true
  movePanelTo(panelDrag.left + event.clientX - panelDrag.startX, panelDrag.top + event.clientY - panelDrag.startY)
}

function stopPanelDrag(event) {
  if (!panelDrag || event.pointerId !== panelDrag.pointerId) return
  if (panelDrag.capture.hasPointerCapture(event.pointerId)) panelDrag.capture.releasePointerCapture(event.pointerId)
  panelDrag = null
  draggingPanel.value = false
}

function movePanelWithKeyboard(event) {
  if (isFullscreen.value || event.target !== event.currentTarget) return
  const directions = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }
  const direction = directions[event.key]
  if (!direction || !panel.value) return
  event.preventDefault()
  const rect = panel.value.getBoundingClientRect()
  const step = event.shiftKey ? 40 : 16
  panelManuallyDragged.value = true
  movePanelTo(rect.left + direction[0] * step, rect.top + direction[1] * step)
}

function startLauncherDrag(event) {
  if (event.button !== 0) return
  const rect = launcher.value?.getBoundingClientRect()
  if (!rect) return
  launcherDrag = { pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, left: rect.left, top: rect.top, moved: false }
  event.currentTarget.setPointerCapture(event.pointerId)
}

function dragLauncher(event) {
  if (!launcherDrag || event.pointerId !== launcherDrag.pointerId) return
  const dx = event.clientX - launcherDrag.startX
  const dy = event.clientY - launcherDrag.startY
  if (!launcherDrag.moved && Math.hypot(dx, dy) < 6) return
  launcherDrag.moved = true
  draggingLauncher.value = true
  moveLauncherTo(launcherDrag.left + dx, launcherDrag.top + dy)
  event.preventDefault()
}

function stopLauncherDrag(event) {
  if (!launcherDrag || event.pointerId !== launcherDrag.pointerId) return
  if (launcher.value?.hasPointerCapture(event.pointerId)) launcher.value.releasePointerCapture(event.pointerId)
  if (launcherDrag.moved) {
    localStorage.setItem(launcherStorageKey.value, JSON.stringify(launcherPosition.value))
    if (!panelManuallyDragged.value) {
      panelPosition.value = null
      if (open.value) nextTick(placePanel)
    }
    suppressLauncherClick = true
    setTimeout(() => { suppressLauncherClick = false }, 400)
  }
  launcherDrag = null
  draggingLauncher.value = false
}

watch(currentConversationId, () => {
  resetPendingActionState()
}, { flush: 'sync' })

onMounted(() => {
  window.addEventListener('resize', handleViewportResize)
  window.addEventListener('keydown', handleEscape)
  handleViewportResize()
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', handleViewportResize)
  window.removeEventListener('keydown', handleEscape)
  setPageScrollLocked(false)
  clearAttachments()
  clearPendingActionPolling()
  pendingActionRequestVersion += 1
  activeStreamController?.abort()
})
</script>

<template>
  <div class="support-widget">
    <Transition name="support-panel">
      <section
        v-if="open"
        id="support-widget-panel"
        ref="panel"
        class="support-widget-panel"
        :class="{ 'is-dragging': draggingPanel, 'is-conversation-page': props.conversationPage, 'is-fullscreen': isFullscreen }"
        :style="panelStyle"
        role="dialog"
        aria-label="AI 在线客服"
        aria-modal="false"
      >
        <header
          class="support-widget-header"
          :title="isFullscreen ? '客服窗口已全屏' : '按住标题栏可拖动窗口，方向键也可移动'"
          :tabindex="isFullscreen ? -1 : 0"
          :aria-label="isFullscreen ? 'AI 在线客服标题栏' : 'AI 在线客服标题栏，可拖动；方向键可移动窗口'"
          @pointerdown="startPanelDrag"
          @pointermove="dragPanel"
          @pointerup="stopPanelDrag"
          @pointercancel="stopPanelDrag"
          @keydown="movePanelWithKeyboard"
        >
          <div class="support-widget-identity">
            <span class="support-widget-avatar"><SparklesIcon /></span>
            <span><strong>AI 在线客服</strong><small>智能助理为你解答</small></span>
          </div>
          <div class="support-widget-actions">
            <button type="button" :title="isFullscreen ? '退出全屏' : '全屏'" :aria-label="isFullscreen ? '退出客服全屏' : '将客服窗口全屏'" @click="toggleFullscreen"><ArrowsPointingInIcon v-if="isFullscreen" /><ArrowsPointingOutIcon v-else /></button>
            <button type="button" :aria-label="showHistory ? '返回聊天' : '查看最近咨询'" :title="showHistory ? '返回聊天' : '最近咨询'" @click="toggleHistory"><ChatBubbleLeftRightIcon v-if="showHistory" /><ClockIcon v-else /></button>
            <button type="button" aria-label="新建咨询" title="新建咨询" :disabled="sending" @click="newConversation"><PlusIcon /></button>
            <button type="button" class="support-widget-close" aria-label="关闭客服窗口" title="关闭" @click="closeWidget(true)"><XMarkIcon /></button>
          </div>
        </header>

        <div v-if="showHistory" class="support-widget-history">
          <div class="support-widget-section-heading"><strong>最近咨询</strong><span>选择会话继续提问</span></div>
          <p v-if="loadingHistory" class="support-widget-state">正在加载…</p>
          <div v-else-if="historyError" class="support-widget-state support-widget-error">{{ historyError }}<button type="button" @click="loadHistory">重新加载</button></div>
          <p v-else-if="!supportSessions.length" class="support-widget-state">暂无客服咨询记录</p>
          <template v-else>
            <button
              v-for="session in supportSessions"
              :key="session.id"
              type="button"
              class="support-widget-session"
              :class="{ active: session.id === currentConversationId }"
              :disabled="loadingSession"
              @click="selectConversation(session.id)"
            >
              <ChatBubbleLeftRightIcon />
              <span><strong>{{ session.title || '新对话' }}</strong><small>{{ session.messages?.length || 0 }} 条消息</small></span>
            </button>
          </template>
          <button type="button" class="support-widget-new-from-history" @click="newConversation"><PlusIcon />发起新咨询</button>
        </div>

        <div v-else ref="messageArea" class="support-widget-messages" role="log" aria-label="客服对话记录">
          <p v-if="loadingSession" class="support-widget-state">正在恢复上次咨询…</p>
          <div v-else-if="!messages.length" class="support-widget-welcome">
            <span class="support-widget-welcome-icon"><SparklesIcon /></span>
            <p class="support-widget-eyebrow">智能客服 · 售后服务</p>
            <h2>你好，有什么可以帮你？</h2>
            <p>可直接描述订单、物流、退换货等问题，我会在这里回复你。</p>
            <div class="support-widget-prompts">
              <button type="button" @click="usePrompt('帮我查询订单进度')">查询订单进度</button>
              <button type="button" @click="usePrompt('退换货需要满足什么条件？')">了解退换货政策</button>
            </div>
          </div>
          <article v-for="(message, index) in messages" :key="index" class="support-widget-message" :class="message.role">
            <span v-if="message.role !== 'user'" class="support-widget-message-avatar"><SparklesIcon /></span>
            <div class="support-widget-message-body">
              <div class="support-widget-bubble">
                <span v-if="message.role === 'user'" class="support-widget-plain">{{ message.content }}</span>
                <template v-else>
                  <ChatMarkdown v-if="message.content" :content="message.content" :streaming="Boolean(message.streaming)" />
                  <span v-if="message.streaming && !message.content" class="support-widget-typing" aria-label="正在生成回答"><i></i><i></i><i></i></span>
                  <div v-if="message.interrupted" class="support-widget-interruption" role="alert">
                    <span>回答中断：{{ message.interruptionMessage }}</span>
                    <button type="button" :disabled="sending" @click="retryInterruptedAnswer"><ArrowPathIcon />重试回答</button>
                  </div>
                  <details v-if="message.citations?.length" class="support-widget-citations">
                    <summary>参考来源 · {{ message.citations.length }}</summary>
                    <p v-for="(citation, citationIndex) in message.citations" :key="citationIndex">{{ citation.fileName }}<span v-if="citation.pageNumber"> · 第 {{ citation.pageNumber }} 页</span></p>
                  </details>
                </template>
                <div v-if="message.attachments?.length" class="support-widget-message-attachments">
                  <a v-for="file in message.attachments" :key="file.id || file.name" :href="fileUrl(file)" :title="file.name" :aria-label="`打开附件：${file.name}`" target="_blank" rel="noopener noreferrer">
                    <img v-if="fileIsImage(file)" :src="fileUrl(file)" :alt="file.name">
                    <DocumentIcon v-else />
                    <span v-if="!fileIsImage(file)">{{ file.name }}</span>
                  </a>
                </div>
              </div>
              <time v-if="formatMessageTime(message.createdAt)" class="support-widget-message-time" :datetime="message.createdAt" :title="formatMessageDateTime(message.createdAt)">{{ formatMessageTime(message.createdAt) }}</time>
            </div>
          </article>
          <section v-if="pendingActions.length" class="support-widget-pending-actions" aria-label="需要人工确认的业务动作">
            <header><strong>业务动作</strong><small>工单结果以服务端状态为准</small></header>
            <PendingActionCard
              v-for="action in pendingActions"
              :key="action.actionId"
              :action="action"
              :busy="actionBusy[action.actionId] || ''"
              :error="actionErrors[action.actionId] || ''"
              @confirm="confirmPendingAction"
              @cancel="cancelPendingAction"
              @refresh="refreshPendingActionStatus"
            />
          </section>
        </div>

        <div v-if="notice" class="support-widget-notice" role="alert">
          <span>{{ notice }}</span>
          <button v-if="!initialized && currentConversationId" type="button" @click="restoreSession">重试加载</button>
        </div>

        <form v-if="!showHistory" class="support-widget-composer" :class="{ 'is-drag-over': draggingFiles }" @submit.prevent="sendMessage" @dragenter="onAttachmentDragEnter" @dragover="onAttachmentDragOver" @dragleave="onAttachmentDragLeave" @drop="onAttachmentDrop">
          <label class="support-widget-input-label" for="support-widget-input">输入咨询内容</label>
          <input ref="fileInput" class="support-widget-file-input" type="file" multiple accept=".png,.jpg,.jpeg,.webp,.gif,.pdf,.txt,.md,.markdown" :disabled="sending" @change="selectAttachments">
          <div v-if="attachments.length" class="support-widget-attachment-tray" aria-label="待发送附件">
            <div v-for="(file, index) in attachments" :key="file.name + index" class="support-widget-attachment-chip">
              <img v-if="file.isImage" :src="file.url" :alt="file.name">
              <DocumentIcon v-else />
              <span><strong>{{ file.name }}</strong><small>{{ formatSize(file.size) }}</small></span>
              <button type="button" :aria-label="`移除附件：${file.name}`" title="移除附件" :disabled="sending" @click="removeAttachment(index)"><XMarkIcon /></button>
            </div>
          </div>
          <div class="support-widget-compose-row">
            <button class="support-widget-attach-button" type="button" title="添加附件" aria-label="添加附件" :disabled="sending" @click="fileInput?.click()"><PaperClipIcon /></button>
            <textarea
              id="support-widget-input"
              ref="composer"
              v-model="draft"
              rows="2"
              placeholder="请输入你的问题…"
              :disabled="sending || loadingSession || (!initialized && Boolean(currentConversationId))"
              @keydown.enter.exact="handleComposerEnter"
              @paste="onAttachmentPaste"
            ></textarea>
            <button class="support-widget-send-button" type="submit" title="发送消息" aria-label="发送消息" :disabled="!draft.trim() || sending || loadingSession || (!initialized && Boolean(currentConversationId))"><PaperAirplaneIcon /></button>
          </div>
          <small class="support-widget-composer-hint">{{ uploadingAttachments ? '正在上传附件…' : attachments.length && !draft.trim() ? '输入问题后发送附件' : 'Enter 发送 · Shift + Enter 换行' }}</small>
          <div v-if="draggingFiles" class="support-widget-drop-hint" aria-hidden="true">松开以添加附件</div>
        </form>
      </section>
    </Transition>

    <button
      v-if="!open"
      ref="launcher"
      type="button"
      class="support-widget-launcher"
      :class="{ 'is-conversation-page': props.conversationPage, 'is-dragging': draggingLauncher }"
      :style="launcherStyle"
      aria-controls="support-widget-panel"
      :aria-expanded="false"
      aria-label="打开 AI 在线客服"
      title="AI 在线客服（可拖动）"
      @pointerdown="startLauncherDrag"
      @pointermove="dragLauncher"
      @pointerup="stopLauncherDrag"
      @pointercancel="stopLauncherDrag"
      @click="handleLauncherClick"
    >
      <ChatBubbleLeftRightIcon />
      <span>在线客服</span>
    </button>
  </div>
</template>

<style scoped>
.support-widget-panel {
  position: fixed;
  right: 24px;
  bottom: 88px;
  z-index: 80;
  width: min(390px, calc(100vw - 24px));
  height: min(560px, calc(100dvh - 110px));
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color: var(--text-color);
  background: var(--surface);
  border: 1px solid var(--border-color);
  border-radius: 18px;
  box-shadow: 0 24px 64px rgba(23, 32, 51, .2), 0 4px 14px rgba(23, 32, 51, .08);
}
.support-widget-panel.is-dragging { box-shadow: 0 30px 72px rgba(23, 32, 51, .27); }
.support-widget-header {
  min-height: 68px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 13px 0 17px;
  color: #fff;
  background: linear-gradient(120deg, #315efb, #397fe9);
  cursor: grab;
  user-select: none;
  touch-action: none;
}
.is-dragging .support-widget-header { cursor: grabbing; }
.support-widget-identity { display: flex; align-items: center; gap: 10px; min-width: 0; }
.support-widget-avatar { width: 35px; height: 35px; display: grid; place-items: center; flex: none; background: rgba(255,255,255,.17); border: 1px solid rgba(255,255,255,.25); border-radius: 11px; }
.support-widget-avatar svg { width: 19px; height: 19px; }
.support-widget-identity strong, .support-widget-identity small { display: block; white-space: nowrap; }
.support-widget-identity strong { font-size: 14px; }
.support-widget-identity small { margin-top: 2px; font-size: 10px; opacity: .85; }
.support-widget-actions { display: flex; align-items: center; gap: 1px; flex: none; }
.support-widget-actions button { width: 29px; height: 29px; display: grid; place-items: center; padding: 0; color: #fff; background: transparent; border: 0; border-radius: 8px; }
.support-widget-actions button:hover, .support-widget-actions button:focus-visible { background: rgba(255,255,255,.18); }
.support-widget-actions button:disabled { opacity: .4; cursor: not-allowed; }
.support-widget-actions svg { width: 16px; height: 16px; }
.support-widget-actions .support-widget-close { margin-left: 3px; background: rgba(255,255,255,.15); }
.support-widget-actions .support-widget-close:hover { background: rgba(255,255,255,.28); }

.support-widget-messages, .support-widget-history { flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; }
.support-widget-messages { padding: 18px 16px 20px; }
.support-widget-welcome { padding: 19px 6px 12px; }
.support-widget-welcome-icon { width: 44px; height: 44px; display: grid; place-items: center; color: var(--primary); background: var(--primary-soft); border-radius: 14px; }
.support-widget-welcome-icon svg { width: 24px; height: 24px; }
.support-widget-eyebrow { margin: 17px 0 5px; color: var(--primary); font-size: 11px; font-weight: 750; letter-spacing: .04em; }
.support-widget-welcome h2 { margin: 0; font-size: 20px; line-height: 1.4; }
.support-widget-welcome > p:last-of-type { margin: 9px 0 19px; color: var(--text-muted); font-size: 12px; line-height: 1.65; }
.support-widget-prompts { display: flex; flex-wrap: wrap; gap: 8px; }
.support-widget-prompts button { min-height: 33px; padding: 5px 10px; color: var(--primary); background: var(--primary-soft); border: 1px solid color-mix(in srgb, var(--primary) 18%, var(--border-color)); border-radius: 999px; font-size: 11px; }
.support-widget-prompts button:hover { border-color: var(--primary); }
.support-widget-message { display: flex; align-items: flex-end; gap: 7px; margin-bottom: 15px; }
.support-widget-message.user { justify-content: flex-end; }
.support-widget-message-avatar { width: 25px; height: 25px; display: grid; place-items: center; flex: none; color: var(--primary); background: var(--primary-soft); border-radius: 8px; }
.support-widget-message:has(.support-widget-message-time) .support-widget-message-avatar { margin-bottom: 17px; }
.support-widget-message-avatar svg { width: 15px; height: 15px; }
.support-widget-message-body { min-width: 0; max-width: calc(100% - 32px); display: flex; flex-direction: column; align-items: flex-start; }
.support-widget-message.user .support-widget-message-body { align-items: flex-end; }
.support-widget-bubble { min-width: 0; max-width: 100%; padding: 10px 12px; color: var(--text-color); background: var(--surface-subtle); border: 1px solid var(--border-color); border-radius: 13px 13px 13px 3px; overflow-wrap: anywhere; font-size: 13px; line-height: 1.65; }
.support-widget-message.user .support-widget-bubble { color: #fff; background: #315efb; border-color: #315efb; border-radius: 13px 13px 3px 13px; }
.support-widget-message-time { margin-top: 5px; color: var(--text-soft); font-size: 10px; font-variant-numeric: tabular-nums; line-height: 1.2; }
.support-widget-plain { white-space: pre-wrap; }
.support-widget-message-attachments { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.support-widget-message-attachments a { max-width: 190px; min-height: 31px; display: inline-flex; align-items: center; gap: 6px; padding: 5px 7px; overflow: hidden; color: inherit; border: 1px solid currentColor; border-radius: 7px; text-decoration: none; }
.support-widget-message-attachments a:has(img) { width: 54px; height: 54px; padding: 2px; }
.support-widget-message-attachments img { width: 100%; height: 100%; object-fit: cover; border-radius: 4px; }
.support-widget-message-attachments svg { width: 17px; height: 17px; flex: none; }
.support-widget-message-attachments span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; }
.support-widget-bubble :deep(.rich-markdown) { font-size: 13px; line-height: 1.65; }
.support-widget-bubble :deep(pre) { max-width: 100%; overflow-x: auto; }
.support-widget-typing { display: inline-flex; align-items: center; gap: 4px; min-height: 18px; }
.support-widget-typing i { width: 5px; height: 5px; background: var(--text-soft); border-radius: 50%; animation: support-typing 1.2s ease-in-out infinite; }
.support-widget-typing i:nth-child(2) { animation-delay: .15s; }
.support-widget-typing i:nth-child(3) { animation-delay: .3s; }
.support-widget-interruption { display: grid; gap: 8px; margin-top: 9px; padding-top: 9px; color: var(--danger); border-top: 1px solid var(--border-color); font-size: 11px; }
.support-widget-interruption button { width: fit-content; display: inline-flex; align-items: center; gap: 5px; padding: 5px 8px; color: var(--primary); background: var(--primary-soft); border: 0; border-radius: 6px; font-size: 11px; }
.support-widget-interruption button:disabled { opacity: .5; }
.support-widget-interruption svg { width: 13px; height: 13px; }
.support-widget-citations { margin-top: 10px; padding-top: 8px; border-top: 1px solid var(--border-color); color: var(--text-muted); font-size: 11px; }
.support-widget-citations summary { cursor: pointer; }
.support-widget-citations p { margin: 5px 0 0; }
.support-widget-pending-actions { display: grid; gap: 10px; margin: 6px 0 2px; }
.support-widget-pending-actions > header { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.support-widget-pending-actions > header strong { font-size: 12px; }
.support-widget-pending-actions > header small { color: var(--text-soft); font-size: 9px; }
.support-widget-pending-actions :deep(.pending-action-card) { width: 100%; margin: 0; padding: 12px; box-shadow: 0 5px 16px rgba(23, 32, 51, .05); }
.support-widget-pending-actions :deep(.pending-action-card > header) { align-items: flex-start; }
.support-widget-pending-actions :deep(.action-title small) { letter-spacing: .02em; }
.support-widget-pending-actions :deep(dl) { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 7px; }
.support-widget-pending-actions :deep(dl > div) { padding: 8px; }
.support-widget-pending-actions :deep(footer) { align-items: stretch; flex-direction: column; }
.support-widget-pending-actions :deep(.action-buttons) { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.support-widget-pending-actions :deep(.action-buttons .refresh) { grid-column: 1 / -1; }
.support-widget-pending-actions :deep(.action-buttons button) { min-height: 36px; }
.support-widget-state { margin: 0; padding: 46px 14px; color: var(--text-muted); font-size: 12px; text-align: center; }
.support-widget-error button { display: block; margin: 8px auto 0; padding: 5px 9px; color: var(--primary); background: var(--primary-soft); border: 0; border-radius: 6px; }
.support-widget-section-heading { padding: 17px 17px 10px; }
.support-widget-section-heading strong, .support-widget-section-heading span { display: block; }
.support-widget-section-heading strong { font-size: 14px; }
.support-widget-section-heading span { margin-top: 3px; color: var(--text-muted); font-size: 11px; }
.support-widget-session { width: calc(100% - 24px); min-height: 57px; display: flex; align-items: center; gap: 10px; margin: 4px 12px; padding: 9px 11px; color: var(--text-color); background: transparent; border: 1px solid transparent; border-radius: 10px; text-align: left; }
.support-widget-session:hover, .support-widget-session.active { background: var(--primary-soft); border-color: color-mix(in srgb, var(--primary) 15%, var(--border-color)); }
.support-widget-session > svg { width: 20px; height: 20px; flex: none; color: var(--primary); }
.support-widget-session span { min-width: 0; }
.support-widget-session strong, .support-widget-session small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.support-widget-session strong { font-size: 12px; }
.support-widget-session small { margin-top: 3px; color: var(--text-muted); font-size: 10px; }
.support-widget-new-from-history { display: flex; align-items: center; gap: 6px; margin: 13px 17px; padding: 7px 0; color: var(--primary); background: transparent; border: 0; font-size: 12px; }
.support-widget-new-from-history svg { width: 15px; height: 15px; }
.support-widget-notice { display: flex; align-items: center; gap: 8px; padding: 8px 16px; color: var(--danger); background: color-mix(in srgb, var(--danger) 7%, var(--surface)); border-top: 1px solid var(--border-color); font-size: 11px; line-height: 1.4; }
.support-widget-notice span { flex: 1; }
.support-widget-notice button { flex: none; color: var(--primary); background: transparent; border: 0; font-size: 11px; }
.support-widget-composer { position: relative; padding: 12px 14px 11px; background: var(--surface); border-top: 1px solid var(--border-color); }
.support-widget-input-label { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
.support-widget-file-input { display: none; }
.support-widget-attachment-tray { max-height: 112px; display: flex; flex-wrap: wrap; gap: 6px; overflow-y: auto; margin-bottom: 8px; }
.support-widget-attachment-chip { min-width: 0; max-width: 100%; display: flex; align-items: center; gap: 6px; padding: 4px 5px 4px 6px; background: var(--surface-subtle); border: 1px solid var(--border-color); border-radius: 8px; }
.support-widget-attachment-chip > img { width: 30px; height: 30px; flex: none; object-fit: cover; border-radius: 5px; }
.support-widget-attachment-chip > svg { width: 19px; height: 19px; flex: none; color: var(--primary); }
.support-widget-attachment-chip > span { min-width: 0; max-width: 125px; }
.support-widget-attachment-chip strong, .support-widget-attachment-chip small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.support-widget-attachment-chip strong { font-size: 10px; font-weight: 650; }
.support-widget-attachment-chip small { color: var(--text-soft); font-size: 9px; }
.support-widget-attachment-chip button { width: 22px; height: 22px; display: grid; place-items: center; flex: none; padding: 0; color: var(--text-soft); background: transparent; border: 0; border-radius: 5px; }
.support-widget-attachment-chip button:hover { color: var(--danger); background: var(--surface); }
.support-widget-attachment-chip button:disabled { opacity: .5; }
.support-widget-attachment-chip button svg { width: 14px; height: 14px; }
.support-widget-compose-row { display: flex; align-items: flex-end; gap: 7px; padding: 7px; background: var(--surface-subtle); border: 1px solid var(--border-color); border-radius: 12px; transition: border-color .18s ease, background-color .18s ease, box-shadow .18s ease; }
.support-widget-compose-row:focus-within { background: var(--surface); border-color: color-mix(in srgb, var(--primary) 55%, var(--border-color)); box-shadow: 0 3px 14px rgba(35, 77, 184, .1); }
.support-widget-compose-row textarea { width: 100%; min-width: 0; min-height: 37px; max-height: 100px; resize: none; padding: 7px 5px; color: var(--text-color); background: transparent; border: 0; outline: none; box-shadow: none; font-size: 13px; line-height: 1.5; }
.support-widget-compose-row textarea:focus, .support-widget-compose-row textarea:focus-visible { border: 0; outline: none; box-shadow: none; }
.support-widget-compose-row textarea::placeholder { color: var(--text-soft); }
.support-widget-compose-row button { width: 34px; height: 34px; display: grid; place-items: center; flex: none; padding: 0; border-radius: 9px; }
.support-widget-compose-row .support-widget-attach-button { color: var(--text-muted); background: var(--surface); border: 1px solid var(--border-color); }
.support-widget-compose-row .support-widget-attach-button:hover:not(:disabled) { color: var(--primary); border-color: color-mix(in srgb, var(--primary) 35%, var(--border-color)); }
.support-widget-compose-row .support-widget-send-button { color: #fff; background: #315efb; border: 0; }
.support-widget-compose-row .support-widget-send-button:hover:not(:disabled) { background: #244bd8; }
.support-widget-compose-row button:disabled { opacity: .45; cursor: not-allowed; }
.support-widget-compose-row button svg { width: 18px; height: 18px; }
.support-widget-composer > .support-widget-composer-hint { display: block; margin: 6px 2px 0; color: var(--text-soft); font-size: 10px; }
.support-widget-composer.is-drag-over .support-widget-compose-row { border-color: var(--primary); box-shadow: 0 0 0 3px color-mix(in srgb, var(--primary) 12%, transparent); }
.support-widget-drop-hint { position: absolute; inset: 0; z-index: 2; display: grid; place-items: center; color: var(--primary); background: color-mix(in srgb, var(--surface) 90%, transparent); border: 1px dashed var(--primary); font-size: 12px; font-weight: 700; pointer-events: none; }

.support-widget-launcher { position: fixed; right: 24px; bottom: 24px; z-index: 81; min-height: 52px; display: inline-flex; align-items: center; justify-content: center; gap: 9px; padding: 0 18px; color: #fff; background: #315efb; border: 1px solid #315efb; border-radius: 999px; box-shadow: 0 10px 28px rgba(36, 75, 216, .26); font-size: 14px; font-weight: 700; touch-action: none; animation: support-breathe 7s ease-in-out infinite; transition: transform .18s ease, background-color .18s ease; }
.support-widget-launcher:hover { transform: translateY(-2px); background: #244bd8; }
.support-widget-launcher.is-dragging { cursor: grabbing; animation: none; transform: none; }
.support-widget-launcher.is-conversation-page { bottom: 110px; }
.support-widget-launcher svg { width: 21px; height: 21px; flex: none; }
.support-widget-launcher:focus-visible, .support-widget-panel button:focus-visible, .support-widget-header:focus-visible { outline: 2px solid #8ca5ff; outline-offset: 2px; }
.support-panel-enter-active, .support-panel-leave-active { transition: opacity .22s ease, transform .22s cubic-bezier(.2,.8,.2,1); }
.support-panel-enter-from, .support-panel-leave-to { opacity: 0; transform: translateY(12px) scale(.97); }
@keyframes support-breathe { 0%, 82%, 100% { box-shadow: 0 10px 28px rgba(36, 75, 216, .26); } 91% { box-shadow: 0 10px 28px rgba(36, 75, 216, .26), 0 0 0 7px rgba(49, 94, 251, .13); } }
@keyframes support-typing { 0%, 60%, 100% { opacity: .4; transform: translateY(0); } 30% { opacity: 1; transform: translateY(-3px); } }
@media (max-width: 860px) {
  .support-widget-panel { right: 12px; bottom: calc(142px + env(safe-area-inset-bottom)); width: min(390px, calc(100vw - 24px)); height: min(560px, calc(100dvh - 166px - env(safe-area-inset-bottom))); }
  .support-widget-panel.is-conversation-page { height: min(560px, calc(100dvh - 240px - env(safe-area-inset-bottom))); }
  .support-widget-launcher { right: 14px; bottom: calc(78px + env(safe-area-inset-bottom)); width: 50px; height: 50px; min-height: 50px; padding: 0; }
  .support-widget-launcher.is-conversation-page { bottom: calc(155px + env(safe-area-inset-bottom)); }
  .support-widget-launcher span { display: none; }
}
.support-widget-panel.is-fullscreen { inset: 0; box-sizing: border-box; width: 100%; height: 100vh; height: 100dvh; min-height: 0; max-width: none; max-height: none; overflow: hidden; border: 0; border-radius: 0; overscroll-behavior: contain; }
.support-widget-panel.is-fullscreen .support-widget-header { padding-top: env(safe-area-inset-top); cursor: default; }
.support-widget-panel.is-fullscreen .support-widget-header, .support-widget-panel.is-fullscreen .support-widget-composer, .support-widget-panel.is-fullscreen .support-widget-notice { flex: 0 0 auto; }
.support-widget-panel.is-fullscreen .support-widget-messages, .support-widget-panel.is-fullscreen .support-widget-history { flex: 1 1 auto; width: 100%; min-height: 0; max-width: 900px; margin-inline: auto; overflow-y: auto; }
.support-widget-panel.is-fullscreen .support-widget-composer { padding-bottom: calc(11px + env(safe-area-inset-bottom)); }
.support-widget-panel.is-fullscreen .support-widget-compose-row, .support-widget-panel.is-fullscreen .support-widget-composer-hint { max-width: 900px; margin-inline: auto; }
@media (prefers-reduced-motion: reduce) {
  .support-widget-launcher, .support-widget-typing i { animation: none; }
  .support-widget-launcher, .support-widget-compose-row, .support-panel-enter-active, .support-panel-leave-active { transition-duration: .01ms; }
}
</style>
