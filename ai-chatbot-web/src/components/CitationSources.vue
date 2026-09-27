<script setup>
import { computed } from 'vue'
import { citationPresentation } from '../services/citationSources'

const props = defineProps({
  citations: { type: Array, default: () => [] },
  answer: { type: String, default: '' },
  knowledgeBaseId: { type: String, default: '' },
  knowledgeBases: { type: Array, default: () => [] },
  compact: { type: Boolean, default: false },
  showHeading: { type: Boolean, default: true }
})

const presentation = computed(() => citationPresentation(props.citations, props.answer))
const knowledgeName = id => props.knowledgeBases.find(base => base.id === id)?.name || id
</script>

<template>
  <section v-if="presentation.groups.length" class="citation-sources" :class="{ compact }" :aria-label="presentation.hasAnswerReferences ? '参考来源' : '检索候选'">
    <header v-if="showHeading" class="citation-heading">
      <strong>{{ presentation.hasAnswerReferences ? '参考来源' : '检索候选' }}</strong>
      <span>{{ presentation.groups.length }} 份文档 · {{ presentation.snippetCount }} 个{{ presentation.hasAnswerReferences ? '引用摘录' : '候选摘录' }}</span>
    </header>
    <p v-else-if="!presentation.hasAnswerReferences" class="citation-candidate-note">回答未标注具体资料；以下是检索候选，请核对原文。</p>
    <div class="citation-documents">
      <details v-for="(group, groupIndex) in presentation.groups" :key="group.key" class="citation-document" :open="groupIndex === 0">
        <summary class="citation-document-heading">
          <strong>{{ group.fileName }}</strong>
          <span v-if="group.knowledgeBaseId || knowledgeBaseId" class="citation-base">{{ knowledgeName(group.knowledgeBaseId || knowledgeBaseId) }}</span>
          <span>{{ group.snippets.length }} 个片段</span>
        </summary>
        <ol class="citation-snippets">
          <li v-for="(snippet, snippetIndex) in group.snippets" :key="snippet.key">
            <details class="citation-snippet" :open="groupIndex === 0 && snippetIndex === 0">
              <summary class="citation-snippet-label">
                <span>[资料 {{ snippet.sourceNumber }}]</span>
                <span v-if="snippet.pageNumber">第 {{ snippet.pageNumber }} 页</span>
                <span v-if="snippet.sectionTitle">{{ snippet.sectionTitle }}</span>
              </summary>
              <p>{{ snippet.excerpt || '无法自动定位到更细的原文语句，请到知识库核对文档。' }}</p>
              <RouterLink v-if="(group.knowledgeBaseId || knowledgeBaseId) && group.documentId" class="citation-original-link" :to="{ path: '/knowledge-bases', query: { knowledge: group.knowledgeBaseId || knowledgeBaseId, document: group.documentId } }" target="_blank" rel="noopener noreferrer">查看原文</RouterLink>
            </details>
          </li>
        </ol>
      </details>
    </div>
  </section>
</template>

<style scoped>
.citation-sources { margin-top: 13px; padding-top: 12px; color: var(--text-color); border-top: 1px solid var(--border-color); font-size: 12px; }
.citation-heading, .citation-document-heading, .citation-snippet-label { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.citation-heading strong { color: var(--text-muted); }
.citation-candidate-note { margin: 0 0 6px; color: var(--text-soft); font-size: 11px; }
.citation-heading span, .citation-document-heading span { flex: none; color: var(--text-soft); font-size: 11px; }
.citation-document-heading .citation-base { max-width: 35%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.citation-documents { display: grid; gap: 10px; margin-top: 10px; }
.citation-document { min-width: 0; overflow: hidden; background: var(--surface); border: 1px solid var(--border-color); border-radius: 9px; }
.citation-document-heading { padding: 10px 12px; background: var(--surface-subtle); }
.citation-document-heading strong { min-width: 0; flex: 1; overflow-wrap: anywhere; color: var(--text-color); font-size: 12px; }
.citation-snippets { display: grid; gap: 0; margin: 0; padding: 0; list-style: none; }
.citation-snippets li { min-width: 0; padding: 0 12px; }
.citation-snippets li + li { border-top: 1px solid var(--border-color); }
.citation-snippet-label { justify-content: flex-start; padding: 10px 0; color: var(--text-soft); font-size: 11px; }
.citation-snippet-label span:first-child { color: var(--primary); font-weight: 700; }
.citation-snippet-label span:last-child { min-width: 0; overflow-wrap: anywhere; }
.citation-snippets p { max-height: 240px; margin: 0 0 12px; overflow: auto; overflow-wrap: anywhere; color: var(--text-muted); line-height: 1.65; white-space: pre-wrap; }
.citation-original-link { display: inline-block; margin: 0 0 12px; color: var(--primary); font-weight: 600; text-decoration: none; }
.citation-original-link:hover { text-decoration: underline; }
.citation-document:not([open]) > .citation-snippets, .citation-snippet:not([open]) > p, .citation-snippet:not([open]) > .citation-original-link { display: none; }
.citation-document-heading, .citation-snippet-label { cursor: pointer; list-style: none; }
.citation-document-heading::-webkit-details-marker, .citation-snippet-label::-webkit-details-marker { display: none; }
.citation-document-heading::after, .citation-snippet-label::after { content: ''; width: 7px; height: 7px; flex: none; border-right: 1.5px solid var(--text-soft); border-bottom: 1.5px solid var(--text-soft); transform: rotate(45deg); transition: transform .16s ease; }
.citation-document[open] > .citation-document-heading::after, .citation-snippet[open] > .citation-snippet-label::after { transform: rotate(225deg); }
.citation-document-heading:hover, .citation-snippet-label:hover { color: var(--primary); }
.citation-document-heading:focus-visible, .citation-snippet-label:focus-visible { outline: 2px solid var(--primary); outline-offset: -2px; }
.compact { margin-top: 0; padding-top: 0; border-top: 0; font-size: 11px; }
.compact .citation-documents { gap: 7px; margin-top: 7px; }
.compact .citation-document-heading { padding: 8px 9px; }
.compact .citation-document-heading strong { font-size: 11px; }
.compact .citation-document-heading span, .compact .citation-snippet-label { font-size: 10px; }
.compact .citation-snippets li { padding: 0 9px; }
.compact .citation-snippet-label { padding: 8px 0; }
.compact .citation-snippets p { max-height: 180px; font-size: 11px; }
@media (prefers-reduced-motion: reduce) { .citation-document-heading::after, .citation-snippet-label::after { transition: none; } }
</style>
