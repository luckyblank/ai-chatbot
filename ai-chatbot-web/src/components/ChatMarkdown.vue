<template>
  <div
    ref="contentRoot"
    class="message-content rich-markdown"
    :class="{ 'is-streaming': streaming }"
    @click="handleContentClick"
    @keydown="handleContentKeydown"
    @error.capture="handleContentError"
    v-html="renderedHtml"
  ></div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import css from 'highlight.js/lib/languages/css'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import { Marked } from 'marked'
import markedKatex from 'marked-katex-extension'
import 'katex/dist/katex.min.css'

const props = defineProps({
  content: { type: String, default: '' },
  streaming: { type: Boolean, default: false }
})

const emit = defineEmits(['preview-image'])
const contentRoot = ref(null)
let renderVersion = 0
let mermaidSequence = 0
let mermaidLoader = null

Object.entries({ bash, shell: bash, css, java, javascript, js: javascript, json, python, py: python, sql, typescript, ts: typescript, html: xml, xml, vue: xml }).forEach(([name, language]) => {
  hljs.registerLanguage(name, language)
})

function loadMermaid() {
  if (!mermaidLoader) {
    mermaidLoader = import('mermaid').then(({ default: mermaid }) => {
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        suppressErrorRendering: true,
        theme: 'base',
        flowchart: { htmlLabels: false, useMaxWidth: true },
        sequence: { useMaxWidth: true, wrap: true }
      })
      return mermaid
    })
  }
  return mermaidLoader
}

function escapeHtml(value = '') {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;')
}

function codeBlock({ text, lang }) {
  const language = String(lang || '').trim().split(/\s+/)[0].toLowerCase()
  if (language === 'mermaid') {
    return `<section class="mermaid-block"><header><span>流程图</span><span class="mermaid-state">完成后渲染</span></header><pre class="mermaid-source"><code>${escapeHtml(text)}</code></pre><div class="mermaid-canvas" role="img" aria-label="由 Mermaid 渲染的流程图"></div></section>`
  }

  let highlighted = escapeHtml(text)
  if (language && hljs.getLanguage(language)) {
    highlighted = hljs.highlight(text, { language, ignoreIllegals: true }).value
  }
  const languageLabel = language ? escapeHtml(language) : '代码'
  const languageClass = language ? ` language-${escapeHtml(language)}` : ''
  return `<section class="code-block"><header><span>${languageLabel}</span><button type="button" data-code-copy aria-label="复制代码">复制</button></header><pre><code class="hljs${languageClass}">${highlighted}</code></pre></section>`
}

const markdown = new Marked(
  markedKatex({
    nonStandard: true,
    throwOnError: false,
    strict: 'ignore',
    trust: false,
    output: 'htmlAndMathml'
  }),
  {
    gfm: true,
    breaks: true,
    renderer: {
      code: codeBlock,
      html({ text }) {
        return escapeHtml(text)
      }
    }
  }
)

function isSafeUrl(rawValue, type) {
  if (!rawValue) return false
  if (type === 'link' && rawValue.startsWith('#')) return true
  try {
    const value = new URL(rawValue, window.location.origin)
    if (type === 'image') return value.protocol === 'http:' || value.protocol === 'https:'
    return ['http:', 'https:', 'mailto:', 'tel:'].includes(value.protocol)
  } catch {
    return false
  }
}

function secureGeneratedHtml(rawHtml) {
  const sanitized = DOMPurify.sanitize(rawHtml, {
    USE_PROFILES: { html: true, mathMl: true },
    ADD_TAGS: ['annotation', 'semantics'],
    ADD_ATTR: ['aria-label', 'aria-hidden', 'role', 'data-code-copy', 'encoding'],
    FORBID_TAGS: ['iframe', 'object', 'embed', 'form', 'script'],
    FORBID_ATTR: ['srcset', 'onerror', 'onclick', 'onload']
  })
  const documentFragment = new DOMParser().parseFromString(`<div>${sanitized}</div>`, 'text/html').body.firstElementChild
  if (!documentFragment) return ''

  documentFragment.querySelectorAll('a').forEach(link => {
    const href = link.getAttribute('href') || ''
    if (!isSafeUrl(href, 'link')) {
      link.removeAttribute('href')
      link.classList.add('unsafe-link')
      return
    }
    if (!href.startsWith('#')) {
      link.setAttribute('target', '_blank')
      link.setAttribute('rel', 'noopener noreferrer nofollow')
      link.setAttribute('referrerpolicy', 'no-referrer')
    }
  })

  documentFragment.querySelectorAll('img').forEach(image => {
    const source = image.getAttribute('src') || ''
    if (!isSafeUrl(source, 'image')) {
      const error = document.createElement('span')
      error.className = 'markdown-image-error'
      error.textContent = `图片地址不可用：${image.getAttribute('alt') || '未命名图片'}`
      image.replaceWith(error)
      return
    }
    image.setAttribute('loading', 'lazy')
    image.setAttribute('decoding', 'async')
    image.setAttribute('referrerpolicy', 'no-referrer')
    image.setAttribute('alt', image.getAttribute('alt') || '对话中的图片')
    image.setAttribute('role', 'button')
    image.setAttribute('tabindex', '0')
    image.setAttribute('title', '点击放大预览')
  })

  documentFragment.querySelectorAll('input[type="checkbox"]').forEach(input => {
    input.setAttribute('disabled', '')
    input.setAttribute('aria-label', input.hasAttribute('checked') ? '已完成' : '未完成')
  })

  documentFragment.querySelectorAll('table').forEach(table => {
    const parent = table.parentNode
    if (!parent) return
    const wrapper = document.createElement('div')
    wrapper.className = 'table-wrap'
    parent.insertBefore(wrapper, table)
    wrapper.appendChild(table)
  })

  return documentFragment.innerHTML
}

const renderedHtml = computed(() => {
  if (!props.content) return ''
  const parsed = markdown.parse(props.content, { async: false })
  return secureGeneratedHtml(typeof parsed === 'string' ? parsed : '')
})

async function renderMermaidDiagrams(version) {
  const root = contentRoot.value
  if (!root || props.streaming || version !== renderVersion) return
  const blocks = Array.from(root.querySelectorAll('.mermaid-block'))
  if (!blocks.length) return
  const mermaid = await loadMermaid()
  if (version !== renderVersion || props.streaming) return
  for (const block of blocks) {
    if (version !== renderVersion || props.streaming) return
    const source = block.querySelector('.mermaid-source code')?.textContent?.trim()
    const canvas = block.querySelector('.mermaid-canvas')
    const state = block.querySelector('.mermaid-state')
    if (!source || !canvas) continue
    try {
      if (state) state.textContent = '正在渲染'
      const diagramId = `chat-mermaid-${Date.now()}-${mermaidSequence++}`
      const { svg } = await mermaid.render(diagramId, source)
      if (version !== renderVersion || !canvas.isConnected) return
      canvas.innerHTML = DOMPurify.sanitize(svg, {
        USE_PROFILES: { svg: true, svgFilters: true },
        ADD_ATTR: ['role', 'aria-label']
      })
      block.classList.add('is-rendered')
      if (state) state.textContent = '已渲染'
    } catch {
      if (version !== renderVersion || !canvas.isConnected) return
      canvas.textContent = '流程图语法暂时无法解析，源码已保留。'
      canvas.classList.add('has-error')
      if (state) state.textContent = '语法错误'
    }
  }
}

async function hydrateRichContent() {
  const version = ++renderVersion
  await nextTick()
  const root = contentRoot.value
  if (!root || version !== renderVersion) return
  await renderMermaidDiagrams(version)
}

async function copyCode(button) {
  const code = button.closest('.code-block')?.querySelector('pre code')?.textContent || ''
  if (!code) return
  try {
    await navigator.clipboard.writeText(code)
    button.textContent = '已复制'
    window.setTimeout(() => {
      if (button.isConnected) button.textContent = '复制'
    }, 1600)
  } catch {
    button.textContent = '复制失败'
  }
}

function handleContentClick(event) {
  const copyButton = event.target.closest('[data-code-copy]')
  if (copyButton) {
    void copyCode(copyButton)
    return
  }
  const image = event.target.closest('img')
  if (image?.src) emit('preview-image', image.src)
}

function handleContentKeydown(event) {
  if (!['Enter', ' '].includes(event.key)) return
  const image = event.target.closest('img')
  if (!image?.src) return
  event.preventDefault()
  emit('preview-image', image.src)
}

function handleContentError(event) {
  const image = event.target?.closest?.('img')
  if (!image?.isConnected) return
  const error = document.createElement('span')
  error.className = 'markdown-image-error'
  error.textContent = `图片加载失败：${image.alt || '未命名图片'}`
  image.replaceWith(error)
}

watch(() => [props.content, props.streaming], hydrateRichContent, { flush: 'post' })
onMounted(hydrateRichContent)
onBeforeUnmount(() => { renderVersion += 1 })
</script>

<style scoped lang="scss">
.rich-markdown{min-width:0;color:inherit;font-size:14px;line-height:1.75;overflow-wrap:anywhere;text-wrap:pretty}
.rich-markdown :deep(> :first-child){margin-top:0}.rich-markdown :deep(> :last-child){margin-bottom:0}
.rich-markdown :deep(p){margin:0 0 10px}.rich-markdown :deep(h1),.rich-markdown :deep(h2),.rich-markdown :deep(h3),.rich-markdown :deep(h4){margin:20px 0 9px;color:inherit;line-height:1.35;letter-spacing:-.015em}.rich-markdown :deep(h1){font-size:22px}.rich-markdown :deep(h2){font-size:19px}.rich-markdown :deep(h3){font-size:16px}.rich-markdown :deep(h4){font-size:14px}
.rich-markdown :deep(ul),.rich-markdown :deep(ol){margin:9px 0;padding-left:23px}.rich-markdown :deep(li){margin:4px 0}.rich-markdown :deep(li.task-list-item){list-style:none}.rich-markdown :deep(.task-list-item input){width:14px;height:14px;margin:0 7px 0 -21px;accent-color:var(--primary);vertical-align:-2px}
.rich-markdown :deep(a){color:var(--primary);text-decoration:underline;text-decoration-color:color-mix(in srgb,var(--primary) 40%,transparent);text-underline-offset:3px}.rich-markdown :deep(a:hover){text-decoration-color:currentColor}.rich-markdown :deep(a.unsafe-link){color:var(--text-soft);cursor:not-allowed;text-decoration-style:dashed}
.rich-markdown :deep(blockquote){margin:12px 0;padding:9px 13px;color:var(--text-muted);background:color-mix(in srgb,var(--primary) 5%,var(--surface));border-left:3px solid color-mix(in srgb,var(--primary) 58%,var(--border-color));border-radius:0 7px 7px 0}.rich-markdown :deep(blockquote p){margin:0}
.rich-markdown :deep(hr){height:1px;margin:18px 0;background:var(--border-color);border:0}
.rich-markdown :deep(:not(pre)>code){padding:2px 5px;color:color-mix(in srgb,var(--primary) 82%,var(--text-color));background:var(--primary-soft);border:1px solid color-mix(in srgb,var(--primary) 12%,var(--border-color));border-radius:4px;font-family:"SFMono-Regular",Consolas,"Liberation Mono",monospace;font-size:.9em}
.rich-markdown :deep(.code-block){margin:14px 0;overflow:hidden;color:#dce5f3;background:#151b26;border:1px solid #2c3544;border-radius:9px}.rich-markdown :deep(.code-block header){height:35px;display:flex;align-items:center;justify-content:space-between;padding:0 10px;color:#9da9ba;background:#1d2532;border-bottom:1px solid #303a49;font:11px/1 "SFMono-Regular",Consolas,monospace;text-transform:lowercase}.rich-markdown :deep(.code-block button){min-width:48px;padding:5px 8px;color:#c9d3e2;background:transparent;border:1px solid #3a4658;border-radius:5px;font:11px/1 inherit;cursor:pointer}.rich-markdown :deep(.code-block button:hover){color:#fff;background:#293446}.rich-markdown :deep(.code-block pre){margin:0;overflow-x:auto;padding:14px 15px}.rich-markdown :deep(.code-block code){display:block;color:#dce5f3;background:transparent;font:12px/1.65 "SFMono-Regular",Consolas,"Liberation Mono",monospace;tab-size:2;white-space:pre}.rich-markdown :deep(.hljs-keyword),.rich-markdown :deep(.hljs-selector-tag),.rich-markdown :deep(.hljs-literal){color:#ff8a9b}.rich-markdown :deep(.hljs-string),.rich-markdown :deep(.hljs-attr){color:#9ad5a5}.rich-markdown :deep(.hljs-number),.rich-markdown :deep(.hljs-variable),.rich-markdown :deep(.hljs-template-variable){color:#9ec5ff}.rich-markdown :deep(.hljs-title),.rich-markdown :deep(.hljs-section),.rich-markdown :deep(.hljs-function){color:#d6b6ff}.rich-markdown :deep(.hljs-comment),.rich-markdown :deep(.hljs-quote){color:#7f8b9d;font-style:italic}
.rich-markdown :deep(.table-wrap){overflow:auto}.rich-markdown :deep(table){width:100%;min-width:440px;margin:14px 0;border-collapse:separate;border-spacing:0;overflow:hidden;color:var(--text-color);background:var(--surface);border:1px solid var(--border-color);border-radius:8px;font-size:12px}.rich-markdown :deep(th),.rich-markdown :deep(td){padding:8px 10px;border-right:1px solid var(--border-color);border-bottom:1px solid var(--border-color);text-align:left}.rich-markdown :deep(th:last-child),.rich-markdown :deep(td:last-child){border-right:0}.rich-markdown :deep(tr:last-child td){border-bottom:0}.rich-markdown :deep(th){color:var(--text-muted);background:var(--surface-strong);font-weight:750}.rich-markdown :deep(tr:nth-child(even) td){background:var(--surface-subtle)}
.rich-markdown :deep(img){display:block;max-width:100%;max-height:560px;margin:14px auto;object-fit:contain;background:var(--surface);border:1px solid var(--border-color);border-radius:9px;box-shadow:0 5px 16px rgba(20,29,45,.08);cursor:zoom-in}.rich-markdown :deep(img:focus-visible){outline:2px solid var(--primary);outline-offset:3px}.rich-markdown :deep(.markdown-image-error){display:block;margin:12px 0;padding:11px;color:var(--text-muted);background:var(--surface-subtle);border:1px dashed var(--border-color);border-radius:7px;font-size:12px;text-align:center}
.rich-markdown :deep(.katex-display){max-width:100%;margin:14px 0;overflow-x:auto;overflow-y:hidden;padding:4px 0}.rich-markdown :deep(.katex){font-size:1.04em}.rich-markdown :deep(.katex-display>.katex){font-size:1.1em}
.rich-markdown :deep(.mermaid-block){margin:14px 0;overflow:hidden;color:var(--text-color);background:var(--surface);border:1px solid var(--border-color);border-radius:9px}.rich-markdown :deep(.mermaid-block>header){height:37px;display:flex;align-items:center;justify-content:space-between;padding:0 11px;color:var(--text-muted);background:var(--surface-subtle);border-bottom:1px solid var(--border-color);font-size:11px;font-weight:700}.rich-markdown :deep(.mermaid-state){color:var(--text-soft);font-weight:500}.rich-markdown :deep(.mermaid-source){margin:0;overflow:auto;padding:13px;color:#dce5f3;background:#151b26;font:12px/1.6 "SFMono-Regular",Consolas,monospace;white-space:pre}.rich-markdown :deep(.mermaid-canvas){display:none;min-height:120px;overflow:auto;padding:16px;background:#fff;text-align:center}.rich-markdown :deep(.mermaid-canvas svg){max-width:100%;height:auto}.rich-markdown :deep(.mermaid-block.is-rendered .mermaid-source){display:none}.rich-markdown :deep(.mermaid-block.is-rendered .mermaid-canvas){display:block}.rich-markdown :deep(.mermaid-canvas.has-error){display:block;min-height:0;color:#9b3b34;background:#fff7f5;font-size:12px}.rich-markdown.is-streaming :deep(.mermaid-state){color:var(--primary)}
</style>
