export function groupCitations(citations = []) {
  const groups = []
  const byDocument = new Map()

  citations.forEach((citation, index) => {
    if (!citation) return
    const documentId = String(citation.documentId || '').trim()
    const fileName = String(citation.fileName || '知识文档').trim()
    const key = documentId ? `id:${documentId}` : `name:${fileName}`
    let group = byDocument.get(key)
    if (!group) {
      group = { key, fileName, snippets: [] }
      byDocument.set(key, group)
      groups.push(group)
    }
    group.snippets.push({
      key: citation.chunkId || `source:${index + 1}`,
      sourceNumber: index + 1,
      pageNumber: citation.pageNumber,
      excerpt: String(citation.excerpt || '').trim()
    })
  })

  return groups
}
