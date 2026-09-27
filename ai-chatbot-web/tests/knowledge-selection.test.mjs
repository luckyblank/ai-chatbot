import assert from 'node:assert/strict'
import test from 'node:test'
import { allowedKnowledgeBases, isWidgetKnowledgeSession, knowledgeBaseIdsFor, knowledgeSelectionIssue } from '../src/services/knowledgeSelection.js'

const bases = [{ id: 'a' }, { id: 'b' }, { id: 'c' }, { id: 'd' }]

test('reads multi-base sessions and legacy single-base sessions', () => {
  assert.deepEqual(knowledgeBaseIdsFor({ knowledgeBaseIds: ['a', 'b', 'a'], knowledgeBaseId: 'c' }), ['a', 'b'])
  assert.deepEqual(knowledgeBaseIdsFor({ knowledgeBaseId: 'c' }), ['c'])
  assert.deepEqual(knowledgeBaseIdsFor({ knowledgeBaseIds: [], knowledgeBaseId: 'c' }), [])
})

test('unrestricted scenario allows all bases; restricted scenario enforces its scope', () => {
  assert.deepEqual(allowedKnowledgeBases(bases, { allowedKnowledgeBaseIds: [] }), bases)
  const restricted = { knowledgeMode: '推荐', allowedKnowledgeBaseIds: ['a', 'b'] }
  assert.deepEqual(allowedKnowledgeBases(bases, restricted).map(base => base.id), ['a', 'b'])
  assert.match(knowledgeSelectionIssue(['c'], restricted, bases), /可用范围/)
  assert.equal(knowledgeSelectionIssue(['a', 'b'], restricted, bases), '')
})

test('required scenario needs a base and every conversation is limited to three', () => {
  assert.match(knowledgeSelectionIssue([], { knowledgeMode: '必选' }, bases), /至少一个/)
  assert.match(knowledgeSelectionIssue(['a', 'b', 'c', 'd'], { knowledgeMode: '推荐' }, bases), /最多选择 3/)
})

test('widget accepts only its fixed single-base knowledge session', () => {
  assert.equal(isWidgetKnowledgeSession({ scenarioCode: 'knowledge-research', knowledgeBaseIds: ['widget'], knowledgeBaseId: 'widget' }, 'widget'), true)
  assert.equal(isWidgetKnowledgeSession({ scenarioCode: 'knowledge-research', knowledgeBaseId: 'widget' }, 'widget'), true)
  assert.equal(isWidgetKnowledgeSession({ scenarioCode: 'knowledge-research', knowledgeBaseIds: ['widget', 'other'], knowledgeBaseId: 'widget' }, 'widget'), false)
  assert.equal(isWidgetKnowledgeSession({ scenarioCode: 'knowledge-research', knowledgeBaseIds: [], knowledgeBaseId: 'widget' }, 'widget'), false)
  assert.equal(isWidgetKnowledgeSession({ scenarioCode: 'general', knowledgeBaseIds: ['widget'] }, 'widget'), false)
  assert.equal(isWidgetKnowledgeSession({ scenarioCode: 'knowledge-research', knowledgeBaseIds: ['widget'] }, ''), false)
})
