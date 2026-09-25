import assert from 'node:assert/strict'
import test from 'node:test'
import { uploadKnowledgeFiles, validateKnowledgeFile } from '../src/services/knowledgeUpload.js'

test('knowledge upload accepts supported files up to 20 MB', () => {
  assert.equal(validateKnowledgeFile({ name: 'policy.PDF', size: 20 * 1024 * 1024 }), '')
  assert.equal(validateKnowledgeFile({ name: 'notes.markdown', size: 1 }), '')
  assert.match(validateKnowledgeFile({ name: 'data.xlsx', size: 20 }), /仅支持/)
  assert.match(validateKnowledgeFile({ name: 'empty.txt', size: 0 }), /为空/)
  assert.match(validateKnowledgeFile({ name: 'large.md', size: 20 * 1024 * 1024 + 1 }), /超过/)
})

test('multi-file upload keeps successful files and reports each rejected or failed file', async () => {
  const uploaded = []
  const progress = []
  const files = [
    { name: 'first.pdf', size: 12 },
    { name: 'bad.exe', size: 20 },
    { name: 'failed.md', size: 9 },
    { name: 'last.txt', size: 5 }
  ]
  const result = await uploadKnowledgeFiles(files, async file => {
    uploaded.push(file.name)
    if (file.name === 'failed.md') throw new Error('服务暂不可用')
  }, item => progress.push(item))

  assert.deepEqual(uploaded, ['first.pdf', 'failed.md', 'last.txt'])
  assert.equal(result.succeeded, 2)
  assert.equal(result.failed, 2)
  assert.deepEqual(result.results.map(item => item.success), [true, false, false, true])
  assert.match(result.results[2].error, /服务暂不可用/)
  assert.deepEqual(progress.filter(item => item.phase === 'done').map(item => item.completed), [1, 2, 3, 4])
})
