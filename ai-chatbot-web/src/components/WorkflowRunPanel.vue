<script setup>
import { computed, ref, watch } from 'vue'
import { PlayIcon, XMarkIcon, ArrowPathIcon, CheckCircleIcon, ExclamationTriangleIcon, ChevronRightIcon } from '@heroicons/vue/24/outline'
import { buildRunInput, inputFields, readPath, validateWorkflow, testCaseResult } from '../services/workflowDesigner'

const props = defineProps({
  workflow: Object, edges: Array, template: Object, phase: String, record: Object, steps: Array,
  error: String, saving: Boolean, dirty: Boolean, running: Boolean, approvalBusy: String,
  refreshing: Boolean, knowledgeBases: Array, aiStatus: Object, resourceError: String
})
const emit = defineEmits(['close', 'run', 'retry', 'approve', 'refresh', 'select-node', 'fix-node', 'reload-resources'])
const values = ref({}), extra = ref('{}'), knowledgeBaseId = ref(''), selectedCase = ref(''), executedCase = ref(null)
const inputError = ref(''), selectedStep = ref(''), tab = ref('trace')
const fields = computed(() => inputFields(props.workflow.nodes))
const cases = computed(() => props.template?.testCases || [])
const currentCase = computed(() => cases.value.find(item => item.id === selectedCase.value))
const needsKnowledge = computed(() => props.workflow.nodes.some(node => node.type === 'knowledge'))
const needsAi = computed(() => props.workflow.nodes.some(node => ['knowledge', 'model'].includes(node.type)))
const issues = computed(() => validateWorkflow(props.workflow, props.edges, knowledgeBaseId.value))
const aiUnavailable = computed(() => needsAi.value && props.aiStatus && (!props.aiStatus.enabled || !props.aiStatus.configured))
const activeStep = computed(() => props.steps.find(step => step.nodeId === selectedStep.value))
const result = computed(() => testCaseResult(executedCase.value, props.record))
const completedCount = computed(() => props.steps.filter(step => step.status === 'completed').length)
const phaseLabel = computed(() => ({ setup: '准备测试', running: '正在运行', waiting: '等待人工确认', completed: '运行完成', failed: '运行失败', interrupted: '连接已中断' })[props.phase] || '试运行')
const statusLabel = status => ({ waiting: '待执行', running: '执行中', completed: '已完成', failed: '失败', skipped: '未走分支', 'not-run': '未执行' })[status] || status
const format = value => typeof value === 'string' ? value : JSON.stringify(value, null, 2)

watch(() => props.workflow.id, (id, previous) => {
  if (!previous || id === previous) return
  values.value = {}; extra.value = '{}'; selectedCase.value = ''; executedCase.value = null; inputError.value = ''
})
watch(() => props.phase, phase => {
  if (phase === 'setup') { inputError.value = ''; selectedStep.value = ''; tab.value = 'trace' }
  if (phase === 'failed') selectedStep.value = props.steps.find(step => step.status === 'failed')?.nodeId || ''
})
function fillCase() {
  const sample = currentCase.value
  if (!sample) return
  values.value = Object.fromEntries(fields.value.map(field => [field.path, readPath(sample.input, field.path) ?? '']))
  extra.value = JSON.stringify(Object.fromEntries(Object.entries(sample.input).filter(([key]) => !fields.value.some(field => field.path.split('.')[0] === key))), null, 2)
  inputError.value = ''
}
function submit() {
  inputError.value = ''
  try {
    if (issues.value.length || aiUnavailable.value) return
    const input = buildRunInput(fields.value, values.value, extra.value)
    executedCase.value = currentCase.value ? JSON.parse(JSON.stringify(currentCase.value)) : null
    emit('run', { input, ...(knowledgeBaseId.value ? { knowledgeBaseId: knowledgeBaseId.value } : {}) })
  } catch (error) { inputError.value = error.message || '请检查测试输入。' }
}
function inspect(step) {
  selectedStep.value = selectedStep.value === step.nodeId ? '' : step.nodeId
  emit('select-node', step.nodeId)
}
</script>

<template>
  <aside class="test-panel" aria-label="工作流试运行">
    <header class="test-heading">
      <span class="test-icon"><PlayIcon /></span>
      <div><strong>试运行</strong><small>用一组数据，验证整个流程</small></div>
      <button class="icon-control" aria-label="关闭试运行面板" type="button" @click="emit('close')"><XMarkIcon /></button>
    </header>
    <div class="test-body">
      <template v-if="phase === 'setup'">
        <div class="section-heading"><span>01</span><h3>测试输入</h3><small>真实执行 · 只读查询</small></div>
        <form id="workflow-test-form" @submit.prevent="submit">
          <div v-if="cases.length" class="sample-picker">
            <label for="workflow-test-case">从场景用例开始</label>
            <select id="workflow-test-case" v-model="selectedCase" @change="fillCase"><option value="">自定义输入</option><option v-for="item in cases" :key="item.id" :value="item.id">{{ item.name }}</option></select>
            <p>{{ currentCase?.description || '选择用例自动填入参数，也可以自行修改。' }}</p>
          </div>
          <label v-for="field in fields" :key="field.path" class="input-field">
            <span>{{ field.label }}<em v-if="field.required">必填</em><small v-else>选填</small></span>
            <textarea v-if="field.path === 'question'" v-model="values[field.path]" rows="3" maxlength="4000" placeholder="例如：这笔订单是否可以申请售后？"></textarea>
            <select v-else-if="field.type === 'boolean'" v-model="values[field.path]"><option value="">请选择</option><option :value="true">是，材料齐全</option><option :value="false">否，需要补充</option></select>
            <input v-else v-model="values[field.path]" maxlength="4000" :placeholder="`填写${field.label}`">
          </label>
          <label v-if="needsKnowledge" class="input-field"><span>本次运行知识库<small>可覆盖未绑定的节点</small></span><select v-model="knowledgeBaseId"><option value="">使用节点绑定的知识库</option><option v-for="base in knowledgeBases" :key="base.id" :value="base.id">{{ base.name }}</option></select></label>
          <details class="advanced-input"><summary>高级输入参数 <small>JSON</small></summary><textarea v-model="extra" rows="5" spellcheck="false" aria-label="高级输入参数 JSON"></textarea><p>表单中已填写的字段优先于高级参数。</p></details>
          <p v-if="inputError" class="input-error" role="alert">{{ inputError }}</p>
        </form>
        <div class="section-heading checks-heading"><span>02</span><h3>运行前检查</h3><small>{{ issues.length ? `${issues.length} 项待处理` : '配置已就绪' }}</small></div>
        <div v-if="!issues.length" class="check-ready"><CheckCircleIcon /><span>节点配置与分支连线检查通过</span></div>
        <button v-for="(issue, index) in issues" :key="index" class="check-issue" type="button" @click="emit('fix-node', issue.nodeId)"><ExclamationTriangleIcon /><span><strong>{{ issue.title }}</strong><small>{{ issue.message }}</small></span><ChevronRightIcon /></button>
        <div v-if="aiUnavailable" class="environment-note" role="alert">当前环境未启用或未配置 AI 服务。知识检索与模型节点需要先配置服务；其他场景可使用内置业务查询用例。</div>
        <div v-else-if="needsAi" class="environment-note">{{ aiStatus ? 'AI 参数已配置，实际连通性将在执行时验证。知识库需要有已索引的文档。' : 'AI 状态暂不可用，执行时将由服务端验证。' }}</div>
        <button v-if="resourceError" class="resource-retry" type="button" @click="emit('reload-resources')">{{ resourceError }} · 点击重试</button>
      </template>
      <template v-else>
        <div class="execution-summary" :class="phase" role="status" aria-live="polite"><span class="state-dot"></span><strong>{{ phaseLabel }}</strong><small>{{ completedCount }} 个节点已完成</small></div>
        <p v-if="error" class="input-error" role="alert">{{ error }}</p>
        <div v-if="result && !running" class="case-result" :class="{ mismatch: !result.pass }"><CheckCircleIcon v-if="result.pass" /><ExclamationTriangleIcon v-else /><div><strong>{{ result.label }}</strong><small>{{ executedCase?.name }} · 预期{{ result.expected }}</small></div></div>
        <div v-if="phase === 'waiting'" class="approval-card"><h3>需要你的确认</h3><p>流程已暂停在「{{ record?.steps?.find(step => step.status === 'waiting')?.nodeName }}」。决定后继续本次运行。</p><div><button type="button" class="approve" :disabled="!!approvalBusy" @click="emit('approve', true)">{{ approvalBusy === 'approve' ? '处理中…' : '同意并继续' }}</button><button type="button" :disabled="!!approvalBusy" @click="emit('approve', false)">{{ approvalBusy === 'reject' ? '处理中…' : '拒绝并结束' }}</button></div></div>
        <div class="result-tabs" role="tablist" aria-label="运行结果视图"><button role="tab" type="button" :aria-selected="tab === 'trace'" @click="tab = 'trace'">执行轨迹</button><button role="tab" type="button" :aria-selected="tab === 'output'" @click="tab = 'output'">最终输出</button></div>
        <div v-if="tab === 'trace'" class="execution-steps">
          <div v-for="(step, index) in steps" :key="step.nodeId" class="execution-step" :class="[step.status, { selected: selectedStep === step.nodeId }]">
            <button type="button" :aria-expanded="selectedStep === step.nodeId" @click="inspect(step)"><span class="step-number">{{ index + 1 }}</span><strong>{{ step.nodeName }}</strong><small>{{ phase === 'waiting' && record?.waitingNodeId === step.nodeId ? '待确认' : statusLabel(step.status) }}</small><ChevronRightIcon /></button>
            <div v-if="selectedStep === step.nodeId" class="step-detail"><p>{{ activeStep?.detail || '该节点尚未执行。' }}</p><small v-if="step.durationMs != null">执行耗时 {{ step.durationMs }} ms</small><p v-if="step.error" class="input-error">{{ step.error }}</p><details v-if="step.input !== undefined"><summary>节点输入</summary><pre>{{ format(step.input) }}</pre></details><details v-if="step.output !== undefined" open><summary>节点输出</summary><pre>{{ format(step.output) }}</pre></details></div>
          </div>
        </div>
        <div v-else class="final-result"><p>{{ phase === 'completed' ? '本次运行的最终输出' : '流程尚未完成，以下为最近保存的上下文。' }}</p><pre v-if="record?.output !== undefined">{{ format(record.output) }}</pre><p v-else>等待服务端返回结果…</p></div>
        <details v-if="record?.id" class="run-metadata"><summary>运行信息</summary><dl><dt>运行编号</dt><dd>{{ record.id }}</dd><dt>开始时间</dt><dd>{{ new Date(record.startedAt).toLocaleString('zh-CN') }}</dd><dt>执行快照</dt><dd>{{ record.definitionVersion }}</dd></dl></details>
      </template>
    </div>
    <footer class="test-footer">
      <template v-if="phase === 'setup'"><p>{{ dirty ? '开始前将保存当前修改，再运行最新版本。' : '使用已保存的流程；业务工具仅执行只读查询。' }}</p><button class="start-run" type="submit" form="workflow-test-form" :disabled="saving || !!issues.length || aiUnavailable"><PlayIcon />{{ saving ? '正在保存…' : dirty ? '保存并开始运行' : '开始运行' }}</button></template>
      <template v-else><button v-if="['waiting', 'interrupted'].includes(phase)" class="refresh-run" type="button" :disabled="refreshing || !!approvalBusy" @click="emit('refresh')"><ArrowPathIcon />{{ refreshing ? '同步中…' : '同步运行状态' }}</button><button v-if="!running && phase !== 'waiting' && !(phase === 'interrupted' && record?.status === 'running')" class="start-run" type="button" @click="emit('retry')"><ArrowPathIcon />修改输入，再次运行</button><p v-if="running">正在接收节点结果，可以收起面板查看画布。</p></template>
    </footer>
  </aside>
</template>

<style scoped>
.test-panel{height:100%;min-height:0;display:flex;flex-direction:column;background:var(--surface);border-left:1px solid var(--border-color);color:var(--text-color)}
.test-heading{display:flex;align-items:center;gap:12px;padding:18px 20px;border-bottom:1px solid var(--border-color)}.test-icon{display:grid;place-items:center;width:36px;height:36px;border-radius:10px;color:var(--primary);background:var(--primary-soft)}.test-icon svg{width:18px}.test-heading strong,.test-heading small{display:block}.test-heading strong{font-size:15px}.test-heading small{margin-top:4px;font-size:11px;color:var(--text-soft)}.icon-control{margin-left:auto;width:30px;height:30px;display:grid;place-items:center;border:0;border-radius:6px;background:transparent;color:var(--text-muted)}.icon-control:hover{background:var(--surface-subtle)}.icon-control svg{width:18px}
.test-body{flex:1;min-height:0;overflow:auto;padding:20px}.section-heading{display:flex;align-items:center;gap:9px;margin-bottom:15px}.section-heading>span{font-size:10px;font-weight:700;color:var(--primary);background:var(--primary-soft);padding:4px 5px;border-radius:4px}.section-heading h3{margin:0;font-size:13px}.section-heading>small{margin-left:auto;color:var(--text-soft);font-size:10px}.checks-heading{margin-top:26px}.sample-picker{padding:12px;background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:9px;margin-bottom:18px}.sample-picker label{display:block;margin-bottom:7px;font-size:11px;font-weight:650}.sample-picker p,.advanced-input p{margin:8px 0 0;color:var(--text-soft);font-size:11px;line-height:1.7}.input-field{display:grid;gap:8px;margin-top:16px}.input-field>span{display:flex;align-items:center;gap:8px;font-size:12px;font-weight:650}.input-field small,.input-field em{font-size:10px;color:var(--text-soft);font-style:normal;font-weight:400}.input-field em{color:var(--primary)}select,input,textarea{width:100%;min-width:0;box-sizing:border-box;border:1px solid var(--border-color);border-radius:7px;background:var(--surface);color:var(--text-color);padding:10px;font:inherit;font-size:12px;outline:none}textarea{resize:vertical;line-height:1.7}input:focus,select:focus,textarea:focus{border-color:var(--primary);box-shadow:0 0 0 3px var(--primary-soft)}.advanced-input{margin-top:18px}.advanced-input summary,.run-metadata summary{color:var(--text-muted);font-size:11px;cursor:pointer}.advanced-input summary small{font:10px monospace;color:var(--text-soft);margin-left:6px}.advanced-input textarea{margin-top:10px;font-family:monospace}
.check-ready{display:flex;align-items:center;gap:8px;color:var(--success);font-size:11px;line-height:1.6}.check-ready svg{width:17px;flex:none}.check-issue{width:100%;display:flex;align-items:center;gap:8px;padding:11px 0;border:0;border-bottom:1px solid var(--border-color);text-align:left;background:transparent;color:var(--warning)}.check-issue>svg{width:16px;flex:none}.check-issue>svg:last-child{margin-left:auto;color:var(--text-soft);width:13px}.check-issue strong,.check-issue small{display:block}.check-issue strong{font-size:11px;color:var(--text-color)}.check-issue small{font-size:11px;margin-top:4px;line-height:1.6}.environment-note{margin-top:14px;padding:11px;border-radius:7px;background:var(--surface-subtle);color:var(--text-muted);font-size:11px;line-height:1.8}.resource-retry{margin-top:12px;color:var(--primary);border:0;background:transparent;font-size:11px}.input-error{padding:10px;background:color-mix(in srgb,var(--danger) 7%,var(--surface));color:var(--danger);border-radius:7px;font-size:12px;line-height:1.7;overflow-wrap:anywhere}.test-footer{flex:none;padding:14px 20px 18px;border-top:1px solid var(--border-color)}.test-footer p{font-size:10px;color:var(--text-soft);line-height:1.6;margin:0 0 10px}.start-run,.refresh-run{width:100%;min-height:40px;display:flex;align-items:center;justify-content:center;gap:8px;border:0;border-radius:8px;background:var(--primary);color:#fff;font-size:12px;font-weight:650}.start-run:hover:not(:disabled){background:var(--primary-hover)}button:disabled{opacity:.5;cursor:not-allowed}.start-run svg,.refresh-run svg{width:16px}.refresh-run{background:var(--surface-subtle);color:var(--text-color);margin-bottom:8px}
.execution-summary{display:flex;align-items:center;gap:8px;padding:14px;border-radius:9px;background:var(--surface-subtle)}.execution-summary strong{font-size:13px}.execution-summary small{margin-left:auto;font-size:10px;color:var(--text-muted)}.state-dot{width:7px;height:7px;border-radius:50%;background:var(--primary)}.completed .state-dot{background:var(--success)}.failed .state-dot{background:var(--danger)}.waiting .state-dot{background:var(--warning)}.case-result{display:flex;align-items:center;gap:9px;margin-top:12px;color:var(--success)}.case-result svg{width:18px;flex:none}.case-result strong,.case-result small{display:block;font-size:11px}.case-result small{color:var(--text-soft);font-size:10px;margin-top:5px}.case-result.mismatch{color:var(--warning)}.approval-card{margin-top:18px;border:1px solid color-mix(in srgb,var(--warning) 35%,var(--border-color));padding:14px;border-radius:9px;background:color-mix(in srgb,var(--warning) 5%,var(--surface))}.approval-card h3{margin:0;font-size:13px}.approval-card p{font-size:11px;line-height:1.8;color:var(--text-muted)}.approval-card>div{display:flex;gap:8px}.approval-card button{flex:1;min-height:36px;border:1px solid var(--border-color);border-radius:6px;background:var(--surface);color:var(--text-muted);font-size:11px}.approval-card .approve{background:var(--primary);border-color:var(--primary);color:white}
.result-tabs{display:flex;gap:20px;margin:20px 0 10px;border-bottom:1px solid var(--border-color)}.result-tabs button{border:0;border-bottom:2px solid transparent;padding:10px 0;background:transparent;color:var(--text-muted);font-size:12px}.result-tabs button[aria-selected=true]{color:var(--primary);border-bottom-color:var(--primary);font-weight:650}.execution-step{border-bottom:1px solid var(--border-color)}.execution-step>button{display:flex;align-items:center;gap:9px;padding:13px 0;width:100%;border:0;background:transparent;color:var(--text-color);text-align:left}.step-number{display:grid;place-items:center;flex:none;width:24px;height:24px;border:1px solid var(--border-color);border-radius:50%;font-size:10px;color:var(--text-soft)}.execution-step.completed .step-number{color:var(--success);border-color:var(--success);background:var(--success-soft)}.execution-step.failed .step-number{color:var(--danger);border-color:var(--danger)}.execution-step strong{font-size:12px;flex:1}.execution-step small{font-size:10px;color:var(--text-soft)}.execution-step>button>svg{width:13px;color:var(--text-soft)}.execution-step.selected>button>svg{transform:rotate(90deg)}.execution-step.skipped{opacity:.6}.step-detail{padding:0 0 14px 33px}.step-detail>p{font-size:11px;line-height:1.7;color:var(--text-muted)}.step-detail details{margin-top:10px}.step-detail summary{font-size:11px;cursor:pointer;color:var(--text-muted)}pre{max-height:320px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;padding:12px;border:1px solid var(--border-color);border-radius:7px;background:var(--surface-subtle);font:11px/1.7 Consolas,monospace}.final-result p{font-size:11px;color:var(--text-muted);line-height:1.7}.run-metadata{margin-top:24px}.run-metadata dl{font-size:10px;line-height:1.7}.run-metadata dt{color:var(--text-soft);margin-top:8px}.run-metadata dd{margin:2px 0;overflow-wrap:anywhere}button:focus-visible,summary:focus-visible{outline:2px solid var(--primary);outline-offset:3px}
</style>
