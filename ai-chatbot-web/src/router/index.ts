// @ts-nocheck
import { createRouter, createWebHistory } from 'vue-router'
import { ensureAuth } from '../services/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', name: 'login', meta: { title: '登录', public: true }, component: () => import('../views/Login.vue') },
    { path: '/', name: 'home', meta: { title: '智能工作台' }, component: () => import('../views/Home.vue') },
    { path: '/customer-service', name: 'customer-service', meta: { title: '会话中心' }, component: () => import('../views/CustomerService.vue') },
    { path: '/knowledge-bases', name: 'knowledge-bases', meta: { title: '知识中心' }, component: () => import('../views/KnowledgeBase.vue') },
    { path: '/scenarios', name: 'scenarios', meta: { title: '场景配置' }, component: () => import('../views/ScenarioCenter.vue') },
    { path: '/workflows/new/design', name: 'workflow-designer-new', meta: { title: '新建工作流', fullscreen: true }, component: () => import('../views/WorkflowDesigner.vue') },
    { path: '/workflows/:id/design', name: 'workflow-designer', meta: { title: '工作流设计器', fullscreen: true }, component: () => import('../views/WorkflowDesigner.vue') },
    { path: '/workflows', name: 'workflows', meta: { title: '工作流编排' }, component: () => import('../views/WorkflowCenter.vue') },
    { path: '/system', name: 'system', meta: { title: '系统管理' }, component: () => import('../views/SystemOverview.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

router.beforeEach(async (to) => {
  document.title = `${to.meta.title || '智能工作台'}｜企业智能服务中心`
  if (to.meta.public) {
    const user = await ensureAuth()
    if (user && to.name === 'login') return String(to.query.redirect || '/')
    return true
  }
  const user = await ensureAuth()
  if (!user) return { name: 'login', query: { redirect: to.fullPath } }
  return true
})

export default router
