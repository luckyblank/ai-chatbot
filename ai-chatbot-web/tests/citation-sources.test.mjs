import assert from 'node:assert/strict'
import test from 'node:test'
import { groupCitations } from '../src/services/citationSources.js'

test('groups hits from the same document while preserving source numbers and excerpts', () => {
  const groups = groupCitations([
    { documentId: 'doc-a', chunkId: 'chunk-1', fileName: '售后手册.md', excerpt: '第一段命中内容' },
    { documentId: 'doc-b', chunkId: 'chunk-2', fileName: '退款规则.pdf', pageNumber: 3, excerpt: '退款条件' },
    { documentId: 'doc-a', chunkId: 'chunk-3', fileName: '售后手册.md', excerpt: '第二段命中内容' }
  ])

  assert.equal(groups.length, 2)
  assert.equal(groups[0].fileName, '售后手册.md')
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
