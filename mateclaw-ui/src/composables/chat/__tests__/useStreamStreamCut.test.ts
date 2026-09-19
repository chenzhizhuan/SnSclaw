import { afterEach, describe, expect, it, vi } from 'vitest'
import { useStream } from '@/composables/chat/useStream'

/**
 * 方案 2 的前端底座：静默断流必须可观测。
 *
 * 平台 chat SSE 单条连接有 10 分钟硬上限（`new Utf8SseEmitter(10 * 60 * 1000L)`），
 * 断流时不会发 `done` / `error`，reader 只看到 EOF —— useStream 需要把它报成
 * 合成的 `stream_closed` 事件，useChat 才能带着 reconnect=true 重新 attach，
 * 避免后端把仍在跑的运行当孤儿回收并杀掉 DSH 子进程。
 */
describe('useStream silent stream cut detection', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  /** A body that ends immediately with the given SSE text. */
  function stubStaticSse(text: string) {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(text, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })))
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null) })
  }

  /**
   * A body that never ends on its own but errors with AbortError when the
   * request signal aborts — mirroring a real browser fetch. The plain
   * `mockResolvedValue(new Response(...))` stub ignores the signal, so an
   * aborted read would hang instead of rejecting.
   */
  function stubAbortableSse() {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((_url: string, init: RequestInit) => {
      let controller!: ReadableStreamDefaultController<Uint8Array>
      const body = new ReadableStream<Uint8Array>({ start(c) { controller = c } })
      init?.signal?.addEventListener('abort', () => {
        try {
          controller.error(Object.assign(new Error('The operation was aborted.'), { name: 'AbortError' }))
        } catch { /* stream already closed */ }
      })
      return Promise.resolve(new Response(body, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      }))
    }))
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null) })
  }

  it('reports stream_closed when the body ends without a terminal event', async () => {
    stubStaticSse('id: 1\nevent: content_delta\ndata: {"delta":"hi"}\n\n')
    const stream = useStream({ url: '/api/test-stream' })
    const closed: any[] = []
    stream.on('stream_closed', (data: any) => closed.push(data))

    await stream.connect({ conversationId: 'conv-1' })

    expect(closed).toEqual([{ conversationId: 'conv-1', reason: 'eof' }])
  })

  it('does not report a cut once the server sent a done envelope', async () => {
    stubStaticSse([
      'id: 1\nevent: content_delta\ndata: {"delta":"hi"}\n\n',
      'id: 2\nevent: done\ndata: {"status":"completed"}\n\n',
    ].join(''))
    const stream = useStream({ url: '/api/test-stream' })
    const closed: any[] = []
    stream.on('stream_closed', (data: any) => closed.push(data))

    await stream.connect({ conversationId: 'conv-1' })

    expect(closed).toEqual([])
  })

  it('keeps stream_closed out of the global onEvent bus', async () => {
    stubStaticSse('')
    const stream = useStream({ url: '/api/test-stream' })
    const seen: string[] = []
    stream.onEvent(event => seen.push(event.type))

    await stream.connect({ conversationId: 'conv-1' })

    expect(seen).not.toContain('stream_closed')
  })

  it('does not report a cut when the caller disconnects on purpose', async () => {
    stubAbortableSse()
    const stream = useStream({ url: '/api/test-stream' })
    const closed: any[] = []
    stream.on('stream_closed', (data: any) => closed.push(data))

    const connecting = stream.connect({ conversationId: 'conv-1' })
    // Let connect() get as far as the read loop before aborting.
    await new Promise(resolve => setTimeout(resolve, 0))
    stream.disconnect()
    await connecting

    expect(closed).toEqual([])
  })

  it('reports a no-data watchdog abort as a cut (the backend may still run)', async () => {
    vi.useFakeTimers()
    stubAbortableSse()
    const stream = useStream({ url: '/api/test-stream' })
    const closed: any[] = []
    stream.on('stream_closed', (data: any) => closed.push(data))

    const connecting = stream.connect({ conversationId: 'conv-1' })
    await vi.advanceTimersByTimeAsync(0)
    // 120s with no data at all → the watchdog aborts the socket.
    await vi.advanceTimersByTimeAsync(120_000)
    await connecting

    expect(closed).toEqual([{ conversationId: 'conv-1', reason: 'no-data' }])
  })

  it('stays silent for a connection superseded by a newer one', async () => {
    const controllers: ReadableStreamDefaultController<Uint8Array>[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => {
      let controller!: ReadableStreamDefaultController<Uint8Array>
      const body = new ReadableStream<Uint8Array>({ start(c) { controller = c; controllers.push(c) } })
      return Promise.resolve(new Response(body, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      }))
    }))
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null) })

    const stream = useStream({ url: '/api/test-stream' })
    const closed: any[] = []
    stream.on('stream_closed', (data: any) => closed.push(data))

    const first = stream.connect({ conversationId: 'conv-1' })
    await new Promise(resolve => setTimeout(resolve, 0))
    const second = stream.connect({ conversationId: 'conv-1' })
    await new Promise(resolve => setTimeout(resolve, 0))

    // The first connection was superseded; its clean close must not be reported.
    controllers[0]?.close()
    await first
    expect(closed).toEqual([])

    // The current connection's close is the real cut.
    controllers[1]?.close()
    await second
    expect(closed).toEqual([{ conversationId: 'conv-1', reason: 'eof' }])
  })
})
