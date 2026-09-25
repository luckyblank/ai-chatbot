<template>
  <section class="scenario-page">
    <header class="page-heading">
      <div><h1>场景编排</h1><p>用业务流程、知识范围和工具权限定义助手能做什么，以及不能做什么。</p></div>
      <span v-if="loading" class="load-state">正在同步场景配置…</span>
    </header>

    <p v-if="pageMessage" class="page-message" :class="messageType" role="status">{{ pageMessage }}</p>

    <div class="scenario-layout">
      <nav class="scenario-menu" aria-label="业务场景">
        <button v-for="item in scenarios" :key="item.code" type="button" :class="{ active: item.code === selected.code }" :aria-current="item.code === selected.code ? 'true' : undefined" @click="selectScenario(item)">
          <strong>{{ item.name }}</strong><small>{{ item.summary }}</small>
        </button>
      </nav>

      <article class="scenario-detail">
        <template v-if="!editMode">
          <div class="detail-heading">
            <div><span>SCENARIO / {{ selected.code.toUpperCase() }}</span><h2>{{ selected.name }}</h2><p>{{ selected.summary }}</p></div>
            <div class="detail-actions"><button type="button" @click="startEdit"><PencilSquareIcon />编辑配置</button><RouterLink :to="`/customer-service?scenario=${selected.code}`">进入场景</RouterLink></div>
          </div>
          <div class="policy-grid">
            <section><label>知识策略</label><strong>知识库{{ selected.knowledgeMode }}</strong><p>{{ knowledgeDescription }}</p></section>
            <section><label>业务工具</label><strong>{{ selected.tools.length }} 个已授权工具</strong><p>{{ selected.tools.length ? selected.tools.join('、') : '本场景不开放业务工具。' }}</p></section>
          </div>
          <section class="workflow"><label>建议处理步骤</label><ol><li v-for="(step, index) in selected.process" :key="`${index}-${step}`"><span>{{ String(index + 1).padStart(2, '0') }}</span><strong>{{ step }}</strong></li></ol></section>
          <section class="guardrail"><ShieldCheckIcon /><div><label>业务边界</label><p>{{ selected.guardrail }}</p></div></section>
        </template>

        <form v-else class="scenario-form" @submit.prevent="saveScenario">
          <div class="form-heading">
            <div><span>EDIT / {{ selected.code.toUpperCase() }}</span><h2>编辑场景配置</h2><p>保存后，展示信息和该场景后续的新回答都会使用这份配置。</p></div>
            <div class="form-actions"><button type="button" :disabled="saving" @click="cancelEdit">取消</button><button class="primary" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存配置' }}</button></div>
          </div>

          <div class="form-grid">
            <label><span>场景名称</span><input v-model.trim="draft.name" maxlength="100" required></label>
            <label><span>会话短名称</span><input v-model.trim="draft.shortName" maxlength="40" required></label>
            <label class="wide"><span>场景说明</span><textarea v-model.trim="draft.summary" rows="3" maxlength="500" required></textarea><small>{{ draft.summary.length }}/500</small></label>
            <label><span>知识策略</span><select v-model="draft.knowledgeMode" required><option value="可选">可选</option><option value="推荐">推荐</option><option value="必选">必选</option></select></label>
            <label class="wide"><span>允许的业务工具</span><textarea v-model="draft.toolsText" rows="5" maxlength="1000" placeholder="每行一个工具；不开放工具时留空"></textarea><small>每行一个工具，模型会按此范围约束工具使用。</small></label>
            <label class="wide"><span>建议处理步骤</span><textarea v-model="draft.processText" rows="6" maxlength="1500" required placeholder="每行一个步骤，按建议顺序排列"></textarea><small>步骤用于提示模型，实际调用以工具权限和用户请求为准；每行一个步骤，至少保留 1 步。</small></label>
            <label class="wide"><span>业务边界</span><textarea v-model.trim="draft.guardrail" rows="4" maxlength="1000" required></textarea><small>{{ draft.guardrail.length }}/1000</small></label>
          </div>
        </form>
      </article>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { PencilSquareIcon, ShieldCheckIcon } from '@heroicons/vue/24/outline'
import { mergeScenario, mergeScenarios, scenarios, scenarioByCode } from '../data/scenarios'
import { scenarioAPI } from '../services/api'

const route = useRoute()
const router = useRouter()
const selected = ref(scenarioByCode(String(route.query.scenario || '')))
const editMode = ref(false)
const loading = ref(false)
const saving = ref(false)
const pageMessage = ref('')
const messageType = ref('info')
const draft = reactive({ name: '', shortName: '', summary: '', knowledgeMode: '可选', toolsText: '', processText: '', guardrail: '' })

const knowledgeDescription = computed(() => ({
  可选: '可以直接进行普通对话，也可以选择知识库增强回答。',
  推荐: '建议绑定对应业务知识库，资料不足时仍可进入人工或工单流程。',
  必选: '必须选择知识库，所有回答都需要可追溯引用。'
})[selected.value.knowledgeMode] || '按场景配置选择知识范围。')

watch(() => route.query.scenario, code => {
  selected.value = scenarioByCode(String(code || ''))
  if (editMode.value) fillDraft()
})

watch(() => route.query.edit, value => {
  if (value === '1' && !editMode.value) startEdit(false)
  else if (value !== '1' && editMode.value) editMode.value = false
}, { immediate: true })

onMounted(async () => {
  loading.value = true
  try {
    mergeScenarios(await scenarioAPI.list())
    selected.value = scenarioByCode(String(route.query.scenario || ''))
    if (editMode.value) fillDraft()
  } catch (error) {
    showMessage(`场景配置同步失败：${error.message}。当前显示内置配置。`, 'error')
  } finally {
    loading.value = false
  }
})

function selectScenario(item) {
  selected.value = item
  pageMessage.value = ''
  if (editMode.value) fillDraft()
  if (route.query.scenario !== item.code) router.replace({ query: { ...route.query, scenario: item.code } })
}

function fillDraft() {
  Object.assign(draft, {
    name: selected.value.name,
    shortName: selected.value.shortName,
    summary: selected.value.summary,
    knowledgeMode: selected.value.knowledgeMode,
    toolsText: selected.value.tools.join('\n'),
    processText: selected.value.process.join('\n'),
    guardrail: selected.value.guardrail
  })
}

function startEdit(updateRoute = true) {
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

function lines(value) {
  return value.split(/\r?\n/).map(item => item.trim()).filter(Boolean)
}

async function saveScenario() {
  const process = lines(draft.processText)
  if (!process.length) {
    showMessage('建议处理步骤至少需要保留一个步骤。', 'error')
    return
  }
  saving.value = true
  pageMessage.value = ''
  try {
    const updated = await scenarioAPI.update(selected.value.code, {
      name: draft.name,
      shortName: draft.shortName,
      summary: draft.summary,
      knowledgeMode: draft.knowledgeMode,
      tools: lines(draft.toolsText),
      process,
      guardrail: draft.guardrail
    })
    selected.value = mergeScenario(updated)
    editMode.value = false
    const query = { ...route.query, scenario: selected.value.code }
    delete query.edit
    await router.replace({ query })
    showMessage('场景配置已保存，后续新回答将使用最新配置。', 'success')
  } catch (error) {
    showMessage(`保存失败：${error.message}`, 'error')
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
@media(max-width:900px){.scenario-page{padding:20px 14px}.scenario-layout{grid-template-columns:1fr}.scenario-menu{display:grid;grid-template-columns:1fr 1fr}.detail-heading,.form-heading{flex-direction:column}.detail-actions,.form-actions{align-self:flex-end}.form-grid{grid-template-columns:1fr}.form-grid .wide{grid-column:auto}}
@media(max-width:560px){.scenario-menu{grid-template-columns:1fr}.scenario-detail{padding:20px 16px}.policy-grid{grid-template-columns:1fr}.detail-actions,.form-actions{width:100%}.detail-actions a,.detail-actions button,.form-actions button{flex:1;justify-content:center}}
</style>
