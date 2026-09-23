<template>
  <article class="pending-action-card" :class="statusTone" :aria-busy="Boolean(busy)">
    <header>
      <span class="action-icon" aria-hidden="true"><DocumentTextIcon /></span>
      <span class="action-title">
        <small>需人工确认 · 服务端草案</small>
        <strong>创建服务工单</strong>
      </span>
      <span class="status-badge"><span></span>{{ statusLabel }}</span>
    </header>

    <dl>
      <div><dt>业务主体</dt><dd>{{ parameters.customerNo || '未提供' }}</dd></div>
      <div><dt>关联订单</dt><dd>{{ parameters.orderNo || '未关联' }}</dd></div>
      <div><dt>分类</dt><dd>{{ parameters.category || '未分类' }}</dd></div>
      <div><dt>优先级</dt><dd>{{ priorityLabel }}</dd></div>
      <div class="action-summary"><dt>问题摘要</dt><dd>{{ parameters.summary || '暂无摘要' }}</dd></div>
    </dl>

    <div v-if="status === 'SUCCEEDED'" class="action-result" role="status">
      <CheckCircleIcon />
      <span><strong>{{ action.result?.ticketNo ? `工单 ${action.result.ticketNo}` : '正式工单已创建' }}</strong><small>{{ resultDescription }}</small></span>
    </div>
    <div v-else-if="status === 'CANCELLED'" class="action-result muted" role="status">
      <XCircleIcon /><span><strong>草案已取消</strong><small>后端未创建正式工单。</small></span>
    </div>
    <div v-else-if="effectiveExpired" class="action-result warning" role="status">
      <ClockIcon /><span><strong>草案已过有效期</strong><small>请重新发起业务请求，旧草案不能确认。</small></span>
    </div>
    <div v-else-if="status === 'FAILED' || status === 'REJECTED'" class="action-result failed" role="alert">
      <ExclamationTriangleIcon /><span><strong>动作执行失败</strong><small>{{ displayedError || '服务端未创建正式工单。' }}</small></span>
    </div>
    <p v-else-if="displayedError" class="action-error" role="alert"><ExclamationTriangleIcon />{{ displayedError }}</p>

    <footer>
      <span class="action-meta">版本 {{ action.version }}<template v-if="expiresAtLabel"> · 有效期至 {{ expiresAtLabel }}</template><template v-if="shortActionId"> · 动作 {{ shortActionId }}</template></span>
      <span class="action-buttons">
        <button type="button" class="refresh" :disabled="Boolean(busy)" :aria-label="`刷新动作 ${action.actionId} 状态`" @click="$emit('refresh', action)"><ArrowPathIcon :class="{ spinning: busy === 'refresh' }" />{{ busy === 'refresh' ? '刷新中' : '刷新状态' }}</button>
        <button v-if="canDecide" type="button" class="cancel" :disabled="Boolean(busy)" @click="$emit('cancel', action)">{{ busy === 'cancel' ? '取消中…' : '取消' }}</button>
        <button v-if="canDecide" type="button" class="confirm" :disabled="Boolean(busy)" @click="$emit('confirm', action)">{{ busy === 'confirm' ? '确认中…' : '确认创建' }}</button>
      </span>
    </footer>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import { ArrowPathIcon, CheckCircleIcon, ClockIcon, DocumentTextIcon, ExclamationTriangleIcon, XCircleIcon } from '@heroicons/vue/24/outline'

const props = defineProps({
  action: { type: Object, required: true },
  busy: { type: String, default: '' },
  error: { type: String, default: '' }
})

defineEmits(['confirm', 'cancel', 'refresh'])

const parameters = computed(() => props.action.parameters || {})
const status = computed(() => String(props.action.status || 'PENDING').toUpperCase())
const effectiveExpired = computed(() => {
  if (status.value === 'EXPIRED') return true
  if (status.value !== 'PENDING' || !props.action.expiresAt) return false
  const expiresAt = new Date(props.action.expiresAt).getTime()
  return Number.isFinite(expiresAt) && expiresAt <= Date.now()
})
const canDecide = computed(() => status.value === 'PENDING' && !effectiveExpired.value)
const displayedError = computed(() => props.error || props.action.lastError || '')
const statusLabel = computed(() => {
  if (effectiveExpired.value) return '已过期'
  return ({ PENDING: '等待确认', SUCCEEDED: '已创建', CANCELLED: '已取消', FAILED: '执行失败', REJECTED: '已拒绝', SUPERSEDED: '已失效' })[status.value] || status.value
})
const statusTone = computed(() => {
  if (effectiveExpired.value) return 'expired'
  return ({ PENDING: 'pending', SUCCEEDED: 'succeeded', CANCELLED: 'cancelled', FAILED: 'failed', REJECTED: 'failed', SUPERSEDED: 'cancelled' })[status.value] || 'cancelled'
})
const priorityLabel = computed(() => ({ LOW: '低', NORMAL: '普通', MEDIUM: '中', HIGH: '高', URGENT: '紧急' })[String(parameters.value.priority || '').toUpperCase()] || parameters.value.priority || '未设置')
const expiresAtLabel = computed(() => formatDateTime(props.action.expiresAt))
const shortActionId = computed(() => String(props.action.actionId || '').slice(-8))
const resultDescription = computed(() => {
  const result = props.action.result || {}
  return [result.status, result.ownerTeam].filter(Boolean).join(' · ') || '创建结果已由服务端确认'
})

const dateTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23'
})

function formatDateTime(value) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : dateTimeFormatter.format(date)
}
</script>

<style scoped>
.pending-action-card{width:min(820px,calc(100% - 52px));margin:0 auto 24px;padding:16px;color:var(--text-color);background:var(--surface);border:1px solid color-mix(in srgb,var(--warning) 48%,var(--border-color));border-radius:9px;box-shadow:0 8px 24px rgba(23,32,51,.06)}
.pending-action-card>header{display:flex;align-items:center;gap:10px;padding-bottom:13px;border-bottom:1px solid var(--border-color)}
.action-icon{width:36px;height:36px;display:grid;place-items:center;flex:none;color:var(--warning);background:color-mix(in srgb,var(--warning) 12%,var(--surface));border-radius:8px}.action-icon svg{width:19px}
.action-title{min-width:0;display:grid;gap:2px}.action-title small{color:var(--text-soft);font-size:10px;font-weight:700;letter-spacing:.06em}.action-title strong{font-size:14px}
.status-badge{display:flex;align-items:center;gap:6px;margin-left:auto;padding:5px 8px;color:var(--warning);background:color-mix(in srgb,var(--warning) 10%,var(--surface));border-radius:6px;font-size:10px;font-weight:750;white-space:nowrap}.status-badge>span{width:7px;height:7px;background:currentColor;border-radius:50%}
.succeeded{border-color:color-mix(in srgb,var(--success) 45%,var(--border-color))}.succeeded .action-icon,.succeeded .status-badge{color:var(--success);background:var(--success-soft)}
.cancelled{border-color:var(--border-color)}.cancelled .action-icon,.cancelled .status-badge{color:var(--text-muted);background:var(--surface-subtle)}
.expired,.failed{border-color:color-mix(in srgb,var(--danger) 36%,var(--border-color))}.expired .action-icon,.expired .status-badge,.failed .action-icon,.failed .status-badge{color:var(--danger);background:color-mix(in srgb,var(--danger) 9%,var(--surface))}
dl{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:14px 0 0}dl>div{min-width:0;display:grid;gap:4px;padding:10px;background:var(--surface-subtle);border-radius:7px}.action-summary{grid-column:1/-1}dt{color:var(--text-soft);font-size:10px;font-weight:700}dd{min-width:0;margin:0;overflow-wrap:anywhere;font-size:12px;line-height:1.5}
.action-result{display:flex;align-items:center;gap:9px;margin-top:13px;padding:10px;color:var(--success);background:var(--success-soft);border-radius:7px}.action-result svg{width:20px;flex:none}.action-result>span{display:grid;gap:2px}.action-result strong{font-size:12px}.action-result small{color:var(--text-muted);font-size:10px}.action-result.muted{color:var(--text-muted);background:var(--surface-subtle)}.action-result.warning,.action-result.failed{color:var(--danger);background:color-mix(in srgb,var(--danger) 8%,var(--surface))}
.action-error{display:flex;align-items:flex-start;gap:7px;margin:12px 0 0;padding:9px;color:var(--danger);background:color-mix(in srgb,var(--danger) 8%,var(--surface));border-radius:7px;font-size:11px;line-height:1.5}.action-error svg{width:16px;flex:none}
footer{display:flex;align-items:center;gap:12px;margin-top:14px}.action-meta{min-width:0;flex:1;color:var(--text-soft);font-size:9px;overflow-wrap:anywhere}.action-buttons{display:flex;align-items:center;gap:7px}.action-buttons button{min-height:34px;display:flex;align-items:center;justify-content:center;gap:5px;padding:0 10px;border-radius:7px;font-size:10px;font-weight:700}.action-buttons svg{width:14px}.action-buttons .refresh{color:var(--text-muted);background:transparent;border:1px solid var(--border-color)}.action-buttons .cancel{color:var(--danger);background:var(--surface);border:1px solid color-mix(in srgb,var(--danger) 30%,var(--border-color))}.action-buttons .confirm{color:#fff;background:var(--primary);border:1px solid var(--primary)}.action-buttons button:disabled{cursor:not-allowed;opacity:.55}.spinning{animation:spin .8s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:760px){.pending-action-card{width:100%;padding:13px}dl{grid-template-columns:repeat(2,minmax(0,1fr))}footer{align-items:stretch;flex-direction:column}.action-buttons{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))}.action-buttons .refresh{grid-column:1/-1}.action-buttons button{min-height:40px}}
@media(prefers-reduced-motion:reduce){.spinning{animation:none}}
</style>
