import { reactive } from 'vue'

const defaultScenarios = [
  {
    code: 'general',
    name: '通用智能助手',
    shortName: '通用助手',
    summary: '不依赖知识库的日常分析、写作与信息整理。',
    knowledgeMode: '可选',
    tools: [],
    process: ['理解用户目标', '缺少关键信息时先澄清', '根据已知信息生成结果', '必要时说明下一步'],
    guardrail: '不虚构企业内部数据，不执行业务写操作。'
  },
  {
    code: 'commerce-support',
    name: '电商售后服务',
    shortName: '电商售后',
    summary: '覆盖订单、物流、退换货、退款资格与售后工单。',
    knowledgeMode: '推荐',
    tools: ['客户权益查询', '客户订单查询', '订单履约查询', '售后资格校验', '创建服务工单'],
    process: ['核验客户与订单', '判断问题类型', '匹配售后政策', '确认处理方案', '准备售后单并等待确认'],
    guardrail: '退款、退货等写操作必须由用户明确确认。'
  },
  {
    code: 'saas-success',
    name: 'SaaS 客户成功',
    shortName: '客户成功',
    summary: '处理租户开通、账号权限、订阅账单、用量与故障升级。',
    knowledgeMode: '推荐',
    tools: ['业务主体查询', '服务订单查询', '工单进度查询', '创建支持工单'],
    process: ['识别租户', '定位产品模块', '知识排障', '检查订阅与用量', '升级支持工单'],
    guardrail: '不展示其他租户数据，不直接变更权限和订阅。'
  },
  {
    code: 'it-service',
    name: '企业 IT 服务台',
    shortName: 'IT 服务台',
    summary: '覆盖账号、权限、软件、设备、网络与内部服务请求。',
    knowledgeMode: '推荐',
    tools: ['业务主体查询', '工单进度查询', '创建 IT 工单'],
    process: ['确认员工身份', '问题分类', '知识自助排障', '检查资产与权限', '派发工单'],
    guardrail: '高权限申请需要审批，敏感凭据不得进入会话。'
  },
  {
    code: 'merchant-ops',
    name: '平台商家运营',
    shortName: '商家运营',
    summary: '处理店铺审核、商品治理、结算、处罚与申诉进度。',
    knowledgeMode: '推荐',
    tools: ['商家主体查询', '服务工单查询', '创建运营工单'],
    process: ['核验商家', '识别业务域', '匹配平台规则', '查询处理进度', '引导补充材料'],
    guardrail: '不承诺审核结果，不绕过风控和平台规则。'
  },
  {
    code: 'knowledge-research',
    name: '制度与产品知识检索',
    shortName: '知识检索',
    summary: '面向员工和客户检索制度、产品手册、流程与公告。',
    knowledgeMode: '推荐',
    tools: [],
    process: ['选择知识域', '检索相关片段', '核对发布日期', '生成带引用回答'],
    guardrail: '资料不足时明确说明，不使用过期内容覆盖新政策。'
  }
]

export const scenarios = reactive(defaultScenarios.map(item => ({ ...item, allowedKnowledgeBaseIds: [], defaultKnowledgeBaseIds: [] })))

// The server catalog is the source of truth for configurable tools. This small
// label fallback keeps existing pages readable before the catalog has loaded.
const fallbackToolLabels = {
  'customer-entitlements': '客户权益查询',
  'customer-orders': '客户订单查询',
  'order-fulfillment': '订单履约查询',
  'after-sales-eligibility': '售后人工复核提示',
  'business-subject': '业务主体查询',
  'service-tickets': '工单进度查询',
  'prepare-service-ticket': '准备服务工单草案',
  '创建服务工单': '准备服务工单草案',
  '创建支持工单': '准备服务工单草案',
  '创建 IT 工单': '准备服务工单草案',
  '创建运营工单': '准备服务工单草案'
}

export const scenarioToolCatalog = reactive([])

export function mergeScenarioToolCatalog(items) {
  if (!Array.isArray(items)) return
  scenarioToolCatalog.splice(0, scenarioToolCatalog.length, ...items.filter(item => item?.id && item?.label))
}

export function toolEntryFor(value) {
  return scenarioToolCatalog.find(item => item.id === value || item.aliases?.includes(value))
}

export function toolLabel(value) {
  return toolEntryFor(value)?.label || fallbackToolLabels[value] || value
}

export function toolIsUnknown(value) {
  return scenarioToolCatalog.length > 0 && !toolEntryFor(value)
}

export function configuredToolCount(values) {
  if (!Array.isArray(values)) return 0
  const identities = values
    .map(value => toolEntryFor(value)?.id || (scenarioToolCatalog.length ? null : value))
    .filter(Boolean)
  return new Set(identities).size
}

export function configuredToolLabels(values) {
  if (!Array.isArray(values)) return []
  const seen = new Set()
  return values.flatMap(value => {
    const entry = toolEntryFor(value)
    if (scenarioToolCatalog.length && !entry) return []
    const identity = entry?.id || value
    if (seen.has(identity)) return []
    seen.add(identity)
    return [toolLabel(value)]
  })
}

export function unrecognizedToolCount(values) {
  return Array.isArray(values) ? values.filter(toolIsUnknown).length : 0
}

export function defaultProcessForScenario(code) {
  return [...(defaultScenarios.find(item => item.code === code)?.process || defaultScenarios[0].process)]
}

const starterPromptsByScenario = {
  general: ['帮我梳理这项任务的要点', '帮我起草一封邮件', '请总结下面这段内容'],
  'commerce-support': ['我想查询订单状态', '这笔订单能申请退货吗', '帮我查看售后工单进度'],
  'saas-success': ['我想了解当前订阅权益', '帮我排查产品使用问题', '查询支持工单进度'],
  'it-service': ['我的账号无法登录', '帮我排查设备或网络问题', '查询 IT 工单进度'],
  'merchant-ops': ['我想了解店铺审核要求', '查询商家服务工单', '帮我核对平台规则'],
  'knowledge-research': ['帮我查找相关制度', '这个产品功能如何使用', '请给出带来源的说明']
}

export function starterPromptsForScenario(code) {
  return [...(starterPromptsByScenario[code] || starterPromptsByScenario.general)]
}

export function mergeScenarios(items) {
  if (!Array.isArray(items) || !items.length) return
  items.forEach((item, index) => {
    const existing = scenarios.find(scenario => scenario.code === item.code)
    const normalized = {
      ...item,
      allowedKnowledgeBaseIds: Array.isArray(item.allowedKnowledgeBaseIds) ? item.allowedKnowledgeBaseIds : [],
      defaultKnowledgeBaseIds: Array.isArray(item.defaultKnowledgeBaseIds) ? item.defaultKnowledgeBaseIds : [],
      tools: Array.isArray(item.tools) ? item.tools : [],
      process: Array.isArray(item.process) ? item.process : [],
      sortOrder: Number.isFinite(item.sortOrder) ? item.sortOrder : index
    }
    if (existing) Object.assign(existing, normalized)
    else scenarios.push(normalized)
  })
  scenarios.sort((first, second) => (first.sortOrder ?? 999) - (second.sortOrder ?? 999))
}

export function mergeScenario(item) {
  mergeScenarios([item])
  return scenarioByCode(item?.code)
}

export const scenarioByCode = (code) => scenarios.find(item => item.code === code) || scenarios[0]
