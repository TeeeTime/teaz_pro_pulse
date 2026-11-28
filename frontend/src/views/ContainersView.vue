<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import ContainerRow from '@/components/ContainerRow.vue'
import DeployButton from '@/components/DeployButton.vue'
import DeployPanel from '@/components/DeployPanel.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import { ApiError, getJson, post, postJson } from '@/api'
import type { Container, ContainerAction, DeployJob, Status } from '@/types'

const REFRESH_MS = 10_000
const DEPLOY_POLL_MS = 2_000
const SEVERITY: Record<Status, number> = { good: 0, warning: 1, critical: 2 }

const containers = ref<Container[]>([])
const loaded = ref(false)
const forbidden = ref(false)
const error = ref<string | null>(null)
const updatedAt = ref<Date | null>(null)
const busyIds = ref(new Set<string>())
const now = ref(Date.now())

function worst(statuses: Status[]): Status {
  return statuses.reduce<Status>((a, b) => (SEVERITY[b] > SEVERITY[a] ? b : a), 'good')
}

// The backend already sorts by app, then service.
const groups = computed(() => {
  const byApp = new Map<string, Container[]>()
  for (const c of containers.value) {
    const list = byApp.get(c.app) ?? []
    list.push(c)
    byApp.set(c.app, list)
  }
  return [...byApp].map(([app, list]) => {
    const projects = deployableProjects(list)
    return {
      app,
      containers: list,
      status: worst(list.map((c) => c.status)),
      running: list.filter((c) => c.state === 'running').length,
      // Usually one; an app spread over several compose projects gets a button per project.
      projects: projects.map((project) => ({ project, label: projects.length > 1 ? `Deploy ${project}` : 'Deploy' })),
    }
  })
})

/**
 * Compose projects in this group that may be deployed: none of the project's
 * containers (in any group) is protected. The deployer checks this again.
 */
function deployableProjects(list: Container[]): string[] {
  const projects = new Set(list.map((c) => c.project).filter((p): p is string => !!p))
  return [...projects].filter((p) => !containers.value.some((c) => c.project === p && c.protected))
}

const overall = computed(() => worst(containers.value.map((c) => c.status)))
const runningCount = computed(() => containers.value.filter((c) => c.state === 'running').length)

async function load() {
  try {
    containers.value = await getJson<Container[]>('/api/containers')
    updatedAt.value = new Date()
    error.value = null
    forbidden.value = false
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) forbidden.value = true
    // Keep showing the last known state; just flag that it's stale.
    else error.value = `Could not load containers: ${e instanceof Error ? e.message : e}`
  } finally {
    loaded.value = true
  }
}

async function runAction(container: Container, action: ContainerAction) {
  busyIds.value.add(container.id)
  try {
    await post(`/api/containers/${encodeURIComponent(container.id)}/${action}`)
    error.value = null
  } catch (e) {
    error.value = `${action} ${container.name} failed: ${e instanceof Error ? e.message : e}`
  } finally {
    busyIds.value.delete(container.id)
    await load()
  }
}

/*
 * Deploys: git pull --ff-only && docker compose up -d --build for a whole
 * compose project, run by the deployer sidecar. One at a time; the log is
 * polled while it runs.
 */
const deploys = ref<Record<string, DeployJob>>({})
/** Projects whose deploy log is open. */
const openDeploys = ref(new Set<string>())
const deployStarting = ref(false)
const deployRunning = computed(() => Object.values(deploys.value).some((j) => j.state === 'running'))

async function loadDeploys() {
  try {
    for (const job of await getJson<DeployJob[]>('/api/deploys')) {
      deploys.value[job.project] = job
      // A deploy started before this page was opened (or by someone else): show it.
      if (job.state === 'running') openDeploys.value.add(job.project)
    }
  } catch {
    // Deployer down or no permission: the Deploy button reports it when used.
  }
}

async function startDeploy(project: string) {
  deployStarting.value = true
  try {
    deploys.value[project] = await postJson<DeployJob>(`/api/deploys/${encodeURIComponent(project)}`)
    openDeploys.value.add(project)
    error.value = null
  } catch (e) {
    error.value = `Deploy ${project} failed: ${e instanceof Error ? e.message : e}`
  } finally {
    deployStarting.value = false
  }
}

async function pollDeploys() {
  const running = Object.values(deploys.value).filter((j) => j.state === 'running')
  let finished = false
  for (const job of running) {
    try {
      const fresh = await getJson<DeployJob>(`/api/deploys/${encodeURIComponent(job.project)}`)
      deploys.value[job.project] = fresh
      if (fresh.state !== 'running') finished = true
    } catch {
      // Try again next round; the deployer may be busy or briefly unreachable.
    }
  }
  // New containers, new start times: show them right away.
  if (finished) await load()
}

function closeDeploy(project: string) {
  openDeploys.value.delete(project)
}

function openDeploysOf(projects: { project: string }[]): DeployJob[] {
  return projects
    .filter(({ project }) => openDeploys.value.has(project) && deploys.value[project])
    .map(({ project }) => deploys.value[project]!)
}

let refreshTimer: ReturnType<typeof setInterval> | undefined
let clockTimer: ReturnType<typeof setInterval> | undefined
let deployTimer: ReturnType<typeof setInterval> | undefined

onMounted(() => {
  load()
  loadDeploys()
  refreshTimer = setInterval(load, REFRESH_MS)
  clockTimer = setInterval(() => (now.value = Date.now()), 1000)
  deployTimer = setInterval(() => {
    if (deployRunning.value) pollDeploys()
  }, DEPLOY_POLL_MS)
})

onBeforeUnmount(() => {
  clearInterval(refreshTimer)
  clearInterval(clockTimer)
  clearInterval(deployTimer)
})
</script>

<template>
  <main class="containers">
    <header class="containers__header">
      <h1 class="containers__title">Containers</h1>
      <div v-if="loaded && !forbidden && containers.length" class="containers__summary">
        <StatusLabel :status="overall" />
        <span>{{ runningCount }} of {{ containers.length }} running</span>
        <span v-if="updatedAt" class="containers__updated">Updated {{ updatedAt.toLocaleTimeString() }}</span>
      </div>
    </header>

    <p v-if="error" class="containers__message containers__message--error" role="alert">{{ error }}</p>

    <p v-if="forbidden" class="containers__message">
      Managing containers needs the <strong>pulse-admin</strong> role in Keycloak. Ask an admin to assign it, then
      sign in again.
    </p>
    <p v-else-if="!loaded" class="containers__message">Loading…</p>
    <p v-else-if="!containers.length && !error" class="containers__message">
      No managed containers. Containers show up here once they carry the
      <code>pro.teaz.pulse.app</code> label.
    </p>

    <section v-for="group in groups" :key="group.app" class="containers__group">
      <header class="containers__group-header">
        <h2 class="containers__group-title">{{ group.app }}</h2>
        <StatusLabel :status="group.status" class="containers__group-status" />
        <span class="containers__group-count">{{ group.running }}/{{ group.containers.length }} running</span>
        <div v-if="group.projects.length" class="containers__group-actions">
          <DeployButton
            v-for="p in group.projects"
            :key="p.project"
            :label="p.label"
            :project="p.project"
            :disabled="deployStarting || deployRunning"
            @deploy="startDeploy(p.project)"
          />
        </div>
      </header>
      <DeployPanel
        v-for="job in openDeploysOf(group.projects)"
        :key="job.project"
        :job="job"
        @close="closeDeploy(job.project)"
      />
      <ul class="containers__list">
        <ContainerRow
          v-for="container in group.containers"
          :key="container.id"
          :container="container"
          :now="now"
          :busy="busyIds.has(container.id)"
          @action="runAction(container, $event)"
        />
      </ul>
    </section>
  </main>
</template>

<style scoped>
.containers {
  container-type: inline-size;
  max-width: 1200px;
  margin: 0 auto;
  /* Below the view switch in App.vue. */
  padding: 16px 24px 64px;
}

.containers__header {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 24px;
}

.containers__title {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--color-text-primary);
}

.containers__summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 16px;
  font-size: 0.9rem;
  color: var(--color-text-primary);
}

.containers__updated {
  color: var(--color-text-secondary);
}

.containers__message {
  margin-bottom: 16px;
  padding: 12px 16px;
  font-size: 0.9rem;
  color: var(--color-text-primary);
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
}

.containers__message--error {
  border-left: 3px solid var(--color-status-critical);
}

.containers__message code {
  font-size: 0.85em;
}

.containers__group {
  margin-bottom: 24px;
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: 0 1px 2px var(--color-shadow);
}

.containers__group-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 16px;
  padding: 12px 16px;
}

.containers__group-title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--color-text-primary);
}

.containers__group-status {
  font-size: 0.85rem;
  color: var(--color-text-primary);
}

.containers__group-count {
  margin-left: auto;
  font-size: 0.85rem;
  color: var(--color-text-secondary);
}

.containers__group-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* Query container for ContainerRow's breakpoints. */
.containers__list {
  container-type: inline-size;
  list-style: none;
  padding: 0;
}

@container (max-width: 420px) {
  .containers {
    padding: 16px 12px 48px;
  }
}
</style>
