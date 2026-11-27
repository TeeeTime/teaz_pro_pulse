/** Same states as teaz_pro_ui's StatusRow. */
export type Status = 'good' | 'warning' | 'critical'

export type ContainerAction = 'start' | 'stop' | 'restart'

/** One entry of GET /api/containers, see the backend's ContainerView. */
export interface Container {
  id: string
  name: string
  app: string
  service: string
  /** Compose project it was started in (what Deploy acts on), null outside compose */
  project: string | null
  image: string
  /** Docker state: created, running, paused, restarting, exited, dead */
  state: string
  /** Docker healthcheck status, null when the container has none */
  health: 'starting' | 'healthy' | 'unhealthy' | null
  status: Status
  startedAt: string | null
  finishedAt: string | null
  restartCount: number
  /** Can be restarted, but not stopped from Pulse */
  protected: boolean
}

/** GET /api/deploys/{project}, see the backend's DeployJob. In memory on the deployer: gone after it restarts. */
export interface DeployJob {
  project: string
  state: 'running' | 'succeeded' | 'failed'
  /** Keycloak user name of whoever clicked Deploy */
  startedBy: string
  startedAt: string
  finishedAt: string | null
  /** Of the command that ended it; null while running, -1 when it never ran or timed out */
  exitCode: number | null
  /** git pull and docker compose output, the last 200,000 characters */
  log: string
}

/** GET /api/metrics, see the backend's MetricsView. CPU is a share of the whole server (all cores = 100). */
export interface Metrics {
  sampledAt: string
  intervalSeconds: number
  host: {
    cores: number
    /** null right after Pulse started (needs two measurements) */
    cpuPercent: number | null
    /** 1, 5 and 15 minutes */
    load: [number, number, number]
    memoryUsedBytes: number
    memoryTotalBytes: number
    diskUsedBytes: number
    diskTotalBytes: number
  }
  /** All running Teaz containers together. */
  teaz: Usage
  /** Everything else on the server, not listed one by one. */
  other: Usage
  /** Running Teaz containers per app, by name. */
  apps: AppUsage[]
  /** Oldest first. */
  history: MetricsPoint[]
}

export interface Usage {
  cpuPercent: number | null
  memoryBytes: number
}

export interface AppUsage extends Usage {
  name: string
  containers: ContainerUsage[]
}

export interface ContainerUsage extends Usage {
  id: string
  name: string
  service: string
  /** Its own limit; null without one */
  memoryLimitBytes: number | null
  /** Bytes per second; null right after Pulse or the container started */
  netRxPerSecond: number | null
  netTxPerSecond: number | null
  blockReadPerSecond: number | null
  blockWritePerSecond: number | null
}

export interface MetricsPoint {
  at: string
  hostCpuPercent: number
  teazCpuPercent: number
  hostMemoryBytes: number
  teazMemoryBytes: number
}
