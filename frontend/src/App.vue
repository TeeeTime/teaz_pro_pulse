<script setup lang="ts">
import { TopBar, useTheme } from '@teeetime/teaz-pro-ui'
import type { TopBarMenuItem } from '@teeetime/teaz-pro-ui'
import ContainersView from '@/views/ContainersView.vue'
import { useAuth } from '@/auth'

// Applies the persisted/system theme to <html data-theme> on first render.
useTheme()

const auth = useAuth()

function onMenuSelect(item: TopBarMenuItem) {
  if (item.label === 'Sign out') auth.logout()
}
</script>

<template>
  <TopBar :user-name="auth.user.value?.name" @select="onMenuSelect">
    <span class="app-name">Teaz Pulse</span>
  </TopBar>

  <ContainersView />
</template>

<style scoped>
.app-name {
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--color-text-primary);
}
</style>
