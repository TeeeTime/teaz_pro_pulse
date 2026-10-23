<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import UsageChart from '@/components/UsageChart.vue'
import type { ChartPoint } from '@/components/UsageChart.vue'
import UsageMeter from '@/components/UsageMeter.vue'
import { ApiError, getJson } from '@/api'
import { formatBytes, formatPercent, formatRate } from '@/format'
import type { ContainerUsage, Metrics } from '@/types'

// The backend measures every 10 s by default; asking in the same rhythm shows each new measurement.
const DEFAULT_REFRESH_MS = 10_000
const GIB = 1024 ** 3

const metrics = ref<Metrics | null>(null)
const loaded = ref(false)
const forbidden = ref(false)
// 503 right after Pulse started: no measurement yet.
const warmingUp = ref(false)
const error = ref<string | null>(null)

async function load() {
  try {
    metrics.value = await getJson<Metrics>('/api/metrics')
    error.value = null
    forbidden.value = false
    warmingUp.value = false
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) forbidden.value = true
    else if (e instanceof ApiError && e.status === 503 && !metrics.value) warmingUp.value = true
    // Keep showing the last measurement; just flag that it's stale.
    else error.value = `Could not load the server's load: ${e instanceof Error ? e.message : e}`
  } finally {
    loaded.value = true
  }
}

const host = computed(() => metrics.value?.host)
const memoryPercent = computed(() =>
  host.value ? (100 * host.value.memoryUsedBytes) / Math.max(host.value.memoryTotalBytes, 1) : null,
)
const diskPercent = computed(() =>
  host.value ? (100 * host.value.diskUsedBytes) / Math.max(host.value.diskTotalBytes, 1) : null,
)

const historyMs = 60 * 60_000
const cpuPoints = computed<ChartPoint[]>(() =>
  (metrics.value?.history ?? []).map((p) => ({ at: Date.parse(p.at), total: p.hostCpuPercent, teaz: p.teazCpuPercent })),
)
const memoryPoints = computed<ChartPoint[]>(() =>
  (metrics.value?.history ?? []).map((p) => ({
    at: Date.parse(p.at),
    total: p.hostMemoryBytes,
    teaz: p.teazMemoryBytes,
  })),
)

/** Bars in the table: relative to the busiest container, the number next to them is the share of the server. */
const maxCpu = computed(() =>
  Math.max(1e-9, ...(metrics.value?.apps ?? []).flatMap((a) => a.containers.map((c) => c.cpuPercent ?? 0))),
)
const maxMemory = computed(() =>
  Math.max(1, ...(metrics.value?.apps ?? []).flatMap((a) => a.containers.map((c) => c.memoryBytes))),
)

function bar(value: number | null, max: number): string {
  return `${Math.min(100, ((value ?? 0) / max) * 100)}%`
}

function memoryTitle(c: ContainerUsage): string {
  return c.memoryLimitBytes ? `${formatBytes(c.memoryBytes)} of its ${formatBytes(c.memoryLimitBytes)} limit` : ''
}

const updated = computed(() => (metrics.value ? new Date(metrics.value.sampledAt).toLocaleTimeString() : null))

let timer: ReturnType<typeof setTimeout> | undefined
async function poll() {
  await load()
  const interval = (metrics.value?.intervalSeconds ?? 0) * 1000 || DEFAULT_REFRESH_MS
  timer = setTimeout(poll, forbidden.value ? 60_000 : interval)
}

onMounted(poll)
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <main class="performance">
    <header class="performance__header">
      <h1 class="performance__title">Performance</h1>
      <p v-if="metrics" class="performance__updated">
        Measured {{ updated }}, every {{ metrics.intervalSeconds }} s
      </p>
    </header>

    <p v-if="error" class="performance__message performance__message--error" role="alert">{{ error }}</p>
    <p v-if="forbidden" class="performance__message">
      The server's load needs the <strong>pulse-admin</strong> role in Keycloak. Ask an admin to assign it, then sign
      in again.
    </p>
    <p v-else-if="warmingUp" class="performance__message">Pulse is taking its first measurements…</p>
    <p v-else-if="!loaded" class="performance__message">Loading…</p>

    <template v-if="metrics && host && !forbidden">
      <section class="performance__cards" aria-label="Server">
        <article class="card">
          <h2 class="card__label">CPU</h2>
          <p class="card__value">{{ formatPercent(host.cpuPercent) }}</p>
          <p class="card__detail">
            {{ host.cores }} cores · load {{ host.load.map((l) => l.toFixed(2)).join(' / ') }}
          </p>
          <UsageMeter
            label="CPU"
            :total="100"
            :parts="[
              { label: 'Teaz', value: metrics.teaz.cpuPercent ?? 0, text: formatPercent(metrics.teaz.cpuPercent), kind: 'teaz' },
              { label: 'Other', value: metrics.other.cpuPercent ?? 0, text: formatPercent(metrics.other.cpuPercent), kind: 'other' },
            ]"
          />
        </article>

        <article class="card">
          <h2 class="card__label">Memory</h2>
          <p class="card__value">{{ formatPercent(memoryPercent) }}</p>
          <p class="card__detail">{{ formatBytes(host.memoryUsedBytes) }} of {{ formatBytes(host.memoryTotalBytes) }} in use</p>
          <UsageMeter
            label="Memory"
            :total="host.memoryTotalBytes"
            :parts="[
              { label: 'Teaz', value: metrics.teaz.memoryBytes, text: formatBytes(metrics.teaz.memoryBytes), kind: 'teaz' },
              { label: 'Other', value: metrics.other.memoryBytes, text: formatBytes(metrics.other.memoryBytes), kind: 'other' },
            ]"
          />
        </article>

        <article class="card">
          <h2 class="card__label">Disk</h2>
          <p class="card__value">{{ formatPercent(diskPercent) }}</p>
          <p class="card__detail">{{ formatBytes(host.diskUsedBytes) }} of {{ formatBytes(host.diskTotalBytes) }} used</p>
          <UsageMeter
            label="Disk"
            :total="host.diskTotalBytes"
            :parts="[{ label: 'Used', value: host.diskUsedBytes, text: formatBytes(host.diskUsedBytes), kind: 'other' }]"
          />
        </article>
      </section>

      <section class="performance__charts">
        <article class="card">
          <h2 class="card__title">CPU, last hour</h2>
          <UsageChart
            :points="cpuPoints"
            :max="100"
            :window-ms="historyMs"
            :unit="1"
            :tick-label="(v) => `${v}%`"
            :format="formatPercent"
            label="CPU of the server and of the Teaz containers over the last hour"
          />
        </article>
        <article class="card">
          <h2 class="card__title">Memory, last hour</h2>
          <UsageChart
            :points="memoryPoints"
            :max="host.memoryTotalBytes"
            :window-ms="historyMs"
            :unit="GIB"
            :tick-label="(v) => `${v} GB`"
            :format="formatBytes"
            label="Memory of the server and of the Teaz containers over the last hour"
          />
        </article>
      </section>

      <section class="performance__table-card card">
        <h2 class="card__title">Teaz containers</h2>
        <p class="card__detail">
          CPU as a share of the whole server; bars compare the containers with each other. Stopped containers use
          nothing and aren't listed.
        </p>
        <div class="usage">
          <table class="usage__table">
            <thead>
              <tr>
                <th scope="col">Container</th>
                <th scope="col" class="usage__num">CPU</th>
                <th scope="col" class="usage__num">Memory</th>
                <th scope="col" class="usage__num usage__wide">Network in / out</th>
                <th scope="col" class="usage__num usage__wide">Disk read / write</th>
              </tr>
            </thead>
            <tbody v-for="app in metrics.apps" :key="app.name">
              <tr class="usage__app">
                <th scope="rowgroup">{{ app.name }}</th>
                <td class="usage__num">{{ formatPercent(app.cpuPercent) }}</td>
                <td class="usage__num">{{ formatBytes(app.memoryBytes) }}</td>
                <td class="usage__wide" />
                <td class="usage__wide" />
              </tr>
              <tr v-for="c in app.containers" :key="c.id" class="usage__row">
                <th scope="row" class="usage__container">
                  <span class="usage__service">{{ c.service }}</span>
                  <span class="usage__name">{{ c.name }}</span>
                </th>
                <td class="usage__num">
                  <span class="usage__value">{{ formatPercent(c.cpuPercent) }}</span>
                  <span class="usage__bar" aria-hidden="true"><span :style="{ width: bar(c.cpuPercent, maxCpu) }" /></span>
                </td>
                <td class="usage__num" :title="memoryTitle(c)">
                  <span class="usage__value">{{ formatBytes(c.memoryBytes) }}</span>
                  <span class="usage__bar" aria-hidden="true"><span :style="{ width: bar(c.memoryBytes, maxMemory) }" /></span>
                </td>
                <td class="usage__num usage__wide usage__rates">
                  {{ formatRate(c.netRxPerSecond) }} / {{ formatRate(c.netTxPerSecond) }}
                </td>
                <td class="usage__num usage__wide usage__rates">
                  {{ formatRate(c.blockReadPerSecond) }} / {{ formatRate(c.blockWritePerSecond) }}
                </td>
              </tr>
            </tbody>
            <tbody>
              <tr class="usage__app usage__other">
                <th scope="row">
                  Everything else on the server
                  <span class="usage__name">Other containers and processes, together</span>
                </th>
                <td class="usage__num">{{ formatPercent(metrics.other.cpuPercent) }}</td>
                <td class="usage__num">{{ formatBytes(metrics.other.memoryBytes) }}</td>
                <td class="usage__wide" />
                <td class="usage__wide" />
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.performance {
  container-type: inline-size;
  max-width: 1200px;
  margin: 0 auto;
  padding: 16px 24px 64px;
}

.performance__header {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 24px;
}

.performance__title {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--color-text-primary);
}

.performance__updated {
  font-size: 0.9rem;
  color: var(--color-text-secondary);
}

.performance__message {
  margin-bottom: 16px;
  padding: 12px 16px;
  font-size: 0.9rem;
  color: var(--color-text-primary);
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
}

.performance__message--error {
  border-left: 3px solid var(--color-status-critical);
}

.performance__cards,
.performance__charts {
  display: grid;
  grid-template-columns: 1fr;
  gap: 16px;
  margin-bottom: 16px;
}

@container (min-width: 640px) {
  .performance__cards {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@container (min-width: 900px) {
  .performance__charts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.card {
  min-width: 0;
  padding: 16px;
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: 0 1px 2px var(--color-shadow);
}

.card__label {
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  text-transform: uppercase;
  color: var(--color-text-secondary);
}

.card__value {
  margin-top: 4px;
  font-size: 2rem;
  font-weight: 600;
  line-height: 1.15;
  color: var(--color-text-primary);
}

.card__detail {
  margin: 2px 0 12px;
  font-size: 0.85rem;
  color: var(--color-text-secondary);
}

.card__title {
  margin-bottom: 12px;
  font-size: 1rem;
  font-weight: 600;
  color: var(--color-text-primary);
}

.card__title + .card__detail {
  margin-top: -8px;
}

/* Query container for the table's narrow layout. */
.usage {
  container-type: inline-size;
}

.usage__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9rem;
}

.usage__table th,
.usage__table td {
  padding: 8px 10px;
  text-align: left;
  font-weight: 400;
  vertical-align: middle;
  border-bottom: 1px solid var(--color-border);
}

.usage__table thead th {
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  text-transform: uppercase;
  color: var(--color-text-secondary);
}

.usage__table .usage__num {
  text-align: right;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.usage__app th,
.usage__app td {
  padding-top: 14px;
  font-weight: 600;
  color: var(--color-text-primary);
}

.usage__table .usage__container {
  padding-left: 22px;
}

.usage__service {
  display: block;
  color: var(--color-text-primary);
}

.usage__name {
  display: block;
  font-size: 0.78rem;
  font-weight: 400;
  color: var(--color-text-secondary);
  overflow-wrap: anywhere;
}

.usage__value {
  display: block;
}

/* Thin bar under the number; its track is a light step of the accent. */
.usage__bar {
  display: block;
  width: 72px;
  height: 4px;
  margin: 4px 0 0 auto;
  background-color: color-mix(in srgb, var(--color-accent) 15%, var(--color-surface));
  border-radius: var(--radius-sm);
}

.usage__bar span {
  display: block;
  height: 100%;
  min-width: 1px;
  background-color: var(--color-accent);
  border-radius: var(--radius-sm);
}

.usage__rates {
  color: var(--color-text-secondary);
}

.usage__other th,
.usage__other td {
  border-bottom: none;
  color: var(--color-text-secondary);
}

@container (max-width: 640px) {
  .usage__wide {
    display: none;
  }

  .usage__table th,
  .usage__table td {
    padding: 8px 6px;
  }

  .usage__table .usage__container {
    padding-left: 14px;
  }
}

@media (max-width: 420px) {
  .performance {
    padding: 16px 12px 48px;
  }

  .card__value {
    font-size: 1.6rem;
  }
}
</style>
