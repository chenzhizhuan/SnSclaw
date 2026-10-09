import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import TtsSettings from '../index.vue'
import { http, settingsApi } from '@/api'

vi.mock('@/api', () => ({ http: { get: vi.fn(), post: vi.fn() }, settingsApi: { get: vi.fn(), update: vi.fn() } }))
let app: App
let host: HTMLDivElement
const saved = { ttsEnabled: true, ttsProvider: 'edge-tts', ttsDefaultVoice: 'zh-CN-YunxiNeural', ttsSpeed: 1.2 }
const pause = vi.fn()
const play = vi.fn().mockResolvedValue(undefined)
async function flush() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() } }
async function mount() {
  host = document.createElement('div')
  app = createApp(TtsSettings)
  app.use(createI18n({ legacy: false, locale: 'en', missingWarn: false, fallbackWarn: false, messages: { en: {} } }))
  app.mount(host)
  await flush()
}
function voice() { return host.querySelector('[aria-label="settings.ttsVoiceLabel"]') as HTMLSelectElement }
function preview() { return host.querySelector('.voice-control button') as HTMLButtonElement }
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(settingsApi.get).mockResolvedValue({ data: { ...saved } } as any)
  vi.mocked(http.get).mockResolvedValue([
    { provider: 'edge-tts', providerLabel: 'Edge', voice: 'zh-CN-YunxiNeural', available: true },
    { provider: 'edge-tts', providerLabel: 'Edge', voice: 'zh-CN-XiaoxiaoNeural', available: true },
    { provider: 'dashscope', providerLabel: 'DashScope', voice: 'longxiaochun_v2', available: false },
  ] as any)
  vi.stubGlobal('Audio', class { pause = pause; play = play; onended = null; onerror = null })
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
})
afterEach(() => { app?.unmount(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

it('restores saved voice, filters options by provider and persists a new selection', async () => {
  await mount()
  expect(voice().value).toBe('zh-CN-YunxiNeural')
  expect(voice().options.length).toBe(3)
  voice().value = 'zh-CN-XiaoxiaoNeural'
  voice().dispatchEvent(new Event('change'))
  await flush()
  ;(host.querySelector('.save-bar .btn-primary') as HTMLButtonElement).click()
  await flush()
  expect(settingsApi.update).toHaveBeenCalledWith(expect.objectContaining({ ttsDefaultVoice: 'zh-CN-XiaoxiaoNeural' }))
})
it('clears incompatible voice when switching provider and disables preview without credentials', async () => {
  await mount()
  const provider = host.querySelector('select')!
  provider.value = 'dashscope'
  provider.dispatchEvent(new Event('change'))
  await flush()
  expect(voice().value).toBe('')
  expect(voice().options.length).toBe(2)
  expect(preview().disabled).toBe(true)
})
it('previews unsaved voice and speed and releases audio on unmount', async () => {
  await mount()
  vi.mocked(http.post).mockResolvedValue(new Blob(['audio']) as any)
  voice().value = 'zh-CN-XiaoxiaoNeural'
  voice().dispatchEvent(new Event('change'))
  await flush()
  preview().click()
  await flush()
  expect(http.post).toHaveBeenCalledWith('/tts/preview', expect.objectContaining({
    provider: 'edge-tts', voice: 'zh-CN-XiaoxiaoNeural', speed: 1.2,
  }), expect.objectContaining({ responseType: 'blob' }))
  expect(settingsApi.update).not.toHaveBeenCalled()
  expect(play).toHaveBeenCalled()
  app.unmount()
  expect(pause).toHaveBeenCalled()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:test')
})
it('discards a preview response after the selected voice changes', async () => {
  await mount()
  let resolve!: (v: any) => void
  vi.mocked(http.post).mockReturnValue(new Promise(r => { resolve = r }))
  preview().click()
  await flush()
  voice().value = ''
  voice().dispatchEvent(new Event('change'))
  await flush()
  resolve(new Blob(['audio']))
  await flush()
  expect(play).not.toHaveBeenCalled()
})
it('shows upstream preview errors returned as JSON blobs', async () => {
  await mount()
  vi.mocked(http.post).mockRejectedValue({ response: { data: new Blob(['{"msg":"Invalid key"}']) } })
  preview().click()
  await flush()
  expect(host.querySelector('[role="alert"]')?.textContent).toBe('Invalid key')
})
