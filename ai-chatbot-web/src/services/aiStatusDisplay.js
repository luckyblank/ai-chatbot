export function describeAiStatus(status, loading = false) {
  if (loading) return { title: 'AI 状态读取中', detail: '正在读取服务端配置', badge: '读取中', kind: 'unknown' }
  if (typeof status?.enabled !== 'boolean' || typeof status?.configured !== 'boolean') {
    return { title: 'AI 状态未知', detail: '暂时无法确认服务端配置', badge: '状态未知', kind: 'unknown' }
  }
  if (!status.enabled) return { title: 'AI 服务未启用', detail: '当前环境已关闭模型调用', badge: '未启用', kind: 'disabled' }
  if (!status.configured) return { title: 'AI 密钥未配置', detail: '当前环境无法调用模型', badge: '未配置', kind: 'missing' }

  const model = typeof status.model === 'string' ? status.model.trim() : ''
  const provider = typeof status.provider === 'string' ? status.provider.trim() : ''
  return {
    title: 'AI 参数已配置',
    detail: [model, provider].filter(Boolean).join(' · ') || '模型参数已配置；未验证连通性',
    badge: '参数已配置',
    kind: 'configured'
  }
}
