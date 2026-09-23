<template>
  <section class="page">
    <header class="page-header">
      <div><h1>企业知识库</h1><p>上传经过授权的企业资料，查看处理状态，并控制参与客服问答的内容。</p></div>
      <button class="primary" @click="showCreate = true"><PlusIcon /> 新建知识库</button>
    </header>

    <div v-if="notice" class="notice" :class="noticeType" role="status" aria-live="polite">{{ notice }}</div>
    <div v-if="loading" class="loading">正在加载知识库…</div>
    <div v-else-if="knowledgeBases.length === 0" class="empty">
      <CircleStackIcon /><h2>还没有知识库</h2><p>创建知识库后即可上传 PDF、TXT 或 Markdown 资料。</p>
      <button class="primary" @click="showCreate = true">创建第一个知识库</button>
    </div>

    <div v-else class="content-grid">
      <aside class="knowledge-list">
        <button v-for="item in knowledgeBases" :key="item.id" :class="{ active: item.id === selectedId }" @click="selectKnowledgeBase(item.id)">
          <CircleStackIcon /><span><strong>{{ item.name }}</strong><small>{{ item.description || '暂无描述' }}</small></span>
        </button>
      </aside>

      <div v-if="selected" class="detail">
        <div class="detail-header">
          <div><h2>{{ selected.name }}</h2><p>{{ selected.description || '暂无描述' }}</p></div>
          <div class="detail-actions">
            <button class="secondary-action" @click="openEditKnowledgeBase"><PencilSquareIcon /> 编辑信息</button>
            <button class="danger-text" @click="removeKnowledgeBase"><TrashIcon /> 删除知识库</button>
          </div>
        </div>

        <label class="upload-zone" :class="{ uploading }" role="button" :tabindex="uploading ? -1 : 0" :aria-disabled="uploading" :aria-busy="uploading" aria-label="选择文件上传到当前知识库" @keydown.enter.prevent="openUploadPicker" @keydown.space.prevent="openUploadPicker">
          <input ref="uploadInput" type="file" accept=".pdf,.txt,.md,.markdown" :disabled="uploading" @change="uploadFile">
          <ArrowUpTrayIcon />
          <strong>{{ uploading ? '正在上传…' : '点击选择文件' }}</strong>
          <span>支持 PDF、TXT、Markdown，单文件最大 20 MB</span>
        </label>

        <div class="documents-header"><h3>文档</h3><span>{{ documents.length }} 个文件</span></div>
        <div v-if="documents.length === 0" class="documents-empty">当前知识库还没有文档。</div>
        <article v-for="document in documents" :key="document.id" class="document-row">
          <DocumentTextIcon class="file-icon" />
          <div class="document-main"><strong>{{ document.fileName }}</strong><span>{{ formatBytes(document.size) }} · {{ formatDate(document.createdAt) }}</span><p v-if="document.errorMessage">{{ document.errorMessage }}</p></div>
          <span class="status" :class="document.status.toLowerCase()">{{ statusLabel[document.status] || document.status }}</span>
          <button type="button" title="预览原文与分词片段" :aria-label="`预览文档：${document.fileName}`" @click="selectDocument(document)"><EyeIcon /></button>
          <button type="button" title="重新索引" :aria-label="`重新索引文档：${document.fileName}`" @click="reindex(document)"><ArrowPathIcon /></button>
          <button type="button" title="删除文档" :aria-label="`删除文档：${document.fileName}`" class="danger-text" @click="removeDocument(document)"><TrashIcon /></button>
        </article>
      </div>
    </div>

    <div v-if="showCreate" class="modal" @click.self="!creating && (showCreate = false)">
      <form class="dialog" role="dialog" aria-modal="true" aria-labelledby="create-knowledge-title" @submit.prevent="createKnowledgeBase">
        <h2 id="create-knowledge-title">新建知识库</h2>
        <label>名称<input v-model="form.name" maxlength="80" required autofocus placeholder="例如：产品帮助中心"></label>
        <label>描述<textarea v-model="form.description" maxlength="500" rows="3" placeholder="说明该知识库包含哪些内容"></textarea></label>
        <div class="dialog-actions"><button type="button" :disabled="creating" @click="showCreate = false">取消</button><button class="primary" type="submit" :disabled="creating">{{ creating ? '创建中…' : '创建' }}</button></div>
      </form>
    </div>

    <div v-if="showEdit" class="modal" @click.self="!updating && (showEdit = false)">
      <form class="dialog" role="dialog" aria-modal="true" aria-labelledby="edit-knowledge-title" aria-describedby="edit-knowledge-description" @submit.prevent="updateKnowledgeBase">
        <span class="dialog-eyebrow">知识库设置</span>
        <h2 id="edit-knowledge-title">编辑名称与描述</h2>
        <p id="edit-knowledge-description" class="dialog-intro">修改后会同步更新会话中的知识范围名称，不影响现有文档和向量索引。</p>
        <label>名称<input v-model="editForm.name" maxlength="80" required autofocus placeholder="例如：产品帮助中心"></label>
        <label>描述<textarea v-model="editForm.description" maxlength="500" rows="4" placeholder="说明该知识库包含哪些内容"></textarea></label>
        <div class="field-count">{{ editForm.description.length }}/500</div>
        <div class="dialog-actions"><button type="button" :disabled="updating" @click="showEdit = false">取消</button><button class="primary" type="submit" :disabled="updating">{{ updating ? '保存中…' : '保存修改' }}</button></div>
      </form>
    </div>

    <div v-if="previewDocument" class="preview-modal" @click.self="closePreviewAndSyncRoute">
      <section class="preview-panel" role="dialog" aria-modal="true" aria-labelledby="knowledge-preview-title">
        <header><div><span>知识文档</span><h2 id="knowledge-preview-title">{{ previewDocument.fileName }}</h2><p>{{ formatBytes(previewDocument.size) }} · {{ previewDocument.chunkIds?.length || 0 }} 个分词片段</p></div><button type="button" title="关闭文档预览" aria-label="关闭文档预览" autofocus @click="closePreviewAndSyncRoute"><XMarkIcon /></button></header>
        <nav><button :class="{ active: previewTab === 'original' }" @click="previewTab = 'original'">原文预览</button><button :class="{ active: previewTab === 'chunks' }" @click="previewTab = 'chunks'">分词片段 <span>{{ chunks.length }}</span></button></nav>
        <div v-if="previewTab === 'original'" class="original-preview">
          <iframe v-if="isPdf(previewDocument)" :src="previewUrl" title="知识文档预览"></iframe>
          <pre v-else-if="previewText">{{ previewText }}</pre>
          <p v-else class="preview-state">正在加载原文…</p>
        </div>
        <div v-else class="chunks-preview">
          <p v-if="chunksLoading" class="preview-state">正在读取分词片段…</p>
          <p v-else-if="!chunks.length" class="preview-state">文档尚未完成索引，暂时没有可查看的片段。</p>
          <article v-for="chunk in chunks" v-else :key="chunk.id"><header><strong>片段 {{ chunk.sequence }}</strong><span v-if="chunk.pageNumber">第 {{ chunk.pageNumber }} 页</span><span>{{ chunk.characterCount }} 字符</span></header><p>{{ chunk.content }}</p></article>
        </div>
      </section>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowPathIcon, ArrowUpTrayIcon, CircleStackIcon, DocumentTextIcon, EyeIcon, PencilSquareIcon, PlusIcon, TrashIcon, XMarkIcon } from '@heroicons/vue/24/outline'
import { knowledgeAPI } from '../services/api'

const knowledgeBases = ref([]), selectedId = ref(''), documents = ref([])
const loading = ref(true), uploading = ref(false), creating = ref(false), showCreate = ref(false)
const showEdit = ref(false), updating = ref(false), editForm = ref({ name: '', description: '' })
const notice = ref(''), noticeType = ref('info'), form = ref({ name: '', description: '' })
const route = useRoute()
const router = useRouter()
const uploadInput = ref(null)
const previewDocument = ref(null), previewTab = ref('original'), previewText = ref(''), chunks = ref([]), chunksLoading = ref(false)
const previewKnowledgeBaseId = ref('')
const catalogLoaded = ref(false)
const selected = computed(() => knowledgeBases.value.find(item => item.id === selectedId.value))
const statusLabel = { UPLOADED: '已上传', INDEXING: '索引中', READY: '可用', PENDING_AI: '等待 AI 配置', FAILED: '处理失败' }
let refreshTimer
let routeSelectionVersion = 0
let documentsRequestVersion = 0
let previewRequestVersion = 0

onMounted(async () => {
  window.addEventListener('keydown', handleEscape)
  await loadKnowledgeBases()
  refreshTimer = window.setInterval(refreshDocuments, 4000)
})
onUnmounted(() => {
  window.removeEventListener('keydown', handleEscape)
  window.clearInterval(refreshTimer)
})
watch(() => `${queryValue(route.query.knowledge)}\u0000${queryValue(route.query.document)}`, () => {
  if (catalogLoaded.value) void applyKnowledgeRoute()
})

async function loadKnowledgeBases() {
  loading.value = true
  try {
    knowledgeBases.value = await knowledgeAPI.list()
    catalogLoaded.value = true
    await applyKnowledgeRoute()
  } catch (error) { showNotice(error.message, 'error') } finally { loading.value = false }
}
async function selectKnowledgeBase(id) {
  if (!knowledgeBases.value.some(item => item.id === id)) return
  const query = { ...route.query, knowledge: id }
  delete query.document
  if (queryValue(route.query.knowledge) === id && !route.query.document) await applyKnowledgeRoute()
  else await router.push({ path: route.path, query })
}
async function applyKnowledgeRoute() {
  const requestVersion = ++routeSelectionVersion
  if (!knowledgeBases.value.length) {
    selectedId.value = ''; documents.value = []; resetPreview()
    return
  }

  const requestedKnowledgeId = queryValue(route.query.knowledge)
  const requestedDocumentId = queryValue(route.query.document)
  const requestedKnowledge = knowledgeBases.value.find(item => item.id === requestedKnowledgeId)
  if (requestedKnowledgeId && !requestedKnowledge) {
    selectedId.value = knowledgeBases.value[0].id
    documents.value = []
    resetPreview()
    showNotice('该知识库不存在或已被删除，已显示可用知识库。', 'info')
    const query = { ...route.query }
    delete query.knowledge
    delete query.document
    await router.replace({ path: route.path, query })
    return
  }

  const currentSelectionIsValid = knowledgeBases.value.some(item => item.id === selectedId.value)
  const nextId = requestedKnowledge?.id || (currentSelectionIsValid ? selectedId.value : knowledgeBases.value[0].id)
  if (nextId !== selectedId.value) {
    selectedId.value = nextId
    documents.value = []
    resetPreview()
  }
  if (!requestedDocumentId || previewDocument.value?.id !== requestedDocumentId || previewKnowledgeBaseId.value !== nextId) resetPreview()

  const loadedDocuments = await refreshDocuments()
  if (requestVersion !== routeSelectionVersion || selectedId.value !== nextId) return

  if (!requestedDocumentId) {
    resetPreview()
    return
  }
  const target = (loadedDocuments || documents.value).find(item => item.id === requestedDocumentId)
  if (target) {
    if (previewDocument.value?.id !== target.id || previewKnowledgeBaseId.value !== nextId) await openDocument(target, nextId)
    return
  }
  resetPreview()
  showNotice('该文档不存在或已被删除，已返回知识库详情。', 'info')
  await clearDocumentQuery()
}
async function refreshDocuments() {
  const knowledgeBaseId = selectedId.value
  const requestVersion = ++documentsRequestVersion
  if (!knowledgeBaseId) { documents.value = []; return }
  try {
    const result = await knowledgeAPI.documents(knowledgeBaseId)
    if (requestVersion === documentsRequestVersion && knowledgeBaseId === selectedId.value) documents.value = result
    return knowledgeBaseId === selectedId.value ? result : null
  } catch (error) {
    if (requestVersion === documentsRequestVersion && !loading.value) showNotice(error.message, 'error')
    return null
  }
}
async function createKnowledgeBase() {
  creating.value = true
  try {
    const created = await knowledgeAPI.create(form.value)
    knowledgeBases.value = [created, ...knowledgeBases.value]
    selectedId.value = created.id; documents.value = []; form.value = { name: '', description: '' }; showCreate.value = false
    const query = { ...route.query, knowledge: created.id }
    delete query.document
    await router.replace({ path: route.path, query })
    showNotice('知识库已创建', 'success')
  } catch (error) { showNotice(error.message, 'error') } finally { creating.value = false }
}
function openEditKnowledgeBase() {
  if (!selected.value) return
  editForm.value = { name: selected.value.name, description: selected.value.description || '' }
  showEdit.value = true
}
async function updateKnowledgeBase() {
  if (!selected.value) return
  updating.value = true
  try {
    const updated = await knowledgeAPI.update(selected.value.id, editForm.value)
    knowledgeBases.value = knowledgeBases.value.map(item => item.id === updated.id ? updated : item)
    showEdit.value = false
    showNotice('知识库信息已更新', 'success')
  } catch (error) { showNotice(error.message, 'error') } finally { updating.value = false }
}
function openUploadPicker() {
  if (!uploading.value) uploadInput.value?.click()
}
async function uploadFile(event) {
  const file = event.target.files?.[0]; event.target.value = ''; if (!file) return
  uploading.value = true
  try { await knowledgeAPI.upload(selectedId.value, file); showNotice('文件已上传，正在处理', 'success'); await refreshDocuments() }
  catch (error) { showNotice(error.message, 'error') } finally { uploading.value = false }
}
async function reindex(document) {
  try { await knowledgeAPI.reindex(selectedId.value, document.id); showNotice('已提交重新索引', 'success'); await refreshDocuments() }
  catch (error) { showNotice(error.message, 'error') }
}
async function removeDocument(document) {
  if (!window.confirm(`确定删除“${document.fileName}”吗？`)) return
  try { await knowledgeAPI.removeDocument(selectedId.value, document.id); await refreshDocuments(); showNotice('文档已删除', 'success') }
  catch (error) { showNotice(error.message, 'error') }
}
async function removeKnowledgeBase() {
  if (!window.confirm(`确定删除知识库“${selected.value.name}”及其全部文档吗？`)) return
  try { await knowledgeAPI.remove(selectedId.value); selectedId.value = ''; documents.value = []; await loadKnowledgeBases(); showNotice('知识库已删除', 'success') }
  catch (error) { showNotice(error.message, 'error') }
}
async function selectDocument(document) {
  const query = { ...route.query, knowledge: selectedId.value, document: document.id }
  if (queryValue(route.query.knowledge) === selectedId.value && queryValue(route.query.document) === document.id) await openDocument(document, selectedId.value)
  else await router.push({ path: route.path, query })
}
async function openDocument(document, knowledgeBaseId = selectedId.value) {
  const requestVersion = ++previewRequestVersion
  previewKnowledgeBaseId.value = knowledgeBaseId
  previewDocument.value = document; previewTab.value = 'original'; previewText.value = ''; chunks.value = []; chunksLoading.value = true
  const url = knowledgeAPI.previewUrl(knowledgeBaseId, document.id)
  if (!isPdf(document)) {
    try {
      const response = await fetch(url, { credentials: 'include' })
      if (!response.ok) throw new Error(`文档读取失败（${response.status}）`)
      const text = await response.text()
      if (requestVersion === previewRequestVersion) previewText.value = text
    } catch { if (requestVersion === previewRequestVersion) previewText.value = '原文加载失败，请稍后重试。' }
  }
  try {
    const result = await knowledgeAPI.chunks(knowledgeBaseId, document.id)
    if (requestVersion === previewRequestVersion) chunks.value = result
  } catch (error) {
    if (requestVersion === previewRequestVersion) showNotice(error.message, 'error')
  } finally {
    if (requestVersion === previewRequestVersion) chunksLoading.value = false
  }
}
function resetPreview() {
  previewRequestVersion += 1
  previewDocument.value = null; previewKnowledgeBaseId.value = ''; previewText.value = ''; chunks.value = []; chunksLoading.value = false
}
async function closePreviewAndSyncRoute() {
  resetPreview()
  await clearDocumentQuery()
}
async function clearDocumentQuery() {
  if (!route.query.document) return
  const query = { ...route.query }
  delete query.document
  await router.replace({ path: route.path, query })
}
function isPdf(document) { return document?.contentType === 'application/pdf' || document?.fileName?.toLowerCase().endsWith('.pdf') }
const previewUrl = computed(() => previewDocument.value ? knowledgeAPI.previewUrl(previewKnowledgeBaseId.value, previewDocument.value.id) : '')
function showNotice(message, type = 'info') { notice.value = message; noticeType.value = type; window.setTimeout(() => { if (notice.value === message) notice.value = '' }, 5000) }
function queryValue(value) { return Array.isArray(value) ? String(value[0] || '') : String(value || '') }
function handleEscape(event) {
  if (event.key !== 'Escape') return
  if (previewDocument.value) { event.preventDefault(); void closePreviewAndSyncRoute(); return }
  if (showEdit.value && !updating.value) { event.preventDefault(); showEdit.value = false; return }
  if (showCreate.value && !creating.value) { event.preventDefault(); showCreate.value = false }
}
function formatBytes(bytes) { if (!bytes) return '0 B'; const units = ['B', 'KB', 'MB', 'GB']; const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), 3); return `${(bytes / 1024 ** i).toFixed(i ? 1 : 0)} ${units[i]}` }
function formatDate(value) { return value ? new Date(value).toLocaleString('zh-CN') : '' }
</script>

<style scoped lang="scss">
.page { max-width: 1180px; margin: 0 auto; padding: 44px 28px 70px; }
.page-header { display: flex; align-items: end; justify-content: space-between; gap: 24px; margin-bottom: 28px; }
h1 { margin: 0 0 8px; font-size: 34px; } .page-header p { color: var(--text-muted); margin: 0; }
button.primary, .primary { display: inline-flex; align-items: center; justify-content: center; gap: 8px; color: white; border: 0; background: var(--primary); padding: 10px 15px; border-radius: 9px; }
button svg { width: 17px; } .notice { padding: 11px 14px; margin-bottom: 18px; border-radius: 9px; background: var(--primary-soft); color: var(--primary); } .notice.error { color: var(--danger); } .notice.success { color: var(--success); }
.loading, .empty { padding: 80px 20px; text-align: center; color: var(--text-muted); background: var(--surface); border: 1px solid var(--border-color); border-radius: 16px; }
.empty > svg { width: 48px; color: var(--primary); } .empty h2 { color: var(--text-color); } .empty button { margin-top: 12px; }
.content-grid { display: grid; grid-template-columns: 280px 1fr; gap: 18px; align-items: start; }
.knowledge-list, .detail { background: var(--surface); border: 1px solid var(--border-color); border-radius: 14px; }
.knowledge-list { padding: 8px; display: flex; flex-direction: column; gap: 5px; }
.knowledge-list button { width: 100%; display: flex; gap: 10px; padding: 12px; border: 0; border-radius: 9px; background: transparent; color: var(--text-color); text-align: left; }
.knowledge-list button.active { background: var(--primary-soft); color: var(--primary); } .knowledge-list svg { width: 20px; flex: none; }
.knowledge-list span { min-width: 0; display: grid; gap: 4px; } .knowledge-list small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-muted); }
.detail { padding: 24px; } .detail-header { display: flex; justify-content: space-between; gap: 16px; align-items: start; } .detail-header h2 { margin: 0 0 6px; } .detail-header p { margin: 0; color: var(--text-muted); }.detail-actions{display:flex;align-items:center;gap:6px;flex:none}.secondary-action{display:inline-flex;align-items:center;gap:6px;padding:7px 9px;color:var(--text-muted);background:transparent;border:0;border-radius:7px}.secondary-action:hover{color:var(--primary);background:var(--primary-soft)}
button.danger-text { display: inline-flex; align-items: center; gap: 6px; color: var(--danger); background: transparent; border: 0; }
.upload-zone { min-height: 150px; margin: 24px 0; display: grid; place-content: center; justify-items: center; gap: 7px; border: 1px dashed var(--border-color); border-radius: 12px; background: var(--surface-subtle); cursor: pointer; }
.upload-zone input { display: none; } .upload-zone:focus-visible { outline: 2px solid var(--primary); outline-offset: 3px; border-color: var(--primary); } .upload-zone svg { width: 32px; color: var(--primary); } .upload-zone span { color: var(--text-muted); font-size: 13px; } .upload-zone.uploading { opacity: .6; pointer-events: none; }
.documents-header { display: flex; justify-content: space-between; align-items: center; margin: 28px 0 12px; } .documents-header h3 { margin: 0; } .documents-header span { color: var(--text-muted); font-size: 13px; }
.documents-empty { color: var(--text-muted); padding: 24px 0; }
.document-row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto auto auto auto; align-items: center; gap: 12px; padding: 14px 0; border-top: 1px solid var(--border-color); }
.file-icon { width: 24px; color: var(--primary); } .document-main { min-width: 0; display: grid; gap: 4px; } .document-main strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .document-main span, .document-main p { margin: 0; color: var(--text-muted); font-size: 12px; } .document-main p { color: var(--danger); }
.status { padding: 5px 8px; border-radius: 99px; color: var(--text-muted); background: var(--surface-subtle); font-size: 12px; } .status.ready { color: var(--success); } .status.failed { color: var(--danger); } .status.indexing { color: var(--primary); }
.document-row > button { display: grid; place-items: center; width: 32px; height: 32px; border: 0; color: var(--text-muted); background: transparent; }
.modal { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; padding: 20px; background: rgba(0, 0, 0, .45); }
.dialog { width: min(480px, 100%); padding: 26px; background: var(--surface); border-radius: 14px; box-shadow:var(--shadow-float); } .dialog h2 { margin:6px 0 0; }.dialog-eyebrow{color:var(--primary);font-size:11px;font-weight:750;letter-spacing:.1em}.dialog-intro{margin:9px 0 20px;color:var(--text-muted);font-size:13px;line-height:1.65}.field-count{margin-top:-10px;color:var(--text-soft);font-size:11px;text-align:right}
.dialog label { display: grid; gap: 7px; margin: 16px 0; font-weight: 600; } .dialog input, .dialog textarea { padding: 10px 12px; color: var(--text-color); background: var(--surface-subtle); border: 1px solid var(--border-color); border-radius: 8px; resize: vertical; }
.dialog-actions { display: flex; justify-content: end; gap: 10px; margin-top: 22px; } .dialog-actions > button:not(.primary) { border: 1px solid var(--border-color); color: var(--text-color); background: var(--surface); border-radius: 9px; padding: 9px 14px; }
.preview-modal { position: fixed; inset: 0; z-index: 110; display: grid; place-items: center; padding: 28px; background: rgba(8, 13, 23, .58); backdrop-filter: blur(5px); }
.preview-panel { width: min(1120px, 100%); height: min(820px, 90vh); display: grid; grid-template-rows: auto auto 1fr; overflow: hidden; background: var(--surface); border-radius: 14px; box-shadow: var(--shadow-float); }
.preview-panel > header { display: flex; justify-content: space-between; gap: 20px; padding: 22px 24px 18px; border-bottom: 1px solid var(--border-color); }.preview-panel > header span { color: var(--primary); font-size: 11px; font-weight: 750; letter-spacing: .1em; }.preview-panel > header h2 { margin: 6px 0 4px; font-size: 21px; }.preview-panel > header p { margin: 0; color: var(--text-muted); font-size: 12px; }.preview-panel > header button { width: 36px; height: 36px; display: grid; place-items: center; color: var(--text-muted); background: transparent; border: 0; }.preview-panel > header svg { width: 21px; }
.preview-panel > nav { display: flex; gap: 20px; padding: 0 24px; border-bottom: 1px solid var(--border-color); }.preview-panel > nav button { position: relative; padding: 13px 2px; color: var(--text-muted); background: transparent; border: 0; font-size: 13px; }.preview-panel > nav button.active { color: var(--primary); font-weight: 700; }.preview-panel > nav button.active::after { content: ''; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px; background: var(--primary); }.preview-panel > nav span { padding: 2px 5px; background: var(--surface-strong); border-radius: 99px; font-size: 10px; }
.original-preview, .chunks-preview { min-height: 0; overflow: auto; }.original-preview iframe { width: 100%; height: 100%; border: 0; }.original-preview pre { min-height: 100%; margin: 0; padding: 26px; color: var(--text-color); background: var(--surface-subtle); font: 13px/1.8 "IBM Plex Mono", Consolas, monospace; white-space: pre-wrap; word-break: break-word; }.preview-state { padding: 70px 24px; color: var(--text-muted); text-align: center; }
.chunks-preview { padding: 18px 24px 30px; background: var(--surface-subtle); }.chunks-preview article { margin-bottom: 12px; padding: 16px 18px; background: var(--surface); border: 1px solid var(--border-color); border-radius: 9px; }.chunks-preview article header { display: flex; align-items: center; gap: 9px; }.chunks-preview article header strong { color: var(--primary); font-size: 12px; }.chunks-preview article header span { padding: 3px 6px; color: var(--text-soft); background: var(--surface-subtle); border-radius: 4px; font-size: 10px; }.chunks-preview article p { margin: 11px 0 0; overflow-wrap: anywhere; color: var(--text-muted); font-size: 13px; line-height: 1.75; white-space: pre-wrap; word-break: break-word; }
@media (max-width: 1000px) { .content-grid { grid-template-columns: 1fr; } .knowledge-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 760px) { .page { padding: 28px 16px 88px; } .page-header { align-items: start; flex-direction: column; } .knowledge-list { display: flex; } .detail { padding: 18px; } .detail-header { flex-direction: column; } .document-row { grid-template-columns: auto 1fr auto; } .document-row .status { grid-column: 2; } }
</style>
