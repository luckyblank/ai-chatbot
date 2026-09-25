export function citedSourceNumbers(citations = [], answer = '') {
  const cited = new Set()
  for (const match of String(answer || '').matchAll(/\[资料\s*(\d+)\s*\]/g)) {
    const number = Number(match[1])
    if (number >= 1 && number <= citations.length && citations[number - 1]) cited.add(number)
  }
  return cited
}

export function groupCitations(citations = [], answer = '') {
  const groups = []
  const byDocument = new Map()
  const cited = citedSourceNumbers(citations, answer)

  citations.forEach((citation, index) => {
    if (!citation || (cited.size && !cited.has(index + 1))) return
    const documentId = String(citation.documentId || '').trim()
    const fileName = String(citation.fileName || '知识文档').trim()
    const key = documentId ? `id:${documentId}` : `name:${fileName}`
    let group = byDocument.get(key)
    if (!group) {
      group = { key, documentId, fileName, snippets: [] }
      byDocument.set(key, group)
      groups.push(group)
    }
    group.snippets.push({
      key: citation.chunkId || `source:${index + 1}`,
      sourceNumber: index + 1,
      pageNumber: citation.pageNumber,
      sectionTitle: String(citation.sectionTitle || '').trim(),
      excerpt: String(citation.excerpt || '').trim()
    })
  })

  return groups
}

export function citationPresentation(citations = [], answer = '') {
  const groups = groupCitations(citations, answer)
  return {
    groups,
    hasAnswerReferences: citedSourceNumbers(citations, answer).size > 0,
    snippetCount: groups.reduce((count, group) => count + group.snippets.length, 0)
  }
}
