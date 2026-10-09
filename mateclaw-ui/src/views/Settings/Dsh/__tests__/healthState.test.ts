import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import DshSettings from '../index.vue'

const api = vi.hoisted(() => ({ status: vi.fn(), testConnection: vi.fn(), saveConfig: vi.fn() }))
vi.mock('@/api', () => ({ dshApi: api }))
vi.mock('@/composables/useMcToast', () => ({ mcToast: { success: vi.fn(), error: vi.fn() } }))
let unmount: (() => void) | undefined
afterEach(() => { unmount?.(); document.body.innerHTML = ''; sessionStorage.clear(); vi.clearAllMocks() })
async function flush() { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick() } }

it('clears a previous successful handshake after a failed recheck', async () => {
  const ready = { state: 'READY', installed: true, enabled: false, config: {}, managed: {}, handshake: { success: true } }
  api.status.mockResolvedValueOnce({ data: ready }).mockResolvedValue({ data: { ...ready, handshake: { success: false } } })
  api.testConnection.mockResolvedValue({ data: { success: false, message: 'SDK handshake failed' } })
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp(DshSettings); app.mount(host); unmount = () => app.unmount()
  await flush()
  const button = (label: string) => [...host.querySelectorAll('button')].find(item => item.textContent === label)!
  expect(button('启用 DSH').disabled).toBe(false)
  button('测试 SDK 握手').click(); await flush()
  expect(button('启用 DSH').disabled).toBe(true)
  expect(host.querySelector('.error-card')?.textContent).toContain('SDK handshake failed')
  expect(api.status).toHaveBeenCalledTimes(2)
})

it('lets an administrator repair malformed stored patches', async () => {
  api.status.mockReset()
  api.status.mockResolvedValueOnce({ data: { state: 'CONFIG_INVALID', installed: false, config: {},
    managed: { 'dsh.patch_paths': '[', 'dsh.api_key': '****' } } })
    .mockResolvedValue({ data: { state: 'READY', installed: true, config: {}, managed: { 'dsh.patch_paths': '[]' } } })
  api.saveConfig.mockResolvedValue({ data: {} })
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp(DshSettings); app.mount(host); unmount = () => app.unmount()
  await flush()
  const patch = [...host.querySelectorAll('label')].find(item => item.textContent?.includes('Patch'))!.querySelector('input')!
  expect(patch.value).toBe('[')
  expect(host.querySelector<HTMLInputElement>('input[type="password"]')!.value).toBe('')
  patch.value = '[]'; patch.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  ;[...host.querySelectorAll('button')].find(item => item.textContent === '保存配置')!.click()
  await flush()
  expect(api.saveConfig).toHaveBeenCalledWith(expect.objectContaining({ 'dsh.patch_paths': '[]' }))
  expect(host.querySelector('.state-pill')?.textContent).toBe('已就绪')
})
