import assert from 'node:assert/strict'
import test from 'node:test'
import { citedSourceNumbers, groupCitations, citationPresentation } from '../src/services/citationSources.js'

test('groups hits from the same document while preserving source numbers and excerpts', () => {
  const groups = groupCitations([
    { documentId: 'doc-a', chunkId: 'chunk-1', fileName: '售后手册.md', excerpt: '第一段命中内容' },
    { documentId: 'doc-b', chunkId: 'chunk-2', fileName: '退款规则.pdf', pageNumber: 3, excerpt: '退款条件' },
    { documentId: 'doc-a', chunkId: 'chunk-3', fileName: '售后手册.md', excerpt: '第二段命中内容' }
  ])

  assert.equal(groups.length, 2)
  assert.equal(groups[0].fileName, '售后手册.md')
  assert.equal(groups[0].documentId, 'doc-a')
  assert.deepEqual(groups[0].snippets.map(item => item.sourceNumber), [1, 3])
  assert.deepEqual(groups[0].snippets.map(item => item.excerpt), ['第一段命中内容', '第二段命中内容'])
  assert.equal(groups[1].snippets[0].pageNumber, 3)
})

test('does not merge different documents that happen to share a file name', () => {
  const groups = groupCitations([
    { documentId: 'doc-a', fileName: '手册.md', excerpt: 'A' },
    { documentId: 'doc-b', fileName: '手册.md', excerpt: 'B' }
  ])
  assert.equal(groups.length, 2)
})

test('shows only sources actually cited by the answer and keeps original numbering', () => {
  const citations = [
    { documentId: 'doc-a', chunkId: 'chunk-1', excerpt: '候选一' },
    { documentId: 'doc-a', chunkId: 'chunk-2', sectionTitle: '退款条件', excerpt: '实际依据' }
  ]
  const groups = groupCitations(citations, '按退款条件办理。[资料 2]')

  assert.deepEqual([...citedSourceNumbers(citations, '按退款条件办理。[资料 2]')], [2])
  assert.equal(groups.length, 1)
  assert.deepEqual(groups[0].snippets.map(item => item.sourceNumber), [2])
  assert.equal(groups[0].snippets[0].sectionTitle, '退款条件')
  assert.equal(groups[0].snippets[0].excerpt, '实际依据')
})

test('retains retrieved candidates when the answer contains no valid source marker', () => {
  const citations = [{ documentId: 'doc-a', excerpt: '候选片段' }]
  assert.equal(groupCitations(citations, '没有标注来源').length, 1)
  assert.equal(groupCitations(citations, '[资料 9]').length, 1)
})

test('uses the displayed count and source type in both citation views', () => {
  const citations = [
    { documentId: 'doc-a', chunkId: 'chunk-1', excerpt: '实际依据' },
    { documentId: 'doc-a', chunkId: 'chunk-2', excerpt: '其他检索候选' }
  ]
  const referenced = citationPresentation(citations, '按规则处理。[资料 1]')
  assert.equal(referenced.hasAnswerReferences, true)
  assert.equal(referenced.snippetCount, 1)
  assert.deepEqual(referenced.groups[0].snippets.map(item => item.sourceNumber), [1])
  const candidates = citationPresentation(citations, '尚无来源标记')
  assert.equal(candidates.hasAnswerReferences, false)
  assert.equal(candidates.snippetCount, 2)
})
