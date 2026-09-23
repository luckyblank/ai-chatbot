<template>
  <section class="system-page">
    <header><h1>系统管理</h1><p>集中查看模型、数据和文件服务配置；敏感信息不会在页面中显示。</p></header>
    <p class="configuration-note">这里展示当前生效的服务配置，不等同于实时健康检查。实际请求异常会在对应业务页面明确提示。</p>
    <div class="service-grid">
      <article><CpuChipIcon /><div><label>模型服务</label><strong>qwen-plus</strong><p>DashScope OpenAI 兼容接口</p></div><span class="configured">已配置</span></article>
      <article><CircleStackIcon /><div><label>向量服务</label><strong>text-embedding-v4</strong><p>1024 维 · 本地持久化索引</p></div><span class="configured">已配置</span></article>
      <article><CircleStackIcon /><div><label>业务数据</label><strong>MySQL</strong><p>沿用当前项目数据源配置</p></div><span class="configured">已配置</span></article>
      <article><FolderOpenIcon /><div><label>文件存储</label><strong>本地文件存储</strong><p>知识文件与会话附件</p></div><span class="local-mode">本地模式</span></article>
    </div>
    <section class="governance"><h2>治理原则</h2><div><p><strong>知识隔离</strong><span>检索范围由服务端绑定，不能由聊天内容覆盖。</span></p><p><strong>工具授权</strong><span>业务工具仅处理客户权益、订单履约和服务工单等受控数据。</span></p><p><strong>写操作确认</strong><span>退款、合同变更、权限调整和创建工单前需要用户明确确认。</span></p><p><strong>敏感数据</strong><span>密钥、密码和完整联系方式不进入追溯信息。</span></p></div></section>
  </section>
</template>
<script setup>
import { CircleStackIcon, CpuChipIcon, FolderOpenIcon } from '@heroicons/vue/24/outline'
</script>
<style scoped lang="scss">
.system-page { max-width: 1260px; margin: 0 auto; padding: 34px; }h1 { margin: 0 0 7px; font-size: 36px; letter-spacing: -.035em; }header p { margin: 0; color: var(--text-muted); font-size: 14px; }.configuration-note{margin:20px 0 0;padding:11px 13px;color:var(--text-muted);background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:8px;font-size:12px;line-height:1.6}
.service-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-top: 14px; }.service-grid article { display: grid; grid-template-columns: 44px 1fr auto; gap: 14px; align-items: center; padding: 20px; background: var(--surface); border: 1px solid var(--border-color); border-radius: 10px; }.service-grid svg { width: 23px; color: var(--primary); }.service-grid label, .service-grid strong, .service-grid p { display: block; }.service-grid label { color: var(--text-soft); font-size: 11px; }.service-grid strong { margin-top: 5px; font-size: 15px; }.service-grid p { margin: 5px 0 0; color: var(--text-muted); font-size: 12px; }.service-grid article > span { padding: 5px 8px; border-radius: 5px; font-size: 11px; }.configured { color: var(--primary); background: var(--primary-soft); }.local-mode { color: var(--warning); background: color-mix(in srgb,var(--warning) 10%,var(--surface)); }
.governance { margin-top: 20px; padding: 24px; background: var(--surface); border: 1px solid var(--border-color); border-radius: 10px; }.governance h2 { margin: 0 0 19px; font-size: 18px; }.governance > div { display: grid; grid-template-columns: 1fr 1fr; gap: 11px; }.governance p { margin: 0; padding: 16px; background: var(--surface-subtle); border-radius: 8px; }.governance strong, .governance span { display: block; }.governance strong { font-size: 14px; }.governance span { margin-top: 7px; color: var(--text-muted); font-size: 12px; line-height: 1.65; }
@media (max-width: 720px) { .system-page { padding: 20px 14px; } .service-grid, .governance > div { grid-template-columns: 1fr; } }
</style>
