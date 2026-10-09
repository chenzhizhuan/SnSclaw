import { createApp } from 'vue'
import { createI18n } from 'vue-i18n'
import { expect, it, vi } from 'vitest'
import ChannelTypePicker from '../ChannelTypePicker.vue'

it('offers supported HTTP access without the unimplemented webhook channel', () => {
  const host = document.createElement('div')
  const pick = vi.fn()
  const app = createApp(ChannelTypePicker, { modelValue: true, onPick: pick })
  app.use(createI18n({ legacy: false, locale: 'en', missingWarn: false, fallbackWarn: false, messages: { en: {} } }))
  app.mount(host)
  try {
    expect(host.querySelector('img[alt="webhook"]')).toBeNull()
    expect(host.querySelector('img[alt="web"]')).not.toBeNull()
    const webchat = host.querySelector('img[alt="webchat"]')
    expect(webchat).not.toBeNull()
    webchat!.closest('button')!.click()
    expect(pick).toHaveBeenCalledWith('webchat')
  } finally {
    app.unmount()
  }
})
