/**
 * Team board event subscription over SSE.
 *
 * EventSource cannot carry the Authorization header, so this reads the SSE
 * body through fetch + ReadableStream (same approach as the chat stream).
 * No auto-reconnect: the board keeps its polling fallback, so a dropped
 * subscription degrades gracefully instead of stacking retry loops.
 */
export interface TeamBoardEvent {
  event: string
  data: Record<string, unknown>
}

export function subscribeTeamEvents(
  teamId: string,
  onEvent: (e: TeamBoardEvent) => void,
): () => void {
  const controller = new AbortController()

  const run = async () => {
    const headers: Record<string, string> = { Accept: 'text/event-stream' }
    const token = localStorage.getItem('token')
    const workspaceId = localStorage.getItem('mc-workspace-id')
    if (token) headers.Authorization = `Bearer ${token}`
    if (workspaceId) headers['X-Workspace-Id'] = workspaceId

    const res = await fetch(`/api/v1/teams/${teamId}/events`, {
      headers,
      signal: controller.signal,
    })
    if (!res.ok || !res.body) return

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // SSE frames are separated by a blank line.
      let sep: number
      while ((sep = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)
        let event = 'message'
        let data = ''
        for (const line of frame.split('\n')) {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          else if (line.startsWith('data:')) data += line.slice(5).trim()
        }
        if (event === 'heartbeat' || !data) continue
        try {
          onEvent({ event, data: JSON.parse(data) })
        } catch {
          // Non-JSON payloads are not board events; ignore.
        }
      }
    }
  }

  run().catch(() => {
    // Aborted or dropped — the board's polling fallback takes over.
  })

  return () => controller.abort()
}
