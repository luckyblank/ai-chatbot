import assert from 'node:assert/strict'
import { test } from 'node:test'
import { buildRunInput, inputFields, validateWorkflow, testCaseResult } from '../src/services/workflowDesigner.js'

const node = (id, type, config = {}) => ({ id, type, name: id, config: JSON.stringify(config) })
const nodes = [node('start', 'input'), node('query', 'tool', { operation: 'queryOrder' }), node('check', 'condition', { field: 'result.orderStatus', operator: 'equals', value: '已完成' }), node('review', 'approval'), node('end', 'output')]
const edges = [{ source: 'start', target: 'query' }, { source: 'query', target: 'check' }, { source: 'check', target: 'review', branch: 'true' }, { source: 'check', target: 'end', branch: 'false' }, { source: 'review', target: 'end' }]
const workflow = { name: '售后', enabled: true, nodes }

test('run form requests business identifiers and never asks users to fabricate a tool result', () => {
  assert.deepEqual(inputFields(nodes).map(field => field.path), ['question', 'orderNo'])
  assert.equal(inputFields(nodes).find(field => field.path === 'orderNo').required, true)
})
test('input form handles nested fields, advanced values and explicit false correctly', () => {
  const fields = [{ path: 'subject.id', label: '编号', required: true }, { path: 'materialsComplete', type: 'boolean' }]
  assert.deepEqual(buildRunInput(fields, { 'subject.id': ' EMP-3108 ', materialsComplete: false }, '{"severity":"高"}'), { subject: { id: 'EMP-3108' }, materialsComplete: false, severity: '高' })
})
test('empty, malformed, non-object and missing mandatory input cannot start a run', () => {
  for (const extra of ['{', '[]', 'null', '1']) assert.throws(() => buildRunInput([], {}, extra))
  assert.throws(() => buildRunInput([], {}), /请填写/)
  assert.throws(() => buildRunInput(inputFields(nodes), { question: '退款' }), /订单编号/)
})
test('prototype keys cannot be injected through JSON or inferred paths', () => {
  assert.throws(() => buildRunInput([], {}, '{"__proto__":{"polluted":true}}'), /字段名/)
  assert.throws(() => buildRunInput([{ path: '__proto__.polluted' }], { '__proto__.polluted': 'yes' }), /字段名/)
  assert.equal({}.polluted, undefined)
})
test('configured approval and both condition branches pass preflight', () => {
  assert.deepEqual(validateWorkflow(workflow, edges), [])
})
test('broken branches, disconnected nodes and cycles identify actionable fixes', () => {
  assert.ok(validateWorkflow(workflow, edges.filter(edge => edge.branch !== 'false')).some(issue => issue.nodeId === 'check'))
  assert.ok(validateWorkflow({ ...workflow, nodes: [...nodes, node('orphan', 'output')] }, edges).some(issue => issue.nodeId === 'orphan'))
  assert.ok(validateWorkflow(workflow, [...edges.filter(edge => edge.source !== 'review'), { source: 'review', target: 'query' }]).some(issue => issue.message.includes('循环')))
})
test('duplicate branch labels, duplicate node IDs and dangling endpoints are blocked', () => {
  assert.ok(validateWorkflow(workflow, [...edges, { source: 'check', target: 'end', branch: 'true' }]).length)
  assert.ok(validateWorkflow({ ...workflow, nodes: [...nodes, node('end', 'output')] }, edges).some(issue => issue.message.includes('重复')))
  assert.ok(validateWorkflow(workflow, [...edges, { source: 'missing', target: 'end' }]).some(issue => issue.message.includes('删除')))
})
test('knowledge selection is satisfied by run-level choice or node binding', () => {
  const knowledge = { ...workflow, nodes: [node('start', 'input'), node('retrieve', 'knowledge'), node('end', 'output')] }
  const connections = [{ source: 'start', target: 'retrieve' }, { source: 'retrieve', target: 'end' }]
  assert.ok(validateWorkflow(knowledge, connections).some(issue => issue.nodeId === 'retrieve'))
  assert.deepEqual(validateWorkflow(knowledge, connections, 'kb-selected'), [])
  knowledge.nodes[1].config = '{"knowledgeBaseId":"kb-bound"}'
  assert.deepEqual(validateWorkflow(knowledge, connections), [])
})
test('invalid config, unsupported write operations, empty prompts and disabled flows are blocked', () => {
  for (const bad of [node('query', 'tool', { operation: 'createTicket' }), node('query', 'model'), { ...node('query', 'tool'), config: '[]' }]) {
    assert.ok(validateWorkflow({ ...workflow, nodes: [nodes[0], bad, ...nodes.slice(2)] }, edges).some(issue => issue.nodeId === 'query'))
  }
  assert.ok(validateWorkflow({ ...workflow, enabled: false }, edges).some(issue => issue.message.includes('启用')))
})
test('sample assertions distinguish wrong branches, failure, waiting and approved completion', () => {
  const sample = { expectedStatus: 'waiting', expectedBranch: 'true' }
  assert.equal(testCaseResult(sample, { status: 'waiting', traversedEdges: [{ branch: 'true' }] }).pass, true)
  assert.equal(testCaseResult(sample, { status: 'completed', traversedEdges: [{ branch: 'false' }] }).pass, false)
  assert.equal(testCaseResult(sample, { status: 'completed', traversedEdges: [{ branch: 'true' }], steps: [{ nodeType: 'approval', status: 'completed' }] }).pass, true)
  assert.equal(testCaseResult(sample, { status: 'failed', traversedEdges: [{ branch: 'true' }] }).pass, false)
})
