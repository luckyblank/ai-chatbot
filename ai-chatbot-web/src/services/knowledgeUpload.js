const MAX_FILE_BYTES = 20 * 1024 * 1024
const ALLOWED_EXTENSION = /\.(pdf|txt|md|markdown)$/i

export function validateKnowledgeFile(file) {
  if (!file?.name || !ALLOWED_EXTENSION.test(file.name)) return '仅支持 PDF、TXT、Markdown 文件'
  if (!file.size) return '文件为空'
  if (file.size > MAX_FILE_BYTES) return '文件超过 20 MB'
  return ''
}

export async function uploadKnowledgeFiles(files, upload, onProgress = () => {}) {
  const queue = Array.from(files || [])
  const results = []
  for (const [index, file] of queue.entries()) {
    onProgress({ phase: 'start', completed: index, total: queue.length, fileName: file.name })
    const validationError = validateKnowledgeFile(file)
    let error = validationError
    if (!error) {
      try { await upload(file) }
      catch (cause) { error = cause?.message || '上传失败，请重试' }
    }
    const result = { fileName: file.name, success: !error, error }
    results.push(result)
    onProgress({ phase: 'done', completed: index + 1, total: queue.length, result })
  }
  return {
    results,
    succeeded: results.filter(result => result.success).length,
    failed: results.filter(result => !result.success).length
  }
}
