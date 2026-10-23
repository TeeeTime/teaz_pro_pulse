<script setup lang="ts">
import { computed } from 'vue'

export interface MeterPart {
  label: string
  value: number
  /** Shown next to the label, e.g. "8.1%" or "1.4 GB". */
  text: string
  kind: 'teaz' | 'other'
}

const props = defineProps<{
  /** The whole bar: 100 for CPU, the server's memory, ... */
  total: number
  parts: MeterPart[]
  /** For screen readers, e.g. "CPU usage". */
  label: string
}>()

/*
 * A share of the server as one bar: Teaz (accent), everything else (neutral),
 * free (the track). Segments are separated by a 2px surface gap, never a
 * border; the legend below carries the values, so nothing depends on telling
 * the colors apart.
 */
const segments = computed(() => {
  const total = Math.max(props.total, 1)
  const used = props.parts.map((p) => ({ ...p, width: Math.max(0, Math.min(100, (p.value / total) * 100)) }))
  const free = Math.max(0, 100 - used.reduce((sum, p) => sum + p.width, 0))
  return { used: used.filter((p) => p.width > 0), free }
})
</script>

<template>
  <div class="meter">
    <div class="meter__bar" role="img" :aria-label="`${label}: ${parts.map((p) => `${p.label} ${p.text}`).join(', ')}`">
      <span
        v-for="part in segments.used"
        :key="part.label"
        class="meter__segment"
        :class="`meter__segment--${part.kind}`"
        :style="{ width: `${part.width}%` }"
      />
      <span v-if="segments.free > 0" class="meter__segment meter__segment--free" :style="{ flexGrow: 1 }" />
    </div>
    <ul class="meter__legend">
      <li v-for="part in parts" :key="part.label" class="meter__item">
        <span class="meter__key" :class="`meter__key--${part.kind}`" aria-hidden="true" />
        <span class="meter__label">{{ part.label }}</span>
        <span class="meter__value">{{ part.text }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.meter__bar {
  display: flex;
  gap: 2px;
  height: 10px;
  background-color: var(--color-surface);
}

.meter__segment {
  min-width: 2px;
  border-radius: var(--radius-sm);
}

.meter__segment--teaz,
.meter__key--teaz {
  background-color: var(--color-accent);
}

.meter__segment--other,
.meter__key--other {
  background-color: color-mix(in srgb, var(--color-text-secondary) 55%, var(--color-surface));
}

.meter__segment--free {
  background-color: var(--color-surface-active);
}

.meter__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  margin-top: 8px;
  padding: 0;
  list-style: none;
  font-size: 0.8rem;
}

.meter__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.meter__key {
  width: 10px;
  height: 10px;
}

.meter__label {
  color: var(--color-text-secondary);
}

.meter__value {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-primary);
}
</style>
