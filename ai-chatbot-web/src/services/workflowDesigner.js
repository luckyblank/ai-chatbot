export const toolFields = {
  queryOrder: 'orderNo', queryBusinessSubject: 'subjectNo',
  queryCustomerEntitlements: 'customerNo', queryServiceTickets: 'customerNo'
}
const labels = { question: '测试问题', orderNo: '订单编号', subjectNo: '业务主体编号', customerNo: '客户编号', severity: '严重程度', category: '问题类型', materialsComplete: '材料是否齐全' }
const unsafe = new Set(['__proto__', 'prototype', 'constructor'])

export function parseConfig(node) {
  const value = JSON.parse(node.config || '{}')
  if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error('执行配置必须是 JSON 对象')
  return value
}

export function inputFields(nodes) {
  const fields = new Map()
  const add = (path, required = false, sample) => {
    if (typeof path !== 'string' || !path || /^(result|matched|actual|found|matches|matchCount|answer|operation|approval|approved)(\.|$)/.test(path)) return
    if (path.split('.').some(key => unsafe.has(key))) return
    const previous = fields.get(path)
    fields.set(path, { path, label: labels[path] || path, required: required || previous?.required || false,
      type: typeof sample === 'boolean' || path === 'materialsComplete' ? 'boolean' : 'text' })
  }
  add('question')
  for (const node of nodes || []) {
    let config
    try { config = parseConfig(node) } catch { continue }
    if (node.type === 'tool') add(config.argumentField || toolFields[config.operation], true)
    if (node.type === 'condition') add(config.field, false, config.value)
    if (node.type === 'knowledge') add(config.questionField || 'question', true)
  }
  return [...fields.values()]
}

export function readPath(input, path) {
  return path.split('.').reduce((value, key) => value?.[key], input)
}

// Form values take precedence over advanced input only when explicitly entered.
export function buildRunInput(fields, values, extra = '{}') {
  const input = JSON.parse(extra.trim() || '{}')
  if (!input || Array.isArray(input) || typeof input !== 'object') throw new Error('高级参数必须是 JSON 对象。')
  const guard = object => {
    if (!object || typeof object !== 'object') return
    if (Array.isArray(object)) throw new Error('输入只支持文本、数字、布尔值和对象。')
    for (const key of Object.keys(object)) {
      if (unsafe.has(key)) throw new Error('输入字段名无效。')
      guard(object[key])
    }
  }
  guard(input)
  for (const field of fields) {
    const value = values[field.path]
    if (value === '' || value === undefined || value === null) continue
    const parts = field.path.split('.')
    if (parts.some(key => unsafe.has(key))) throw new Error('输入字段名无效。')
    let target = input
    for (const part of parts.slice(0, -1)) {
      if (!target[part] || typeof target[part] !== 'object') target[part] = {}
      target = target[part]
    }
    target[parts.at(-1)] = field.type === 'boolean' ? value === true || value === 'true' : typeof value === 'string' ? value.trim() : value
  }
  if (!Object.keys(input).length) throw new Error('请填写测试问题或业务参数。')
  for (const field of fields.filter(item => item.required)) {
    const value = readPath(input, field.path)
    if (value === undefined || value === null || String(value).trim() === '') throw new Error(`请填写${field.label}。`)
  }
  return input
}

export function validateWorkflow(workflow, edges, knowledgeBaseId = '') {
  const issues = []
  const nodes = workflow.nodes || []
  const add = (node, message) => issues.push({ nodeId: node?.id, title: node?.name || '流程设置', message })
  if (!workflow.name?.trim()) add(null, '请填写工作流名称。')
  if (!workflow.enabled) add(null, '请先启用工作流。')
  if (nodes.filter(node => node.type === 'input').length !== 1) add(null, '需要且只能有一个开始节点。')
  if (!nodes.some(node => node.type === 'output')) add(null, '至少添加一个结束节点。')
  const ids = new Set()
  for (const node of nodes) {
    if (!node.id || ids.has(node.id)) add(node, '节点标识为空或重复。')
    ids.add(node.id)
    if (!node.name?.trim()) add(node, '请填写节点名称。')
    let config
    try { config = parseConfig(node) } catch { add(node, '执行配置必须是有效的 JSON 对象。'); continue }
    const outgoing = edges.filter(edge => edge.source === node.id)
    if (node.type === 'condition') {
      if (typeof config.field !== 'string' || !config.field.trim() || !['equals', 'contains', 'exists'].includes(config.operator)) add(node, '请设置判断字段和有效的比较方式。')
      if (config.operator !== 'exists' && (config.value === undefined || config.value === null || config.value === '')) add(node, '请填写比较值。')
      if (outgoing.length !== 2 || !['true', 'false'].every(branch => outgoing.filter(edge => edge.branch === branch).length === 1)) add(node, '分别连接一条“满足”和“不满足”分支。')
    } else if (node.type !== 'output' && outgoing.length !== 1) add(node, '需要且只能连接一个后续节点。')
    if (node.type === 'output' && outgoing.length) add(node, '结束节点不能连接后续节点。')
    if (node.type === 'tool' && !Object.hasOwn(toolFields, config.operation)) add(node, '请选择受支持的只读查询工具。')
    if (node.type === 'knowledge' && !((typeof config.knowledgeBaseId === 'string' && config.knowledgeBaseId.trim()) || knowledgeBaseId?.trim())) add(node, '请选择用于本次运行的知识库，或在节点中绑定知识库。')
    if (node.type === 'model' && (typeof config.prompt !== 'string' || !config.prompt.trim())) add(node, '请填写模型处理指令。')
  }
  for (const edge of edges) if (!ids.has(edge.source) || !ids.has(edge.target)) add(null, '存在指向已删除节点的连线。')
  const visited = new Set(), stack = new Set()
  let cyclic = false
  const visit = id => {
    if (stack.has(id)) { cyclic = true; return }
    if (visited.has(id)) return
    visited.add(id); stack.add(id)
    edges.filter(edge => edge.source === id).forEach(edge => visit(edge.target))
    stack.delete(id)
  }
  nodes.filter(node => node.type === 'input').forEach(node => visit(node.id))
  if (cyclic) add(null, '存在循环连线，请移除回路后运行。')
  nodes.filter(node => !visited.has(node.id)).forEach(node => add(node, '该节点无法从开始节点到达。'))
  return issues
}

export function testCaseResult(testCase, record) {
  if (!testCase || !record) return null
  const branchMatches = !testCase.expectedBranch || (record.traversedEdges || []).some(edge => edge.branch === testCase.expectedBranch)
  const approvedCompletion = testCase.expectedStatus === 'waiting' && record.status === 'completed' && (record.steps || []).some(step => step.nodeType === 'approval' && step.status === 'completed')
  const pass = branchMatches && (record.status === testCase.expectedStatus || approvedCompletion)
  return { pass, label: pass ? '符合用例预期' : '与用例预期不同', expected: testCase.expectedStatus === 'waiting' ? '进入人工确认' : '运行完成' }
}
