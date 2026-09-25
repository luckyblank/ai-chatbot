<script setup>
import { computed } from 'vue'
import { parseConfig } from '../services/workflowDesigner'
const props = defineProps({ node: Object, nodes: Array, edges: Array, disabled: Boolean, knowledgeBases: Array })
const emit = defineEmits(['update-config', 'branch'])
const config = computed(() => { try { return parseConfig(props.node) } catch { return null } })
const targets = computed(() => props.nodes.filter(node => node.id !== props.node.id && node.type !== 'input'))
function update(key, value) {
  const next = { ...config.value, [key]: value }
  if (value === '') delete next[key]
  emit('update-config', JSON.stringify(next, null, 2))
}
const branchTarget = branch => props.edges.find(edge => edge.source === props.node.id && edge.branch === branch)?.target || ''
</script>
<template>
  <div class="node-config-fields">
    <div class="config-section-title">执行设置</div>
    <p v-if="!config" class="config-invalid">JSON 格式有误，请在高级配置中修正。</p>
    <template v-else-if="node.type === 'condition'">
      <label>判断字段<input :value="config.field" :disabled="disabled" placeholder="例如 category 或 result.orderStatus" @input="update('field', $event.target.value)"></label>
      <label>比较方式<select :value="config.operator || ''" :disabled="disabled" @change="update('operator', $event.target.value)"><option value="" disabled>请选择</option><option value="equals">等于</option><option value="contains">包含</option><option value="exists">已填写 / 存在</option></select></label>
      <label v-if="config.operator !== 'exists'">比较值<input :value="config.value" :disabled="disabled" placeholder="例如 退款" @input="update('value', $event.target.value)"></label>
      <label v-for="branch in ['true', 'false']" :key="branch" class="branch-field"><span class="branch-label" :class="branch">{{ branch === 'true' ? '满足条件' : '不满足条件' }}</span><select :value="branchTarget(branch)" :disabled="disabled" @change="emit('branch', branch, $event.target.value)"><option value="">选择后续节点</option><option v-for="target in targets" :key="target.id" :value="target.id">{{ target.name }}</option></select></label>
    </template>
    <template v-else-if="node.type === 'tool'"><label>查询工具<select :value="config.operation || ''" :disabled="disabled" @change="update('operation', $event.target.value)"><option value="" disabled>请选择</option><option value="queryOrder">订单履约查询</option><option value="queryCustomerOrders">客户订单查询</option><option value="queryBusinessSubject">业务主体查询</option><option value="queryCustomerEntitlements">客户权益查询</option><option value="queryServiceTickets">服务工单查询</option></select></label><label>输入参数字段<input :value="config.argumentField" :disabled="disabled" placeholder="留空使用工具默认字段" @input="update('argumentField', $event.target.value)"></label><p>查询权限由适用场景与当前账号共同决定。</p></template>
    <template v-else-if="node.type === 'knowledge'"><label>绑定知识库<select :value="config.knowledgeBaseId || ''" :disabled="disabled" @change="update('knowledgeBaseId', $event.target.value)"><option value="">运行时选择</option><option v-for="base in knowledgeBases" :key="base.id" :value="base.id">{{ base.name }}</option></select></label><label>问题字段<input :value="config.questionField" :disabled="disabled" placeholder="question" @input="update('questionField', $event.target.value)"></label><label>召回片段数<input type="number" min="1" max="10" :value="config.topK || 3" :disabled="disabled" @input="update('topK', Number($event.target.value))"></label></template>
    <label v-else-if="node.type === 'model'">处理指令<textarea :value="config.prompt" :disabled="disabled" rows="5" maxlength="2000" placeholder="说明模型如何处理输入…" @input="update('prompt', $event.target.value)"></textarea></label>
    <p v-else>{{ node.type === 'approval' ? '执行到这里会暂停，等待管理员同意或拒绝。' : node.type === 'input' ? '接收试运行面板中填写的数据，无需额外配置。' : '输出当前上下文与上游处理结果。' }}</p>
    <details :open="!config"><summary>高级配置 <small>JSON</small></summary><textarea :value="node.config" rows="7" spellcheck="false" aria-label="节点执行配置 JSON" :disabled="disabled" @input="emit('update-config', $event.target.value)"></textarea></details>
  </div>
</template>
<style scoped>
.node-config-fields{margin-top:20px;padding-top:18px;border-top:1px solid var(--border-color)}.config-section-title{font-size:12px;font-weight:700}.node-config-fields label{display:grid;gap:7px;margin-top:13px;font-size:11px;color:var(--text-muted)}input,select,textarea{width:100%;min-width:0;box-sizing:border-box;padding:9px;color:var(--text-color);background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:6px;font:11px/1.7 inherit;outline:none}input:focus,select:focus,textarea:focus{border-color:var(--primary);box-shadow:0 0 0 2px var(--primary-soft)}textarea{resize:vertical}.node-config-fields p{font-size:11px;line-height:1.8;color:var(--text-soft)}.node-config-fields details{margin-top:17px}.node-config-fields summary{font-size:11px;color:var(--text-muted);cursor:pointer}.node-config-fields details textarea{margin-top:10px;font:11px/1.7 Consolas,monospace}.node-config-fields summary small{color:var(--text-soft);margin-left:6px}.branch-label{font-size:10px}.branch-label.true{color:var(--success)}.branch-label.false{color:var(--warning)}.config-invalid{color:var(--danger)!important}
</style>
