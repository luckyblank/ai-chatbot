<template>
  <section class="conversation-workspace" :class="{ 'sessions-collapsed': sessionSidebarCollapsed, 'context-collapsed': contextSidebarCollapsed }">
    <aside id="conversation-drawer" class="session-sidebar" :class="{ 'mobile-open': mobileSessionsOpen, collapsed: sessionSidebarCollapsed }" :role="mobileSessionsOpen ? 'dialog' : undefined" :aria-modal="mobileSessionsOpen ? 'true' : undefined" aria-labelledby="conversation-drawer-title">
      <button class="collapsed-rail" type="button" title="展开会话中心" aria-label="展开会话中心" @click="toggleSessionSidebar"><ChatBubbleLeftRightIcon /><span>会话</span></button>
      <div class="session-sidebar-content">
        <div class="session-heading">
          <span id="conversation-drawer-title">会话中心</span>
          <span class="session-heading-actions">
            <button type="button" title="新建会话" aria-label="新建会话" :disabled="sending" @click="createConversation"><PlusIcon /></button>
            <button ref="mobileDrawerClose" class="drawer-close" type="button" title="关闭会话列表" aria-label="关闭会话列表" @click="closeMobileSessions"><XMarkIcon /></button>
          </span>
        </div>
        <label class="session-search"><MagnifyingGlassIcon /><input v-model="conversationSearch" placeholder="搜索会话"></label>
        <div class="history-scope" role="group" aria-label="会话历史范围">
          <button type="button" :class="{ active: historyScope === 'current' }" :aria-pressed="historyScope === 'current'" @click="setHistoryScope('current')">当前筛选</button>
          <button type="button" :class="{ active: historyScope === 'all' }" :aria-pressed="historyScope === 'all'" @click="setHistoryScope('all')">全部会话</button>
        </div>
        <div class="history-summary"><span>{{ historyScope === 'all' ? '全部历史' : '场景与知识范围' }}</span><small>{{ filteredConversations.length }}</small></div>
        <div class="session-list" aria-live="polite">
          <section v-for="group in conversationGroups" :key="group.label" class="session-group">
            <h3>
              <button
                type="button"
                class="session-group-toggle"
                :class="{ 'is-collapsed': collapsedConversationGroups.has(group.label) }"
                :aria-expanded="!collapsedConversationGroups.has(group.label)"
                :aria-controls="`session-group-items-${group.id}`"
                @click="toggleConversationGroup(group.label)"
              >
                <span>{{ group.label }}</span>
                <small>{{ group.items.length }}</small>
                <ChevronDownIcon aria-hidden="true" />
              </button>
            </h3>
            <Transition name="session-group-content">
              <div v-show="!collapsedConversationGroups.has(group.label)" :id="`session-group-items-${group.id}`" class="session-group-items">
                <article v-for="session in group.items" :key="session.id" :class="{ active: session.id === currentConversationId }">
                  <button class="session-open" type="button" :disabled="sending" :aria-current="session.id === currentConversationId ? 'true' : undefined" :aria-label="`打开会话：${session.title || '新对话'}`" @click="selectConversation(session.id)">
                    <ChatBubbleLeftRightIcon class="session-icon" />
                    <span class="session-copy"><span class="title-viewport"><strong v-title-overflow class="title-track">{{ session.title || '新对话' }}</strong></span><small>{{ conversationMeta(session) }}</small></span>
                  </button>
                  <span class="session-actions" aria-label="会话操作"><button type="button" title="修改名称" :aria-label="`修改会话名称：${session.title || '新对话'}`" @click.stop="startRename(session)"><PencilSquareIcon /></button><button type="button" title="删除会话" :aria-label="`删除会话：${session.title || '新对话'}`" @click.stop="startDelete(session)"><TrashIcon /></button></span>
                </article>
              </div>
            </Transition>
          </section>
          <div v-if="!filteredConversations.length" class="empty-list"><ClockIcon /><strong>{{ emptyConversationTitle }}</strong><span>{{ emptyConversationHint }}</span></div>
        </div>
      </div>
    </aside>
    <button class="panel-toggle session-panel-toggle" type="button" :title="sessionSidebarCollapsed ? '展开会话中心' : '收起会话中心'" :aria-label="sessionSidebarCollapsed ? '展开会话中心' : '收起会话中心'" :aria-expanded="!sessionSidebarCollapsed" @click="toggleSessionSidebar"><PanelEdgeHandleIcon :direction="sessionSidebarCollapsed ? 'right' : 'left'" /></button>
    <button v-if="mobileSessionsOpen" class="mobile-session-backdrop" type="button" aria-label="关闭会话列表" @click="closeMobileSessions"></button>

    <section class="conversation-surface">
      <header class="conversation-toolbar">
        <button ref="mobileSessionTrigger" class="mobile-session-trigger" type="button" aria-controls="conversation-drawer" :aria-expanded="mobileSessionsOpen" @click="openMobileSessions"><ChatBubbleLeftRightIcon /><span>会话列表</span><small>{{ filteredConversations.length }}</small></button>
        <label><span>业务场景</span><select v-model="selectedScenarioCode" :disabled="sending" @change="changeScenario"><option v-for="item in scenarios" :key="item.code" :value="item.code">{{ item.name }}</option></select></label>
        <label><span>知识范围</span><select v-model="selectedKnowledgeBaseId" :disabled="loadingKnowledgeBases || sending" @change="changeKnowledgeBase"><option value="">不使用知识库 · 普通聊天</option><option v-for="item in knowledgeBases" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <div class="mode-state"><span :class="{ rag: selectedKnowledgeBaseId }"></span><strong>{{ selectedKnowledgeBaseId ? 'RAG 已启用' : '普通对话' }}</strong></div>
      </header>

      <div ref="messageArea" class="messages">
        <div v-if="messages.length === 0" class="conversation-empty">
          <span class="mode-label">{{ selectedScenario.name }}</span>
          <h1>{{ selectedKnowledgeBaseId ? '基于企业知识开始对话' : '直接开始一次智能对话' }}</h1>
          <p>{{ selectedKnowledgeBaseId ? `回答将从“${currentKnowledgeBase?.name}”检索依据，并展示引用与完整链路。` : '当前未选择知识库，问题会直接交给模型，不执行向量检索。' }}</p>
          <div class="starter-grid"><button v-for="step in selectedScenario.process.slice(0, 3)" :key="step" @click="input = `请帮我完成：${step}`"><ArrowUpRightIcon />{{ step }}</button></div>
        </div>

        <article v-for="(message, index) in messages" :key="index" class="message" :class="message.role">
          <div class="avatar" aria-hidden="true"><UserIcon v-if="message.role === 'user'" /><SparklesIcon v-else /></div>
          <div class="message-body">
            <div class="message-meta">
              <strong>{{ message.role === 'user' ? '你' : selectedScenario.shortName }}</strong>
              <span>{{ message.role === 'user' ? '用户请求' : message.streaming ? '正在生成' : '智能响应' }}</span>
              <time v-if="formatMessageTime(message.createdAt)" :datetime="message.createdAt" :title="formatMessageDateTime(message.createdAt)">{{ formatMessageTime(message.createdAt) }}</time>
            </div>
            <div class="bubble" :class="{ streaming: message.streaming }" :aria-live="message.streaming ? 'polite' : undefined">
                <ChatMarkdown v-if="message.content" :content="message.content" :streaming="Boolean(message.streaming)" @preview-image="previewImage = $event" />
                <div v-else-if="message.streaming" class="stream-waiting"><i></i><i></i><i></i><span>正在组织回答</span></div>
                <span v-if="message.streaming && message.content" class="stream-caret" aria-hidden="true"></span>
                <div v-if="message.attachments?.length" class="message-attachments">
                  <template v-for="file in message.attachments" :key="file.id || file.name">
                    <button v-if="fileIsImage(file)" class="image-only" type="button" :title="`预览 ${file.name}`" :aria-label="`放大预览图片：${file.name}`" @click="previewImage = fileUrl(file)"><img :src="fileUrl(file)" :alt="file.name"></button>
                    <a v-else :href="fileUrl(file)" target="_blank" rel="noopener"><DocumentIcon /><span>{{ file.name }}</span></a>
                  </template>
                </div>
                <details v-if="message.traces?.length" class="trace-panel">
                  <summary><span><CommandLineIcon />链路追溯</span><small>{{ message.traces.length }} 个步骤</small></summary>
                  <ol class="trace-list"><li v-for="(step, traceIndex) in message.traces" :key="traceIndex" :class="step.status"><span class="trace-dot"></span><div><div class="trace-heading"><span class="phase-badge">{{ tracePhaseLabel(step.phase) }}</span><strong>{{ step.title }}</strong><time v-if="step.durationMs !== null && step.durationMs !== undefined">{{ step.durationMs }} ms</time></div><p>{{ step.detail }}</p></div></li></ol>
                </details>
                <CitationSources v-if="message.citations?.length" :citations="message.citations" />
                <div v-if="message.interrupted" class="message-interruption" role="alert"><ExclamationCircleIcon /><span>回答中断：{{ message.interruptionMessage || '连接已断开' }}。可重新生成或同步会话后继续。</span><button type="button" :disabled="sending" @click="retryInterruptedAnswer(index)">重新生成</button><button type="button" :disabled="sending" @click="discardInterruptedAnswer">同步并继续</button></div>
            </div>
            <div v-if="!message.streaming && message.content" class="message-actions" :aria-label="`${message.role === 'user' ? '用户消息' : 'AI 回复'}操作`">
              <button v-if="message.role === 'user'" type="button" title="编辑消息" aria-label="编辑消息" :disabled="sending" @click="startMessageEdit(message, index)"><PencilSquareIcon /><span>{{ editingMessageIndex === index ? '编辑中' : '编辑' }}</span></button>
              <button type="button" :class="{ copied: copiedMessageIndex === index }" :title="copiedMessageIndex === index ? '已复制' : '复制内容'" :aria-label="copiedMessageIndex === index ? '内容已复制' : '复制内容'" @click="copyMessage(message, index)"><CheckIcon v-if="copiedMessageIndex === index" /><ClipboardDocumentIcon v-else /><span>{{ copiedMessageIndex === index ? '已复制' : '复制' }}</span></button>
            </div>
          </div>
        </article>
        <section v-if="pendingActions.length" class="pending-action-stack" aria-label="需要人工确认的业务动作">
          <header><span>业务动作</span><small>工单结果以服务端状态为准</small></header>
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
        <article v-if="sending && !hasStreamingMessage" class="message assistant"><div class="avatar" aria-hidden="true"><SparklesIcon /></div><div class="message-body"><div class="message-meta"><strong>{{ selectedScenario.shortName }}</strong><span>{{ uploadingAttachments ? '正在上传附件' : '正在建立回答链路' }}</span></div><div class="bubble typing"><i></i><i></i><i></i></div></div></article>
      </div>

      <div v-if="notice" class="notice" role="status" aria-live="polite">{{ notice }}<button type="button" @click="notice = ''">关闭</button></div>
      <div v-if="attachments.length" class="attachment-tray">
        <div v-for="(file, index) in attachments" :key="file.name + index" :class="{ 'image-item': file.isImage }">
          <button v-if="file.isImage" class="pending-image" type="button" title="点击放大预览" :aria-label="`放大预览待发送图片：${file.name}`" @click="previewImage = file.url"><img :src="file.url" alt="待发送图片"></button>
          <template v-else><DocumentIcon /><span><strong>{{ file.name }}</strong><small>{{ formatSize(file.size) }}</small></span></template>
          <button class="remove-attachment" type="button" title="移除附件" :aria-label="`移除附件：${file.name}`" @click="removeAttachment(index)"><XMarkIcon /></button>
        </div>
      </div>
      <form class="composer" :class="{ 'is-editing': editingMessageIndex >= 0, 'drag-over': draggingFiles }" @submit.prevent="sendMessage" @dragenter="onAttachmentDragEnter" @dragover="onAttachmentDragOver" @dragleave="onAttachmentDragLeave" @drop="onAttachmentDrop">
        <div v-if="editingMessageIndex >= 0" class="composer-edit-state">
          <span><PencilSquareIcon /><span><strong>编辑消息</strong><small>发送后将替换此消息及其后的回复</small></span></span>
          <button type="button" title="取消编辑" aria-label="取消编辑消息" :disabled="sending" @click="cancelMessageEdit"><XMarkIcon /></button>
        </div>
        <input ref="fileInput" class="file-input" type="file" multiple accept=".png,.jpg,.jpeg,.webp,.gif,.pdf,.txt,.md,.markdown" @change="selectAttachments">
        <button class="attach-button" type="button" title="添加附件" aria-label="添加附件" @click="fileInput?.click()"><PaperClipIcon /></button>
        <textarea ref="composerTextarea" v-model="input" rows="1" :disabled="sending" :placeholder="editingMessageIndex >= 0 ? '修改这条消息' : selectedScenarioCode === 'commerce-support' ? '描述售后问题；可提供客户编号或订单号' : '输入问题；知识库为可选项'" @keydown.enter.exact.prevent="sendMessage" @paste="onAttachmentPaste"></textarea>
        <button v-if="hasStreamingMessage" class="send-button stop-button" type="button" title="停止生成" aria-label="停止生成" @click="stopGeneration">停止</button>
        <button v-else class="send-button" type="submit" :aria-label="editingMessageIndex >= 0 ? '发送修改后的消息' : '发送消息'" :disabled="!input.trim() || sending"><PaperAirplaneIcon /></button>
        <div class="composer-foot"><span>{{ editingMessageIndex >= 0 ? 'Enter 重新发送 · Esc 取消编辑' : 'Enter 发送 · Shift + Enter 换行' }}</span><span>{{ selectedKnowledgeBaseId ? '知识增强' : '普通模型' }} · {{ selectedScenario.shortName }}</span></div>
        <div v-if="draggingFiles" class="composer-drop-hint" aria-hidden="true">松开以添加附件</div>
      </form>
    </section>

    <aside class="context-sidebar" :class="{ collapsed: contextSidebarCollapsed }">
      <button class="collapsed-rail" type="button" title="展开场景指引" aria-label="展开场景指引" @click="toggleContextSidebar"><InformationCircleIcon /><span>场景</span></button>
      <div class="context-sidebar-content">
        <header><span>场景指引</span><RouterLink :to="{ path: '/scenarios', query: { scenario: selectedScenario.code, edit: '1' } }">编辑场景</RouterLink></header>
        <section><label>当前场景</label><h2>{{ selectedScenario.name }}</h2><p>{{ selectedScenario.summary }}</p></section>
        <section><label>建议处理步骤</label><ol class="process-list"><li v-for="(step, index) in selectedScenario.process" :key="step"><span>{{ index + 1 }}</span>{{ step }}</li></ol></section>
        <section><label>允许的业务工具</label><div v-if="selectedScenario.tools.length" class="tool-list"><span v-for="tool in selectedScenario.tools" :key="tool"><WrenchScrewdriverIcon />{{ tool }}</span></div><p v-else>该场景不开放业务工具。</p><p v-if="selectedScenarioCode === 'commerce-support'">可用客户编号查询其订单；客服登录账号 ID 不能代替客户编号。</p></section>
        <section><label>知识上下文</label><div class="knowledge-context"><CircleStackIcon /><span><strong>{{ currentKnowledgeBase?.name || '未选择知识库' }}</strong><small>{{ currentKnowledgeBase ? '回答将执行向量检索' : '当前为普通聊天模式' }}</small></span></div><RouterLink class="manage-knowledge" :to="selectedKnowledgeBaseId ? { path: '/knowledge-bases', query: { knowledge: selectedKnowledgeBaseId } } : '/knowledge-bases'">进入知识中心</RouterLink></section>
        <section class="guardrail"><ShieldCheckIcon /><div><label>业务边界</label><p>{{ selectedScenario.guardrail }}</p></div></section>
      </div>
    </aside>
    <button class="panel-toggle context-panel-toggle" type="button" :title="contextSidebarCollapsed ? '展开场景指引' : '收起场景指引'" :aria-label="contextSidebarCollapsed ? '展开场景指引' : '收起场景指引'" :aria-expanded="!contextSidebarCollapsed" @click="toggleContextSidebar"><PanelEdgeHandleIcon :direction="contextSidebarCollapsed ? 'left' : 'right'" /></button>

    <Transition name="toast">
      <div v-if="toast" class="conversation-toast" :class="toast.type" role="status" aria-live="polite">
        <span class="toast-symbol"><CheckCircleIcon v-if="toast.type === 'success'" /><ExclamationCircleIcon v-else /></span>
        <span><strong>{{ toast.title }}</strong><small>{{ toast.message }}</small></span>
        <button type="button" title="关闭提示" aria-label="关闭提示" @click="dismissToast"><XMarkIcon /></button>
      </div>
    </Transition>

    <ImagePreview v-if="previewImage" :src="previewImage" alt="附件图片预览" @close="previewImage = ''" />

    <div v-if="renameTarget" class="modal" @click.self="renameTarget = null"><form class="dialog" role="dialog" aria-modal="true" aria-labelledby="rename-dialog-title" @submit.prevent="confirmRename"><span class="dialog-eyebrow">会话管理</span><h2 id="rename-dialog-title">修改对话名称</h2><label>名称<input v-model.trim="renameTitle" maxlength="120" required autofocus></label><div class="dialog-actions"><button type="button" @click="renameTarget = null">取消</button><button class="primary" type="submit">保存</button></div></form></div>
    <div v-if="deleteTarget" class="modal" @click.self="!deleting && (deleteTarget = null)"><section class="dialog danger-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-dialog-title" aria-describedby="delete-dialog-description"><span class="danger-icon"><TrashIcon /></span><h2 id="delete-dialog-title">确认删除这个会话？</h2><p id="delete-dialog-description">“{{ deleteTarget.title || '新对话' }}”的消息与附件将一并删除，且无法恢复。</p><div class="dialog-actions"><button type="button" :disabled="deleting" autofocus @click="deleteTarget = null">取消</button><button class="danger" type="button" :disabled="deleting" @click="confirmDelete">{{ deleting ? '删除中…' : '确认删除' }}</button></div></section></div>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowUpRightIcon, ChatBubbleLeftRightIcon, CheckCircleIcon, CheckIcon, ChevronDownIcon, CircleStackIcon, ClipboardDocumentIcon, ClockIcon, CommandLineIcon, DocumentIcon, ExclamationCircleIcon, InformationCircleIcon, MagnifyingGlassIcon, PaperAirplaneIcon, PaperClipIcon, PencilSquareIcon, PlusIcon, ShieldCheckIcon, SparklesIcon, TrashIcon, UserIcon, WrenchScrewdriverIcon, XMarkIcon } from '@heroicons/vue/24/outline'
import ChatMarkdown from '../components/ChatMarkdown.vue'
import CitationSources from '../components/CitationSources.vue'
import ImagePreview from '../components/ImagePreview.vue'
import PendingActionCard from '../components/PendingActionCard.vue'
import PanelEdgeHandleIcon from '../components/icons/PanelEdgeHandleIcon.vue'
import { scenarios, scenarioByCode } from '../data/scenarios'
import { BASE_URL, conversationAPI, createRequestId, knowledgeAPI, pendingActionAPI } from '../services/api'

const route = useRoute()
const router = useRouter()
const knowledgeBases = ref([]), conversations = ref([]), messages = ref([]), attachments = ref([])
const pendingActions = ref([])
const actionBusy = reactive({}), actionErrors = reactive({})
const selectedKnowledgeBaseId = ref(''), selectedScenarioCode = ref(scenarioByCode(route.query.scenario).code), currentConversationId = ref('')
const input = ref(''), notice = ref(''), conversationSearch = ref(''), previewImage = ref('')
const sending = ref(false), uploadingAttachments = ref(false), loadingKnowledgeBases = ref(false), deleting = ref(false)
const draggingFiles = ref(false)
const workspaceLoaded = ref(false)
const mobileSessionsOpen = ref(false)
const historyScope = ref(localStorage.getItem('conversation-history-scope') === 'all' ? 'all' : 'current')
const collapsedConversationGroups = ref(new Set(readCollapsedConversationGroups()))
const sessionSidebarCollapsed = ref(localStorage.getItem('conversation-sessions-collapsed') === 'true')
const contextSidebarCollapsed = ref(localStorage.getItem('conversation-context-collapsed') === 'true')
const toast = ref(null)
const editingMessageIndex = ref(-1)
const copiedMessageIndex = ref(-1)
const messageArea = ref(null), fileInput = ref(null), composerTextarea = ref(null), renameTarget = ref(null), deleteTarget = ref(null), renameTitle = ref('')
const mobileSessionTrigger = ref(null), mobileDrawerClose = ref(null)
const selectedScenario = computed(() => scenarioByCode(selectedScenarioCode.value))
const currentKnowledgeBase = computed(() => knowledgeBases.value.find(item => item.id === selectedKnowledgeBaseId.value))
const scopedConversations = computed(() => {
  if (historyScope.value === 'all') return conversations.value
  return conversations.value.filter(item => {
    const scenarioMatches = (item.scenarioCode || 'general') === selectedScenarioCode.value
    const knowledgeMatches = (item.knowledgeBaseId || '') === selectedKnowledgeBaseId.value
    return scenarioMatches && knowledgeMatches
  })
})
const filteredConversations = computed(() => {
  const query = conversationSearch.value.trim().toLocaleLowerCase('zh-CN')
  if (!query) return scopedConversations.value
  return scopedConversations.value.filter(item => `${item.title || ''} ${conversationMeta(item)}`.toLocaleLowerCase('zh-CN').includes(query))
})
const conversationGroups = computed(() => {
  const groups = new Map()
  filteredConversations.value.forEach(session => {
    const label = conversationDateGroup(session.updatedAt || session.createdAt)
    if (!groups.has(label)) groups.set(label, [])
    groups.get(label).push(session)
  })
  return Array.from(groups, ([label, items]) => ({ label, id: label.replace(/\s+/g, '-'), items }))
})
const emptyConversationTitle = computed(() => {
  if (conversationSearch.value.trim()) return '没有匹配的会话'
  return historyScope.value === 'all' ? '暂无历史会话' : '当前筛选下暂无会话'
})
const emptyConversationHint = computed(() => conversationSearch.value.trim() ? '试试其他关键词' : historyScope.value === 'all' ? '新会话会按时间归档在这里' : '可切换到“全部会话”查看完整历史')
const hasStreamingMessage = computed(() => messages.value.some(item => item.streaming))
let conversationRequestVersion = 0
let activeStreamController = null
const manuallyStoppedStreams = new WeakSet()
let streamScrollFrame = 0
let toastTimer = 0
let copyTimer = 0
let editDraftBackup = null
let attachmentDragDepth = 0
let pendingActionPollTimer = 0
let pendingActionRequestVersion = 0

const vTitleOverflow = {
  mounted(element) {
    const viewport = element.parentElement
    const update = () => {
      const overflow = Math.max(0, element.scrollWidth - element.clientWidth)
      viewport?.classList.toggle('is-overflowing', overflow > 1)
      element.style.setProperty('--title-overflow', `${overflow}px`)
    }
    const observer = new ResizeObserver(update)
    observer.observe(element)
    if (viewport) observer.observe(viewport)
    element._titleOverflowObserver = observer
    requestAnimationFrame(update)
  },
  updated(element) {
    requestAnimationFrame(() => {
      const viewport = element.parentElement
      const overflow = Math.max(0, element.scrollWidth - element.clientWidth)
      viewport?.classList.toggle('is-overflowing', overflow > 1)
      element.style.setProperty('--title-overflow', `${overflow}px`)
    })
  },
  unmounted(element) {
    element._titleOverflowObserver?.disconnect()
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleEscape)
  void loadWorkspace()
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleEscape)
  activeStreamController?.abort()
  if (streamScrollFrame) cancelAnimationFrame(streamScrollFrame)
  if (toastTimer) window.clearTimeout(toastTimer)
  if (copyTimer) window.clearTimeout(copyTimer)
  clearPendingActionPolling()
  pendingActionRequestVersion += 1
  attachments.value.forEach(revokeAttachmentPreview)
  editDraftBackup?.attachments?.forEach(revokeAttachmentPreview)
})
watch(currentConversationId, () => {
  cancelMessageEdit(false)
  resetPendingActionState()
}, { flush: 'sync' })
watch(() => queryValue(route.query.conversation), id => {
  activeStreamController?.abort()
  mobileSessionsOpen.value = false
  if (workspaceLoaded.value) void applyConversationRoute(id)
})
watch(() => queryValue(route.query.scenario), code => {
  if (!code) return
  const nextScenarioCode = scenarioByCode(code).code
  if (currentConversationId.value && nextScenarioCode !== selectedScenarioCode.value) {
    activeStreamController?.abort()
    selectedScenarioCode.value = nextScenarioCode
    conversationRequestVersion += 1
    currentConversationId.value = ''
    messages.value = []
    void clearConversationQuery()
    return
  }
  selectedScenarioCode.value = nextScenarioCode
})

async function loadWorkspace() {
  loadingKnowledgeBases.value = true
  try {
    const [bases, sessions] = await Promise.all([knowledgeAPI.list(), conversationAPI.list()])
    knowledgeBases.value = bases; conversations.value = sessions
    workspaceLoaded.value = true
    await applyConversationRoute(queryValue(route.query.conversation))
  } catch (error) { notice.value = error.message } finally { loadingKnowledgeBases.value = false }
}
async function changeScenario() {
  conversationRequestVersion += 1
  currentConversationId.value = ''
  messages.value = []
  await closeMobileSessions()
  const query = { ...route.query, scenario: selectedScenarioCode.value }
  delete query.conversation
  await router.replace({ path: route.path, query })
}
async function changeKnowledgeBase() {
  conversationRequestVersion += 1
  messages.value = []; currentConversationId.value = ''
  await clearConversationQuery()
}
async function createConversation() {
  await closeMobileSessions()
  try {
    const session = await conversationAPI.create(selectedKnowledgeBaseId.value, `${selectedScenario.value.shortName} · 新对话`, selectedScenarioCode.value)
    conversations.value = [session, ...conversations.value.filter(item => item.id !== session.id)]
    currentConversationId.value = session.id; messages.value = []
    await setConversationQuery(session.id, true, session.scenarioCode || selectedScenarioCode.value)
    return session
  }
  catch (error) { notice.value = error.message; return null }
}
async function selectConversation(id) {
  const normalizedId = String(id || '').trim()
  if (!normalizedId) return
  await closeMobileSessions()
  if (queryValue(route.query.conversation) === normalizedId) {
    await applyConversationRoute(normalizedId)
    return
  }
  await setConversationQuery(normalizedId)
}
async function applyConversationRoute(id) {
  const requestedId = String(id || '').trim()
  const requestVersion = ++conversationRequestVersion
  if (!requestedId) {
    currentConversationId.value = ''
    messages.value = []
    return
  }
  if (requestedId === currentConversationId.value) {
    await refreshPendingActions(requestedId)
    return
  }
  try {
    const session = await conversationAPI.get(requestedId)
    if (requestVersion !== conversationRequestVersion || queryValue(route.query.conversation) !== requestedId) return
    currentConversationId.value = session.id
    selectedKnowledgeBaseId.value = session.knowledgeBaseId || ''
    selectedScenarioCode.value = session.scenarioCode || selectedScenarioCode.value
    messages.value = session.messages || []
    mergePendingActions(session.pendingActions)
    if (!conversations.value.some(item => item.id === session.id)) conversations.value = [session, ...conversations.value]
    notice.value = ''
    if (queryValue(route.query.scenario) !== selectedScenarioCode.value) {
      const query = { ...route.query, scenario: selectedScenarioCode.value }
      await router.replace({ path: route.path, query })
    }
    await refreshPendingActions(session.id)
    await scrollToBottom()
  } catch (error) {
    if (requestVersion !== conversationRequestVersion) return
    currentConversationId.value = ''
    selectedKnowledgeBaseId.value = ''
    messages.value = []
    resetPendingActionState()
    if (error.status === 400 || error.status === 404) {
      conversations.value = conversations.value.filter(item => item.id !== requestedId)
      notice.value = '该会话不存在或已被删除，已返回空白会话。'
      await clearConversationQuery()
    } else {
      notice.value = `会话加载失败：${error.message}`
    }
  }
}
async function setConversationQuery(id, replace = false, scenarioCode = '') {
  const query = { ...route.query, conversation: id }
  if (scenarioCode) query.scenario = scenarioCode
  if (replace) await router.replace({ path: route.path, query })
  else await router.push({ path: route.path, query })
}
async function clearConversationQuery() {
  if (!route.query.conversation) return
  const query = { ...route.query }
  delete query.conversation
  await router.replace({ path: route.path, query })
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
  } catch { return null }
}

async function confirmPendingAction(action) {
  const actionId = action?.actionId
  if (!actionId || actionBusy[actionId] || String(action.status).toUpperCase() !== 'PENDING') return
  actionBusy[actionId] = 'confirm'
  delete actionErrors[actionId]
  try {
    const updated = await pendingActionAPI.confirm(actionId, action.version)
    upsertPendingAction(updated)
    if (String(updated?.status || '').toUpperCase() === 'SUCCEEDED') {
      const ticketNo = updated?.result?.ticketNo
      showToast('工单已创建', ticketNo ? `服务端已确认工单号 ${ticketNo}。` : '服务端已返回正式工单结果。', 'success')
    } else {
      showToast('动作状态已更新', '服务端尚未返回正式工单成功结果，请刷新状态。', 'error')
    }
  } catch (error) {
    const recovered = await recoverPendingAction(actionId)
    if (String(recovered?.status || '').toUpperCase() === 'SUCCEEDED') {
      const ticketNo = recovered.result?.ticketNo
      showToast('工单已创建', ticketNo ? `已从服务端恢复工单 ${ticketNo} 的成功结果。` : '已从服务端恢复正式工单成功结果。', 'success')
    } else {
      actionErrors[actionId] = error.message
      showToast('确认失败', error.message, 'error')
    }
  } finally {
    delete actionBusy[actionId]
    schedulePendingActionPolling()
  }
}

async function cancelPendingAction(action) {
  const actionId = action?.actionId
  if (!actionId || actionBusy[actionId] || String(action.status).toUpperCase() !== 'PENDING') return
  actionBusy[actionId] = 'cancel'
  delete actionErrors[actionId]
  try {
    const updated = await pendingActionAPI.cancel(actionId, action.version)
    upsertPendingAction(updated)
    if (String(updated?.status || '').toUpperCase() === 'CANCELLED') {
      showToast('草案已取消', '服务端已取消该动作，未创建正式工单。', 'success')
    } else {
      showToast('动作状态已更新', '服务端未返回取消结果，请刷新状态。', 'error')
    }
  } catch (error) {
    const recovered = await recoverPendingAction(actionId)
    if (String(recovered?.status || '').toUpperCase() === 'CANCELLED') {
      showToast('草案已取消', '已从服务端恢复取消结果。', 'success')
    } else {
      actionErrors[actionId] = error.message
      showToast('取消失败', error.message, 'error')
    }
  } finally {
    delete actionBusy[actionId]
    schedulePendingActionPolling()
  }
}

function startRename(session) { mobileSessionsOpen.value = false; renameTarget.value = session; renameTitle.value = session.title || '新对话' }
async function confirmRename() {
  try { const updated = await conversationAPI.rename(renameTarget.value.id, renameTitle.value); const index = conversations.value.findIndex(item => item.id === updated.id); if (index >= 0) conversations.value[index] = updated; renameTarget.value = null }
  catch (error) { notice.value = error.message }
}
function startDelete(session) { mobileSessionsOpen.value = false; deleteTarget.value = session }
async function confirmDelete() {
  deleting.value = true
  try {
    const id = deleteTarget.value.id
    await conversationAPI.remove(id)
    conversations.value = conversations.value.filter(item => item.id !== id)
    if (currentConversationId.value === id) { currentConversationId.value = ''; messages.value = []; await clearConversationQuery() }
    deleteTarget.value = null
    showToast('会话已删除', '该会话及其附件已从历史记录中移除。', 'success')
  }
  catch (error) { showToast('删除失败', error.message, 'error') } finally { deleting.value = false }
}
function startMessageEdit(message, index) {
  if (sending.value || message.role !== 'user') return
  if (editingMessageIndex.value >= 0) cancelMessageEdit()
  editDraftBackup = { input: input.value, attachments: [...attachments.value] }
  editingMessageIndex.value = index
  input.value = message.content || ''
  attachments.value = (message.attachments || []).map(file => ({
    ...file,
    isImage: fileIsImage(file),
    url: fileUrl(file),
    persisted: true
  }))
  nextTick(() => {
    composerTextarea.value?.focus()
    composerTextarea.value?.setSelectionRange(input.value.length, input.value.length)
  })
}
function cancelMessageEdit(restoreDraft = true) {
  if (editingMessageIndex.value < 0) return
  attachments.value.forEach(revokeAttachmentPreview)
  if (restoreDraft && editDraftBackup) {
    input.value = editDraftBackup.input
    attachments.value = editDraftBackup.attachments
  } else {
    editDraftBackup?.attachments?.forEach(revokeAttachmentPreview)
    input.value = ''
    attachments.value = []
  }
  editingMessageIndex.value = -1
  editDraftBackup = null
}
async function resendEditedMessage() {
  const index = editingMessageIndex.value
  const content = input.value.trim()
  const originalMessage = messages.value[index]
  if (!content || sending.value || !currentConversationId.value || originalMessage?.role !== 'user') return
  const targetConversationId = currentConversationId.value
  const requestId = originalMessage.requestId || createRequestId()
  const streamController = new AbortController()
  let assistantMessage = null
  let editedUserMessage = null
  activeStreamController = streamController
  sending.value = true
  uploadingAttachments.value = attachments.value.some(file => Boolean(file.file))
  try {
    const uploaded = []
    for (const item of attachments.value) {
      if (item.persisted && item.id) uploaded.push(item)
      else if (item.file) uploaded.push(await conversationAPI.uploadAttachment(targetConversationId, item.file))
      streamController.signal.throwIfAborted()
    }
    uploadingAttachments.value = false
    editedUserMessage = reactive({ ...originalMessage, content, attachments: uploaded, requestId, createdAt: new Date().toISOString() })
    assistantMessage = reactive({ role: 'assistant', content: '', citations: [], traces: [], requestId, streaming: true, createdAt: new Date().toISOString() })
    messages.value.splice(index, messages.value.length - index,
      editedUserMessage, assistantMessage)
    copiedMessageIndex.value = -1
    cancelMessageEdit(false)
    await scrollToBottom()
    const result = await conversationAPI.regenerateStream(
      targetConversationId,
      index,
      content,
      uploaded.map(item => item.id),
      {
        delta: ({ delta }) => {
          assistantMessage.content += delta || ''
          queueScrollToBottom()
        },
        complete: (completed) => {
          assistantMessage.content = completed.answer || assistantMessage.content
          assistantMessage.citations = completed.citations || []
          assistantMessage.traces = completed.traces || []
          assistantMessage.requestId = requestId
          editedUserMessage.requestId = requestId
          assistantMessage.completed = true
          assistantMessage.streaming = false
          queueScrollToBottom()
        }
      },
      streamController.signal,
      requestId
    )
    assistantMessage.content = result?.answer || assistantMessage.content
    assistantMessage.citations = result?.citations || assistantMessage.citations
    assistantMessage.traces = result?.traces || assistantMessage.traces
    assistantMessage.requestId = requestId
    editedUserMessage.requestId = requestId
    assistantMessage.streaming = false
    try { conversations.value = await conversationAPI.list() }
    catch (refreshError) { notice.value = `回复已重新生成，但会话列表同步失败：${refreshError.message}` }
  } catch (error) {
    if (assistantMessage) assistantMessage.streaming = false
    if ((error.name !== 'AbortError' || manuallyStoppedStreams.has(streamController)) && !assistantMessage?.completed) {
      if (!assistantMessage) notice.value = `消息发送失败：${error.message}`
      else markInterrupted(assistantMessage,
        manuallyStoppedStreams.has(streamController) ? new Error('已手动停止') : error,
        { kind: 'regenerate', userIndex: index, previousCreatedAt: originalMessage.createdAt, requestId })
    }
  } finally {
    if (activeStreamController === streamController) activeStreamController = null
    sending.value = false
    uploadingAttachments.value = false
    await refreshPendingActions(targetConversationId, true)
    await scrollToBottom()
  }
}
async function copyMessage(message, index) {
  const content = String(message.content || '').trim()
  if (!content) return
  try {
    await writeClipboardText(content)
    copiedMessageIndex.value = index
    if (copyTimer) window.clearTimeout(copyTimer)
    copyTimer = window.setTimeout(() => { copiedMessageIndex.value = -1; copyTimer = 0 }, 1800)
  } catch (error) {
    showToast('复制失败', '浏览器未允许访问剪贴板，请手动选择内容复制。', 'error')
  }
}
async function writeClipboardText(content) {
  if (navigator.clipboard?.writeText) {
    try { await navigator.clipboard.writeText(content); return } catch { /* 使用兼容模式 */ }
  }
  const field = document.createElement('textarea')
  field.value = content
  field.setAttribute('readonly', '')
  field.style.position = 'fixed'
  field.style.opacity = '0'
  document.body.appendChild(field)
  field.select()
  const copied = document.execCommand('copy')
  field.remove()
  if (!copied) throw new Error('copy failed')
}
async function sendMessage() {
  const content = input.value.trim(); if (!content || sending.value) return
  if (messages.value.some(message => message.interrupted)) { notice.value = '请先重新生成或同步中断的回答，再发送新消息。'; return }
  if (editingMessageIndex.value >= 0) { await resendEditedMessage(); return }
  const isFirstQuestion = !messages.value.some(message => message.role === 'user')
  sending.value = true
  notice.value = ''
  if (!currentConversationId.value && !await createConversation()) { sending.value = false; return }
  const targetConversationId = currentConversationId.value
  const requestId = createRequestId()
  const streamController = new AbortController()
  let assistantMessage = null
  let userMessage = null
  let userIndex = -1
  activeStreamController = streamController
  uploadingAttachments.value = attachments.value.length > 0
  try {
    const uploaded = []
    for (const item of attachments.value) {
      uploaded.push(await conversationAPI.uploadAttachment(targetConversationId, item.file))
      streamController.signal.throwIfAborted()
    }
    streamController.signal.throwIfAborted()
    uploadingAttachments.value = false
    userIndex = messages.value.length
    userMessage = reactive({ role: 'user', content, citations: [], traces: [], attachments: uploaded, requestId, createdAt: new Date().toISOString() })
    messages.value.push(userMessage)
    assistantMessage = reactive({ role: 'assistant', content: '', citations: [], traces: [], requestId, streaming: true, createdAt: new Date().toISOString() })
    messages.value.push(assistantMessage)
    attachments.value.forEach(revokeAttachmentPreview); attachments.value = []
    input.value = ''; await scrollToBottom()
    const result = await conversationAPI.sendStream(
      targetConversationId,
      content,
      uploaded.map(item => item.id),
      {
        delta: ({ delta }) => {
          assistantMessage.content += delta || ''
          queueScrollToBottom()
        },
        complete: (completed) => {
          assistantMessage.content = completed.answer || assistantMessage.content
          assistantMessage.citations = completed.citations || []
          assistantMessage.traces = completed.traces || []
          assistantMessage.requestId = requestId
          userMessage.requestId = requestId
          assistantMessage.completed = true
          assistantMessage.streaming = false
          queueScrollToBottom()
        }
      },
      streamController.signal,
      requestId
    )
    assistantMessage.content = result?.answer || assistantMessage.content
    assistantMessage.citations = result?.citations || assistantMessage.citations
    assistantMessage.traces = result?.traces || assistantMessage.traces
    assistantMessage.requestId = requestId
    userMessage.requestId = requestId
    assistantMessage.streaming = false
    try {
      conversations.value = await conversationAPI.list()
      if (isFirstQuestion) {
        const refreshedSession = await conversationAPI.get(targetConversationId)
        const sessionIndex = conversations.value.findIndex(item => item.id === refreshedSession.id)
        if (sessionIndex >= 0) conversations.value[sessionIndex] = { ...conversations.value[sessionIndex], ...refreshedSession }
      }
    } catch (refreshError) { notice.value = `回答已生成，但会话名称同步失败：${refreshError.message}` }
  } catch (error) {
    if (error.name === 'AbortError') {
      if (assistantMessage && manuallyStoppedStreams.has(streamController) && !assistantMessage.completed) {
        markInterrupted(assistantMessage, new Error('已手动停止'), { kind: 'send', userIndex, requestId })
      } else if (assistantMessage) assistantMessage.streaming = false
    } else if (!assistantMessage?.completed) {
      if (assistantMessage) markInterrupted(assistantMessage, error, { kind: 'send', userIndex, requestId })
      else notice.value = `消息发送失败：${error.message}`
    }
  }
  finally {
    if (activeStreamController === streamController) activeStreamController = null
    sending.value = false
    uploadingAttachments.value = false
    await refreshPendingActions(targetConversationId, true)
    await scrollToBottom()
  }
}
function markInterrupted(message, error, retry) {
  message.streaming = false
  message.interrupted = true
  message.interruptionMessage = error.message || '连接已断开'
  message.retry = retry
  notice.value = error.message === '已手动停止'
    ? '已停止生成。可重新生成或同步会话后继续。'
    : '回答已中断，可在该回答下方重新生成或同步会话。'
}
function stopGeneration() {
  const controller = activeStreamController
  if (!controller || controller.signal.aborted || !hasStreamingMessage.value) return
  manuallyStoppedStreams.add(controller)
  controller.abort()
}
async function discardInterruptedAnswer() {
  if (sending.value || !currentConversationId.value) return
  sending.value = true
  try {
    const session = await conversationAPI.get(currentConversationId.value)
    messages.value = session.messages || []
    await refreshPendingActions(currentConversationId.value, true)
    notice.value = '已同步服务端会话记录。'
  } catch (error) {
    notice.value = `会话同步失败：${error.message}`
  } finally {
    sending.value = false
    await scrollToBottom()
  }
}
async function retryInterruptedAnswer(index) {
  const assistant = messages.value[index]
  const retry = assistant?.retry
  const user = messages.value[retry?.userIndex]
  if (sending.value || !retry || user?.role !== 'user' || !currentConversationId.value) return
  const conversationId = currentConversationId.value
  const requestId = retry.requestId || user.requestId || assistant.requestId || createRequestId()
  sending.value = true
  notice.value = ''
  let streamStarted = false
  const controller = new AbortController()
  try {
    const session = await conversationAPI.get(conversationId)
    const savedMessages = session.messages || []
    const savedUser = savedMessages[retry.userIndex]
    const alreadySaved = retry.kind === 'send'
      ? savedMessages.length !== retry.userIndex
      : !savedUser || savedUser.createdAt !== retry.previousCreatedAt
    if (alreadySaved) {
      messages.value = savedMessages
      await refreshPendingActions(conversationId, true)
      notice.value = '已从服务端恢复这轮会话，无需重复发送。'
      return
    }
    if (index !== messages.value.length - 1) {
      notice.value = '只能重新生成当前会话最后一轮中断的回答。'
      return
    }
    streamStarted = true
    activeStreamController = controller
    assistant.content = ''
    assistant.citations = []
    assistant.traces = []
    assistant.interrupted = false
    assistant.streaming = true
    assistant.requestId = requestId
    user.requestId = requestId
    const attachmentIds = (user.attachments || []).map(item => item.id)
    const handlers = {
      delta: ({ delta }) => { assistant.content += delta || ''; queueScrollToBottom() },
      complete: (completed) => {
        assistant.content = completed.answer || assistant.content
        assistant.citations = completed.citations || []
        assistant.traces = completed.traces || []
        assistant.requestId = requestId
        user.requestId = requestId
        assistant.completed = true
        assistant.streaming = false
        queueScrollToBottom()
      }
    }
    const result = retry.kind === 'regenerate'
      ? await conversationAPI.regenerateStream(conversationId, retry.userIndex, user.content, attachmentIds, handlers, controller.signal, requestId)
      : await conversationAPI.sendStream(conversationId, user.content, attachmentIds, handlers, controller.signal, requestId)
    assistant.content = result?.answer || assistant.content
    assistant.citations = result?.citations || assistant.citations
    assistant.traces = result?.traces || assistant.traces
    assistant.requestId = requestId
    user.requestId = requestId
    assistant.completed = true
    assistant.streaming = false
    assistant.retry = null
    try { conversations.value = await conversationAPI.list() }
    catch (error) { notice.value = `回答已生成，但会话列表同步失败：${error.message}` }
  } catch (error) {
    if (streamStarted) {
      assistant.streaming = false
      if ((error.name !== 'AbortError' || manuallyStoppedStreams.has(controller)) && !assistant.completed) {
        markInterrupted(assistant,
          manuallyStoppedStreams.has(controller) ? new Error('已手动停止') : error, retry)
      }
    } else {
      notice.value = `重试前核对会话失败：${error.message}`
    }
  } finally {
    if (activeStreamController === controller) activeStreamController = null
    sending.value = false
    await refreshPendingActions(conversationId, true)
    await scrollToBottom()
  }
}
const ATTACHMENT_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'webp', 'gif', 'pdf', 'txt', 'md', 'markdown'])
const CLIPBOARD_MIME_EXTENSIONS = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp', 'image/gif': 'gif', 'application/pdf': 'pdf', 'text/plain': 'txt' }

function addAttachments(files) {
  if (sending.value || !files.length) return
  if (attachments.value.length + files.length > 10) { notice.value = '单次最多添加 10 个附件。'; return }
  const prepared = files.map(file => {
    const hasExtension = /\.[^.]+$/.test(file.name)
    const extension = hasExtension ? file.name.split('.').pop().toLowerCase() : CLIPBOARD_MIME_EXTENSIONS[file.type]
    if (!ATTACHMENT_EXTENSIONS.has(extension)) return null
    if (hasExtension) return file
    return new File([file], `${file.name || '粘贴文件'}.${extension}`, { type: file.type, lastModified: file.lastModified })
  })
  if (prepared.some(file => !file)) { notice.value = '仅支持 PNG、JPG、WEBP、GIF、PDF、TXT 和 Markdown 文件。'; return }
  if (prepared.some(file => !file.size || file.size > 20 * 1024 * 1024)) { notice.value = '附件不能为空，且单个文件不能超过 20 MB。'; return }
  attachments.value.push(...prepared.map(file => ({ file, name: file.name, size: file.size, isImage: /\.(png|jpe?g|webp|gif)$/i.test(file.name), url: URL.createObjectURL(file) })))
  notice.value = ''
}
function selectAttachments(event) {
  addAttachments(Array.from(event.target.files || []))
  event.target.value = ''
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
function revokeAttachmentPreview(file) { if (file?.file && file.url?.startsWith('blob:')) URL.revokeObjectURL(file.url) }
function removeAttachment(index) { const [file] = attachments.value.splice(index, 1); if (file) revokeAttachmentPreview(file) }
function knowledgeBaseName(id) { return knowledgeBases.value.find(item => item.id === id)?.name || '知识问答' }
function conversationMeta(session) {
  const scenarioName = scenarioByCode(session.scenarioCode || 'general').shortName
  const knowledgeName = session.knowledgeBaseId ? knowledgeBaseName(session.knowledgeBaseId) : '普通对话'
  return `${scenarioName} · ${knowledgeName}`
}
function readCollapsedConversationGroups() {
  try {
    const stored = JSON.parse(localStorage.getItem('conversation-collapsed-groups') || '[]')
    return Array.isArray(stored) ? stored.filter(label => typeof label === 'string') : []
  } catch {
    return []
  }
}
function toggleConversationGroup(label) {
  const next = new Set(collapsedConversationGroups.value)
  if (next.has(label)) next.delete(label)
  else next.add(label)
  collapsedConversationGroups.value = next
  localStorage.setItem('conversation-collapsed-groups', JSON.stringify([...next]))
}
function conversationDateGroup(value) {
  if (!value) return '更早'
  const target = new Date(value)
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  target.setHours(0, 0, 0, 0)
  const difference = Math.floor((today - target) / 86400000)
  if (difference <= 0) return '今天'
  if (difference === 1) return '昨天'
  if (difference < 7) return '过去 7 天'
  if (difference < 30) return '过去 30 天'
  return '更早'
}
function setHistoryScope(scope) {
  historyScope.value = scope
  localStorage.setItem('conversation-history-scope', scope)
}
function toggleSessionSidebar() {
  sessionSidebarCollapsed.value = !sessionSidebarCollapsed.value
  localStorage.setItem('conversation-sessions-collapsed', String(sessionSidebarCollapsed.value))
}
function toggleContextSidebar() {
  contextSidebarCollapsed.value = !contextSidebarCollapsed.value
  localStorage.setItem('conversation-context-collapsed', String(contextSidebarCollapsed.value))
}
function showToast(title, message, type = 'success') {
  if (toastTimer) window.clearTimeout(toastTimer)
  toast.value = { title, message, type }
  toastTimer = window.setTimeout(() => { toast.value = null; toastTimer = 0 }, 3600)
}
function dismissToast() {
  if (toastTimer) window.clearTimeout(toastTimer)
  toastTimer = 0
  toast.value = null
}
const messageTimeFormatter = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
const messageDateTimeFormatter = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
function validMessageDate(value) { const date = new Date(value); return Number.isNaN(date.getTime()) ? null : date }
function formatMessageTime(value) { const date = validMessageDate(value); return date ? messageTimeFormatter.format(date) : '' }
function formatMessageDateTime(value) { const date = validMessageDate(value); return date ? messageDateTimeFormatter.format(date) : '' }
function formatSize(value) { return value < 1024 * 1024 ? `${Math.max(1, Math.round(value / 1024))} KB` : `${(value / 1024 / 1024).toFixed(1)} MB` }
function tracePhaseLabel(phase) { return ({ scope: '范围', retrieval: '检索', tool: '工具', model: '模型', complete: '完成' })[phase] || '链路' }
function fileIsImage(file) { return Boolean(file.image ?? file.isImage) }
function fileUrl(file) { const url = file.contentUrl || file.url || ''; return url.startsWith('/') ? `${BASE_URL}${url}` : url }
function queryValue(value) { return Array.isArray(value) ? String(value[0] || '') : String(value || '') }
async function openMobileSessions() {
  mobileSessionsOpen.value = true
  await nextTick()
  mobileDrawerClose.value?.focus()
}
async function closeMobileSessions(restoreFocus = true) {
  const wasOpen = mobileSessionsOpen.value
  mobileSessionsOpen.value = false
  if (wasOpen && restoreFocus) {
    await nextTick()
    mobileSessionTrigger.value?.focus()
  }
}
function handleEscape(event) {
  if (event.key !== 'Escape') return
  if (deleteTarget.value && !deleting.value) { event.preventDefault(); deleteTarget.value = null; return }
  if (renameTarget.value) { event.preventDefault(); renameTarget.value = null; return }
  if (previewImage.value) { event.preventDefault(); previewImage.value = ''; return }
  if (editingMessageIndex.value >= 0) { event.preventDefault(); cancelMessageEdit(); return }
  if (mobileSessionsOpen.value) { event.preventDefault(); void closeMobileSessions() }
}
async function scrollToBottom() { await nextTick(); if (messageArea.value) messageArea.value.scrollTop = messageArea.value.scrollHeight }
function queueScrollToBottom() {
  if (streamScrollFrame) return
  streamScrollFrame = requestAnimationFrame(() => {
    streamScrollFrame = 0
    if (messageArea.value) messageArea.value.scrollTop = messageArea.value.scrollHeight
  })
}
</script>

<style scoped lang="scss">
.pending-action-stack{max-width:920px;margin:0 auto 8px}.pending-action-stack>header{width:min(820px,calc(100% - 52px));display:flex;align-items:center;justify-content:space-between;margin:0 auto 9px;color:var(--text-muted);font-size:11px;font-weight:750}.pending-action-stack>header small{color:var(--text-soft);font-size:9px;font-weight:600}@media(max-width:760px){.pending-action-stack>header{width:100%}}
.conversation-workspace{--session-panel-width:286px;--context-panel-width:330px;position:relative;height:calc(100dvh - 68px);min-height:0;display:grid;grid-template-columns:var(--session-panel-width) minmax(480px,1fr) var(--context-panel-width);background:var(--surface);overflow:hidden;transition:grid-template-columns .22s cubic-bezier(.2,.8,.2,1)}.conversation-workspace.sessions-collapsed{--session-panel-width:48px}.conversation-workspace.context-collapsed{--context-panel-width:48px}.session-sidebar,.context-sidebar{min-width:0;min-height:0;background:var(--surface-subtle)}.session-sidebar{display:flex;flex-direction:column;padding:18px 14px;border-right:1px solid var(--border-color)}.session-heading{display:flex;align-items:center;justify-content:space-between;padding:0 5px 14px}#conversation-drawer-title{font-size:15px;font-weight:750}.session-heading-actions{display:flex;align-items:center;gap:4px}.session-heading-actions button{width:34px;height:34px;display:grid;place-items:center;color:#fff;background:var(--primary);border:0;border-radius:8px}.session-heading-actions .drawer-close{display:none;color:var(--text-muted);background:transparent;border:1px solid var(--border-color)}.session-heading svg{width:18px}.session-search{display:flex;align-items:center;gap:8px;padding:0 10px;background:var(--surface);border:1px solid var(--border-color);border-radius:8px}.session-search svg{width:17px;color:var(--text-soft)}.session-search input{width:100%;height:40px;color:var(--text-color);background:transparent;border:0;outline:0;font-size:13px}.session-list{min-height:0;flex:1;overflow:auto}.session-group>article{width:100%;display:grid;grid-template-columns:minmax(0,1fr) 58px;align-items:stretch;overflow:hidden;color:var(--text-muted);border-radius:8px}.session-group>article:hover{background:var(--surface)}.session-group>article.active{color:var(--primary);background:var(--primary-soft)}.session-open{min-width:0;display:grid;grid-template-columns:19px minmax(0,1fr);gap:9px;align-items:start;padding:11px 8px 11px 10px;color:inherit;background:transparent;border:0;border-radius:8px;text-align:left}.session-open:focus-visible{outline:2px solid var(--primary);outline-offset:-2px}.session-icon{width:18px;margin-top:2px}.session-copy{min-width:0}.title-viewport{width:100%;display:block;overflow:hidden;white-space:nowrap}.title-track{display:block;width:100%;overflow:hidden;font-size:13px;text-overflow:ellipsis;white-space:nowrap}.title-viewport.is-overflowing:hover .title-track,.session-open:focus-visible .title-viewport.is-overflowing .title-track{overflow:visible;text-overflow:clip;animation:titleMarquee 6s ease-in-out .65s infinite alternate}.session-copy small{display:block;margin-top:5px;overflow:hidden;color:var(--text-soft);font-size:11px;text-overflow:ellipsis;white-space:nowrap}.session-actions{width:58px;display:flex;align-items:center;justify-content:flex-end;gap:2px;align-self:center;padding-left:4px;border-left:1px solid color-mix(in srgb,var(--border-color) 78%,transparent)}.session-actions button{width:26px;height:26px;display:grid;place-items:center;color:var(--text-soft);background:transparent;border:0;border-radius:5px;transition:color .16s ease,background .16s ease}.session-list article:hover .session-actions button,.session-list article.active .session-actions button{color:var(--text-muted)}.session-actions button:hover{color:var(--primary)!important;background:var(--surface)}.session-actions button:last-child:hover{color:var(--danger)!important;background:#fff0ed}.session-actions button:focus-visible{outline:2px solid var(--primary);outline-offset:1px}.session-actions svg{width:15px}.empty-list{min-height:180px;display:grid;place-content:center;justify-items:center;gap:7px;padding:28px 8px;color:var(--text-soft);font-size:12px;text-align:center}.empty-list svg{width:23px}.empty-list strong{color:var(--text-muted);font-size:13px}.mobile-session-backdrop{display:none}
.session-sidebar-content,.context-sidebar-content{min-width:0;min-height:0;height:100%}.session-sidebar-content{display:flex;flex-direction:column}.collapsed-rail{display:none}.session-sidebar.collapsed,.context-sidebar.collapsed{padding:8px 6px}.session-sidebar.collapsed .session-sidebar-content,.context-sidebar.collapsed .context-sidebar-content{display:none}.session-sidebar.collapsed .collapsed-rail,.context-sidebar.collapsed .collapsed-rail{width:100%;display:flex;flex-direction:column;align-items:center;gap:8px;padding:10px 2px;color:var(--text-soft);background:transparent;border:0;border-radius:7px;font-size:10px;letter-spacing:.08em}.session-sidebar.collapsed .collapsed-rail:hover,.context-sidebar.collapsed .collapsed-rail:hover{color:var(--primary);background:var(--primary-soft)}.collapsed-rail svg{width:19px}.history-scope{display:grid;grid-template-columns:1fr 1fr;gap:3px;margin-top:10px;padding:3px;background:var(--surface-strong);border-radius:8px}.history-scope button{min-height:30px;padding:0 7px;color:var(--text-soft);background:transparent;border:0;border-radius:6px;font-size:11px;font-weight:700}.history-scope button:hover{color:var(--text-color)}.history-scope button.active{color:var(--text-color);background:var(--surface);box-shadow:0 1px 3px rgba(23,32,51,.1)}.history-summary{display:flex;align-items:center;justify-content:space-between;padding:15px 6px 7px;color:var(--text-soft);font-size:10px}.history-summary small{min-width:18px;text-align:right}.session-group{margin:0}.session-group+.session-group{margin-top:13px}.session-group h3{display:flex;align-items:center;justify-content:space-between;margin:0;padding:7px 7px 4px;color:var(--text-soft);font-size:10px;font-weight:700}.session-group h3 span{font-weight:inherit}.session-group h3 small{font-size:9px;font-weight:600}.panel-toggle{position:absolute;top:50%;z-index:12;width:22px;height:48px;display:grid;place-items:center;padding:0;opacity:0;color:var(--text-soft);background:color-mix(in srgb,var(--surface) 82%,transparent);border:1px solid transparent;border-radius:999px;box-shadow:none;backdrop-filter:blur(8px);transform:translateY(-50%);transition:left .22s cubic-bezier(.2,.8,.2,1),right .22s cubic-bezier(.2,.8,.2,1),opacity .16s ease,color .16s ease,background-color .16s ease,border-color .16s ease}.panel-toggle:hover,.panel-toggle:focus-visible,.session-sidebar:hover+.session-panel-toggle,.context-sidebar:hover+.context-panel-toggle{opacity:1}.panel-toggle:hover,.panel-toggle:focus-visible{color:var(--primary);background:color-mix(in srgb,var(--surface) 96%,transparent);border-color:color-mix(in srgb,var(--primary) 22%,var(--border-color))}.panel-toggle:focus-visible{outline:2px solid color-mix(in srgb,var(--primary) 38%,transparent);outline-offset:2px}.panel-toggle svg{width:14px;height:26px}.session-panel-toggle{left:calc(var(--session-panel-width) - 11px)}.context-panel-toggle{right:calc(var(--context-panel-width) - 11px)}
.conversation-workspace.sessions-collapsed .session-panel-toggle{background:transparent;border-color:transparent;backdrop-filter:none}.conversation-workspace.sessions-collapsed .session-panel-toggle::before{content:"";position:absolute;inset:0 0 0 50%;background:color-mix(in srgb,var(--surface) 88%,transparent);border:1px solid color-mix(in srgb,var(--border-color) 82%,transparent);border-left:0;border-radius:0 999px 999px 0;backdrop-filter:blur(8px);transition:background-color .16s ease,border-color .16s ease}.conversation-workspace.sessions-collapsed .session-panel-toggle:hover,.conversation-workspace.sessions-collapsed .session-panel-toggle:focus-visible{background:transparent;border-color:transparent}.conversation-workspace.sessions-collapsed .session-panel-toggle:hover::before,.conversation-workspace.sessions-collapsed .session-panel-toggle:focus-visible::before{background:color-mix(in srgb,var(--surface) 98%,transparent);border-color:color-mix(in srgb,var(--primary) 22%,var(--border-color))}.conversation-workspace.sessions-collapsed .session-panel-toggle svg{position:relative;z-index:1;width:8px;transform:translateX(5px)}.context-sidebar.collapsed .collapsed-rail{padding-inline:0;letter-spacing:0}.context-sidebar.collapsed .collapsed-rail span{display:block;white-space:nowrap}
.conversation-surface{min-width:0;min-height:0;height:100%;display:grid;grid-template-rows:auto minmax(0,1fr) auto auto auto;overflow:hidden;background:var(--surface)}.conversation-toolbar{min-height:72px;display:flex;align-items:center;gap:14px;padding:10px 22px;border-bottom:1px solid var(--border-color)}.mobile-session-trigger{display:none}.conversation-toolbar label{min-width:200px}.conversation-toolbar label>span{display:block;margin-bottom:5px;color:var(--text-soft);font-size:11px;font-weight:750;letter-spacing:.08em}.conversation-toolbar select{width:100%;padding:8px 32px 8px 10px;color:var(--text-color);background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:7px;font-size:13px}.mode-state{display:flex;align-items:center;gap:8px;margin-left:auto;color:var(--text-muted);font-size:12px;white-space:nowrap}.mode-state>span{width:8px;height:8px;border-radius:50%;background:var(--text-soft)}.mode-state>span.rag{background:var(--success);box-shadow:0 0 0 3px var(--success-soft)}
.messages{min-height:0;overflow-y:auto;overscroll-behavior:contain;padding:30px clamp(24px,4vw,64px)}.conversation-empty{min-height:100%;display:grid;align-content:center;justify-items:center;text-align:center}.mode-label{padding:5px 9px;color:var(--primary);background:var(--primary-soft);border-radius:5px;font-size:12px;font-weight:700}.conversation-empty h1{margin:18px 0 10px;font-size:clamp(28px,2vw,36px);letter-spacing:-.035em}.conversation-empty>p{max-width:620px;margin:0;color:var(--text-muted);font-size:14px;line-height:1.75}.starter-grid{width:min(760px,100%);display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:28px}.starter-grid button{display:flex;align-items:center;gap:8px;min-height:58px;padding:12px;color:var(--text-muted);background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:9px;text-align:left;font-size:13px}.starter-grid button:hover{color:var(--primary);border-color:color-mix(in srgb,var(--primary) 40%,var(--border-color))}.starter-grid svg{width:17px;color:var(--primary)}
.message{max-width:920px;display:grid;grid-template-columns:40px minmax(0,1fr);gap:12px;margin:0 auto 28px}.message.user{grid-template-columns:minmax(0,1fr) 40px}.message.user .avatar{grid-column:2}.message.user .message-body{grid-row:1;grid-column:1;justify-self:end}.message.user .message-body.editing{width:min(620px,92%)}.avatar{width:40px;height:40px;display:grid;place-items:center;color:var(--primary);background:var(--primary-soft);border:1px solid color-mix(in srgb,var(--primary) 12%,var(--border-color));border-radius:50%}.avatar svg{width:20px;height:20px;stroke-width:1.8}.user .avatar{color:#fff;background:var(--primary);border-color:var(--primary)}.message-body{min-width:0;max-width:92%}.message-meta{display:flex;align-items:center;gap:8px;margin-bottom:7px}.user .message-meta{justify-content:flex-end}.message-meta strong{font-size:13px}.message-meta span,.message-meta time{color:var(--text-soft);font-size:11px}.message-meta time{font-variant-numeric:tabular-nums}.message-meta time::before{content:"·";margin-right:8px}.bubble{padding:14px 16px;background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:4px 12px 12px 12px}.user .bubble{color:#fff;background:var(--primary);border:0;border-radius:12px 4px 12px 12px}.message-content{font-size:14px;line-height:1.75;overflow-wrap:anywhere}.message-content :deep(p){margin:0 0 9px}.message-content :deep(p:last-child){margin-bottom:0}.message-content :deep(table){width:100%;margin:12px 0;border-collapse:collapse;font-size:13px}.message-content :deep(th),.message-content :deep(td){padding:8px 9px;border:1px solid var(--border-color);text-align:left}.message-content :deep(th){background:var(--primary-soft)}.message-content :deep(ul),.message-content :deep(ol){padding-left:22px}.user .message-content :deep(a){color:#fff}.message-actions{display:flex;align-items:center;gap:3px;margin-top:7px;opacity:.72;transition:opacity .16s ease}.message:hover .message-actions,.message:focus-within .message-actions{opacity:1}.user .message-actions{justify-content:flex-end}.message-actions button{min-height:30px;display:flex;align-items:center;gap:5px;padding:0 9px;color:var(--text-soft);background:transparent;border:1px solid transparent;border-radius:7px;font-size:11px;transition:color .16s ease,background-color .16s ease,border-color .16s ease}.message-actions button:hover{color:var(--primary);background:var(--surface-subtle);border-color:var(--border-color)}.message-actions button:focus-visible{outline:2px solid color-mix(in srgb,var(--primary) 38%,transparent);outline-offset:1px}.message-actions button:disabled{opacity:.45;cursor:not-allowed}.message-actions button.copied{color:var(--success)}.message-actions svg{width:15px;height:15px}.message-editor{width:100%;padding:10px;background:var(--surface);border:1px solid color-mix(in srgb,var(--primary) 38%,var(--border-color));border-radius:12px;box-shadow:0 8px 22px rgba(23,32,51,.08)}.message-editor textarea{width:100%;min-height:84px;resize:vertical;padding:10px 11px;color:var(--text-color);background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:8px;outline:0;font:inherit;font-size:14px;line-height:1.65}.message-editor textarea:focus{border-color:color-mix(in srgb,var(--primary) 55%,var(--border-color));box-shadow:0 0 0 3px color-mix(in srgb,var(--primary) 10%,transparent)}.message-editor-foot{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:8px}.message-editor-foot>span:first-child{color:var(--text-soft);font-size:10px}.message-editor-foot>span:last-child{display:flex;gap:7px}.message-editor button{min-height:30px;padding:0 11px;color:var(--text-muted);background:var(--surface);border:1px solid var(--border-color);border-radius:7px;font-size:11px}.message-editor button.primary{color:#fff;background:var(--primary);border-color:var(--primary)}.message-editor button:disabled{opacity:.5}
.message-attachments{display:flex;flex-wrap:wrap;gap:8px;margin-top:11px}.message-attachments .image-only{width:58px;height:58px;overflow:hidden;padding:0;background:rgba(255,255,255,.1);border:1px solid currentColor;border-radius:7px}.message-attachments .image-only img{width:100%;height:100%;object-fit:cover}.message-attachments a{max-width:220px;display:flex;align-items:center;gap:7px;padding:8px;color:inherit;border:1px solid currentColor;border-radius:7px;text-decoration:none}.message-attachments a svg{width:19px;flex:none}.message-attachments a span{overflow:hidden;font-size:12px;text-overflow:ellipsis;white-space:nowrap}.bubble.streaming{position:relative}.stream-waiting{display:flex;align-items:center;gap:5px;color:var(--text-muted);font-size:12px}.stream-waiting i,.typing i{display:inline-block;width:6px;height:6px;border-radius:50%;background:var(--text-soft);animation:pulse 1s infinite alternate}.stream-waiting i:nth-child(2),.typing i:nth-child(2){animation-delay:.2s}.stream-waiting i:nth-child(3),.typing i:nth-child(3){animation-delay:.4s}.stream-waiting span{margin-left:4px}.stream-caret{width:2px;height:1.05em;display:inline-block;margin-left:3px;vertical-align:-.12em;background:var(--primary);animation:streamBlink .8s steps(1,end) infinite}.typing i{margin:0 3px}.trace-panel{margin-top:14px;overflow:hidden;color:var(--text-color);background:var(--surface);border:1px solid var(--border-color);border-radius:9px}.trace-panel>summary{display:flex;justify-content:space-between;padding:10px 12px;cursor:pointer;font-size:12px}.trace-panel>summary>span{display:flex;align-items:center;gap:7px;font-weight:700}.trace-panel>summary svg{width:16px}.trace-panel>summary small{color:var(--text-soft)}.trace-list{margin:0;padding:2px 12px 12px;list-style:none}.trace-list li{position:relative;display:grid;grid-template-columns:11px 1fr;gap:9px;padding:9px 0}.trace-list li:not(:last-child)::after{content:"";position:absolute;left:3px;top:20px;bottom:-6px;width:1px;background:var(--border-color)}.trace-dot{width:8px;height:8px;margin-top:5px;border-radius:50%;background:var(--success);z-index:1}.trace-list li.skipped .trace-dot{background:var(--text-soft)}.trace-list li.failed .trace-dot{background:var(--danger)}.trace-heading{display:flex;align-items:center;gap:7px}.trace-heading strong{font-size:12px}.trace-heading time{margin-left:auto;color:var(--text-soft);font-size:11px}.phase-badge{padding:2px 6px;color:var(--primary);background:var(--primary-soft);border-radius:4px;font-size:10px}.trace-list p{margin:5px 0 0;color:var(--text-muted);font-size:12px;line-height:1.6}.citations{margin-top:13px;padding-top:12px;color:var(--text-color);border-top:1px solid var(--border-color);font-size:12px}.citations>strong{color:var(--text-muted)}.citations details{margin-top:7px}.citations summary{color:var(--primary);cursor:pointer}.citations p{color:var(--text-muted);line-height:1.6}
.notice{display:flex;justify-content:space-between;gap:10px;margin:0 20px 9px;padding:10px 12px;color:var(--warning);background:#fff7ed;border:1px solid #fed7aa;border-radius:7px;font-size:12px}.notice button{color:inherit;background:transparent;border:0}.attachment-tray{display:flex;gap:8px;overflow-x:auto;padding:9px 20px 0}.attachment-tray>div{position:relative;min-width:210px;display:grid;grid-template-columns:38px 1fr 22px;gap:8px;align-items:center;padding:7px;background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:8px}.attachment-tray>div.image-item{min-width:58px;width:58px;height:58px;display:block;padding:4px}.pending-image{width:48px;height:48px;display:block;overflow:hidden;padding:0;background:transparent;border:0;border-radius:6px}.pending-image img{width:100%;height:100%;object-fit:cover}.attachment-tray>div>svg{width:22px}.attachment-tray span{min-width:0}.attachment-tray strong,.attachment-tray small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.attachment-tray strong{font-size:12px}.attachment-tray small{margin-top:3px;color:var(--text-soft);font-size:10px}.remove-attachment{color:var(--text-soft);background:transparent;border:0}.remove-attachment svg{width:16px}.image-item .remove-attachment{position:absolute;right:-5px;top:-6px;width:19px;height:19px;display:grid;place-items:center;padding:0;color:#fff;background:#26334a;border-radius:50%;box-shadow:0 2px 6px rgba(0,0,0,.2)}.image-item .remove-attachment svg{width:12px}
.composer{position:relative;z-index:2;display:grid;grid-template-columns:38px minmax(0,1fr) 40px;gap:9px;margin:11px 20px 18px;padding:9px 9px 27px;background:var(--surface);border:1px solid var(--border-color);border-radius:11px;box-shadow:0 8px 24px rgba(23,32,51,.07)}.file-input{display:none}.attach-button,.send-button{height:38px;display:grid;place-items:center;border-radius:8px}.attach-button{color:var(--text-muted);background:var(--surface-subtle);border:1px solid var(--border-color)}.send-button{color:#fff;background:var(--primary);border:0}.send-button:disabled{opacity:.4}.composer button svg{width:19px}.composer textarea{min-width:0;min-height:38px;max-height:120px;resize:none;padding:8px 2px;color:var(--text-color);background:transparent;border:0;outline:0;font-size:14px}.composer-foot{position:absolute;left:57px;right:11px;bottom:7px;display:flex;justify-content:space-between;color:var(--text-soft);font-size:10px}
.context-sidebar{overflow-y:auto;padding:19px;border-left:1px solid var(--border-color)}.context-sidebar-content>header{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:21px}.context-sidebar-content>header span{min-width:0;font-size:14px;font-weight:750;white-space:nowrap}.context-sidebar-content>header a{flex:none;color:var(--primary);font-size:12px;text-decoration:none;white-space:nowrap}.context-sidebar section{padding:17px 0;border-top:1px solid var(--border-color)}.context-sidebar section:first-of-type{padding-top:0;border-top:0}.context-sidebar label{display:block;margin-bottom:9px;color:var(--text-soft);font-size:11px;font-weight:750;letter-spacing:.09em}.context-sidebar h2{margin:0 0 7px;font-size:17px}.context-sidebar p{margin:0;color:var(--text-muted);font-size:12px;line-height:1.68}.process-list{margin:0;padding:0;list-style:none}.process-list li{display:flex;align-items:center;gap:9px;padding:7px 0;font-size:12px}.process-list span{width:22px;height:22px;display:grid;place-items:center;color:var(--primary);background:var(--primary-soft);border-radius:5px;font-size:10px}.tool-list{display:flex;flex-wrap:wrap;gap:6px}.tool-list span{display:flex;align-items:center;gap:5px;padding:6px 7px;color:var(--text-muted);background:var(--surface);border:1px solid var(--border-color);border-radius:5px;font-size:11px}.tool-list svg{width:14px}.knowledge-context{display:flex;gap:9px;align-items:flex-start}.knowledge-context>svg{width:20px;color:var(--primary)}.knowledge-context strong,.knowledge-context small{display:block}.knowledge-context strong{font-size:12px}.knowledge-context small{margin-top:4px;color:var(--text-soft);font-size:11px}.manage-knowledge{display:inline-block;margin-top:11px;color:var(--primary);font-size:11px;text-decoration:none}.context-sidebar section.guardrail{display:flex;gap:9px;padding:14px;color:var(--success);background:var(--success-soft);border:0;border-radius:8px}.guardrail>svg{width:20px;flex:none}.guardrail label{color:inherit}.guardrail p{color:var(--text-color)}
.image-preview{position:fixed;inset:0;z-index:210;display:grid;place-items:center;padding:40px;background:rgba(6,10,17,.86);backdrop-filter:blur(8px)}.image-preview img{max-width:min(1200px,92vw);max-height:86vh;border-radius:9px;box-shadow:var(--shadow-float)}.image-preview>button{position:fixed;top:24px;right:24px;width:42px;height:42px;display:grid;place-items:center;color:#fff;background:rgba(255,255,255,.12);border:1px solid rgba(255,255,255,.24);border-radius:8px}.image-preview svg{width:22px}.modal{position:fixed;inset:0;z-index:220;display:grid;place-items:center;padding:20px;background:rgba(6,10,17,.58);backdrop-filter:blur(5px)}.dialog{width:min(450px,100%);padding:26px;background:var(--surface);border-radius:12px;box-shadow:var(--shadow-float)}.dialog-eyebrow{color:var(--primary);font-size:11px;font-weight:750;letter-spacing:.1em}.dialog h2{margin:7px 0 20px;font-size:23px}.dialog label{display:grid;gap:7px;color:var(--text-muted);font-size:13px;font-weight:650}.dialog input{height:42px;padding:0 11px;color:var(--text-color);background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:8px;outline:0}.dialog-actions{display:flex;justify-content:flex-end;gap:9px;margin-top:22px}.dialog-actions button{padding:9px 15px;color:var(--text-color);background:var(--surface);border:1px solid var(--border-color);border-radius:8px}.dialog-actions button.primary{color:#fff;background:var(--primary);border-color:var(--primary)}.dialog-actions button.danger{color:#fff;background:var(--danger);border-color:var(--danger)}.danger-dialog{text-align:center}.danger-icon{width:46px;height:46px;display:grid;place-items:center;margin:0 auto;color:var(--danger);background:#fff0ed;border-radius:50%}.danger-icon svg{width:22px}.danger-dialog h2{margin:14px 0 8px}.danger-dialog p{margin:0;color:var(--text-muted);font-size:13px;line-height:1.65}.danger-dialog .dialog-actions{justify-content:center}
.conversation-toast{position:fixed;z-index:240;top:84px;right:22px;width:min(360px,calc(100vw - 32px));display:grid;grid-template-columns:36px minmax(0,1fr) 28px;align-items:center;gap:10px;padding:11px 10px 11px 12px;color:var(--text-color);background:color-mix(in srgb,var(--surface) 96%,transparent);border:1px solid var(--border-color);border-radius:11px;box-shadow:var(--shadow-float);backdrop-filter:blur(14px)}.conversation-toast.success{border-color:color-mix(in srgb,var(--success) 34%,var(--border-color))}.conversation-toast.error{border-color:color-mix(in srgb,var(--danger) 34%,var(--border-color))}.toast-symbol{width:34px;height:34px;display:grid;place-items:center;color:var(--success);background:var(--success-soft);border-radius:8px}.conversation-toast.error .toast-symbol{color:var(--danger);background:color-mix(in srgb,var(--danger) 10%,var(--surface))}.toast-symbol svg{width:19px}.conversation-toast>span:nth-child(2){min-width:0}.conversation-toast strong,.conversation-toast small{display:block}.conversation-toast strong{font-size:13px}.conversation-toast small{margin-top:3px;overflow:hidden;color:var(--text-muted);font-size:11px;line-height:1.45;text-overflow:ellipsis;white-space:nowrap}.conversation-toast>button{width:28px;height:28px;display:grid;place-items:center;padding:0;color:var(--text-soft);background:transparent;border:0;border-radius:6px}.conversation-toast>button:hover{color:var(--text-color);background:var(--surface-subtle)}.conversation-toast>button svg{width:15px}.toast-enter-active,.toast-leave-active{transition:opacity .18s ease,transform .18s ease}.toast-enter-from,.toast-leave-to{opacity:0;transform:translateY(-8px) scale(.98)}
.session-actions{border-left-color:transparent;opacity:0;pointer-events:none;transition:opacity .16s ease,border-color .16s ease}.session-list article:hover .session-actions,.session-list article:focus-within .session-actions{border-left-color:color-mix(in srgb,var(--border-color) 78%,transparent);opacity:1;pointer-events:auto}.session-list article:focus-within .session-actions button{color:var(--text-muted)}
@media(hover:none){.session-actions{border-left-color:color-mix(in srgb,var(--border-color) 78%,transparent);opacity:1;pointer-events:auto}.message-actions{opacity:1}}
@keyframes pulse{to{opacity:.25;transform:translateY(-2px)}}@keyframes streamBlink{50%{opacity:0}}@keyframes titleMarquee{0%,12%{transform:translateX(0)}88%,100%{transform:translateX(calc(-1 * var(--title-overflow)))}}
@media(prefers-reduced-motion:reduce){.title-viewport.is-overflowing:hover .title-track,.session-open:focus-visible .title-viewport.is-overflowing .title-track{overflow:hidden;text-overflow:ellipsis;animation:none}.stream-waiting i,.typing i,.stream-caret{animation:none}}
@media(min-width:1600px){.conversation-workspace{--session-panel-width:310px;--context-panel-width:360px;height:calc(100dvh - 72px);grid-template-columns:var(--session-panel-width) minmax(560px,1fr) var(--context-panel-width)}.conversation-workspace.sessions-collapsed{--session-panel-width:48px}.conversation-workspace.context-collapsed{--context-panel-width:48px}.message{max-width:1040px}.context-sidebar{padding:22px}.session-sidebar{padding-inline:16px}.session-sidebar.collapsed,.context-sidebar.collapsed{padding-inline:6px}}
@media(max-width:1250px){.conversation-workspace{--session-panel-width:260px;grid-template-columns:var(--session-panel-width) 1fr}.conversation-workspace.sessions-collapsed{--session-panel-width:48px}.context-sidebar,.context-panel-toggle{display:none}.conversation-toolbar label{min-width:0;flex:1}.mode-state{display:none}}
@media(max-width:760px){.conversation-workspace,.conversation-workspace.sessions-collapsed,.conversation-workspace.context-collapsed{height:auto;min-height:calc(100vh - 132px);grid-template-columns:1fr;overflow:visible}.panel-toggle{display:none}.session-sidebar,.session-sidebar.collapsed{width:min(340px,calc(100vw - 32px));position:fixed;top:68px;bottom:64px;left:0;z-index:91;display:flex;visibility:hidden;transform:translateX(-105%);padding:18px 14px;border-right:1px solid var(--border-color);box-shadow:var(--shadow-float);transition:transform .2s ease,visibility .2s}.session-sidebar.mobile-open{visibility:visible;transform:translateX(0)}.session-sidebar.collapsed .session-sidebar-content{display:flex}.session-sidebar.collapsed .collapsed-rail{display:none}.session-heading-actions .drawer-close{display:grid}.mobile-session-backdrop{position:fixed;inset:68px 0 64px;z-index:90;display:block;padding:0;background:rgba(6,10,17,.48);border:0}.conversation-surface{min-height:calc(100vh - 132px)}.conversation-toolbar{align-items:stretch;flex-direction:column}.mobile-session-trigger{width:fit-content;min-height:38px;display:flex;align-items:center;gap:7px;padding:0 11px;color:var(--text-muted);background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:8px}.mobile-session-trigger svg{width:18px}.mobile-session-trigger span{font-size:13px;font-weight:700}.mobile-session-trigger small{min-width:20px;padding:2px 6px;color:var(--primary);background:var(--primary-soft);border-radius:99px;text-align:center}.conversation-toolbar label{min-width:0}.mode-state{margin-left:0}.messages{min-height:460px;padding:22px 14px}.starter-grid{grid-template-columns:1fr}.message-body,.message.user .message-body.editing{max-width:100%;width:100%}.message-editor-foot{align-items:flex-end;flex-direction:column}.composer{margin-inline:10px}.conversation-toast{top:80px;right:16px}}
@media(max-width:760px){.session-sidebar.mobile-open .session-actions{border-left-color:color-mix(in srgb,var(--border-color) 78%,transparent);opacity:1;pointer-events:auto}}
.composer{transition:border-color .16s ease,box-shadow .16s ease}
.message-interruption{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin-top:12px;padding:10px;color:#9a3412;background:#fff7ed;border:1px solid #fed7aa;border-radius:8px;font-size:12px;line-height:1.5}
.message-interruption>svg{width:17px;height:17px;flex:none}.message-interruption>span{flex:1;min-width:180px}.message-interruption button{padding:5px 9px;color:#9a3412;background:#fff;border:1px solid #fdba74;border-radius:6px;font-size:12px;cursor:pointer}.message-interruption button:disabled{opacity:.5;cursor:default}
.composer.drag-over{border-color:var(--primary);box-shadow:0 0 0 3px color-mix(in srgb,var(--primary) 15%,transparent),0 8px 24px rgba(23,32,51,.07)}
.composer-drop-hint{position:absolute;inset:3px;z-index:3;display:grid;place-items:center;pointer-events:none;color:var(--primary);background:color-mix(in srgb,var(--primary-soft) 88%,var(--surface));border:1px dashed var(--primary);border-radius:8px;font-size:13px;font-weight:700}
.composer.is-editing{padding-top:50px;border-color:color-mix(in srgb,var(--primary) 38%,var(--border-color));box-shadow:0 8px 28px rgba(23,32,51,.09),0 0 0 3px color-mix(in srgb,var(--primary) 7%,transparent)}
.composer-edit-state{position:absolute;top:0;left:0;right:0;height:40px;display:flex;align-items:center;justify-content:space-between;padding:0 9px 0 12px;color:var(--primary);background:color-mix(in srgb,var(--primary-soft) 70%,var(--surface));border-bottom:1px solid color-mix(in srgb,var(--primary) 14%,var(--border-color));border-radius:10px 10px 0 0}
.composer-edit-state>span{display:flex;align-items:center;gap:8px}.composer-edit-state>span>svg{width:17px}.composer-edit-state strong,.composer-edit-state small{display:inline}.composer-edit-state strong{font-size:12px}.composer-edit-state small{margin-left:8px;color:var(--text-soft);font-size:10px}
.composer-edit-state>button{width:28px;height:28px;display:grid;place-items:center;padding:0;color:var(--text-soft);background:transparent;border:0;border-radius:6px}.composer-edit-state>button:hover{color:var(--primary);background:var(--surface)}.composer-edit-state>button svg{width:15px}
@media(max-width:760px){.composer-edit-state small{display:none}}
.composer .stop-button{color:var(--danger);background:color-mix(in srgb,var(--danger) 10%,var(--surface));border:1px solid color-mix(in srgb,var(--danger) 28%,var(--border-color));font-size:11px;font-weight:700;cursor:pointer}
.composer .stop-button:hover{background:color-mix(in srgb,var(--danger) 17%,var(--surface))}

.session-group h3{padding:0}
.session-group-toggle{width:100%;display:flex;align-items:center;gap:6px;padding:7px;color:inherit;background:transparent;border:0;border-radius:6px;text-align:left;font:inherit;cursor:pointer;transition:color .16s ease,background-color .16s ease}
.session-group-toggle>span{margin-right:auto}
.session-group-toggle:hover{color:var(--text-color);background:color-mix(in srgb,var(--surface) 68%,transparent)}
.session-group-toggle:focus-visible{outline:2px solid color-mix(in srgb,var(--primary) 65%,transparent);outline-offset:1px}
.session-group-toggle>svg{width:13px;height:13px;flex:none;transition:transform .16s ease}
.session-group-toggle.is-collapsed>svg{transform:rotate(-90deg)}
.session-group-content-enter-active,.session-group-content-leave-active{overflow:hidden;transform-origin:top;transition:opacity .14s ease,transform .14s ease}
.session-group-content-enter-from,.session-group-content-leave-to{opacity:0;transform:translateY(-3px)}
@media(prefers-reduced-motion:reduce){.session-group-toggle,.session-group-toggle>svg,.session-group-content-enter-active,.session-group-content-leave-active{transition:none}}
</style>
