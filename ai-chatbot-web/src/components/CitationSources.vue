<script setup>
import { computed } from 'vue'
import { groupCitations } from '../services/citationSources'

const props = defineProps({
  citations: { type: Array, default: () => [] },
  compact: { type: Boolean, default: false },
  showHeading: { type: Boolean, default: true }
})

const groups = computed(() => groupCitations(props.citations))
const snippetCount = computed(() => groups.value.reduce((count, group) => count + group.snippets.length, 0))
</script>

<template>
  <section v-if="groups.length" class="citation-sources" :class="{ compact }" aria-label="参考来源">
    <header v-if="showHeading" class="citation-heading">
      <strong>参考来源</strong>
      <span>{{ groups.length }} 份文档 · {{ snippetCount }} 个命中片段</span>
    </header>
    <div class="citation-documents">
      <article v-for="group in groups" :key="group.key" class="citation-document">
        <div class="citation-document-heading">
          <strong>{{ group.fileName }}</strong>
          <span>{{ group.snippets.length }} 个片段</span>
        </div>
        <ol class="citation-snippets">
          <li v-for="snippet in group.snippets" :key="snippet.key">
            <div class="citation-snippet-label">
              <span>[资料 {{ snippet.sourceNumber }}]</span>
              <span v-if="snippet.pageNumber">第 {{ snippet.pageNumber }} 页</span>
            </div>
            <p>{{ snippet.excerpt || '该片段暂无可展示的文本' }}</p>
          </li>
        </ol>
      </article>
    </div>
  </section>
</template>

<style scoped>
.citation-sources { margin-top: 13px; padding-top: 12px; color: var(--text-color); border-top: 1px solid var(--border-color); font-size: 12px; }
.citation-heading, .citation-document-heading, .citation-snippet-label { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.citation-heading strong { color: var(--text-muted); }
.citation-heading span, .citation-document-heading span { flex: none; color: var(--text-soft); font-size: 11px; }
.citation-documents { display: grid; gap: 10px; margin-top: 10px; }
.citation-document { min-width: 0; overflow: hidden; background: var(--surface); border: 1px solid var(--border-color); border-radius: 9px; }
.citation-document-heading { padding: 10px 12px; background: var(--surface-subtle); }
.citation-document-heading strong { min-width: 0; overflow-wrap: anywhere; color: var(--text-color); font-size: 12px; }
.citation-snippets { display: grid; gap: 0; margin: 0; padding: 0; list-style: none; }
.citation-snippets li { min-width: 0; padding: 10px 12px 12px; }
.citation-snippets li + li { border-top: 1px solid var(--border-color); }
.citation-snippet-label { justify-content: flex-start; color: var(--text-soft); font-size: 11px; }
.citation-snippet-label span:first-child { color: var(--primary); font-weight: 700; }
.citation-snippets p { max-height: 240px; margin: 7px 0 0; overflow: auto; overflow-wrap: anywhere; color: var(--text-muted); line-height: 1.65; white-space: pre-wrap; }
.compact { margin-top: 0; padding-top: 0; border-top: 0; font-size: 11px; }
.compact .citation-documents { gap: 7px; margin-top: 7px; }
.compact .citation-document-heading { padding: 8px 9px; }
.compact .citation-document-heading strong { font-size: 11px; }
.compact .citation-document-heading span, .compact .citation-snippet-label { font-size: 10px; }
.compact .citation-snippets li { padding: 8px 9px; }
.compact .citation-snippets p { max-height: 180px; font-size: 11px; }
</style>
