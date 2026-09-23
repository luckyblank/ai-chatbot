import { reactive } from 'vue'

const defaultScenarios = [
  {
    code: 'general',
    name: '通用智能助手',
    shortName: '通用助手',
    summary: '不依赖知识库的日常分析、写作与信息整理。',
    knowledgeMode: '可选',
    tools: [],
    process: ['理解任务', '澄清约束', '生成结果', '确认下一步'],
    guardrail: '不虚构企业内部数据，不执行业务写操作。'
  },
  {
    code: 'commerce-support',
    name: '电商售后服务',
    shortName: '电商售后',
    summary: '覆盖订单、物流、退换货、退款资格与售后工单。',
    knowledgeMode: '推荐',
    tools: ['客户权益查询', '订单履约查询', '售后资格校验', '创建服务工单'],
    process: ['核验订单', '判断问题类型', '匹配售后政策', '确认处理方案', '创建售后单'],
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

export const scenarios = reactive(defaultScenarios.map(item => ({ ...item })))

export function mergeScenarios(items) {
  if (!Array.isArray(items) || !items.length) return
  items.forEach((item, index) => {
    const existing = scenarios.find(scenario => scenario.code === item.code)
    const normalized = {
      ...item,
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
