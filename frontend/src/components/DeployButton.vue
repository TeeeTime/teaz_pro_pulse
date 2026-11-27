<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'

defineProps<{
  /** Shown on the button, e.g. "Deploy" or "Deploy teaz_pro_study". */
  label: string
  project: string
  disabled: boolean
}>()

const emit = defineEmits<{ deploy: [] }>()

/* Like stop/restart in ContainerRow: a second click confirms. A deploy rebuilds and recreates the app's containers. */
const CONFIRM_MS = 4000
const confirming = ref(false)
let confirmTimer: ReturnType<typeof setTimeout> | undefined

function click() {
  if (confirming.value) {
    reset()
    emit('deploy')
    return
  }
  confirming.value = true
  clearTimeout(confirmTimer)
  confirmTimer = setTimeout(reset, CONFIRM_MS)
}

function reset() {
  confirming.value = false
  clearTimeout(confirmTimer)
}

onBeforeUnmount(() => clearTimeout(confirmTimer))
</script>

<template>
  <button
    type="button"
    class="deploy-button"
    :class="{ 'deploy-button--confirm': confirming }"
    :disabled="disabled"
    :title="`git pull --ff-only && docker compose up -d --build for ${project}`"
    @click="click"
  >
    {{ confirming ? `Confirm ${label.toLowerCase()}` : label }}
  </button>
</template>

<style scoped>
.deploy-button {
  padding: 6px 12px;
  font: inherit;
  font-size: 0.85rem;
  font-weight: 600;
  white-space: nowrap;
  color: var(--color-text-primary);
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition:
    background-color var(--transition-fast),
    border-color var(--transition-fast);
}

.deploy-button:hover:not(:disabled) {
  background-color: var(--color-surface-hover);
}

.deploy-button:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: 1px;
}

.deploy-button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.deploy-button--confirm {
  color: var(--color-text-on-accent);
  background-color: var(--color-accent);
  border-color: var(--color-accent);
}

.deploy-button--confirm:hover:not(:disabled) {
  background-color: var(--color-accent-hover);
}
</style>
