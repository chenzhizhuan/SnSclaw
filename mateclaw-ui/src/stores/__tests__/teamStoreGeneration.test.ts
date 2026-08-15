import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  get: vi.fn(),
  listTasks: vi.fn(),
  taskStats: vi.fn(),
  list: vi.fn(),
  create: vi.fn(),
  delete: vi.fn(),
}))
vi.mock('@/api/index', () => ({ teamApi: api }))

import { useTeamStore } from '../useTeamStore'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

const detail = (id: string) => ({ data: { team: { team: { id }, leadName: `lead-${id}` }, members: [{ agentId: id }] } })
const task = (id: string) => ({ task: { id, status: 'pending' } })

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

describe('useTeamStore request generation', () => {
  it('keeps team detail and tasks from the latest A/B open when responses resolve in reverse', async () => {
    const a = deferred<any>()
    const b = deferred<any>()
    api.get.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    api.listTasks.mockImplementation((teamId: string, statuses: string[]) =>
      Promise.resolve({ data: statuses.includes('pending') ? [task(`${teamId}-task`)] : [] }))
    api.taskStats.mockResolvedValue({ data: {} })
    const store = useTeamStore()

    const openA = store.openTeam('A')
    const openB = store.openTeam('B')
    b.resolve(detail('B'))
    await openB
    a.resolve(detail('A'))
    await openA

    expect(store.currentTeam?.team.id).toBe('B')
    expect(store.members.map(member => member.agentId)).toEqual(['B'])
    expect(store.tasks.map(item => item.task.id)).toEqual(['B-task'])
  })

  it('close invalidates pending detail and board responses', async () => {
    const detailRequest = deferred<any>()
    api.get.mockReturnValue(detailRequest.promise)
    const store = useTeamStore()
    const opening = store.openTeam('A')
    store.closeTeam()
    detailRequest.resolve(detail('A'))
    await opening

    expect(store.currentTeam).toBeNull()
    expect(store.members).toEqual([])
    expect(store.tasks).toEqual([])
  })

  it('close invalidates board responses already in flight', async () => {
    api.get.mockResolvedValue(detail('A'))
    const active = deferred<any>()
    const completed = deferred<any>()
    const closed = deferred<any>()
    const stats = deferred<any>()
    api.listTasks
      .mockReturnValueOnce(active.promise)
      .mockReturnValueOnce(completed.promise)
      .mockReturnValueOnce(closed.promise)
    api.taskStats.mockReturnValue(stats.promise)
    const store = useTeamStore()
    const opening = store.openTeam('A')
    await vi.waitFor(() => expect(api.listTasks).toHaveBeenCalledTimes(3))

    store.closeTeam()
    active.resolve({ data: [task('stale-active')] })
    completed.resolve({ data: [task('stale-completed')] })
    closed.resolve({ data: [task('stale-closed')] })
    stats.resolve({ data: { completed: 1 } })
    await opening

    expect(store.currentTeam).toBeNull()
    expect(store.tasks).toEqual([])
    expect(store.taskStats).toEqual({})
  })
})
