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
  <TopBar
    app-name="Pulse"
    home-url="https://portal.teaz.pro"
    :user-name="auth.user.value?.name"
    @select="onMenuSelect"
  />

  <ContainersView />
</template>
