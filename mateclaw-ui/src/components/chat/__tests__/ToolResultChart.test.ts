import { createApp, nextTick } from 'vue'
import { createPinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useThemeStore } from '@/stores/useThemeStore'
const mock = vi.hoisted(() => ({ init: vi.fn(), setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn(), disconnect: vi.fn() }))
vi.mock('echarts', () => ({ init: mock.init }))
import ToolResultChart from '../tool-results/ToolResultChart.vue'
let app: ReturnType<typeof createApp> | undefined
let host: HTMLElement
function mount() {
  vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => {} })
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect = mock.disconnect })
  mock.init.mockReturnValue({ setOption: mock.setOption, dispose: mock.dispose, resize: mock.resize })
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(ToolResultChart, { data: { tooltip: { formatter: '<img onerror=bad>' }, series: [{ type: 'bar', data: [1] }] } })
  const pinia = createPinia()
  app.use(pinia)
  app.mount(host)
  return useThemeStore(pinia)
}
afterEach(() => { app?.unmount(); app = undefined; document.body.innerHTML = ''; vi.clearAllMocks(); vi.unstubAllGlobals() })
describe('tool chart lifecycle', () => {
  it('owns one canvas instance, sanitizes options, and disposes on unmount', async () => {
    mount()
    await vi.waitFor(() => expect(mock.setOption).toHaveBeenCalledOnce())
    expect(host.querySelector('.echarts-block')).toBeNull()
    expect(mock.init).toHaveBeenCalledOnce()
    expect(mock.setOption.mock.calls[0][0].tooltip).toEqual({ renderMode: 'richText', confine: true })
    app!.unmount(); app = undefined
    expect(mock.dispose).toHaveBeenCalledOnce()
    expect(mock.disconnect).toHaveBeenCalledOnce()
  })
  it('does not mount after unmount while lazy loading', async () => {
    mount()
    app!.unmount(); app = undefined
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(mock.init).not.toHaveBeenCalled()
  })
  it('shows readable JSON when chart option rendering fails', async () => {
    mock.setOption.mockImplementationOnce(() => { throw new Error('Unsupported chart') })
    mount()
    await vi.waitFor(() => expect(host.querySelector('pre')).not.toBeNull())
    expect(host.querySelector('pre')?.textContent).toContain('series')
    expect(host.querySelector('img')).toBeNull()
    expect(mock.dispose).toHaveBeenCalledOnce()
  })
})
