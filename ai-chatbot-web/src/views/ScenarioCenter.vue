<template>
  <section class="scenario-page">
    <header class="page-heading">
      <div><h1>场景编排</h1><p>用业务流程、知识范围和工具权限定义助手能做什么，以及不能做什么。</p></div>
      <span v-if="loading" class="load-state">正在同步场景配置…</span>
    </header>

    <p v-if="pageMessage" class="page-message" :class="messageType" :role="messageType === 'error' ? 'alert' : 'status'">{{ pageMessage }} <button v-if="scenarioError" type="button" :disabled="loading" @click="reloadScenarios">重试同步</button><RouterLink v-if="messageType === 'success'" :to="{ path: '/customer-service', query: { scenario: selected.code } }">进入场景试问</RouterLink></p>

    <div class="scenario-layout">
      <nav class="scenario-menu" aria-label="业务场景">
        <button v-for="item in scenarios" :key="item.code" type="button" :class="{ active: item.code === selected.code }" :aria-current="item.code === selected.code ? 'true' : undefined" @click="selectScenario(item)">
          <strong>{{ item.name }}</strong><small>{{ item.summary }}</small>
        </button>
      </nav>

      <article class="scenario-detail">
        <div v-if="loading && route.query.edit === '1'" class="editor-state" role="status">正在加载场景配置与工具目录…</div>
        <template v-else-if="!editMode">
          <div class="detail-heading">
            <div><span>SCENARIO / {{ selected.code.toUpperCase() }}</span><h2>{{ selected.name }}</h2><p>{{ selected.summary }}</p></div>
            <div class="detail-actions"><button v-if="canEdit && scenariosReady" type="button" @click="startEdit"><PencilSquareIcon />编辑配置</button><RouterLink :to="`/customer-service?scenario=${selected.code}`">进入场景</RouterLink></div>
          </div>
          <div class="policy-grid">
            <section><label>知识策略</label><strong>知识库{{ selected.knowledgeMode }}</strong><p>{{ knowledgeDescription }}</p></section>
            <section><label>业务工具</label><strong>{{ catalogReady ? selectedToolStatus.available.length + ' 个已配置工具' : catalogError ? '工具配置暂不可核对' : '正在核对工具配置…' }}</strong><p v-if="catalogReady">{{ selectedToolStatus.available.length ? selectedToolStatus.available.join('、') : '本场景不使用业务工具。' }}</p><p v-if="catalogReady && selectedToolStatus.unknown.length" class="unknown-summary">待修正：{{ selectedToolStatus.unknown.join('、') }}</p><p v-else-if="catalogError">工具目录暂不可用，无法核对当前配置。<button type="button" :disabled="catalogLoading" @click="loadToolCatalog">重试</button></p></section>
          </div>
          <section class="workflow"><label>处理指引（供模型参考）</label><ol><li v-for="(step, index) in selected.process" :key="`${index}-${step}`"><span>{{ String(index + 1).padStart(2, '0') }}</span><strong>{{ step }}</strong></li></ol><p>模型会按具体问题选择适用步骤；这些步骤不会自动执行操作。</p></section>
          <section class="guardrail"><ShieldCheckIcon /><div><label>业务边界</label><p>{{ selected.guardrail }}</p></div></section>
        </template>

        <form v-else class="scenario-form" @submit.prevent="saveScenario">
          <div class="form-heading">
            <div><span>EDIT / {{ selected.code.toUpperCase() }}</span><h2>编辑场景配置</h2><p>保存后，展示信息和该场景后续的新回答都会使用这份配置。</p></div>
            <div class="form-actions"><button type="button" :disabled="saving" @click="cancelEdit">取消</button><button class="primary" type="submit" :disabled="saving || loading || !scenariosReady || !catalogReady || validationErrors.length">{{ saving ? '保存中…' : '保存配置' }}</button></div>
          </div>

          <div class="form-grid">
            <label><span>场景名称</span><input v-model.trim="draft.name" maxlength="100" required></label>
            <label><span>会话短名称</span><input v-model.trim="draft.shortName" maxlength="40" required></label>
            <label class="wide"><span>场景说明</span><textarea v-model.trim="draft.summary" rows="3" maxlength="500" required></textarea><small>{{ draft.summary.length }}/500</small></label>
            <label><span>知识策略</span><select v-model="draft.knowledgeMode" required><option value="可选">可选</option><option value="推荐">推荐</option><option value="必选">必选</option></select></label>
            <section class="wide editor-section" aria-labelledby="tools-heading">
              <div class="section-heading"><div><h3 id="tools-heading">允许的业务工具</h3><p>选择本场景可调用的能力；实际使用还取决于账号权限、必要信息和 AI 服务状态。留空表示不使用业务工具。</p></div><span class="section-count">已选 {{ draft.toolIds.length }} 项</span></div>
              <p v-if="catalogLoading" class="editor-state" role="status">正在加载可用工具…</p>
              <div v-else-if="catalogError" class="editor-state error" role="alert">工具目录加载失败：{{ catalogError }} <button type="button" @click="loadToolCatalog">重试加载</button></div>
              <p v-else-if="!toolCatalog.length" class="editor-state">暂无可用业务工具，本场景可以只使用知识库与处理指引。</p>
              <div v-else class="tool-options">
                <label v-for="tool in toolCatalog" :key="tool.id" class="tool-option" :class="{ selected: draft.toolIds.includes(tool.id) }">
                  <input v-model="draft.toolIds" type="checkbox" :value="tool.id" :disabled="saving" @change="onToolChange(tool)">
                  <span class="tool-option-body"><span class="tool-option-top"><strong>{{ tool.label }}</strong><span class="tool-category">{{ tool.category }}</span></span><span class="tool-description">{{ tool.description }}</span><span class="tool-meta">需要：{{ formatInputs(tool.requiredInputs) }} · {{ tool.confirmation ? '准备草案，提交前需用户确认' : '只读查询' }}</span></span>
                </label>
              </div>
              <div v-if="draft.unknownTools.length" class="unknown-tools" role="alert"><strong>历史配置中有不可用工具，保存前请移除</strong><div v-for="tool in draft.unknownTools" :key="tool"><span>{{ tool }} · 不可用，待修正</span><button type="button" :disabled="saving" :aria-label="`移除不可用工具 ${tool}`" @click="removeUnknownTool(tool)">移除</button></div></div>
              <p v-if="toolWarning" class="field-warning" role="status">{{ toolWarning }}</p>
              <p v-if="catalogReady && !draft.toolIds.length && !draft.unknownTools.length" class="empty-note">本场景不使用业务工具。</p>
            </section>
            <section class="wide editor-section" aria-labelledby="process-heading">
              <div class="section-heading"><div><h3 id="process-heading">处理指引（供模型参考）</h3><p>每步尽量写成“遇到什么情况 → 做什么”。模型会按实际问题选择步骤，不会自动按顺序执行。</p></div><button type="button" class="text-action" :disabled="saving" @click="restoreRecommendedSteps">使用本场景推荐步骤</button></div>
              <div class="step-list">
                <div v-for="(step, index) in draft.steps" :key="step.id" class="step-row">
                  <span class="step-number" :aria-label="`第 ${index + 1} 步`">{{ String(index + 1).padStart(2, '0') }}</span>
                  <div class="step-content"><label :for="`scenario-step-${step.id}`">第 {{ index + 1 }} 步</label><input :id="`scenario-step-${step.id}`" v-model="step.text" maxlength="120" :disabled="saving" placeholder="例如：用户信息不足 → 先询问订单号"><div class="step-foot"><span>{{ step.text.trim().length }}/120 字</span><select v-if="chosenTools.length" :disabled="saving" aria-label="在此步骤中引用已选业务工具" :value="''" @change="insertToolInStep(index, $event)"><option value="">引用已选工具…</option><option v-for="tool in chosenTools" :key="tool.id" :value="tool.id">{{ tool.label }}</option></select></div></div>
                  <div class="step-actions"><button type="button" :disabled="saving || index === 0" :aria-label="`上移第 ${index + 1} 步`" title="上移" @click="moveStep(index, -1)"><ArrowUpIcon /></button><button type="button" :disabled="saving || index === draft.steps.length - 1" :aria-label="`下移第 ${index + 1} 步`" title="下移" @click="moveStep(index, 1)"><ArrowDownIcon /></button><button type="button" :disabled="saving" :aria-label="`删除第 ${index + 1} 步`" title="删除" @click="removeStep(index)"><TrashIcon /></button></div>
                </div>
              </div>
              <button type="button" class="add-step" :disabled="saving || draft.steps.length >= 20" @click="addStep"><PlusIcon />添加步骤</button><small>最多 20 步，每步最多 120 字。实际工具调用仍以授权范围和用户请求为准。</small>
            </section>
            <label class="wide"><span>业务边界</span><textarea v-model.trim="draft.guardrail" rows="4" maxlength="1000" required></textarea><small>{{ draft.guardrail.length }}/1000</small></label>
          </div>
          <div class="config-summary"><div><h3>保存前确认</h3><p>知识库{{ draft.knowledgeMode }} · {{ draft.toolIds.length }} 个业务工具 · {{ draft.steps.length }} 个处理步骤</p><p v-if="draft.toolIds.length">已选工具：{{ chosenTools.map(tool => tool.label).join('、') }}</p><p v-else>本场景不使用业务工具。</p></div><button class="primary" type="submit" :disabled="saving || loading || !scenariosReady || !catalogReady || validationErrors.length">{{ saving ? '保存中…' : '保存配置' }}</button></div>
          <ul v-if="validationErrors.length && !catalogLoading" class="validation-list" role="alert"><li v-for="error in validationErrors" :key="error">{{ error }}</li></ul>
        </form>
      </article>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowDownIcon, ArrowUpIcon, PencilSquareIcon, PlusIcon, ShieldCheckIcon, TrashIcon } from '@heroicons/vue/24/outline'
import { defaultProcessForScenario, mergeScenario, mergeScenarios, scenarios, scenarioByCode } from '../data/scenarios'
import { scenarioAPI } from '../services/api'
import { authState } from '../services/auth'

const route = useRoute()
const router = useRouter()
const selected = ref(scenarioByCode(String(route.query.scenario || '')))
const editMode = ref(false)
const loading = ref(true)
const scenariosReady = ref(false)
const scenarioError = ref('')
const saving = ref(false)
const pageMessage = ref('')
const messageType = ref('info')
const catalogLoading = ref(false)
const catalogReady = ref(false)
const catalogError = ref('')
const toolCatalog = ref([])
const toolWarning = ref('')
let stepSequence = 0
const draft = reactive({ name: '', shortName: '', summary: '', knowledgeMode: '可选', toolIds: [], unknownTools: [], steps: [], guardrail: '' })
const canEdit = computed(() => authState.user?.role === 'ADMIN')
const chosenTools = computed(() => toolCatalog.value.filter(tool => draft.toolIds.includes(tool.id)))

const selectedToolStatus = computed(() => {
  const { ids, unknown } = resolveTools(selected.value.tools)
  return {
    available: ids.map(id => toolCatalog.value.find(tool => tool.id === id)?.label || id),
    unknown
  }
})
const validationErrors = computed(() => {
  const errors = []
  if (!scenariosReady.value) errors.push('场景配置尚未同步，暂不能保存。')
  if (!catalogReady.value) errors.push('工具目录尚未加载，暂不能保存。')
  if (draft.unknownTools.length) errors.push('请移除历史配置中不可用的业务工具。')
  if (!draft.steps.length) errors.push('处理指引至少保留 1 个步骤。')
  if (draft.steps.length > 20) errors.push('处理指引最多 20 个步骤。')
  const seen = new Set()
  draft.steps.forEach((step, index) => {
    const content = step.text.trim()
    if (!content) errors.push('第 ' + (index + 1) + ' 步不能为空。')
    else if (content.length > 120) errors.push('第 ' + (index + 1) + ' 步不能超过 120 字。')
    const normalized = content.toLocaleLowerCase()
    if (content && seen.has(normalized)) errors.push('第 ' + (index + 1) + ' 步与前面步骤重复。')
    seen.add(normalized)
    toolCatalog.value.forEach(tool => {
      if (!draft.toolIds.includes(tool.id) && toolIsReferenced(content, tool)) {
        errors.push('第 ' + (index + 1) + ' 步引用了未开放的“' + tool.label + '”。')
      }
    })
  })
  return errors
})

const knowledgeDescription = computed(() => ({
  可选: '可以直接进行普通对话，也可以选择知识库增强回答。',
  推荐: '建议绑定对应业务知识库，资料不足时仍可进入人工或工单流程。',
  必选: '必须选择知识库，所有回答都需要可追溯引用。'
})[selected.value.knowledgeMode] || '按场景配置选择知识范围。')

watch(() => route.query.scenario, code => {
  selected.value = scenarioByCode(String(code || ''))
  if (editMode.value) fillDraft()
})

watch(
  [() => route.query.edit, canEdit, () => authState.checked, scenariosReady, loading],
  ([value, editable, authChecked, ready, isLoading]) => {
    if (value !== '1') {
      if (editMode.value) editMode.value = false
      return
    }
    if (isLoading || !authChecked) return
    if (!ready || !editable) {
      editMode.value = false
      const query = { ...route.query }
      delete query.edit
      router.replace({ query })
      showMessage(!ready ? '场景配置同步失败，暂不能编辑。' : '仅管理员可编辑场景配置。', 'error')
      return
    }
    if (!editMode.value) startEdit(false)
  },
  { immediate: true }
)
onMounted(async () => {
  loading.value = true
  const [scenariosResult] = await Promise.allSettled([scenarioAPI.list(), loadToolCatalog()])
  try {
    if (scenariosResult.status === 'rejected') throw scenariosResult.reason
    if (!Array.isArray(scenariosResult.value) || !scenariosResult.value.length) throw new Error('未返回场景配置')
    mergeScenarios(scenariosResult.value)
    scenariosReady.value = true
    scenarioError.value = ''
    selected.value = scenarioByCode(String(route.query.scenario || ''))
    if (editMode.value) fillDraft()
  } catch (error) {
    scenariosReady.value = false
    scenarioError.value = error.message || '请稍后重试'
    showMessage(`场景配置同步失败：${error.message}。当前显示内置配置。`, 'error')
  } finally {
    loading.value = false
  }
})

async function reloadScenarios() {
  loading.value = true
  try {
    const items = await scenarioAPI.list()
    if (!Array.isArray(items) || !items.length) throw new Error('未返回场景配置')
    mergeScenarios(items)
    selected.value = scenarioByCode(String(route.query.scenario || ''))
    scenariosReady.value = true
    scenarioError.value = ''
    showMessage('场景配置已重新同步。', 'info')
  } catch (error) {
    scenariosReady.value = false
    scenarioError.value = error.message || '请稍后重试'
    showMessage('场景配置同步失败：' + scenarioError.value + '。当前显示内置配置。', 'error')
  } finally {
    loading.value = false
  }
}

function selectScenario(item) {
  selected.value = item
  if (scenarioError.value) showMessage('场景配置同步失败：' + scenarioError.value + '。当前显示内置配置。', 'error')
  else pageMessage.value = ''
  if (editMode.value) fillDraft()
  if (route.query.scenario !== item.code) router.replace({ query: { ...route.query, scenario: item.code } })
}

function fillDraft() {
  const { ids, unknown } = resolveTools(selected.value.tools)
  Object.assign(draft, {
    name: selected.value.name,
    shortName: selected.value.shortName,
    summary: selected.value.summary,
    knowledgeMode: selected.value.knowledgeMode,
    toolIds: ids,
    unknownTools: unknown,
    steps: selected.value.process.map(text => makeStep(text)),
    guardrail: selected.value.guardrail
  })
  toolWarning.value = ''
}

function startEdit(updateRoute = true) {
  if (!scenariosReady.value) {
    showMessage('场景配置尚未同步，暂不能编辑。', 'error')
    return
  }
  if (!canEdit.value) {
    showMessage('仅管理员可编辑场景配置。', 'error')
    return
  }
  fillDraft()
  pageMessage.value = ''
  editMode.value = true
  if (updateRoute && route.query.edit !== '1') router.replace({ query: { ...route.query, scenario: selected.value.code, edit: '1' } })
}

function cancelEdit() {
  editMode.value = false
  const query = { ...route.query }
  delete query.edit
  router.replace({ query })
}

function normalizeToolValue(value) {
  return String(value || '').trim().toLocaleLowerCase()
}

function resolveTools(values) {
  const ids = []
  const unknown = []
  for (const value of Array.isArray(values) ? values : []) {
    const name = String(value || '').trim()
    if (!name) continue
    const normalized = normalizeToolValue(name)
    const match = toolCatalog.value.find(tool =>
      [tool.id, tool.label, ...(tool.aliases || [])]
        .some(alias => normalizeToolValue(alias) === normalized))
    if (match) {
      if (!ids.includes(match.id)) ids.push(match.id)
    } else if (!unknown.includes(name)) unknown.push(name)
  }
  return { ids, unknown }
}

function formatInputs(inputs) {
  if (Array.isArray(inputs)) return inputs.length ? inputs.join('、') : '无需额外信息'
  return String(inputs || '').trim() || '无需额外信息'
}

function toolIsReferenced(text, tool) {
  const content = normalizeToolValue(text)
  return [tool.id, tool.label, ...(tool.aliases || [])]
    .some(name => content.includes(normalizeToolValue(name)))
}

async function loadToolCatalog() {
  catalogLoading.value = true
  catalogError.value = ''
  catalogReady.value = false
  try {
    const items = await scenarioAPI.toolCatalog()
    if (!Array.isArray(items)) throw new Error('返回数据格式不正确')
    toolCatalog.value = items
    catalogReady.value = true
    if (editMode.value) {
      const resolved = resolveTools([...draft.toolIds, ...draft.unknownTools])
      draft.toolIds = resolved.ids
      draft.unknownTools = resolved.unknown
    }
  } catch (error) {
    catalogError.value = error.message || '请稍后重试'
  } finally {
    catalogLoading.value = false
  }
}

function makeStep(text = '') {
  return { id: ++stepSequence, text: String(text || '') }
}

function addStep() {
  if (draft.steps.length < 20) draft.steps.push(makeStep())
}

function removeStep(index) {
  draft.steps.splice(index, 1)
}

function moveStep(index, direction) {
  const destination = index + direction
  if (destination < 0 || destination >= draft.steps.length) return
  const [step] = draft.steps.splice(index, 1)
  draft.steps.splice(destination, 0, step)
}

function restoreRecommendedSteps() {
  const process = defaultProcessForScenario(selected.value.code)
  draft.steps = (process.length ? process : selected.value.process)
    .map(text => makeStep(text))
  toolWarning.value = ''
}

function insertToolInStep(index, event) {
  const tool = toolCatalog.value.find(item =>
    item.id === event.target.value && draft.toolIds.includes(item.id))
  event.target.value = ''
  if (!tool) return
  const reference = '使用「' + tool.label + '」'
  if (draft.steps[index].text.includes(reference)) return
  draft.steps[index].text = draft.steps[index].text.trim()
    ? draft.steps[index].text.trim() + '；需要时' + reference
    : '需要时' + reference
}

function onToolChange(tool) {
  if (draft.toolIds.includes(tool.id)) {
    toolWarning.value = ''
    return
  }
  toolWarning.value = draft.steps.some(step => toolIsReferenced(step.text, tool))
    ? '已取消“' + tool.label + '”，处理指引仍引用它，请调整相关步骤。'
    : '已取消“' + tool.label + '”，请检查处理指引是否仍需要这项能力。'
}

function removeUnknownTool(tool) {
  draft.unknownTools = draft.unknownTools.filter(value => value !== tool)
}
async function saveScenario() {
  if (!scenariosReady.value) {
    showMessage('场景配置尚未同步，暂不能保存。', 'error')
    return
  }
  if (!canEdit.value) {
    showMessage('仅管理员可编辑场景配置。', 'error')
    return
  }
  if (validationErrors.value.length) {
    showMessage(validationErrors.value[0], 'error')
    return
  }
  saving.value = true
  pageMessage.value = ''
  try {
    const updated = await scenarioAPI.update(selected.value.code, {
      name: draft.name.trim(),
      shortName: draft.shortName.trim(),
      summary: draft.summary.trim(),
      knowledgeMode: draft.knowledgeMode,
      tools: [...draft.toolIds],
      process: draft.steps.map(step => step.text.trim()),
      guardrail: draft.guardrail.trim()
    })
    selected.value = mergeScenario(updated)
    editMode.value = false
    const query = { ...route.query, scenario: selected.value.code }
    delete query.edit
    await router.replace({ query })
    showMessage('场景配置已保存，后续新回答将使用最新配置。', 'success')
  } catch (error) {
    showMessage('保存失败：' + error.message, 'error')
  } finally {
    saving.value = false
  }
}
function showMessage(message, type) {
  pageMessage.value = message
  messageType.value = type
}
</script>

<style scoped lang="scss">
.scenario-page{max-width:1380px;margin:0 auto;padding:34px}.page-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:24px}.page-heading h1{margin:0 0 7px;font-size:36px;letter-spacing:-.035em}.page-heading p,.detail-heading p,.form-heading p{margin:0;color:var(--text-muted);font-size:14px}.load-state{color:var(--text-soft);font-size:12px}.page-message{margin:18px 0 -8px;padding:11px 13px;border:1px solid var(--border-color);border-radius:8px;font-size:12px}.page-message.success{color:var(--success);background:var(--success-soft)}.page-message.error{color:var(--danger);background:color-mix(in srgb,var(--danger) 8%,var(--surface))}
.scenario-layout{display:grid;grid-template-columns:310px minmax(0,1fr);gap:16px;margin-top:26px}.scenario-menu,.scenario-detail{background:var(--surface);border:1px solid var(--border-color);border-radius:10px}.scenario-menu{display:flex;flex-direction:column;align-self:start;gap:3px;padding:8px}.scenario-menu button{padding:13px;color:inherit;background:transparent;border:0;border-radius:7px;text-align:left}.scenario-menu button:hover{background:var(--surface-subtle)}.scenario-menu button.active{color:var(--primary);background:var(--primary-soft)}.scenario-menu strong,.scenario-menu small{display:block}.scenario-menu strong{font-size:14px}.scenario-menu small{margin-top:5px;color:var(--text-muted);font-size:11px;line-height:1.6}.scenario-detail{min-width:0;padding:28px}
.detail-heading,.form-heading{display:flex;justify-content:space-between;gap:22px;padding-bottom:25px;border-bottom:1px solid var(--border-color)}.detail-heading>div:first-child,.form-heading>div:first-child{min-width:0}.detail-heading>div>span,.form-heading>div>span{color:var(--primary);font-size:12px;font-weight:750;letter-spacing:.12em}.detail-heading h2,.form-heading h2{margin:8px 0 7px;font-size:27px}.detail-actions,.form-actions{display:flex;align-items:flex-start;gap:8px;flex:none}.detail-actions a,.detail-actions button,.form-actions button{height:40px;display:flex;align-items:center;gap:7px;padding:0 15px;color:var(--text-color);background:var(--surface);border:1px solid var(--border-color);border-radius:8px;font-size:13px;text-decoration:none;white-space:nowrap}.detail-actions button svg{width:16px}.detail-actions a,.form-actions button.primary{color:#fff;background:var(--primary);border-color:var(--primary)}.detail-actions button:hover,.form-actions button:hover{background:var(--surface-subtle)}.detail-actions a:hover,.form-actions button.primary:hover{background:var(--primary-hover)}.form-actions button:disabled{cursor:wait;opacity:.6}
.policy-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:20px 0}.policy-grid section,.workflow{padding:17px;background:var(--surface-subtle);border-radius:8px}label{display:block;margin-bottom:8px;color:var(--text-soft);font-size:11px;font-weight:750;letter-spacing:.1em;text-transform:uppercase}.policy-grid strong{font-size:15px}.policy-grid p{margin:7px 0 0;color:var(--text-muted);font-size:12px;line-height:1.65}.workflow ol{display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:8px;margin:0;padding:0;list-style:none}.workflow li{min-height:84px;padding:13px;background:var(--surface);border:1px solid var(--border-color);border-radius:8px}.workflow li span{display:block;color:var(--primary);font-size:11px}.workflow li strong{display:block;margin-top:15px;font-size:13px}.guardrail{display:flex;gap:12px;margin-top:12px;padding:17px;color:var(--success);background:var(--success-soft);border-radius:8px}.guardrail svg{width:21px;flex:none}.guardrail label{color:inherit}.guardrail p{margin:0;color:var(--text-color);font-size:13px}
.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:18px 16px;padding-top:24px}.form-grid label{position:relative;margin:0;color:var(--text-muted);font-size:12px;letter-spacing:0;text-transform:none}.form-grid label>span{display:block;margin-bottom:8px;color:var(--text-color);font-size:13px}.form-grid .wide{grid-column:1/-1}.form-grid input,.form-grid select,.form-grid textarea{width:100%;padding:10px 11px;color:var(--text-color);background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:8px;outline:0}.form-grid input,.form-grid select{height:42px}.form-grid textarea{resize:vertical;line-height:1.65}.form-grid input:focus,.form-grid select:focus,.form-grid textarea:focus{border-color:var(--primary);box-shadow:0 0 0 3px color-mix(in srgb,var(--primary) 12%,transparent)}.form-grid small{display:block;margin-top:6px;color:var(--text-soft);font-size:11px;font-weight:400;line-height:1.5}
.editor-section{min-width:0;padding:18px;background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:10px}
.policy-grid p.unknown-summary{color:var(--danger)}
.policy-grid button{margin-left:6px;padding:0;color:var(--primary);background:none;border:0;font-size:12px;cursor:pointer}
.page-message button{margin-left:8px;padding:2px 7px;color:inherit;background:none;border:1px solid currentColor;border-radius:5px;font-size:12px;cursor:pointer}
.section-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:14px;margin-bottom:15px}
.section-heading h3,.config-summary h3{margin:0 0 6px;color:var(--text-color);font-size:15px}
.section-heading p,.config-summary p{margin:0;color:var(--text-muted);font-size:12px;line-height:1.6}
.section-count{flex:none;padding:5px 9px;color:var(--primary);background:var(--primary-soft);border-radius:6px;font-size:11px;font-weight:700}
.tool-options{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:10px}
.form-grid .tool-option{display:flex;align-items:flex-start;gap:11px;margin:0;padding:14px;color:var(--text-color);background:var(--surface);border:1px solid var(--border-color);border-radius:9px;cursor:pointer;letter-spacing:0;text-transform:none;transition:border-color .18s,background .18s,box-shadow .18s}
.form-grid .tool-option:hover{border-color:var(--primary)}
.form-grid .tool-option.selected{border-color:var(--primary);background:color-mix(in srgb,var(--primary) 5%,var(--surface))}
.form-grid .tool-option:focus-within{box-shadow:0 0 0 3px color-mix(in srgb,var(--primary) 14%,transparent)}
.form-grid .tool-option input{width:17px;height:17px;flex:none;margin:2px 0 0;padding:0;accent-color:var(--primary);box-shadow:none}
.tool-option-body{display:flex;min-width:0;flex-direction:column;gap:6px}
.form-grid .tool-option .tool-option-body{display:flex;margin:0;color:inherit;font-size:inherit}
.tool-option-top{display:flex;align-items:center;justify-content:space-between;gap:8px}
.tool-option-top strong{font-size:13px;line-height:1.4}
.tool-category{flex:none;padding:3px 6px;color:var(--text-muted);background:var(--surface-subtle);border-radius:5px;font-size:10px}
.tool-description,.tool-meta{color:var(--text-muted);font-size:11px;font-weight:400;line-height:1.55;text-wrap:pretty}
.tool-meta{color:var(--text-soft)}
.editor-state,.empty-note,.field-warning{margin:10px 0 0;color:var(--text-muted);font-size:12px;line-height:1.6}
.editor-state.error,.field-warning{color:var(--danger)}
.editor-state button,.unknown-tools button{margin-left:9px;padding:2px 6px;color:var(--primary);background:none;border:0;font-size:12px;cursor:pointer}
.unknown-tools{margin-top:12px;padding:11px 12px;color:var(--danger);background:color-mix(in srgb,var(--danger) 7%,var(--surface));border-radius:7px;font-size:12px}
.unknown-tools>strong{display:block;margin-bottom:6px}
.unknown-tools>div{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:4px 0}
.text-action{flex:none;padding:5px 0;color:var(--primary);background:none;border:0;font-size:12px;font-weight:700;cursor:pointer}
.text-action:hover,.editor-state button:hover,.unknown-tools button:hover{text-decoration:underline}
.step-list{display:grid;gap:9px}
.step-row{display:grid;grid-template-columns:32px minmax(0,1fr) auto;align-items:start;gap:10px;padding:12px;background:var(--surface);border:1px solid var(--border-color);border-radius:8px}
.step-number{padding-top:9px;color:var(--primary);font-size:12px;font-weight:750}
.step-content{min-width:0}
.form-grid .step-content label{display:block;margin:0 0 5px;color:var(--text-muted);font-size:11px;letter-spacing:0;text-transform:none}
.form-grid .step-content input{height:40px;background:var(--surface)}
.step-foot{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-top:5px;color:var(--text-soft);font-size:10px}
.form-grid .step-foot select{width:auto;max-width:210px;height:29px;padding:2px 8px;background:var(--surface);font-size:11px}
.step-actions{display:flex;gap:4px}
.step-actions button{display:grid;width:30px;height:32px;place-items:center;color:var(--text-muted);background:var(--surface);border:1px solid var(--border-color);border-radius:6px;cursor:pointer}
.step-actions button:hover:not(:disabled){color:var(--primary);border-color:var(--primary)}
.step-actions button:disabled{opacity:.38;cursor:default}
.step-actions svg,.add-step svg{width:15px;height:15px}
.add-step{display:flex;align-items:center;gap:6px;margin-top:12px;padding:8px 11px;color:var(--primary);background:var(--surface);border:1px solid var(--border-color);border-radius:7px;font-size:12px;font-weight:700;cursor:pointer}
.add-step:hover:not(:disabled){border-color:var(--primary)}
.add-step:disabled,.text-action:disabled{opacity:.5;cursor:default}
.config-summary{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:24px;padding:17px;background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:9px}
.config-summary p+p{margin-top:4px}
.config-summary button{height:39px;flex:none;padding:0 15px;color:#fff;background:var(--primary);border:1px solid var(--primary);border-radius:7px;cursor:pointer}
.config-summary button:hover:not(:disabled){background:var(--primary-hover)}
.config-summary button:disabled{opacity:.5;cursor:default}
.validation-list{margin:10px 0 0;padding:10px 12px 10px 29px;color:var(--danger);background:color-mix(in srgb,var(--danger) 7%,var(--surface));border-radius:7px;font-size:12px;line-height:1.65}
.page-message a{margin-left:8px;color:inherit;font-weight:700;text-decoration:underline}
.workflow>p{margin:10px 0 0;color:var(--text-muted);font-size:11px;line-height:1.5}
.scenario-form button:focus-visible,.scenario-detail a:focus-visible{outline:2px solid var(--primary);outline-offset:2px}
@media(max-width:900px){.scenario-page{padding:20px 14px}.scenario-layout{grid-template-columns:1fr}.scenario-menu{display:grid;grid-template-columns:1fr 1fr}.detail-heading,.form-heading{flex-direction:column}.detail-actions,.form-actions{align-self:flex-end}.form-grid{grid-template-columns:1fr}.form-grid .wide{grid-column:auto}}
@media(max-width:560px){.scenario-menu{grid-template-columns:1fr}.scenario-detail{padding:20px 16px}.policy-grid{grid-template-columns:1fr}.detail-actions,.form-actions{width:100%}.detail-actions a,.detail-actions button,.form-actions button{flex:1;justify-content:center}}
@media(max-width:700px){.section-heading,.config-summary{flex-direction:column;align-items:stretch}.section-count,.text-action{align-self:flex-start}.tool-options{grid-template-columns:1fr}.config-summary button{width:100%}}
@media(max-width:560px){.step-row{grid-template-columns:27px minmax(0,1fr)}.step-actions{grid-column:2;justify-content:flex-end}.step-foot{align-items:flex-start;flex-direction:column}.form-grid .step-foot select{max-width:100%}}
@media(max-width:560px){.step-actions button{width:44px;height:44px}.step-actions svg{width:18px;height:18px}}
</style>
