// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const streamMock = vi.hoisted(() => {
  const handlers = new Map<string, Set<(data?: any) => void>>()
  const globalHandlers = new Set<(event: any) => void>()
  return {
    handlers,
    globalHandlers,
    connect: vi.fn().mockResolvedValue(undefined),
    disconnect: vi.fn(),
    resetDedup: vi.fn(),
    on: vi.fn((event: string, handler: (data?: any) => void) => {
      const listeners = handlers.get(event) ?? new Set()
      listeners.add(handler)
      handlers.set(event, listeners)
      return () => listeners.delete(handler)
    }),
    onEvent: vi.fn((handler: (event: any) => void) => {
      globalHandlers.add(handler)
      return () => globalHandlers.delete(handler)
    }),
  }
})

vi.mock('../useStream', () => ({
  useStream: () => streamMock,
}))

import { useChat } from '../useChat'

/**
 * Mirrors useStream: server events go to the global `onEvent` bus and then to the
 * typed handlers; the client-generated `stream_closed` goes to typed handlers
 * only (deliverLocalEvent) and must never reach the global bus.
 */
function fire(event: string, data?: any) {
  if (event !== 'stream_closed') {
    streamMock.globalHandlers.forEach(handler => handler({ type: event, data }))
  }
  streamMock.handlers.get(event)?.forEach(handler => handler(data))
}

/**
 * 方案 2：断流后自动 re-attach。
 *
 * 断流（10 分钟 SSE 上限 / 代理 / 网络）不带任何协议信号，前端此前只会把这一轮
 * 卡在"生成中"，随后后端按孤儿回收并杀掉 DSH 子进程。这里要求：仍在生成中的轮次
 * 收到 `stream_closed` 时自动带 reconnect=true 重新接入；轮次已结束则不动。
 */
describe('useChat auto re-attach after a silent stream cut', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    streamMock.handlers.clear()
    streamMock.globalHandlers.clear()
    setActivePinia(createPinia())
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('re-attaches to the running stream when the connection is cut mid-turn', async () => {
    const chat = useChat({ baseUrl: '', onStreamEnd: vi.fn() })
    await chat.sendMessage('run a long task', {
      conversationId: 'conv-cut',
      agentId: 'agent-1',
    })
    streamMock.connect.mockClear()

    fire('thinking_delta', { delta: 'working' })
    expect(chat.isGenerating.value).toBe(true)

    fire('stream_closed', { conversationId: 'conv-cut', reason: 'eof' })
    await vi.advanceTimersByTimeAsync(0)

    expect(streamMock.connect).toHaveBeenCalledTimes(1)
    expect(streamMock.connect).toHaveBeenCalledWith({
      conversationId: 'conv-cut',
      reconnect: true,
    })
    expect(chat.streamPhase.value).toBe('reconnecting')
  })

  it('does not re-attach after the turn already ended', async () => {
    const chat = useChat({ baseUrl: '', onStreamEnd: vi.fn() })
    await chat.sendMessage('hi', { conversationId: 'conv-cut', agentId: 'agent-1' })
    streamMock.connect.mockClear()

    fire('done', { status: 'completed', conversationId: 'conv-cut' })
    fire('stream_closed', { conversationId: 'conv-cut', reason: 'eof' })
    await vi.advanceTimersByTimeAsync(0)

    expect(streamMock.connect).not.toHaveBeenCalled()
  })

  it('ignores a cut that belongs to another conversation', async () => {
    const chat = useChat({ baseUrl: '', onStreamEnd: vi.fn() })
    await chat.sendMessage('hi', { conversationId: 'conv-cut', agentId: 'agent-1' })
    streamMock.connect.mockClear()

    fire('stream_closed', { conversationId: 'conv-other', reason: 'eof' })
    await vi.advanceTimersByTimeAsync(0)

    expect(streamMock.connect).not.toHaveBeenCalled()
  })

  it('gives up after repeated cuts without progress instead of looping forever', async () => {
    const onStreamEnd = vi.fn()
    const chat = useChat({ baseUrl: '', onStreamEnd })
    await chat.sendMessage('long', { conversationId: 'conv-cut', agentId: 'agent-1' })
    streamMock.connect.mockClear()

    for (let i = 0; i < 7; i++) {
      fire('stream_closed', { conversationId: 'conv-cut', reason: 'eof' })
      await vi.advanceTimersByTimeAsync(0)
    }

    expect(streamMock.connect).toHaveBeenCalledTimes(6)
    expect(chat.streamPhase.value).toBe('failed')
    expect(chat.messages.value.at(-1)?.status).toBe('failed')
    expect(onStreamEnd).toHaveBeenCalledWith({
      conversationId: 'conv-cut',
      reason: 'error',
    })
  })

  it('resets the attempt budget whenever the stream makes progress', async () => {
    const chat = useChat({ baseUrl: '', onStreamEnd: vi.fn() })
    await chat.sendMessage('long', { conversationId: 'conv-cut', agentId: 'agent-1' })
    streamMock.connect.mockClear()

    // 5 cuts, then real progress, then 5 more cuts: the cap must not trip,
    // because only *consecutive* silent cuts count against it.
    for (let i = 0; i < 5; i++) {
      fire('stream_closed', { conversationId: 'conv-cut', reason: 'eof' })
      await vi.advanceTimersByTimeAsync(0)
    }
    fire('content_delta', { delta: 'still working', conversationId: 'conv-cut' })
    for (let i = 0; i < 5; i++) {
      fire('stream_closed', { conversationId: 'conv-cut', reason: 'eof' })
      await vi.advanceTimersByTimeAsync(0)
    }

    expect(streamMock.connect).toHaveBeenCalledTimes(10)
    expect(chat.streamPhase.value).toBe('reconnecting')
  })
})
