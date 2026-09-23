<template>
  <div
    ref="dialog"
    class="image-viewer"
    role="dialog"
    aria-modal="true"
    aria-label="图片预览"
    tabindex="-1"
    @keydown="handleKeydown"
    @wheel.prevent="handleWheel"
  >
    <div
      class="image-stage"
      :class="{ dragging, 'can-drag': scale > 1 }"
      @pointerdown="startDrag"
      @pointermove="dragImage"
      @pointerup="endDrag"
      @pointercancel="endDrag"
      @dblclick="toggleZoom"
    >
      <img
        :src="src"
        :alt="alt"
        draggable="false"
        :style="{ transform: `translate3d(${offset.x}px, ${offset.y}px, 0) scale(${scale})` }"
      >
    </div>

    <button class="viewer-close" type="button" title="关闭预览" aria-label="关闭图片预览" @click="$emit('close')">
      <XMarkIcon />
    </button>

    <div class="viewer-toolbar" role="toolbar" aria-label="图片缩放工具">
      <button type="button" title="缩小" aria-label="缩小图片" :disabled="scale <= MIN_SCALE" @click="zoomBy(-STEP)"><MinusIcon /></button>
      <button class="scale-value" type="button" title="恢复原始大小" aria-label="恢复原始大小" @click="resetView">{{ Math.round(scale * 100) }}%</button>
      <button type="button" title="放大" aria-label="放大图片" :disabled="scale >= MAX_SCALE" @click="zoomBy(STEP)"><PlusIcon /></button>
      <span class="toolbar-divider" aria-hidden="true"></span>
      <button type="button" title="复位" aria-label="复位图片位置和缩放" @click="resetView"><ArrowPathIcon /></button>
    </div>

    <p class="viewer-hint">滚轮缩放 · 拖动查看 · 双击放大</p>
  </div>
</template>

<script setup>
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ArrowPathIcon, MinusIcon, PlusIcon, XMarkIcon } from '@heroicons/vue/24/outline'

const props = defineProps({
  src: { type: String, required: true },
  alt: { type: String, default: '预览图片' }
})
const emit = defineEmits(['close'])

const MIN_SCALE = 0.25
const MAX_SCALE = 4
const STEP = 0.25
const dialog = ref(null)
const scale = ref(1)
const offset = reactive({ x: 0, y: 0 })
const dragging = ref(false)
const pointer = reactive({ id: null, x: 0, y: 0 })

onMounted(async () => {
  await nextTick()
  dialog.value?.focus()
})
watch(() => props.src, resetView)

function clamp(value) {
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, Number(value.toFixed(2))))
}
function setScale(value) {
  scale.value = clamp(value)
  if (scale.value <= 1) {
    offset.x = 0
    offset.y = 0
  }
}
function zoomBy(delta) {
  setScale(scale.value + delta)
}
function resetView() {
  scale.value = 1
  offset.x = 0
  offset.y = 0
}
function toggleZoom() {
  setScale(scale.value > 1 ? 1 : 2)
}
function handleWheel(event) {
  zoomBy(event.deltaY < 0 ? STEP : -STEP)
}
function startDrag(event) {
  if (event.button !== 0 || scale.value <= 1) return
  dragging.value = true
  pointer.id = event.pointerId
  pointer.x = event.clientX
  pointer.y = event.clientY
  event.currentTarget.setPointerCapture?.(event.pointerId)
}
function dragImage(event) {
  if (!dragging.value || event.pointerId !== pointer.id) return
  offset.x += event.clientX - pointer.x
  offset.y += event.clientY - pointer.y
  pointer.x = event.clientX
  pointer.y = event.clientY
}
function endDrag(event) {
  if (event.pointerId !== pointer.id) return
  dragging.value = false
  event.currentTarget.releasePointerCapture?.(event.pointerId)
  pointer.id = null
}
function handleKeydown(event) {
  if (event.key === 'Escape') emit('close')
  if (event.key === '+' || event.key === '=') zoomBy(STEP)
  if (event.key === '-') zoomBy(-STEP)
  if (event.key === '0') resetView()
}
</script>

<style scoped>
.image-viewer{position:fixed;inset:0;z-index:260;overflow:hidden;color:#fff;background:rgba(12,17,25,.9);backdrop-filter:blur(12px);outline:0;user-select:none}.image-stage{position:absolute;inset:0;display:grid;place-items:center;padding:70px 54px 100px;cursor:default;touch-action:none}.image-stage:has(img[style*="scale(1."]),.image-stage:has(img[style*="scale(2"]),.image-stage:has(img[style*="scale(3"]),.image-stage:has(img[style*="scale(4"]){cursor:grab}.image-stage.dragging{cursor:grabbing}.image-stage img{max-width:min(1280px,92vw);max-height:calc(100vh - 170px);object-fit:contain;border-radius:10px;box-shadow:0 24px 70px rgba(0,0,0,.38);will-change:transform;transition:transform .14s ease}.image-stage.dragging img{transition:none}.viewer-close{position:fixed;top:24px;right:24px;width:42px;height:42px;display:grid;place-items:center;padding:0;color:#fff;background:rgba(255,255,255,.1);border:1px solid rgba(255,255,255,.2);border-radius:12px;backdrop-filter:blur(10px);transition:background-color .16s ease,border-color .16s ease,transform .16s ease}.viewer-close:hover{background:rgba(255,255,255,.18);border-color:rgba(255,255,255,.35);transform:scale(1.04)}.viewer-close svg{width:21px}.viewer-toolbar{position:fixed;left:50%;bottom:30px;display:flex;align-items:center;gap:4px;padding:6px;color:#fff;background:rgba(23,29,39,.78);border:1px solid rgba(255,255,255,.16);border-radius:14px;box-shadow:0 12px 30px rgba(0,0,0,.3);backdrop-filter:blur(14px);transform:translateX(-50%)}.viewer-toolbar button{width:36px;height:34px;display:grid;place-items:center;padding:0;color:inherit;background:transparent;border:0;border-radius:9px}.viewer-toolbar button:hover:not(:disabled){background:rgba(255,255,255,.12)}.viewer-toolbar button:focus-visible,.viewer-close:focus-visible{outline:2px solid rgba(139,177,255,.95);outline-offset:2px}.viewer-toolbar button:disabled{opacity:.35;cursor:not-allowed}.viewer-toolbar svg{width:18px}.viewer-toolbar .scale-value{width:58px;font-size:12px;font-variant-numeric:tabular-nums}.toolbar-divider{width:1px;height:18px;margin:0 3px;background:rgba(255,255,255,.16)}.viewer-hint{position:fixed;left:24px;bottom:24px;margin:0;color:rgba(255,255,255,.58);font-size:11px;letter-spacing:.02em}@media(max-width:640px){.image-stage{padding:64px 14px 100px}.image-stage img{max-width:94vw;max-height:calc(100vh - 160px)}.viewer-close{top:16px;right:16px}.viewer-toolbar{bottom:22px}.viewer-hint{display:none}}@media(prefers-reduced-motion:reduce){.image-stage img,.viewer-close{transition:none}}
.image-stage.can-drag{cursor:grab}.image-stage.can-drag.dragging{cursor:grabbing}
</style>
