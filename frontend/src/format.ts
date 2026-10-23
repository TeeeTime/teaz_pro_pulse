/** Compact duration, two largest units: "45s", "12m 3s", "5h 12m", "3d 4h". */
export function formatDuration(ms: number): string {
  const seconds = Math.max(0, Math.floor(ms / 1000))
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const secs = seconds % 60

  if (days > 0) return `${days}d ${hours}h`
  if (hours > 0) return `${hours}h ${minutes}m`
  if (minutes > 0) return `${minutes}m ${secs}s`
  return `${secs}s`
}

export function since(iso: string | null, now: number): string | null {
  return iso ? formatDuration(now - Date.parse(iso)) : null
}

const GB = 1024 ** 3
const MB = 1024 ** 2

/** "512 MB", "1.4 GB" (binary units, as Docker and free count). */
export function formatBytes(bytes: number): string {
  if (bytes >= GB) return `${(bytes / GB).toFixed(bytes >= 10 * GB ? 0 : 1)} GB`
  if (bytes >= MB) return `${Math.round(bytes / MB)} MB`
  return `${Math.round(bytes / 1024)} KB`
}

/** "0.4%", "12%"; an en dash before there's a value. */
export function formatPercent(percent: number | null): string {
  if (percent === null) return '–'
  return `${percent < 10 ? percent.toFixed(1) : Math.round(percent)}%`
}

/** "0 B/s", "12 KB/s", "3.1 MB/s"; an en dash before there's a value. */
export function formatRate(bytesPerSecond: number | null): string {
  if (bytesPerSecond === null) return '–'
  if (bytesPerSecond >= MB) return `${(bytesPerSecond / MB).toFixed(1)} MB/s`
  if (bytesPerSecond >= 1024) return `${Math.round(bytesPerSecond / 1024)} KB/s`
  return `${Math.round(bytesPerSecond)} B/s`
}

/** "14:05" */
export function formatClock(date: Date): string {
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}
