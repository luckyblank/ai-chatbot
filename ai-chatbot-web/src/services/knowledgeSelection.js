export const MAX_CONVERSATION_KNOWLEDGE_BASES = 3

export function knowledgeBaseIdsFor(value) {
  if (Array.isArray(value?.knowledgeBaseIds)) {
    return [...new Set(value.knowledgeBaseIds.map(id => String(id || '').trim()).filter(Boolean))]
  }
  const legacyId = String(value?.knowledgeBaseId || '').trim()
  return legacyId ? [legacyId] : []
}

export function isWidgetKnowledgeSession(session, widgetKnowledgeBaseId) {
  const fixedId = String(widgetKnowledgeBaseId || '').trim()
  if (!fixedId || session?.scenarioCode !== 'knowledge-research') return false
  const ids = knowledgeBaseIdsFor(session)
  return ids.length === 1 && ids[0] === fixedId
}

export function allowedKnowledgeBases(bases, scenario) {
  const allowed = Array.isArray(scenario?.allowedKnowledgeBaseIds) ? scenario.allowedKnowledgeBaseIds : []
  return allowed.length ? bases.filter(base => allowed.includes(base.id)) : bases
}

export function knowledgeSelectionIssue(ids, scenario, bases) {
  if (ids.length > MAX_CONVERSATION_KNOWLEDGE_BASES) return '一次最多选择 3 个知识库。'
  if (scenario?.knowledgeMode === '必选' && !ids.length) return '当前场景必须选择至少一个知识库。'
  const available = new Set(allowedKnowledgeBases(bases, scenario).map(base => base.id))
  if (ids.some(id => !available.has(id))) return '所选知识库不在当前场景的可用范围内，请重新选择。'
  return ''
}
