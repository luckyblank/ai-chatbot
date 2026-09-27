import assert from 'node:assert/strict'
import test from 'node:test'
import {
  configuredToolCount,
  configuredToolLabels,
  defaultProcessForScenario,
  mergeScenarioToolCatalog,
  toolIsUnknown,
  toolLabel,
  unrecognizedToolCount
} from '../src/data/scenarios.js'

test('resolves legacy tool labels against the server catalog and ignores unavailable entries', () => {
  mergeScenarioToolCatalog([
    {
      id: 'order-fulfillment', label: '订单履约查询',
      aliases: ['订单履约查询', '服务订单查询']
    },
    {
      id: 'prepare-service-ticket', label: '准备工单草案',
      aliases: ['创建服务工单', '创建 IT 工单']
    }
  ])

  const saved = ['服务订单查询', 'order-fulfillment', '创建 IT 工单', '未注册工具']
  assert.deepEqual(configuredToolLabels(saved), ['订单履约查询', '准备工单草案'])
  assert.equal(configuredToolCount(saved), 2)
  assert.equal(unrecognizedToolCount(saved), 1)
  assert.equal(toolIsUnknown('未注册工具'), true)
  assert.equal(toolLabel('prepare-service-ticket'), '准备工单草案')
})

test('recommended steps are a fresh copy of the scenario template', () => {
  const steps = defaultProcessForScenario('general')
  assert.deepEqual(steps, [
    '理解用户目标', '缺少关键信息时先澄清',
    '根据已知信息生成结果', '必要时说明下一步'
  ])
  steps.pop()
  assert.equal(defaultProcessForScenario('general').length, 4)
})
