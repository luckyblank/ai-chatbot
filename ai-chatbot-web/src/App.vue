<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useDark, useToggle } from '@vueuse/core'
import {
  AdjustmentsHorizontalIcon, ArrowLeftOnRectangleIcon, ChatBubbleLeftRightIcon,
  ChatBubbleOvalLeftEllipsisIcon, ChevronDownIcon, ChevronRightIcon, CircleStackIcon, Cog6ToothIcon,
  MagnifyingGlassIcon, MoonIcon, RectangleGroupIcon, SunIcon, UserCircleIcon,
  XMarkIcon, QueueListIcon
} from '@heroicons/vue/24/outline'
import SidebarPanelIcon from './components/icons/SidebarPanelIcon.vue'
import SupportWidget from './components/SupportWidget.vue'
import AccountMenu from './components/AccountMenu.vue'
import { mergeScenarioToolCatalog, mergeScenarios, scenarios } from './data/scenarios'
import { authAPI, conversationAPI, knowledgeAPI, scenarioAPI, workflowAPI } from './services/api'
import { authState, clearAuthenticatedUser } from './services/auth'
import { aiStatusState, describeAiStatus, refreshAiStatus, resetAiStatus } from './services/aiStatus'

const route = useRoute()
const router = useRouter()
const isDark = useDark()
const toggleDark = useToggle(isDark)
const pageTitle = computed(() => route.meta.title || '智能工作台')
const isPublicPage = computed(() => Boolean(route.meta.public))
const isFullscreenPage = computed(() => Boolean(route.meta.fullscreen))
const sidebarCollapsed = ref(localStorage.getItem('enterprise-sidebar-collapsed') === 'true')
const searchOpen = ref(false)
const searchLoading = ref(false)
const searchError = ref('')
const searchQuery = ref('')
const searchInput = ref(null)
const searchBody = ref(null)
const dynamicSearchItems = ref([])
const activeSearchIndex = ref(0)
const aiStatusDisplay = computed(() => describeAiStatus(aiStatusState.status, aiStatusState.loading))

const navigation = [
  { label: '智能工作台', to: '/', icon: RectangleGroupIcon },
  { label: '会话中心', to: '/customer-service', icon: ChatBubbleLeftRightIcon },
  { label: '知识中心', to: '/knowledge-bases', icon: CircleStackIcon },
  { label: '场景配置', to: '/scenarios', icon: AdjustmentsHorizontalIcon },
  { label: '工作流编排', to: '/workflows', icon: QueueListIcon }
]

const searchResults = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  const staticItems = [
    ...navigation.map(item => ({ type: '功能', title: item.label, subtitle: '进入功能页面', to: item.to })),
    ...scenarios.map(item => ({ type: '场景', title: item.name, subtitle: item.summary, to: `/customer-service?scenario=${item.code}` }))
  ]
  const all = [...staticItems, ...dynamicSearchItems.value]
  if (!query) return all.slice(0, 12)
  return all.filter(item => `${item.title} ${item.subtitle || ''} ${item.type}`.toLowerCase().includes(query)).slice(0, 30)
})

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
  localStorage.setItem('enterprise-sidebar-collapsed', String(sidebarCollapsed.value))
}

async function openSearch() {
  searchOpen.value = true
  searchQuery.value = ''
  searchError.value = ''
  activeSearchIndex.value = 0
  await nextTick(); searchInput.value?.focus()
  searchLoading.value = true
  try {
    const [sessionsResult, basesResult, workflowsResult] = await Promise.allSettled([
      conversationAPI.list(), knowledgeAPI.list(), workflowAPI.list()
    ])
    const sessions = sessionsResult.status === 'fulfilled' ? sessionsResult.value : []
    const bases = basesResult.status === 'fulfilled' ? basesResult.value : []
    const workflows = workflowsResult.status === 'fulfilled' ? workflowsResult.value : []
    const documentGroups = await Promise.all(bases.map(async base => {
      try { return await knowledgeAPI.documents(base.id) } catch { return [] }
    }))
    dynamicSearchItems.value = [
      ...sessions.map(item => ({ type: '会话', title: item.title || '新对话', subtitle: item.knowledgeBaseId ? '知识增强会话' : '普通会话', to: `/customer-service?conversation=${item.id}` })),
      ...bases.map(item => ({ type: '知识库', title: item.name, subtitle: item.description || '企业知识库', to: `/knowledge-bases?knowledge=${item.id}` })),
      ...documentGroups.flat().map(item => ({ type: '文档', title: item.fileName, subtitle: '查看原文与分词片段', to: `/knowledge-bases?knowledge=${item.knowledgeBaseId}&document=${item.id}` })),
      ...workflows.map(item => ({ type: '工作流', title: item.name, subtitle: item.description, to: `/workflows?workflow=${item.id}` }))
    ]
    if ([sessionsResult, basesResult, workflowsResult].some(result => result.status === 'rejected')) {
      searchError.value = '部分业务数据暂时无法同步，仍可搜索已加载的内容。'
    }
  } finally { searchLoading.value = false }
}

function selectSearchResult(item) {
  if (!item) return
  searchOpen.value = false
  router.push(item.to)
}

function handleKeydown(event) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    if (isFullscreenPage.value) return
    event.preventDefault()
    if (!searchOpen.value) openSearch()
    return
  }
  if (!searchOpen.value) return
  if (event.key === 'Escape') { searchOpen.value = false; return }
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    if (searchResults.value.length) activeSearchIndex.value = (activeSearchIndex.value + 1) % searchResults.value.length
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    if (searchResults.value.length) activeSearchIndex.value = (activeSearchIndex.value - 1 + searchResults.value.length) % searchResults.value.length
  }
  if (event.key === 'Enter' && event.target === searchInput.value && !event.isComposing && searchResults.value.length) {
    event.preventDefault()
    selectSearchResult(searchResults.value[activeSearchIndex.value])
  }
}

watch(searchQuery, () => { activeSearchIndex.value = 0 })
watch(() => authState.user, user => {
  if (user) refreshAiStatus()
  else resetAiStatus()
}, { immediate: true })
watch(isFullscreenPage, fullscreen => { if (fullscreen) searchOpen.value = false })
watch(searchResults, results => {
  if (!results.length) activeSearchIndex.value = 0
  else if (activeSearchIndex.value >= results.length) activeSearchIndex.value = results.length - 1
})
watch(activeSearchIndex, async index => {
  await nextTick()
  const container = searchBody.value
  const activeItem = container?.querySelector(`#global-search-result-${index}`)
  if (!container || !activeItem) return
  const itemTop = activeItem.offsetTop
  const itemBottom = itemTop + activeItem.offsetHeight
  if (itemTop < container.scrollTop) container.scrollTop = itemTop
  else if (itemBottom > container.scrollTop + container.clientHeight) container.scrollTop = itemBottom - container.clientHeight
})

async function logout() {
  try { await authAPI.logout() } finally {
    clearAuthenticatedUser(); await router.replace('/login')
  }
}

function handleAuthRequired() {
  clearAuthenticatedUser()
  if (route.name !== 'login') router.replace({ name: 'login', query: { redirect: route.fullPath } })
}

onMounted(() => {
  window.addEventListener('keydown', handleKeydown)
  window.addEventListener('auth:required', handleAuthRequired)
  scenarioAPI.list().then(mergeScenarios).catch(() => { /* 后端不可用时保留内置场景，避免阻断主界面 */ })
  scenarioAPI.toolCatalog().then(mergeScenarioToolCatalog).catch(() => { /* 工具目录不可用时保留已有场景展示 */ })
})
onBeforeUnmount(() => { window.removeEventListener('keydown', handleKeydown); window.removeEventListener('auth:required', handleAuthRequired) })
</script>

<template>
  <RouterView v-if="isPublicPage" />
  <main v-else-if="isFullscreenPage" class="fullscreen-stage" :class="{ dark: isDark }"><RouterView /></main>
  <div v-else class="enterprise-shell" :class="{ dark: isDark, 'sidebar-collapsed': sidebarCollapsed }">
    <aside class="product-sidebar">
      <RouterLink to="/" class="product-identity" :title="sidebarCollapsed ? '企业智能服务中心' : ''">
        <span class="product-symbol"><ChatBubbleOvalLeftEllipsisIcon /></span>
        <span class="identity-copy"><strong>企业智能服务中心</strong><small>AI SERVICE HUB</small></span>
      </RouterLink>

      <nav class="primary-navigation" aria-label="产品导航">
        <span class="nav-caption">工作空间</span>
        <RouterLink v-for="item in navigation" :key="item.to" :to="item.to" :title="sidebarCollapsed ? item.label : ''">
          <component :is="item.icon" /><span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="service-summary">
        <div><span class="configuration-dot" :class="`configuration-dot--${aiStatusDisplay.kind}`"></span><strong>{{ aiStatusDisplay.title }}</strong></div>
        <small>{{ aiStatusDisplay.detail }}</small>
      </div>
    </aside>

    <section class="application-stage">
      <header class="global-toolbar">
        <div class="toolbar-leading">
          <button class="sidebar-control" type="button" :title="sidebarCollapsed ? '展开导航' : '收起导航'" :aria-label="sidebarCollapsed ? '展开导航' : '收起导航'" @click="toggleSidebar">
            <SidebarPanelIcon :collapsed="sidebarCollapsed" />
          </button>
          <div class="breadcrumb"><span>服务中心</span><ChevronRightIcon /><strong>{{ pageTitle }}</strong></div>
        </div>
        <button class="global-search" type="button" aria-label="搜索会话、文档、场景与工作流" @click="openSearch">
          <MagnifyingGlassIcon /><span>搜索会话、文档、场景与工作流</span><kbd>Ctrl K</kbd>
        </button>
        <div class="toolbar-actions">
          <AccountMenu :user="authState.user" :dark="isDark" @toggle-theme="toggleDark()" @logout="logout" />
        </div>
      </header>
      <main class="route-stage"><RouterView /></main>
    </section>

    <SupportWidget :conversation-page="route.name === 'customer-service'" />

    <div v-if="searchOpen" class="search-backdrop" @click.self="searchOpen = false">
      <section class="search-dialog" role="dialog" aria-modal="true" aria-label="全局搜索">
        <header><MagnifyingGlassIcon /><input ref="searchInput" v-model="searchQuery" role="combobox" aria-expanded="true" aria-controls="global-search-results" :aria-activedescendant="searchResults.length ? `global-search-result-${activeSearchIndex}` : undefined" placeholder="输入会话名称、文档、场景或工作流"><button type="button" aria-label="关闭搜索" @click="searchOpen = false"><XMarkIcon /></button></header>
        <div id="global-search-results" ref="searchBody" class="search-body" role="listbox">
          <p v-if="searchLoading" class="search-state">正在同步可搜索内容…</p>
          <template v-else>
            <p v-if="searchError" class="search-warning">{{ searchError }}</p>
            <button v-for="(item, index) in searchResults" :id="`global-search-result-${index}`" :key="`${item.type}-${item.to}-${item.title}`" type="button" role="option" :aria-selected="index === activeSearchIndex" class="search-result" :class="{ active: index === activeSearchIndex }" @mouseenter="activeSearchIndex = index" @click="selectSearchResult(item)">
              <span class="result-type">{{ item.type }}</span><span><strong>{{ item.title }}</strong><small>{{ item.subtitle }}</small></span><ChevronRightIcon />
            </button>
            <p v-if="!searchResults.length" class="search-state">没有找到匹配内容</p>
          </template>
        </div>
        <footer><span>↑↓ 浏览</span><span>Enter 打开</span><span>Esc 关闭</span></footer>
      </section>
    </div>
  </div>
</template>

<style lang="scss">
:root {
  --canvas: #f4f6f9; --surface: #fff; --surface-subtle: #f7f9fc; --surface-strong: #edf1f6;
  --text-color: #172033; --text-muted: #5f6b7c; --text-soft: #667085; --border-color: #dce2ea;
  --primary: #315efb; --primary-hover: #244bd8; --primary-soft: #edf1ff;
  --success: #087e6b; --success-soft: #e8f6f2; --warning: #9a5515; --danger: #c2412d;
  --sidebar:#fff;--sidebar-text:#344054;--sidebar-strong:#101828;--sidebar-muted:#667085;
  --sidebar-border:#e3e8ef;--sidebar-hover:#f5f7fa;--sidebar-active:#eaf0ff;--sidebar-active-text:#244bd8;
  --sidebar-card:#f7f9fc;--sidebar-card-border:#dce3ec;--sidebar-symbol:#f3f5f8;
  --shadow-float: 0 18px 48px rgba(23,32,51,.16);
  --font-caption: clamp(11px, .62vw, 13px); --font-small: clamp(12px, .7vw, 14px);
  --font-body: clamp(14px, .78vw, 16px); --font-title: clamp(24px, 1.7vw, 36px);
}
.dark { --canvas:#11151c;--surface:#181d26;--surface-subtle:#1e2530;--surface-strong:#27303d;--text-color:#f0f3f8;--text-muted:#b5becc;--text-soft:#aab4c3;--border-color:#323c4b;--primary:#7895ff;--primary-hover:#92a9ff;--primary-soft:#252e4d;--success:#55c8b5;--success-soft:#173c37;--sidebar:#0c111b;--sidebar-text:#dbe3ef;--sidebar-strong:#fff;--sidebar-muted:#8f9bad;--sidebar-border:#263040;--sidebar-hover:#161e2b;--sidebar-active:#202b49;--sidebar-active-text:#fff;--sidebar-card:#121925;--sidebar-card-border:#2a3546;--sidebar-symbol:#182130; }
* { box-sizing: border-box; }
html, body, #app { min-height: 100%; margin: 0; }
body { font-family: "IBM Plex Sans", "HarmonyOS Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif; color: var(--text-color); background: var(--canvas); font-size: var(--font-body); }
button,input,textarea,select { font: inherit; } button { cursor:pointer; }
.fullscreen-stage{min-width:0;min-height:100vh;color:var(--text-color);background:var(--canvas)}
.enterprise-shell { min-height:100vh;display:grid;grid-template-columns:252px minmax(0,1fr);color:var(--text-color);background:var(--canvas);transition:grid-template-columns .2s ease; }
.enterprise-shell.sidebar-collapsed { grid-template-columns:76px minmax(0,1fr); }
.product-sidebar { height:100vh;position:sticky;top:0;display:flex;flex-direction:column;padding:20px 14px 16px;color:var(--sidebar-text);background:var(--sidebar);border-right:1px solid var(--sidebar-border);overflow:hidden;transition:background-color .2s ease,border-color .2s ease,color .2s ease; }
.product-identity { display:flex;align-items:center;gap:12px;min-height:50px;padding:0 8px 20px;color:var(--sidebar-strong);text-decoration:none;border-bottom:1px solid var(--sidebar-border);white-space:nowrap; }
.product-symbol { width:36px;height:36px;position:relative;display:grid;place-items:center;flex:none;color:var(--sidebar-strong);border:1px solid var(--sidebar-border);border-radius:9px;background:var(--sidebar-symbol); }.product-symbol::after{content:"";position:absolute;right:5px;bottom:5px;width:5px;height:5px;border:2px solid var(--sidebar);border-radius:50%;background:#18a88d}.product-symbol svg{width:21px}.product-identity strong{display:block;font-size:15px}.product-identity small{display:block;margin-top:4px;color:var(--sidebar-muted);font-size:11px;letter-spacing:.12em}
.primary-navigation{display:flex;flex-direction:column;gap:5px;margin-top:22px}.nav-caption{padding:0 11px 8px;color:var(--sidebar-muted);font-size:12px;font-weight:700;letter-spacing:.1em}.primary-navigation a{min-height:46px;display:flex;align-items:center;gap:12px;padding:0 12px;color:var(--sidebar-text);text-decoration:none;border-radius:8px;white-space:nowrap;transition:color .15s ease,background-color .15s ease}.primary-navigation a:hover{color:var(--sidebar-strong);background:var(--sidebar-hover)}.primary-navigation a.router-link-exact-active,.primary-navigation a.router-link-active:not([href='/']){color:var(--sidebar-active-text);background:var(--sidebar-active)}.primary-navigation svg{width:20px;flex:none}.primary-navigation span{font-size:15px}
.service-summary{margin-top:auto;padding:13px;color:var(--sidebar-text);border:1px solid var(--sidebar-card-border);border-radius:9px;background:var(--sidebar-card);white-space:nowrap}.service-summary div{display:flex;align-items:center;gap:8px}.service-summary strong{font-size:13px}.service-summary small{display:block;margin:6px 0 0 15px;color:var(--sidebar-muted);font-size:12px}.configuration-dot{width:8px;height:8px;border-radius:50%;background:var(--primary);box-shadow:0 0 0 3px color-mix(in srgb,var(--primary) 18%,transparent)}
.configuration-dot--disabled,.configuration-dot--unknown{background:var(--sidebar-muted);box-shadow:0 0 0 3px color-mix(in srgb,var(--sidebar-muted) 18%,transparent)}.configuration-dot--missing{background:var(--warning);box-shadow:0 0 0 3px color-mix(in srgb,var(--warning) 18%,transparent)}
.sidebar-collapsed .identity-copy,.sidebar-collapsed .primary-navigation span,.sidebar-collapsed .nav-caption,.sidebar-collapsed .service-summary{display:none}.sidebar-collapsed .product-identity{padding-inline:6px}.sidebar-collapsed .primary-navigation a{justify-content:center;padding:0}
.application-stage{min-width:0;min-height:100vh}.global-toolbar{height:68px;position:sticky;top:0;z-index:30;display:grid;grid-template-columns:minmax(220px,1fr) minmax(300px,460px) minmax(180px,1fr);align-items:center;gap:24px;padding:0 26px 0 14px;background:color-mix(in srgb,var(--surface) 94%,transparent);border-bottom:1px solid var(--border-color);backdrop-filter:blur(14px)}.toolbar-leading,.toolbar-actions,.breadcrumb{display:flex;align-items:center}.toolbar-leading{min-width:0}.sidebar-control{width:34px;height:34px;display:grid;place-items:center;flex:none;margin-right:10px;color:var(--text-muted);background:transparent;border:1px solid transparent;border-radius:8px;transition:.15s}.sidebar-control:hover{color:var(--text-color);background:var(--surface-subtle);border-color:var(--border-color)}.sidebar-control svg{width:18px}.breadcrumb{min-width:0;gap:9px;color:var(--text-muted);font-size:14px;white-space:nowrap}.breadcrumb svg{width:14px;flex:none}.breadcrumb strong{overflow:hidden;color:var(--text-color);text-overflow:ellipsis}.global-search{min-height:40px;display:flex;align-items:center;gap:10px;padding:0 11px;color:var(--text-muted);background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:9px;text-align:left}.global-search svg{width:18px}.global-search span{flex:1;font-size:13px}.global-search kbd{padding:3px 6px;color:var(--text-soft);background:var(--surface);border:1px solid var(--border-color);border-radius:5px;font-size:11px}.toolbar-actions{justify-content:flex-end}.route-stage{min-height:calc(100vh - 68px)}
.search-backdrop{position:fixed;inset:0;z-index:200;display:grid;place-items:start center;padding-top:10vh;background:rgba(10,16,27,.52);backdrop-filter:blur(5px)}.search-dialog{width:min(720px,calc(100vw - 36px));overflow:hidden;background:var(--surface);border:1px solid var(--border-color);border-radius:14px;box-shadow:var(--shadow-float)}.search-dialog>header{height:58px;display:grid;grid-template-columns:22px 1fr 34px;align-items:center;gap:11px;padding:0 16px;border-bottom:1px solid var(--border-color)}.search-dialog>header svg{width:21px;color:var(--text-muted)}.search-dialog input{width:100%;height:100%;color:var(--text-color);background:transparent;border:0;outline:0;font-size:16px}.search-dialog header button{height:32px;display:grid;place-items:center;color:var(--text-muted);background:transparent;border:0}.search-body{max-height:56vh;overflow:auto;padding:9px}.search-result{width:100%;display:grid;grid-template-columns:58px 1fr 18px;align-items:center;gap:12px;padding:12px;color:var(--text-color);background:transparent;border:0;border-radius:9px;text-align:left}.search-result:hover,.search-result.active{background:var(--primary-soft)}.search-result:focus-visible{outline:2px solid var(--primary);outline-offset:-2px}.result-type{padding:4px 6px;color:var(--primary);background:var(--primary-soft);border-radius:5px;font-size:11px;text-align:center}.search-result strong,.search-result small{display:block}.search-result strong{font-size:14px}.search-result small{margin-top:4px;color:var(--text-muted);font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.search-result>svg{width:16px;color:var(--text-soft)}.search-state{padding:50px 20px;color:var(--text-muted);text-align:center}.search-warning{margin:2px 4px 8px;padding:9px 11px;color:var(--warning);background:color-mix(in srgb,var(--warning) 9%,var(--surface));border-radius:7px;font-size:12px;line-height:1.5}.search-dialog>footer{display:flex;gap:18px;padding:10px 16px;color:var(--text-soft);background:var(--surface-subtle);font-size:11px}
@media(min-width:1600px){.enterprise-shell{grid-template-columns:270px minmax(0,1fr)}.enterprise-shell.sidebar-collapsed{grid-template-columns:82px minmax(0,1fr)}.global-toolbar{height:72px}.route-stage{min-height:calc(100vh - 72px)}}
@media(max-width:1100px){.global-toolbar{grid-template-columns:minmax(210px,1fr) minmax(260px,380px) auto;gap:16px}}
@media(max-width:860px){.enterprise-shell,.enterprise-shell.sidebar-collapsed{grid-template-columns:1fr;padding-bottom:calc(64px + env(safe-area-inset-bottom))}.product-sidebar{width:100%;height:calc(64px + env(safe-area-inset-bottom));position:fixed;top:auto;bottom:0;z-index:50;display:block;padding:7px 10px calc(7px + env(safe-area-inset-bottom))}.product-identity,.nav-caption,.service-summary{display:none}.primary-navigation{height:50px;flex-direction:row;justify-content:space-around;margin:0}.primary-navigation a,.sidebar-collapsed .primary-navigation a{min-height:50px;flex:1;flex-direction:column;justify-content:center;gap:2px;padding:0}.primary-navigation a.router-link-exact-active,.primary-navigation a.router-link-active:not([href='/']){box-shadow:none}.primary-navigation svg{width:19px}.primary-navigation span,.sidebar-collapsed .primary-navigation span{display:block;font-size:10px}.global-toolbar{grid-template-columns:minmax(0,1fr) 40px auto;gap:8px;padding:0 14px}.global-search{width:40px;min-height:40px;justify-content:center;padding:0}.global-search span,.global-search kbd,.sidebar-control{display:none}}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{scroll-behavior:auto!important;transition-duration:.01ms!important;animation-duration:.01ms!important}}
</style>
