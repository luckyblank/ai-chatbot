<template>
  <section class="workflow-page" :aria-busy="loading">
    <header class="page-header">
      <div>
        <h1>工作流编排</h1>
        <p>集中管理服务流程、运行记录与启停状态，复杂编排在独立设计器中完成。</p>
      </div>
      <div class="header-actions">
        <RouterLink class="primary" :to="{ name: 'workflow-designer-new' }" target="_blank" rel="noopener"><PlusIcon /> 新建工作流</RouterLink>
      </div>
    </header>

    <div v-if="notice" class="notice" role="status" aria-live="polite">{{ notice }}</div>

    <div v-if="accessDenied" class="access-denied" role="alert"><ExclamationCircleIcon /><strong>仅管理员可管理和试运行工作流</strong><p>此处会调用真实知识库、业务数据与模型服务。请使用管理员账号访问。</p></div>

    <div v-else class="workspace">
      <aside class="workflow-list" aria-label="工作流列表">
        <div class="list-heading"><strong>工作流</strong><span>{{ workflows.length }}</span></div>
        <div v-if="loading" class="list-loading" role="status"><i v-for="index in 4" :key="index"></i></div>
        <template v-else>
          <button v-for="item in workflows" :key="item.id" type="button" :class="{ active: item.id === selectedId }" :aria-current="item.id === selectedId ? 'true' : undefined" @click="selectWorkflow(item.id)">
            <span class="status-dot" :class="{ enabled: item.enabled }"></span>
            <span><strong>{{ item.name }}</strong><small>{{ scenarioName(item.scenarioCode) }}</small></span>
            <ChevronRightIcon />
          </button>
          <div v-if="!workflows.length" class="empty-list"><QueueListIcon /><strong>还没有工作流</strong><span>新建一条流程后，可在这里查看运行状态。</span></div>
        </template>
      </aside>

      <main v-if="selected" class="workflow-detail">
        <header class="detail-header">
          <div>
            <div class="badges"><span>{{ scenarioName(selected.scenarioCode) }}</span><span :class="selected.enabled ? 'online' : 'offline'">{{ selected.enabled ? '已启用' : '已停用' }}</span></div>
            <h2>{{ selected.name }}</h2>
            <p>{{ selected.description || '暂未填写业务说明。' }}</p>
          </div>
          <div class="actions">
            <RouterLink class="designer-link" :to="{ name: 'workflow-designer', params: { id: selected.id } }" target="_blank" rel="noopener"><PencilSquareIcon /> 打开设计器</RouterLink>
            <RouterLink v-if="selected.enabled" class="run-button" :to="{ name: 'workflow-designer', params: { id: selected.id }, query: { run: '1' } }" target="_blank" rel="noopener"><PlayIcon />试运行</RouterLink>
            <button v-else class="run-button" type="button" disabled title="请先启用工作流"><PlayIcon />试运行</button>
          </div>
        </header>

        <section class="canvas-section">
          <div class="section-heading"><div><span>流程预览</span><strong>{{ selected.nodes.length }} 个节点{{ Array.isArray(selected.edges) ? ` · ${selected.edges.length} 条连线` : ' · 旧版顺序连线' }}</strong></div><small>进入设计器查看和编辑真实执行路径</small></div>
          <div class="workflow-canvas" role="region" :aria-label="`${selected.name}流程预览`">
            <template v-for="(node, index) in selected.nodes" :key="node.id">
              <article class="node-card" :class="node.type">
                <div class="node-top"><span>{{ index + 1 }}</span><component :is="nodeIcon(node.type)" /></div>
                <label>{{ nodeTypeLabel(node.type) }}</label><h3>{{ node.name }}</h3><p>{{ node.description || '暂未填写节点说明' }}</p>
              </article>
              <div v-if="index < selected.nodes.length - 1 && !Array.isArray(selected.edges)" class="connector" aria-hidden="true"><span></span><ChevronRightIcon /></div>
            </template>
          </div>
        </section>

        <section class="run-section">
          <div class="section-heading"><div><span>最近运行</span><strong>执行记录</strong></div><small>查看真实节点执行结果与失败原因</small></div>
          <div v-if="lastRun" class="run-result">
            <header><div><component :is="lastRun.status === 'failed' ? ExclamationCircleIcon : CheckCircleIcon" :class="{ failed: lastRun.status === 'failed' }" /><span><strong>{{ runStatusLabel(lastRun.status) }}</strong><small>{{ formatDate(lastRun.completedAt || lastRun.startedAt) }} · {{ lastRun.steps.length }} 个步骤</small></span></div><code>{{ shortId(lastRun.id) }}</code></header>
            <ol><li v-for="step in lastRun.steps" :key="step.nodeId"><span class="run-dot" :class="step.status"></span><div><strong>{{ step.nodeName }}</strong><p>{{ step.detail || runStatusLabel(step.status) }}</p></div><time>{{ step.durationMs == null ? '' : `${step.durationMs} ms` }}</time></li></ol>
          </div>
          <div v-else class="run-empty"><PlayIcon /><span><strong>尚未运行</strong><small>点击上方“试运行”，输入测试数据后查看真实节点执行过程。</small></span></div>
        </section>
      </main>

      <main v-else-if="!loading" class="workflow-detail empty-detail"><QueueListIcon /><h2>选择一条工作流</h2><p>查看流程节点、运行记录，或进入独立设计器继续编排。</p></main>
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { CheckCircleIcon, ChevronRightIcon, CircleStackIcon, CpuChipIcon, CursorArrowRaysIcon, ExclamationCircleIcon, FunnelIcon, HandRaisedIcon, PencilSquareIcon, PlayIcon, PlusIcon, QueueListIcon, WrenchScrewdriverIcon } from '@heroicons/vue/24/outline'
import { scenarioByCode } from '../data/scenarios'
import { workflowAPI } from '../services/api'
import { authState } from '../services/auth'

const route = useRoute()
const router = useRouter()
const workflows = ref([])
const selectedId = ref('')
const runs = ref([])
const loading = ref(true)
const notice = ref('')
const accessDenied = ref(false)
const selected = computed(() => workflows.value.find(item => item.id === selectedId.value))
const lastRun = computed(() => runs.value.find(run => run.mode === 'execution'))

onMounted(() => {
  void load()
  window.addEventListener('focus', handleWindowFocus)
})
onBeforeUnmount(() => {
  window.removeEventListener('focus', handleWindowFocus)
})

watch(() => route.query.workflow, id => {
  if (!workflows.value.length) return
  const requestedId = String(id || '')
  if (requestedId && workflows.value.some(item => item.id === requestedId)) {
    if (requestedId !== selectedId.value) void selectWorkflow(requestedId, false)
    return
  }
  const fallbackId = String(workflows.value[0]?.id || '')
  if (fallbackId && fallbackId !== selectedId.value) void selectWorkflow(fallbackId, false)
  if (requestedId) void router.replace({ query: fallbackId ? { workflow: fallbackId } : {} })
})

async function load() {
  loading.value = true
  notice.value = ''
  accessDenied.value = false
  if (authState.user && authState.user.role !== 'ADMIN') {
    workflows.value = []
    runs.value = []
    selectedId.value = ''
    accessDenied.value = true
    loading.value = false
    return
  }
  try {
    workflows.value = await workflowAPI.list()
    const requestedId = String(route.query.workflow || '')
    selectedId.value = workflows.value.some(item => item.id === requestedId) ? requestedId : String(workflows.value[0]?.id || '')
    if (selectedId.value) await loadRuns()
    if (requestedId && requestedId !== selectedId.value) await router.replace({ query: selectedId.value ? { workflow: selectedId.value } : {} })
  } catch (error) {
    if (error.status === 403) {
      workflows.value = []
      runs.value = []
      selectedId.value = ''
      accessDenied.value = true
    }
    else notice.value = error.message
  } finally {
    loading.value = false
  }
}

function handleWindowFocus() {
  if (document.visibilityState === 'visible') void load()
}

async function selectWorkflow(id, updateRoute = true) {
  selectedId.value = id
  await loadRuns()
  if (updateRoute) await router.replace({ query: { workflow: id } })
}

async function loadRuns() {
  try { runs.value = await workflowAPI.runs(selectedId.value) }
  catch { runs.value = [] }
}

function scenarioName(code) { return scenarioByCode(code).name }
function nodeTypeLabel(type) { return ({ input: '开始', condition: '条件判断', knowledge: '知识检索', tool: '业务工具', model: '模型处理', approval: '人工处理', output: '结束' })[type] || '处理节点' }
function nodeIcon(type) { return ({ input: CursorArrowRaysIcon, condition: FunnelIcon, knowledge: CircleStackIcon, tool: WrenchScrewdriverIcon, model: CpuChipIcon, approval: HandRaisedIcon, output: CheckCircleIcon })[type] || FunnelIcon }
function formatDate(value) { return value ? new Date(value).toLocaleString('zh-CN') : '' }
function shortId(value) { return value ? `${value.slice(0, 8)}…` : '' }
function runStatusLabel(status) { return ({ completed: '运行完成', failed: '运行失败', waiting: '等待人工确认', skipped: '未走分支' })[status] || '运行中' }
</script>

<style scoped lang="scss">
.workflow-page{max-width:1500px;margin:0 auto;padding:34px clamp(22px,3vw,46px) 70px}.page-header{display:flex;align-items:end;justify-content:space-between;gap:24px;margin-bottom:26px}.page-header h1{margin:0 0 7px;font-size:34px;letter-spacing:-.035em}.page-header p{margin:0;color:var(--text-muted);font-size:14px}.header-actions,.actions{display:flex;align-items:center;gap:8px}.primary,.actions>a{min-height:40px;display:inline-flex;align-items:center;justify-content:center;gap:7px;padding:0 13px;border-radius:8px;text-decoration:none;white-space:nowrap}.primary{color:#fff;background:var(--primary);border:1px solid var(--primary);font-weight:650}.primary:hover{background:var(--primary-hover)}.primary svg,.actions svg,.header-actions svg{width:17px}.action-menu{position:relative;flex:none}.menu-trigger{width:40px;height:40px;display:grid;place-items:center;padding:0;color:var(--text-muted);background:var(--surface);border:1px solid var(--border-color);border-radius:8px}.menu-trigger:hover,.menu-trigger[aria-expanded="true"]{color:var(--primary);background:var(--primary-soft);border-color:color-mix(in srgb,var(--primary) 30%,var(--border-color))}.menu-trigger svg{width:19px}.action-popover{position:absolute;z-index:20;top:calc(100% + 8px);right:0;width:230px;padding:6px;background:var(--surface);border:1px solid var(--border-color);border-radius:9px;box-shadow:var(--shadow-float)}.action-popover>a,.action-popover>button{width:100%;min-height:52px;display:grid;grid-template-columns:20px minmax(0,1fr);align-items:center;gap:10px;padding:8px 10px;color:var(--text-color);background:transparent;border:0;border-radius:7px;text-align:left;text-decoration:none}.action-popover>a:hover,.action-popover>button:hover:not(:disabled){color:var(--primary);background:var(--surface-subtle)}.action-popover>button:disabled{opacity:.45}.action-popover svg{width:18px}.action-popover strong,.action-popover small{display:block}.action-popover strong{font-size:12px}.action-popover small{margin-top:3px;color:var(--text-soft);font-size:10px;line-height:1.4}.notice{margin-bottom:16px;padding:11px 14px;color:var(--primary);background:var(--primary-soft);border-radius:8px;font-size:13px}.workspace{display:grid;grid-template-columns:280px minmax(0,1fr);gap:16px;align-items:start}.workflow-list,.workflow-detail{background:var(--surface);border:1px solid var(--border-color);border-radius:12px}.workflow-list{padding:9px}.list-heading{display:flex;justify-content:space-between;padding:11px 10px 12px}.list-heading strong{font-size:14px}.list-heading span{color:var(--text-soft);font-size:12px}.workflow-list>button{width:100%;display:grid;grid-template-columns:9px minmax(0,1fr) 16px;align-items:center;gap:10px;padding:12px 10px;color:var(--text-color);background:transparent;border:0;border-radius:8px;text-align:left}.workflow-list>button:hover{background:var(--surface-subtle)}.workflow-list>button:focus-visible{outline:2px solid var(--primary);outline-offset:-2px}.workflow-list>button.active{background:var(--primary-soft)}.status-dot{width:8px;height:8px;border-radius:50%;background:var(--text-soft)}.status-dot.enabled{background:var(--success)}.workflow-list button strong,.workflow-list button small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.workflow-list button strong{font-size:14px}.workflow-list button small{margin-top:4px;color:var(--text-muted);font-size:12px}.workflow-list button>svg{width:15px;color:var(--text-soft)}.list-loading{display:grid;gap:8px;padding:5px}.list-loading i{height:58px;background:linear-gradient(90deg,var(--surface-subtle),var(--surface-strong),var(--surface-subtle));background-size:220% 100%;border-radius:8px;animation:loading 1.2s linear infinite}.empty-list{display:grid;justify-items:center;padding:34px 18px;color:var(--text-muted);text-align:center}.empty-list svg{width:30px}.empty-list strong{margin-top:10px;color:var(--text-color);font-size:14px}.empty-list span{margin-top:5px;font-size:12px;line-height:1.6}.workflow-detail{min-width:0;padding:25px}.empty-detail{min-height:420px;display:flex;align-items:center;justify-content:center;flex-direction:column;text-align:center}.empty-detail svg{width:38px;color:var(--text-soft)}.empty-detail h2{margin:13px 0 5px;font-size:20px}.empty-detail p{margin:0;color:var(--text-muted);font-size:13px}.detail-header{display:flex;justify-content:space-between;gap:24px;padding-bottom:24px;border-bottom:1px solid var(--border-color)}.badges{display:flex;gap:7px}.badges span{padding:4px 7px;color:var(--primary);background:var(--primary-soft);border-radius:5px;font-size:11px}.badges .online{color:var(--success);background:var(--success-soft)}.badges .offline{color:var(--text-muted);background:var(--surface-strong)}.detail-header h2{margin:12px 0 7px;font-size:26px}.detail-header p{max-width:720px;margin:0;color:var(--text-muted);font-size:14px;line-height:1.65}.actions{align-items:flex-start;justify-content:flex-end}.actions .designer-link{color:var(--primary);background:var(--primary-soft);border:1px solid transparent}.actions .designer-link:hover{border-color:color-mix(in srgb,var(--primary) 28%,transparent)}.actions .menu-trigger{width:40px}.canvas-section,.run-section{padding-top:25px}.section-heading{display:flex;justify-content:space-between;align-items:end;margin-bottom:15px}.section-heading>div span,.section-heading>div strong{display:block}.section-heading span{color:var(--text-soft);font-size:11px;font-weight:700;letter-spacing:.08em}.section-heading strong{margin-top:4px;font-size:15px}.section-heading small{color:var(--text-soft);font-size:12px}.workflow-canvas{display:flex;align-items:stretch;overflow-x:auto;padding:4px 2px 14px}.node-card{width:178px;min-width:178px;min-height:166px;padding:14px;background:var(--surface-subtle);border:1px solid var(--border-color);border-top:3px solid var(--primary);border-radius:9px}.node-card.knowledge{border-top-color:#7357c6}.node-card.tool{border-top-color:#d97706}.node-card.approval,.node-card.output{border-top-color:var(--success)}.node-top{display:flex;justify-content:space-between}.node-top>span{width:24px;height:24px;display:grid;place-items:center;color:var(--primary);background:var(--primary-soft);border-radius:5px;font-size:11px;font-weight:700}.node-top svg{width:21px;color:var(--text-muted)}.node-card label{display:block;margin-top:16px;color:var(--text-soft);font-size:10px;letter-spacing:.08em}.node-card h3{margin:5px 0 6px;font-size:15px}.node-card p{margin:0;color:var(--text-muted);font-size:12px;line-height:1.55;text-wrap:pretty}.connector{width:40px;min-width:40px;display:flex;align-items:center;color:var(--primary)}.connector span{height:1px;flex:1;background:var(--primary)}.connector svg{width:16px;margin-left:-4px}.run-result,.run-empty{border:1px solid var(--border-color);border-radius:9px}.run-result>header{display:flex;justify-content:space-between;align-items:center;padding:14px 16px;background:var(--surface-subtle);border-bottom:1px solid var(--border-color)}.run-result header>div{display:flex;align-items:center;gap:10px}.run-result header svg{width:23px;color:var(--success)}.run-result header strong,.run-result header small{display:block}.run-result header strong{font-size:14px}.run-result header small{margin-top:3px;color:var(--text-muted);font-size:11px}.run-result code{color:var(--text-soft);font-size:11px}.run-result ol{margin:0;padding:9px 16px;list-style:none}.run-result li{display:grid;grid-template-columns:10px minmax(0,1fr) auto;gap:10px;align-items:start;padding:10px 0}.run-dot{width:8px;height:8px;margin-top:5px;border-radius:50%;background:var(--success)}.run-result li strong{font-size:13px}.run-result li p{margin:4px 0 0;color:var(--text-muted);font-size:12px}.run-result time{color:var(--text-soft);font-size:11px}.run-empty{display:flex;align-items:center;justify-content:center;gap:12px;padding:34px;color:var(--text-muted)}.run-empty>svg{width:28px}.run-empty strong,.run-empty small{display:block}.run-empty small{margin-top:5px}.menu-trigger:focus-visible,.action-popover>a:focus-visible,.action-popover>button:focus-visible,.actions>a:focus-visible,.primary:focus-visible{outline:2px solid var(--primary);outline-offset:2px}@keyframes loading{to{background-position:-220% 0}}@media(max-width:1100px){.workspace{grid-template-columns:240px minmax(0,1fr)}.detail-header{flex-direction:column}.actions{justify-content:flex-start}.detail-popover{right:auto;left:0}}@media(max-width:820px){.workflow-page{padding:26px 14px 90px}.page-header{align-items:flex-start;flex-direction:column}.header-actions{width:100%;flex-wrap:wrap}.header-actions>.primary{flex:1}.workspace{grid-template-columns:1fr}.workflow-list{max-height:270px;overflow:auto}.section-heading small{display:none}}@media(max-width:520px){.workflow-detail{padding:18px}.workflow-canvas{margin-inline:-18px;padding-inline:18px}.run-result li{grid-template-columns:10px minmax(0,1fr)}.run-result time{grid-column:2}}@media(prefers-reduced-motion:reduce){.list-loading i{animation:none}}
.run-button{min-height:40px;display:inline-flex;align-items:center;justify-content:center;gap:7px;padding:0 13px;color:var(--text-color);background:var(--surface);border:1px solid var(--border-color);border-radius:8px;font-size:12px;font-weight:650;text-decoration:none;white-space:nowrap}.run-button:hover:not(:disabled){color:var(--primary);background:var(--primary-soft);border-color:color-mix(in srgb,var(--primary) 30%,var(--border-color))}.run-button:disabled{cursor:not-allowed;opacity:.45}.run-button:focus-visible{outline:2px solid var(--primary);outline-offset:2px}.run-result header svg.failed{color:var(--danger)}.run-dot.failed{background:var(--danger)}
.access-denied{min-height:300px;display:flex;align-items:center;justify-content:center;flex-direction:column;padding:26px;color:var(--text-muted);background:var(--surface);border:1px solid var(--border-color);border-radius:12px;text-align:center}.access-denied svg{width:34px;color:var(--warning)}.access-denied strong{margin-top:12px;color:var(--text-color);font-size:16px}.access-denied p{max-width:440px;margin:7px 0 0;font-size:12px;line-height:1.7}
</style>
