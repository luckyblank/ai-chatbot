<template>
  <section class="workbench-page" :aria-busy="loading">
    <header class="page-heading">
      <div class="heading-copy">
        <h1>智能工作台</h1>
        <p>从一个入口处理客户服务、内部支持与企业知识任务。</p>
      </div>
      <div class="heading-actions" aria-label="常用操作">
        <RouterLink class="secondary" to="/#services"><CpuChipIcon /><span>运行环境</span></RouterLink>
        <RouterLink class="secondary" to="/knowledge-bases">
          <CircleStackIcon />
          <span>管理知识</span>
        </RouterLink>
        <RouterLink class="primary" to="/customer-service">
          <ChatBubbleLeftRightIcon />
          <span>发起会话</span>
        </RouterLink>
      </div>
    </header>

    <div class="metric-strip" aria-label="服务概览">
      <RouterLink class="metric-card metric-link" to="/knowledge-bases" aria-label="查看全部知识库">
        <span>知识库</span>
        <strong>{{ metricValue(metrics.knowledgeBases) }}</strong>
        <small>个可用知识域</small>
        <ArrowUpRightIcon />
      </RouterLink>
      <RouterLink class="metric-card metric-link" to="/knowledge-bases" aria-label="查看已索引文档">
        <span>已索引文档</span>
        <strong>{{ metricValue(metrics.readyDocuments) }}</strong>
        <small>个可检索文件</small>
        <ArrowUpRightIcon />
      </RouterLink>
      <RouterLink class="metric-card metric-link" to="/customer-service" aria-label="查看历史会话">
        <span>历史会话</span>
        <strong>{{ metricValue(metrics.conversations) }}</strong>
        <small>条已保存会话</small>
        <ArrowUpRightIcon />
      </RouterLink>
      <article class="metric-card status-card" :class="{ error: loadError, checking: loading }" :title="loadError">
        <span>服务状态</span>
        <strong><i></i>{{ statusText }}</strong>
        <small>{{ statusHint }}</small>
        <button v-if="loadError && !loading" type="button" aria-label="重新检测工作台数据" @click="loadDashboard">
          <ArrowPathIcon />
          重新检测
        </button>
      </article>
    </div>

    <div class="content-grid">
      <section class="scenario-section panel">
        <div class="section-heading">
          <div>
            <h2>选择业务场景</h2>
            <p>场景决定知识策略、可用工具与业务边界。</p>
          </div>
          <RouterLink to="/scenarios">查看全部<ChevronRightIcon /></RouterLink>
        </div>
        <div class="scenario-grid">
          <RouterLink
            v-for="scenario in scenarios"
            :key="scenario.code"
            :to="{ path: '/customer-service', query: { scenario: scenario.code } }"
            class="scenario-card"
            :aria-label="`使用${scenario.name}发起会话`"
          >
            <div class="scenario-code">{{ scenario.shortName.slice(0, 2) }}</div>
            <div class="scenario-copy">
              <h3>{{ scenario.name }}</h3>
              <p>{{ scenario.summary }}</p>
              <span>{{ scenario.knowledgeMode === '可选' ? '知识库可选' : `知识库${scenario.knowledgeMode}` }}</span>
              <span>{{ scenario.tools.length }} 个业务工具</span>
            </div>
            <ArrowUpRightIcon class="card-arrow" />
          </RouterLink>
        </div>
      </section>

      <aside class="activity-column" aria-label="近期业务动态">
        <section class="panel compact-panel">
          <div class="section-heading">
            <div>
              <h2>最近会话</h2>
              <p>继续未完成的服务任务。</p>
            </div>
            <RouterLink to="/customer-service">全部<ChevronRightIcon /></RouterLink>
          </div>

          <div v-if="loading" class="list-skeleton" aria-hidden="true">
            <i v-for="index in 3" :key="index"></i>
          </div>
          <div v-else-if="recentConversations.length" class="activity-list">
            <RouterLink
              v-for="item in recentConversations"
              :key="item.id"
              :to="{ path: '/customer-service', query: { conversation: item.id } }"
              :aria-label="`继续会话：${item.title || '新对话'}`"
            >
              <ChatBubbleLeftRightIcon />
              <span>
                <strong :title="item.title || '新对话'">{{ item.title || '新对话' }}</strong>
                <small>{{ formatTime(item.updatedAt || item.createdAt) }}</small>
              </span>
              <ChevronRightIcon class="row-arrow" />
            </RouterLink>
          </div>
          <div v-else class="empty-state">
            <ChatBubbleLeftRightIcon />
            <strong>{{ dataErrors.conversations ? '会话暂时无法读取' : '还没有会话记录' }}</strong>
            <span>{{ dataErrors.conversations ? '请稍后重新检测服务状态。' : '选择一个业务场景，开始处理第一项服务任务。' }}</span>
            <RouterLink v-if="!dataErrors.conversations" to="/customer-service">发起会话<ArrowUpRightIcon /></RouterLink>
          </div>
        </section>

        <section class="panel compact-panel">
          <div class="section-heading">
            <div>
              <h2>知识状态</h2>
              <p>当前接入的企业知识域。</p>
            </div>
            <RouterLink to="/knowledge-bases">管理<ChevronRightIcon /></RouterLink>
          </div>

          <div v-if="loading" class="list-skeleton" aria-hidden="true">
            <i v-for="index in 4" :key="index"></i>
          </div>
          <div v-else-if="knowledgeBases.length" class="knowledge-list">
            <RouterLink
              v-for="item in knowledgeBases.slice(0, 4)"
              :key="item.id"
              :to="{ path: '/knowledge-bases', query: { knowledge: item.id } }"
              :aria-label="`查看知识库：${item.name}`"
            >
              <CircleStackIcon />
              <span>
                <strong :title="item.name">{{ item.name }}</strong>
                <small :title="item.description || '暂无说明'">{{ item.description || '暂无说明' }}</small>
              </span>
              <ChevronRightIcon class="row-arrow" />
            </RouterLink>
          </div>
          <div v-else class="empty-state">
            <CircleStackIcon />
            <strong>{{ dataErrors.knowledge ? '知识库暂时无法读取' : '暂未创建知识库' }}</strong>
            <span>{{ dataErrors.knowledge ? '请稍后重新检测服务状态。' : '创建企业知识域，为智能回复提供可靠依据。' }}</span>
            <RouterLink v-if="!dataErrors.knowledge" to="/knowledge-bases">管理知识<ArrowUpRightIcon /></RouterLink>
          </div>
        </section>
      </aside>
    </div>
    <WorkbenchServices :documents="metrics.readyDocuments" :loading="loading" :data-error="loadError" @refresh="loadDashboard" />
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  ArrowPathIcon,
  ArrowUpRightIcon,
  ChatBubbleLeftRightIcon,
  ChevronRightIcon,
  CircleStackIcon,
  CpuChipIcon
} from '@heroicons/vue/24/outline'
import { scenarios } from '../data/scenarios'
import { conversationAPI, knowledgeAPI } from '../services/api'
import WorkbenchServices from '../components/WorkbenchServices.vue'

const metrics = reactive({ knowledgeBases: null, readyDocuments: null, conversations: null })
const knowledgeBases = ref([])
const recentConversations = ref([])
const loading = ref(true)
const dataErrors = reactive({ knowledge: '', conversations: '', documents: '' })

const loadError = computed(() => Object.values(dataErrors).filter(Boolean).join('；'))
const statusText = computed(() => loading.value ? '同步中' : loadError.value ? '部分数据异常' : '数据已同步')
const statusHint = computed(() => {
  if (loading.value) return '正在读取会话、知识与索引状态'
  if (loadError.value) return '部分数据未能加载，可重新检测'
  return '会话、知识与索引状态均可用'
})

onMounted(loadDashboard)

async function loadDashboard() {
  loading.value = true
  Object.keys(dataErrors).forEach(key => { dataErrors[key] = '' })
  metrics.knowledgeBases = null
  metrics.readyDocuments = null
  metrics.conversations = null

  const [baseResult, conversationResult] = await Promise.allSettled([
    knowledgeAPI.list(),
    conversationAPI.list()
  ])

  if (baseResult.status === 'fulfilled') {
    knowledgeBases.value = baseResult.value
    metrics.knowledgeBases = baseResult.value.length

    const documentResults = await Promise.allSettled(baseResult.value.map(item => knowledgeAPI.documents(item.id)))
    metrics.readyDocuments = documentResults
      .filter(result => result.status === 'fulfilled')
      .flatMap(result => result.value)
      .filter(item => item.status === 'READY').length

    const unavailableCount = documentResults.filter(result => result.status === 'rejected').length
    if (unavailableCount) dataErrors.documents = `${unavailableCount} 个知识库的索引状态读取失败`
  } else {
    knowledgeBases.value = []
    dataErrors.knowledge = errorMessage(baseResult.reason, '知识服务连接失败')
  }

  if (conversationResult.status === 'fulfilled') {
    metrics.conversations = conversationResult.value.length
    recentConversations.value = [...conversationResult.value]
      .sort((left, right) => timestamp(right.updatedAt || right.createdAt) - timestamp(left.updatedAt || left.createdAt))
      .slice(0, 4)
  } else {
    recentConversations.value = []
    dataErrors.conversations = errorMessage(conversationResult.reason, '会话服务连接失败')
  }

  loading.value = false
}

function metricValue(value) {
  return value === null ? '—' : value
}

function timestamp(value) {
  const parsed = new Date(value || '').getTime()
  return Number.isNaN(parsed) ? 0 : parsed
}

function formatTime(value) {
  const parsed = new Date(value || '')
  if (Number.isNaN(parsed.getTime())) return '时间未知'
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit'
  }).format(parsed)
}

function errorMessage(error, fallback) {
  return error instanceof Error && error.message ? error.message : fallback
}
</script>

<style scoped lang="scss">
.workbench-page {
  max-width: 1480px;
  margin: 0 auto;
  padding: 18px 30px 30px;
}

.workbench-page a,
.workbench-page button {
  transition: color .16s ease, background-color .16s ease, border-color .16s ease, transform .16s ease, box-shadow .16s ease;
}

.workbench-page a:focus-visible,
.workbench-page button:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}

.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 20px;
}

.heading-copy { min-width: 0; }

h1 {
  margin: 0 0 7px;
  font-size: clamp(28px, 2.5vw, 36px);
  line-height: 1.1;
  letter-spacing: -.035em;
}

.page-heading p,
.section-heading p {
  margin: 0;
  color: var(--text-muted);
  font-size: 14px;
  line-height: 1.55;
  text-wrap: pretty;
}

.heading-actions {
  display: flex;
  flex: none;
  gap: 8px;
}

.heading-actions a {
  min-height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0 15px;
  border-radius: 8px;
  text-decoration: none;
  font-size: 14px;
  font-weight: 650;
}

.heading-actions svg { width: 17px; }
.heading-actions a:hover { transform: translateY(-1px); }
.primary { color: white; background: var(--primary); border: 1px solid var(--primary); }
.primary:hover { background: var(--primary-hover); border-color: var(--primary-hover); }
.secondary { color: var(--text-color); background: var(--surface); border: 1px solid var(--border-color); }
.secondary:hover { color: var(--primary); border-color: color-mix(in srgb, var(--primary) 36%, var(--border-color)); }

.metric-strip {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  margin-bottom: 18px;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--border-color);
  border-radius: 10px;
}

.metric-card {
  min-width: 0;
  position: relative;
  padding: 17px 20px;
  border-right: 1px solid var(--border-color);
}

.metric-card:last-child { border-right: 0; }
.metric-link { color: inherit; text-decoration: none; }
.metric-link:hover { background: var(--surface-subtle); }
.metric-link:hover > strong { color: var(--primary); }
.metric-link > svg { width: 15px; position: absolute; top: 18px; right: 18px; color: var(--text-soft); opacity: 0; transform: translate(-3px, 3px); }
.metric-link:hover > svg,
.metric-link:focus-visible > svg { opacity: 1; transform: translate(0, 0); }
.metric-card > span { display: block; color: var(--text-muted); font-size: 13px; }
.metric-card > strong { display: block; margin: 7px 0 5px; font-size: 28px; letter-spacing: -.03em; }
.metric-card > small { display: block; overflow: hidden; color: var(--text-soft); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }

.status-card strong { display: flex; align-items: center; gap: 8px; font-size: 15px; }
.status-card i { width: 8px; height: 8px; flex: none; border-radius: 50%; background: var(--success); box-shadow: 0 0 0 4px var(--success-soft); }
.status-card.checking i { background: var(--text-soft); box-shadow: 0 0 0 4px var(--surface-strong); animation: statusPulse 1.2s ease-in-out infinite alternate; }
.status-card.error i { background: var(--danger); box-shadow: 0 0 0 4px color-mix(in srgb, var(--danger) 12%, transparent); }
.status-card button { display: inline-flex; align-items: center; gap: 5px; margin-top: 8px; padding: 0; color: var(--primary); background: transparent; border: 0; font-size: 11px; }
.status-card button:hover { color: var(--primary-hover); }
.status-card button svg { width: 13px; }

.content-grid { display: grid; grid-template-columns: minmax(0, 1fr) 330px; gap: 18px; }
.panel { background: var(--surface); border: 1px solid var(--border-color); border-radius: 10px; }
.scenario-section { padding: 22px; }

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.section-heading h2 { margin: 0 0 5px; font-size: 18px; }
.section-heading > a { min-height: 30px; display: inline-flex; align-items: center; gap: 2px; margin: -5px -7px 0 0; padding: 5px 7px; color: var(--primary); border-radius: 6px; font-size: 13px; text-decoration: none; white-space: nowrap; }
.section-heading > a:hover { background: var(--primary-soft); }
.section-heading > a svg { width: 14px; }

.scenario-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.scenario-card { min-width: 0; display: grid; grid-template-columns: 42px minmax(0, 1fr) 18px; align-items: start; gap: 12px; padding: 15px; color: inherit; background: var(--surface-subtle); border: 1px solid transparent; border-radius: 8px; text-decoration: none; }
.scenario-card:hover { transform: translateY(-1px); background: var(--surface); border-color: var(--border-color); box-shadow: 0 5px 18px rgba(23, 32, 51, .06); }
.scenario-card:hover .card-arrow { color: var(--primary); transform: translate(2px, -2px); }
.scenario-copy { min-width: 0; }
.card-arrow { width: 16px; color: var(--text-soft); transition: color .16s ease, transform .16s ease; }
.scenario-code { width: 42px; height: 42px; display: grid; place-items: center; color: var(--primary); background: var(--primary-soft); border-radius: 8px; font-size: 13px; font-weight: 750; }
.scenario-card h3 { margin: 0 0 6px; font-size: 15px; }
.scenario-card p { min-height: 40px; margin: 0 0 11px; color: var(--text-muted); font-size: 12px; line-height: 1.65; text-wrap: pretty; }
.scenario-card span { display: inline-block; margin: 0 5px 3px 0; padding: 4px 7px; color: var(--text-muted); background: var(--surface-strong); border-radius: 4px; font-size: 11px; }

.activity-column { display: flex; flex-direction: column; gap: 18px; }
.compact-panel { padding: 18px; }
.activity-list,
.knowledge-list { display: flex; flex-direction: column; gap: 4px; }
.activity-list a,
.knowledge-list a { min-width: 0; display: grid; grid-template-columns: 18px minmax(0, 1fr) 14px; align-items: center; gap: 10px; padding: 9px; color: inherit; border: 1px solid transparent; border-radius: 7px; text-decoration: none; }
.activity-list a:hover,
.knowledge-list a:hover { background: var(--surface-subtle); border-color: var(--border-color); }
.activity-list a:hover .row-arrow,
.knowledge-list a:hover .row-arrow { color: var(--primary); transform: translateX(2px); }
.activity-list > a > svg:first-child,
.knowledge-list > a > svg:first-child { width: 17px; flex: none; color: var(--text-soft); }
.activity-list span,
.knowledge-list span { min-width: 0; }
.activity-list strong,
.knowledge-list strong { display: block; overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.activity-list small,
.knowledge-list small { display: block; overflow: hidden; margin-top: 4px; color: var(--text-soft); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.row-arrow { width: 14px; color: var(--text-soft); transition: color .16s ease, transform .16s ease; }

.list-skeleton { display: grid; gap: 7px; padding: 1px 0; }
.list-skeleton i { height: 51px; border-radius: 7px; background: linear-gradient(90deg, var(--surface-subtle) 20%, var(--surface-strong) 50%, var(--surface-subtle) 80%); background-size: 220% 100%; animation: skeleton 1.3s ease-in-out infinite; }
.empty-state { min-height: 164px; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 22px 10px; color: var(--text-soft); text-align: center; }
.empty-state > svg { width: 26px; }
.empty-state > strong { margin-top: 10px; color: var(--text-color); font-size: 13px; }
.empty-state > span { max-width: 250px; margin-top: 5px; font-size: 11px; line-height: 1.6; text-wrap: pretty; }
.empty-state > a { display: inline-flex; align-items: center; gap: 4px; margin-top: 12px; padding: 7px 9px; color: var(--primary); background: var(--primary-soft); border-radius: 6px; font-size: 12px; text-decoration: none; }
.empty-state > a:hover { color: var(--primary-hover); }
.empty-state > a svg { width: 14px; }

@keyframes statusPulse { to { opacity: .45; } }
@keyframes skeleton { to { background-position-x: -220%; } }

@media (max-width: 1080px) {
  .content-grid { grid-template-columns: 1fr; }
  .activity-column { display: grid; grid-template-columns: 1fr 1fr; }
}

@media (max-width: 720px) {
  .workbench-page { padding: 16px 14px 24px; }
  .page-heading { align-items: flex-start; flex-direction: column; margin-bottom: 18px; }
  .heading-actions { width: 100%; }
  .heading-actions a { flex: 1; }
  .metric-strip { grid-template-columns: 1fr 1fr; }
  .metric-card { border-bottom: 1px solid var(--border-color); }
  .metric-card:nth-child(2) { border-right: 0; }
  .metric-card:nth-child(n + 3) { border-bottom: 0; }
  .scenario-section { padding: 18px; }
  .scenario-grid,
  .activity-column { grid-template-columns: 1fr; }
}

@media (max-width: 520px) {
  .metric-strip { grid-template-columns: 1fr; }
  .metric-strip > .metric-card { border-right: 0; border-bottom: 1px solid var(--border-color); }
  .metric-strip > .metric-card:last-child { border-bottom: 0; }
  .scenario-card { grid-template-columns: 38px minmax(0, 1fr) 16px; gap: 10px; padding: 13px; }
  .scenario-code { width: 38px; height: 38px; }
}

@media (prefers-reduced-motion: reduce) {
  .status-card.checking i,
  .list-skeleton i { animation: none; }
}
</style>
