import { useAuth } from '@/auth'

/** A failed API call; `message` is the backend's problem detail when it sent one. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
  }
}

// Relative /api URLs: same host in prod (Traefik), Vite's proxy in dev.
async function request(method: 'GET' | 'POST', path: string): Promise<Response> {
  const token = await useAuth().getToken()
  const response = await fetch(path, {
    method,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!response.ok) {
    let detail: string | undefined
    try {
      detail = (await response.json()).detail
    } catch {
      // no JSON body
    }
    throw new ApiError(response.status, detail ?? `${response.status} ${response.statusText}`)
  }
  return response
}

export async function getJson<T>(path: string): Promise<T> {
  return (await request('GET', path)).json()
}

export async function post(path: string): Promise<void> {
  await request('POST', path)
}
