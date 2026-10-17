<script setup lang="ts">
import type { Status } from '@/types'

defineProps<{ status: Status }>()

// Same words as teaz_pro_ui's StatusListTile. The text carries the meaning;
// the swatch is only a secondary cue (warning is low-contrast on light).
const LABEL: Record<Status, string> = {
  good: 'Operational',
  warning: 'Degraded',
  critical: 'Down',
}
</script>

<template>
  <span class="status-label">
    <span class="status-label__swatch" :class="`status-label__swatch--${status}`" aria-hidden="true" />
    {{ LABEL[status] }}
  </span>
</template>

<style scoped>
.status-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.status-label__swatch {
  width: 8px;
  height: 8px;
  flex-shrink: 0;
}

.status-label__swatch--good {
  background-color: var(--color-status-good);
}

.status-label__swatch--warning {
  background-color: var(--color-status-warning);
}

.status-label__swatch--critical {
  background-color: var(--color-status-critical);
}
</style>
