<script setup lang="ts">
import { ref, watch } from 'vue'
import { TopBar, useTheme } from '@teeetime/teaz-pro-ui'
import type { TopBarMenuItem } from '@teeetime/teaz-pro-ui'
import ContainersView from '@/views/ContainersView.vue'
import PerformanceView from '@/views/PerformanceView.vue'
import { useAuth } from '@/auth'

// Applies the persisted/system theme to <html data-theme> on first render.
useTheme()

const auth = useAuth()

function onMenuSelect(item: TopBarMenuItem) {
  if (item.label === 'Sign out') auth.logout()
}

type View = 'containers' | 'performance'
const VIEWS: { view: View; label: string }[] = [
  { view: 'containers', label: 'Containers' },
  { view: 'performance', label: 'Performance' },
]

// The view lives in the URL (?view=performance), so a reload or a link keeps it. Each view polls only while shown.
const requested = new URLSearchParams(window.location.search).get('view')
const view = ref<View>(VIEWS.some((v) => v.view === requested) ? (requested as View) : 'containers')

watch(view, () => {
  const url = new URL(window.location.href)
  if (view.value === 'containers') url.searchParams.delete('view')
  else url.searchParams.set('view', view.value)
  window.history.replaceState(null, '', url)
})
</script>

<template>
  <TopBar
    app-name="Pulse"
    home-url="https://portal.teaz.pro"
    :user-name="auth.user.value?.name"
    @select="onMenuSelect"
  />

  <nav class="views" aria-label="View">
    <div class="segmented" role="group">
      <button
        v-for="option in VIEWS"
        :key="option.view"
        type="button"
        class="segmented__option"
        :class="{ 'segmented__option--active': view === option.view }"
        :aria-pressed="view === option.view"
        @click="view = option.view"
      >
        {{ option.label }}
      </button>
    </div>
  </nav>

  <ContainersView v-if="view === 'containers'" />
  <PerformanceView v-else />
</template>

<style scoped>
.views {
  max-width: 1200px;
  margin: 0 auto;
  padding: 24px 24px 0;
}

/* The same segmented control as Calendar's and Fitness's view switches. */
.segmented {
  display: inline-flex;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.segmented__option {
  padding: 7px 14px;
  font: inherit;
  font-size: 0.85rem;
  color: var(--color-text-secondary);
  background-color: var(--color-surface);
  border: none;
  cursor: pointer;
}

.segmented__option + .segmented__option {
  border-left: 1px solid var(--color-border);
}

.segmented__option:hover {
  background-color: var(--color-surface-hover);
}

.segmented__option:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: -2px;
}

.segmented__option--active {
  font-weight: 600;
  color: var(--color-text-primary);
  background-color: var(--color-surface-active);
}

@media (max-width: 420px) {
  .views {
    padding: 16px 12px 0;
  }
}
</style>
