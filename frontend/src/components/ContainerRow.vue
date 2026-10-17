<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import StatusLabel from '@/components/StatusLabel.vue'
import { since } from '@/format'
import type { Container, ContainerAction } from '@/types'

const props = defineProps<{
  container: Container
  /** Current time in ms, ticked by the parent so every row's uptime moves together. */
  now: number
  /** An action on this container is in flight. */
  busy: boolean
}>()

const emit = defineEmits<{ action: [ContainerAction] }>()

const running = computed(() => props.container.state === 'running')

const uptime = computed(() => {
  const c = props.container
  if (running.value) return `Up ${since(c.startedAt, props.now) ?? '?'}`
  const stopped = since(c.finishedAt, props.now)
  return stopped ? `Stopped ${stopped} ago` : 'Never started'
})

const details = computed(() => {
  const c = props.container
  const parts = [c.state]
  if (c.health) parts.push(c.health)
  if (c.restartCount > 0) parts.push(`${c.restartCount} ${c.restartCount === 1 ? 'restart' : 'restarts'}`)
  return parts.join(' · ')
})

/*
 * Stop and restart take a second click to confirm: these are production
 * containers and a misclick takes an app offline. Start doesn't, as it
 * only ever brings something back.
 */
const CONFIRM_MS = 4000
const confirming = ref<ContainerAction | null>(null)
let confirmTimer: ReturnType<typeof setTimeout> | undefined

function request(action: ContainerAction) {
  if (action === 'start' || confirming.value === action) {
    resetConfirm()
    emit('action', action)
    return
  }
  confirming.value = action
  clearTimeout(confirmTimer)
  confirmTimer = setTimeout(resetConfirm, CONFIRM_MS)
}

function resetConfirm() {
  confirming.value = null
  clearTimeout(confirmTimer)
}

onBeforeUnmount(() => clearTimeout(confirmTimer))
</script>

<template>
  <li class="container-row">
    <div class="container-row__main">
      <div class="container-row__title">
        <span class="container-row__service">{{ container.service }}</span>
        <span
          v-if="container.protected"
          class="container-row__badge"
          title="Pulse depends on this container: it can be restarted, but not stopped from here."
        >
          Protected
        </span>
      </div>
      <span class="container-row__name">{{ container.name }} · {{ container.image }}</span>
    </div>

    <div class="container-row__state">
      <StatusLabel :status="container.status" />
      <span class="container-row__details">{{ details }}</span>
    </div>

    <span class="container-row__uptime">{{ uptime }}</span>

    <div class="container-row__actions">
      <button
        v-if="!running"
        type="button"
        class="container-row__button"
        :disabled="busy"
        @click="request('start')"
      >
        Start
      </button>
      <template v-else>
        <button
          type="button"
          class="container-row__button"
          :class="{ 'container-row__button--confirm': confirming === 'restart' }"
          :disabled="busy"
          @click="request('restart')"
        >
          {{ confirming === 'restart' ? 'Confirm restart' : 'Restart' }}
        </button>
        <button
          v-if="!container.protected"
          type="button"
          class="container-row__button"
          :class="{ 'container-row__button--confirm': confirming === 'stop' }"
          :disabled="busy"
          @click="request('stop')"
        >
          {{ confirming === 'stop' ? 'Confirm stop' : 'Stop' }}
        </button>
      </template>
    </div>
  </li>
</template>

<style scoped>
.container-row {
  display: grid;
  /* Fixed action column (fits "Confirm restart" + "Stop"), so columns line up across rows. */
  grid-template-columns: minmax(0, 2fr) minmax(0, 1.5fr) minmax(0, 1fr) 13rem;
  align-items: center;
  gap: 8px 16px;
  padding: 12px 16px;
  border-top: 1px solid var(--color-border);
}

.container-row__main,
.container-row__state {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.container-row__title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.container-row__service {
  font-weight: 600;
  color: var(--color-text-primary);
}

.container-row__badge {
  padding: 0 6px;
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  text-transform: uppercase;
  color: var(--color-text-secondary);
  border: 1px solid var(--color-border);
  cursor: help;
}

.container-row__name,
.container-row__details {
  font-size: 0.8rem;
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.container-row__state {
  font-size: 0.85rem;
  color: var(--color-text-primary);
}

.container-row__uptime {
  font-size: 0.85rem;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-secondary);
}

.container-row__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.container-row__button {
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

.container-row__button:hover:not(:disabled) {
  background-color: var(--color-surface-hover);
}

.container-row__button:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: 1px;
}

.container-row__button:disabled {
  cursor: progress;
  opacity: 0.5;
}

.container-row__button--confirm {
  color: var(--color-text-on-accent);
  background-color: var(--color-accent);
  border-color: var(--color-accent);
}

.container-row__button--confirm:hover:not(:disabled) {
  background-color: var(--color-accent-hover);
}

/* Widths of the surrounding list (a query container, see ContainersView).
   Half a desktop screen: uptime moves under the state column. */
@container (max-width: 860px) {
  .container-row {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 13rem;
  }

  .container-row__uptime {
    grid-column: 2;
    grid-row: 2;
  }

  .container-row__actions {
    grid-column: 3;
    grid-row: 1 / span 2;
  }
}

/* Phone: everything stacked, actions full width at the bottom. */
@container (max-width: 520px) {
  .container-row {
    grid-template-columns: minmax(0, 1fr);
  }

  .container-row__uptime,
  .container-row__actions {
    grid-column: 1;
    grid-row: auto;
  }

  .container-row__actions {
    justify-content: stretch;
  }

  .container-row__button {
    flex: 1;
  }
}
</style>
