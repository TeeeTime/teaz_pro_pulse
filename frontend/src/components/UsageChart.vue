<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { formatClock } from '@/format'

export interface ChartPoint {
  /** ms since epoch */
  at: number
  /** The whole server. */
  total: number
  /** The Teaz containers' part of it. */
  teaz: number
}

const props = defineProps<{
  points: ChartPoint[]
  /** Top of the y axis: 100 for CPU, the server's memory, ... */
  max: number
  /** How much time the x axis spans, ending at the last point. */
  windowMs: number
  /** Axis ticks are counted in this unit (1 for %, 1 GiB for memory) ... */
  unit: number
  /** ... and labeled like this, e.g. (v) => `${v}%`. */
  tickLabel: (value: number) => string
  /** Values in the tooltip, e.g. formatPercent. */
  format: (value: number) => string
  /** For screen readers, e.g. "CPU over the last hour". */
  label: string
}>()

/*
 * The server's total as a neutral area (context) and the Teaz containers as
 * an accent line with a light wash: line vs. area, plus the legend and the
 * tooltip, so the two never depend on color alone. One y axis, from 0.
 * Hover or arrow keys move a crosshair that reads out both at that time.
 */
const HEIGHT = 200
const MARGIN = { top: 10, right: 12, bottom: 24, left: 52 }

const wrapper = ref<HTMLElement | null>(null)
const width = ref(600)
let observer: ResizeObserver | undefined
onMounted(() => {
  observer = new ResizeObserver(([entry]) => {
    if (entry) width.value = Math.max(240, entry.contentRect.width)
  })
  if (wrapper.value) observer.observe(wrapper.value)
})
onBeforeUnmount(() => observer?.disconnect())

const plot = computed(() => ({
  left: MARGIN.left,
  right: width.value - MARGIN.right,
  top: MARGIN.top,
  bottom: HEIGHT - MARGIN.bottom,
}))

const end = computed(() => props.points.at(-1)?.at ?? Date.now())
const start = computed(() => end.value - props.windowMs)

function x(at: number): number {
  const p = plot.value
  return p.left + ((at - start.value) / props.windowMs) * (p.right - p.left)
}

function y(value: number): number {
  const p = plot.value
  const share = Math.min(1, Math.max(0, value / Math.max(props.max, 1e-9)))
  return p.bottom - share * (p.bottom - p.top)
}

function line(values: number[]): string {
  return props.points.map((p, i) => `${i ? 'L' : 'M'}${x(p.at).toFixed(1)},${y(values[i] ?? 0).toFixed(1)}`).join('')
}

function area(values: number[]): string {
  if (!props.points.length) return ''
  const base = plot.value.bottom.toFixed(1)
  const first = x(props.points[0]!.at).toFixed(1)
  const last = x(props.points.at(-1)!.at).toFixed(1)
  return `M${first},${base}${line(values).replace(/^M/, 'L')}L${last},${base}Z`
}

const totals = computed(() => props.points.map((p) => p.total))
const teaz = computed(() => props.points.map((p) => p.teaz))

/** Four or five round steps from 0 up to max, counted in the unit (1, 2, 2.5, 5 × 10^n). */
const yTicks = computed(() => {
  const top = props.max / props.unit
  const raw = top / 4
  const magnitude = 10 ** Math.floor(Math.log10(raw || 1))
  const step = ([1, 2, 2.5, 5, 10].find((s) => s * magnitude >= raw) ?? 10) * magnitude
  const ticks: number[] = []
  for (let v = 0; v <= top + 1e-9; v += step) ticks.push(v)
  return ticks
})

/** Every 15 minutes on the clock. */
const xTicks = computed(() => {
  const step = 15 * 60_000
  const ticks: number[] = []
  for (let t = Math.ceil(start.value / step) * step; t <= end.value; t += step) ticks.push(t)
  return ticks
})

const active = ref<number | null>(null)

function nearest(at: number): number {
  let best = 0
  props.points.forEach((p, i) => {
    if (Math.abs(p.at - at) < Math.abs(props.points[best]!.at - at)) best = i
  })
  return best
}

function onPointer(event: PointerEvent) {
  if (!props.points.length) return
  const svg = event.currentTarget as SVGSVGElement
  const box = svg.getBoundingClientRect()
  const p = plot.value
  const at = start.value + ((event.clientX - box.left - p.left) / (p.right - p.left)) * props.windowMs
  active.value = nearest(at)
}

function onKey(event: KeyboardEvent) {
  if (!props.points.length) return
  const last = props.points.length - 1
  if (event.key === 'ArrowLeft') active.value = Math.max(0, (active.value ?? last + 1) - 1)
  else if (event.key === 'ArrowRight') active.value = Math.min(last, (active.value ?? -1) + 1)
  else if (event.key === 'Escape') active.value = null
  else return
  event.preventDefault()
}

const readout = computed(() => {
  const point = active.value === null ? undefined : props.points[active.value]
  if (!point) return null
  const px = x(point.at)
  return {
    x: px,
    // Right of the crosshair, or left of it in the right half, so it never leaves the chart.
    flip: px > width.value / 2,
    time: formatClock(new Date(point.at)),
    total: props.format(point.total),
    teaz: props.format(point.teaz),
    yTotal: y(point.total),
    yTeaz: y(point.teaz),
  }
})
</script>

<template>
  <div ref="wrapper" class="chart">
    <ul class="chart__legend">
      <li class="chart__legend-item">
        <span class="chart__key chart__key--total" aria-hidden="true" />
        Server
      </li>
      <li class="chart__legend-item">
        <span class="chart__key chart__key--teaz" aria-hidden="true" />
        Teaz containers
      </li>
    </ul>

    <div class="chart__plot">
      <svg
        :width="width"
        :height="HEIGHT"
        :viewBox="`0 0 ${width} ${HEIGHT}`"
        role="img"
        :aria-label="`${label}. Use the arrow keys to read values.`"
        tabindex="0"
        @pointermove="onPointer"
        @pointerleave="active = null"
        @keydown="onKey"
        @blur="active = null"
      >
        <g class="chart__grid">
          <template v-for="tick in yTicks" :key="tick">
            <line :x1="plot.left" :x2="plot.right" :y1="y(tick * unit)" :y2="y(tick * unit)" />
            <text class="chart__axis" :x="plot.left - 8" :y="y(tick * unit)" text-anchor="end" dominant-baseline="middle">
              {{ tickLabel(tick) }}
            </text>
          </template>
          <text v-for="tick in xTicks" :key="tick" class="chart__axis" :x="x(tick)" :y="HEIGHT - 6" text-anchor="middle">
            {{ formatClock(new Date(tick)) }}
          </text>
        </g>

        <path class="chart__total-area" :d="area(totals)" />
        <path class="chart__teaz-area" :d="area(teaz)" />
        <path class="chart__teaz-line" :d="line(teaz)" />

        <g v-if="readout" class="chart__cursor">
          <line :x1="readout.x" :x2="readout.x" :y1="plot.top" :y2="plot.bottom" />
          <circle class="chart__dot chart__dot--total" :cx="readout.x" :cy="readout.yTotal" r="4" />
          <circle class="chart__dot chart__dot--teaz" :cx="readout.x" :cy="readout.yTeaz" r="4" />
        </g>
      </svg>

      <div
        v-if="readout"
        class="chart__tooltip"
        :class="{ 'chart__tooltip--flip': readout.flip }"
        :style="{ left: `${readout.x}px` }"
        role="status"
      >
        <span class="chart__tooltip-time">{{ readout.time }}</span>
        <span class="chart__tooltip-row">
          <span class="chart__key chart__key--total" aria-hidden="true" />
          <strong>{{ readout.total }}</strong> Server
        </span>
        <span class="chart__tooltip-row">
          <span class="chart__key chart__key--teaz" aria-hidden="true" />
          <strong>{{ readout.teaz }}</strong> Teaz
        </span>
      </div>

      <p v-if="points.length < 2" class="chart__empty">The chart fills in over the next minutes.</p>
    </div>
  </div>
</template>

<style scoped>
.chart {
  min-width: 0;
}

.chart__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  margin-bottom: 8px;
  padding: 0;
  list-style: none;
  font-size: 0.8rem;
  color: var(--color-text-secondary);
}

.chart__legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* Legend keys mirror the marks: a filled rect for the area, a short line for the line. */
.chart__key {
  display: inline-block;
  flex-shrink: 0;
}

.chart__key--total {
  width: 12px;
  height: 10px;
  background-color: color-mix(in srgb, var(--color-text-secondary) 28%, transparent);
}

.chart__key--teaz {
  width: 14px;
  height: 2px;
  background-color: var(--color-accent);
}

.chart__plot {
  position: relative;
}

.chart svg {
  display: block;
  overflow: visible;
  outline: none;
}

.chart svg:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: 2px;
}

.chart__grid line {
  stroke: var(--color-border);
  stroke-width: 1;
}

.chart__axis {
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  fill: var(--color-text-secondary);
}

.chart__total-area {
  fill: color-mix(in srgb, var(--color-text-secondary) 28%, transparent);
}

.chart__teaz-area {
  fill: color-mix(in srgb, var(--color-accent) 12%, transparent);
}

.chart__teaz-line {
  fill: none;
  stroke: var(--color-accent);
  stroke-width: 2;
  stroke-linejoin: round;
  stroke-linecap: round;
}

.chart__cursor line {
  stroke: var(--color-text-secondary);
  stroke-width: 1;
}

/* A 2px ring in the surface color keeps the dots readable where they sit on a line or area. */
.chart__dot {
  stroke: var(--color-surface);
  stroke-width: 2;
}

.chart__dot--total {
  fill: var(--color-text-secondary);
}

.chart__dot--teaz {
  fill: var(--color-accent);
}

.chart__tooltip {
  position: absolute;
  top: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  font-size: 0.8rem;
  white-space: nowrap;
  color: var(--color-text-secondary);
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
  box-shadow: 0 4px 12px var(--color-shadow-strong);
  transform: translateX(12px);
  pointer-events: none;
}

.chart__tooltip--flip {
  transform: translateX(calc(-100% - 12px));
}

.chart__tooltip-time {
  font-weight: 600;
  color: var(--color-text-primary);
}

.chart__tooltip-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.chart__tooltip-row strong {
  font-variant-numeric: tabular-nums;
  color: var(--color-text-primary);
}

.chart__empty {
  position: absolute;
  inset: 0 12px 24px 52px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.85rem;
  color: var(--color-text-secondary);
}
</style>
