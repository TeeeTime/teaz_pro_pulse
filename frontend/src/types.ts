/** Same states as teaz_pro_ui's StatusRow. */
export type Status = 'good' | 'warning' | 'critical'

export type ContainerAction = 'start' | 'stop' | 'restart'

/** One entry of GET /api/containers, see the backend's ContainerView. */
export interface Container {
  id: string
  name: string
  app: string
  service: string
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
