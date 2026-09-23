import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

export function useLoginViewportFit() {
  const panelElement = ref(null)
  const cardElement = ref(null)
  const cardScale = ref(1)
  const cardTop = ref(108)
  let observer

  function fit() {
    const panel = panelElement.value
    const card = cardElement.value
    if (!panel || !card) return

    const height = panel.clientHeight
    const narrow = window.innerWidth <= 640
    const handwritten = panel.querySelector('.handwritten')
    const handwrittenBottom = handwritten
      ? handwritten.getBoundingClientRect().bottom - panel.getBoundingClientRect().top
      : 0
    const topInset = Math.max(narrow ? 70 : 90, handwrittenBottom + 20)
    const bottomInset = narrow ? 24 : 32
    const availableHeight = Math.max(1, height - topInset - bottomInset)
    const availableWidth = panel.clientWidth - (narrow ? 32 : 48)

    const scale = Math.max(.1, Math.min(
      1,
      availableWidth / card.offsetWidth,
      availableHeight / card.offsetHeight
    ))
    cardScale.value = scale
    cardTop.value = topInset + (availableHeight - card.offsetHeight * scale) / 2
  }

  onMounted(async () => {
    await nextTick()
    fit()
    if (typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(fit)
      observer.observe(cardElement.value)
    }
    window.addEventListener('resize', fit)
  })
  onBeforeUnmount(() => {
    observer?.disconnect()
    window.removeEventListener('resize', fit)
  })

  return { panelElement, cardElement, cardScale, cardTop }
}
