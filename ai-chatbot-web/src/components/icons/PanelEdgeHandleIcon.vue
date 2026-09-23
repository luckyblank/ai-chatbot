<script setup>
defineProps({
  direction: {
    type: String,
    default: 'left',
    validator: value => ['left', 'right'].includes(value)
  }
})
</script>

<template>
  <svg viewBox="0 0 16 28" fill="none" stroke="currentColor" stroke-width="1.55" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <template v-if="direction === 'left'">
      <path class="edge-line" d="M11.5 5.5v17" />
      <path d="m7.8 10.3-3.1 3.7 3.1 3.7" />
    </template>
    <template v-else>
      <path class="edge-line" d="M4.5 5.5v17" />
      <path d="m8.2 10.3 3.1 3.7-3.1 3.7" />
    </template>
  </svg>
</template>

<style scoped>
.edge-line { opacity: .42; }
</style>
