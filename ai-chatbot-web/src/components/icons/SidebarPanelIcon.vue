<script setup>
defineProps({ collapsed: { type: Boolean, default: false } })
</script>

<template>
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <rect x="3" y="4" width="18" height="16" rx="2.5" />
    <path d="M8.5 4v16" />
    <path v-if="collapsed" d="m13.5 9 3 3-3 3" />
    <path v-else d="m16.5 9-3 3 3 3" />
  </svg>
</template>
