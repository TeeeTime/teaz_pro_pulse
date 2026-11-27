<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { DeployJob, Status } from '@/types'

const props = defineProps<{ job: DeployJob }>()
const emit = defineEmits<{ close: [] }>()

const STATUS: Record<DeployJob['state'], Status> = { running: 'warning', succeeded: 'good', failed: 'critical' }

const title = computed(() => {
  const j = props.job
  if (j.state === 'running') return 'Deploying…'
  if (j.state === 'succeeded') return 'Deployed'
  return j.exitCode != null && j.exitCode >= 0 ? `Deploy failed (exit ${j.exitCode})` : 'Deploy failed'
})

const meta = computed(() => {
  const j = props.job
  const started = new Date(j.startedAt)
  const parts = [j.project, `started ${started.toLocaleTimeString()} by ${j.startedBy}`]
  if (j.finishedAt) {
    const seconds = Math.round((new Date(j.finishedAt).getTime() - started.getTime()) / 1000)
    parts.push(seconds < 60 ? `took ${seconds}s` : `took ${Math.floor(seconds / 60)}m ${seconds % 60}s`)
  }
  return parts.join(' · ')
})

// Follow the log while it grows, unless the reader scrolled up.
const logEl = ref<HTMLPreElement | null>(null)
watch(
  () => props.job.log,
  async () => {
    const el = logEl.value
    const atBottom = !el || el.scrollHeight - el.scrollTop - el.clientHeight < 24
    await nextTick()
    if (atBottom && logEl.value) logEl.value.scrollTop = logEl.value.scrollHeight
  },
  { immediate: true },
)
</script>

<template>
  <div class="deploy-panel" :class="`deploy-panel--${STATUS[job.state]}`" role="status">
    <div class="deploy-panel__header">
      <span class="deploy-panel__title">{{ title }}</span>
      <span class="deploy-panel__meta">{{ meta }}</span>
      <button
        v-if="job.state !== 'running'"
        type="button"
        class="deploy-panel__close"
        @click="emit('close')"
      >
        Close
      </button>
    </div>
    <pre ref="logEl" class="deploy-panel__log">{{ job.log || 'Starting…' }}</pre>
  </div>
</template>

<style scoped>
.deploy-panel {
  margin: 0 16px 12px;
  border: 1px solid var(--color-border);
  border-left: 3px solid var(--color-status-warning);
}

.deploy-panel--good {
  border-left-color: var(--color-status-good);
}

.deploy-panel--critical {
  border-left-color: var(--color-status-critical);
}

.deploy-panel__header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
  padding: 8px 12px;
}

.deploy-panel__title {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--color-text-primary);
}

.deploy-panel__meta {
  min-width: 0;
  font-size: 0.8rem;
  color: var(--color-text-secondary);
}

.deploy-panel__close {
  margin-left: auto;
  padding: 2px 10px;
  font: inherit;
  font-size: 0.8rem;
  color: var(--color-text-primary);
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.deploy-panel__close:hover {
  background-color: var(--color-surface-hover);
}

.deploy-panel__close:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: 1px;
}

.deploy-panel__log {
  max-height: 320px;
  margin: 0;
  padding: 8px 12px;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.75rem;
  line-height: 1.45;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  color: var(--color-text-primary);
  background-color: var(--color-surface-hover);
  border-top: 1px solid var(--color-border);
}
</style>
