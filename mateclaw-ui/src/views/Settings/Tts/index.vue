<template>
  <div class="settings-section tts-section">
    <div class="section-header">
      <h2 class="section-title">{{ t('settings.ttsTitle') }}</h2>
      <p class="section-desc">{{ t('settings.ttsDesc') }}</p>
    </div>

    <div class="settings-card">
      <!-- 总开关 -->
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.ttsEnabled') }}</div>
          <div class="setting-hint">{{ t('settings.hints.ttsEnabled') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input v-model="settings.ttsEnabled" type="checkbox" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>

      <!-- 首选 Provider -->
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.ttsProvider') }}</div>
          <div class="setting-hint">{{ t('settings.hints.ttsProvider') }}</div>
        </div>
        <div class="setting-control">
          <select v-model="settings.ttsProvider" @change="onProviderChange" class="form-input" :disabled="!settings.ttsEnabled">
            <option value="auto">{{ t('settings.ttsProviderOptions.auto') }}</option>
            <option value="edge-tts">Edge TTS ({{ t('settings.ttsProviderTags.free') }})</option>
            <option value="dashscope">DashScope (CosyVoice)</option>
            <option value="openai">OpenAI TTS</option>
          </select>
        </div>
      </div>

      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.ttsVoiceLabel') }}</div>
          <div class="setting-hint">{{ t('settings.ttsVoiceHint') }}</div>
          <div v-if="settings.ttsProvider === 'auto' && activeProvider" class="setting-hint">
            {{ t('settings.ttsAutoVoiceProvider', { provider: activeProviderLabel }) }}
          </div>
        </div>
        <div class="setting-control voice-control">
          <select v-model="settings.ttsDefaultVoice" :aria-label="t('settings.ttsVoiceLabel')"
            class="form-input" :disabled="!settings.ttsEnabled || voicesLoading || !voiceOptions.length">
            <option value="">{{ t('settings.ttsVoiceDefault') }}</option>
            <option v-for="voice in voiceOptions" :key="voice.voice" :value="voice.voice">
              {{ voiceLabel(voice.voice) }}
            </option>
          </select>
          <button class="btn-secondary" :disabled="!settings.ttsEnabled || !canPreview || previewState === 'loading'"
            @click="previewVoice">{{ t(previewState === 'loading' ? 'settings.ttsPreviewLoading' : previewState === 'playing' ? 'settings.ttsPreviewStop' : 'settings.ttsPreview') }}</button>
        </div>
      </div>
      <p v-if="voiceError" class="voice-error" role="alert">{{ voiceError }}</p>
      <p v-if="voiceOptions.length && !canPreview" class="setting-hint">{{ t('settings.ttsVoiceUnavailable') }}</p>

      <!-- Fallback 开关 -->
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.ttsFallbackEnabled') }}</div>
          <div class="setting-hint">{{ t('settings.hints.ttsFallbackEnabled') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input v-model="settings.ttsFallbackEnabled" type="checkbox" :disabled="!settings.ttsEnabled" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>

      <!-- 自动模式 -->
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.ttsAutoMode') }}</div>
          <div class="setting-hint">{{ t('settings.hints.ttsAutoMode') }}</div>
        </div>
        <div class="setting-control">
          <select v-model="settings.ttsAutoMode" class="form-input" :disabled="!settings.ttsEnabled">
            <option value="off">{{ t('settings.ttsAutoModeOptions.off') }}</option>
            <option value="always">{{ t('settings.ttsAutoModeOptions.always') }}</option>
          </select>
        </div>
      </div>

      <!-- 语速 -->
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.ttsSpeed') }}</div>
          <div class="setting-hint">{{ t('settings.hints.ttsSpeed') }}</div>
        </div>
        <div class="setting-control speed-control">
          <input
            v-model.number="settings.ttsSpeed"
            type="range"
            min="0.5"
            max="2.0"
            step="0.1"
            :disabled="!settings.ttsEnabled"
            class="speed-slider"
          />
          <span class="speed-value">{{ settings.ttsSpeed?.toFixed(1) }}x</span>
        </div>
      </div>
    </div>

    <!-- Provider 说明 -->
    <template v-if="settings.ttsEnabled">
      <div class="provider-section">
        <div class="provider-header">
          <span class="provider-name">Edge TTS</span>
          <span class="provider-tag tag-free">{{ t('settings.ttsProviderTags.free') }}</span>
          <span class="provider-tag">{{ t('settings.ttsProviderTags.noKeyNeeded') }}</span>
        </div>
        <div class="settings-card">
          <div class="setting-item">
            <div class="setting-info">
              <div class="setting-hint">{{ t('settings.hints.edgeTtsInfo') }}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="provider-section">
        <div class="provider-header">
          <span class="provider-name">DashScope (CosyVoice)</span>
          <span class="provider-tag">{{ t('settings.ttsProviderTags.reuseLlmKey') }}</span>
        </div>
        <div class="settings-card">
          <div class="setting-item">
            <div class="setting-info">
              <div class="setting-hint">{{ t('settings.hints.dashscopeTtsInfo') }}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="provider-section">
        <div class="provider-header">
          <span class="provider-name">OpenAI TTS</span>
          <span class="provider-tag">{{ t('settings.ttsProviderTags.reuseLlmKey') }}</span>
        </div>
        <div class="settings-card">
          <div class="setting-item">
            <div class="setting-info">
              <div class="setting-hint">{{ t('settings.hints.openaiTtsInfo') }}</div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <div class="save-bar">
      <button class="btn-secondary" @click="loadSettings">{{ t('common.reset') }}</button>
      <button class="btn-primary" @click="onSaveSettings">{{ t('settings.actions.saveSystem') }}</button>
    </div>

    <div v-if="savedTip" class="save-tip">{{ savedTip }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { http, settingsApi } from '@/api'

const { t } = useI18n()
const savedTip = ref('')

const settings = reactive({
  ttsEnabled: false,
  ttsProvider: 'auto',
  ttsFallbackEnabled: true,
  ttsAutoMode: 'off',
  ttsDefaultVoice: '',
  ttsSpeed: 1.0,
})

interface VoiceOption { voice: string; provider: string; providerLabel: string; available: boolean; isDefault: boolean }
const voices = ref<VoiceOption[]>([])
const voicesLoading = ref(true)
const voiceError = ref('')
const previewState = ref<'idle' | 'loading' | 'playing'>('idle')
const activeProvider = computed(() => settings.ttsProvider === 'auto'
  ? voices.value.find(v => v.available)?.provider : settings.ttsProvider)
const voiceOptions = computed(() => voices.value.filter(v => v.provider === activeProvider.value))
const activeProviderLabel = computed(() => voiceOptions.value[0]?.providerLabel || '')
const canPreview = computed(() => !voicesLoading.value && voiceOptions.value.some(v => v.available))
const edgeNames: Record<string, string> = {
  longxiaochun_v2: '龙小淳', longxiaoxia_v2: '龙小夏', longshu_v2: '龙书',
  longhua_v2: '龙华', longwan_v2: '龙婉', longcheng_v2: '龙橙',
  'zh-CN-XiaoxiaoNeural': '晓晓', 'zh-CN-YunxiNeural': '云希',
  'zh-CN-YunjianNeural': '云健', 'zh-CN-XiaoyiNeural': '晓伊', 'zh-CN-YunyangNeural': '云扬',
}
function voiceLabel(id: string) { return edgeNames[id] ? `${edgeNames[id]} · ${id}` : id }
let audio: HTMLAudioElement | null = null
let audioUrl: string | null = null
let previewVersion = 0
let previewAbort: AbortController | null = null
function stopPreview() {
  ++previewVersion
  previewAbort?.abort()
  previewAbort = null
  if (audio) { audio.pause(); audio.onended = null; audio.onerror = null }
  audio = null
  if (audioUrl) URL.revokeObjectURL(audioUrl)
  audioUrl = null
  previewState.value = 'idle'
}
function onProviderChange() {
  settings.ttsDefaultVoice = ''
  voiceError.value = ''
  stopPreview()
}
watch(() => [settings.ttsDefaultVoice, settings.ttsSpeed, settings.ttsEnabled], stopPreview)
onBeforeUnmount(stopPreview)

async function previewVoice() {
  if (previewState.value === 'playing') { stopPreview(); return }
  stopPreview()
  const version = previewVersion
  previewAbort = new AbortController()
  previewState.value = 'loading'
  voiceError.value = ''
  try {
    const blob = await http.post('/tts/preview', {
      provider: activeProvider.value, voice: settings.ttsDefaultVoice,
      speed: settings.ttsSpeed, text: t('settings.ttsPreviewText'),
    }, { responseType: 'blob', timeout: 70000, signal: previewAbort.signal }) as unknown as Blob
    if (version !== previewVersion) return
    audioUrl = URL.createObjectURL(blob)
    audio = new Audio(audioUrl)
    audio.onended = stopPreview
    audio.onerror = () => { stopPreview(); voiceError.value = t('settings.ttsPreviewFailed') }
    await audio.play()
    if (version === previewVersion) previewState.value = 'playing'
  } catch (error: any) {
    let message = error?.message || t('settings.ttsPreviewFailed')
    if (error?.response?.data instanceof Blob) {
      try { message = JSON.parse(await error.response.data.text()).msg || message } catch { /* Keep network error. */ }
    }
    if (version !== previewVersion) return
    stopPreview()
    voiceError.value = message
  }
}

onMounted(async () => {
  try {
    await Promise.all([loadSettings(), loadVoices()])
  } catch (error) {
    voiceError.value = error instanceof Error ? error.message : t('settings.ttsPreviewFailed')
  }
})
async function loadVoices() {
  try { voices.value = await http.get('/tts/voices') as unknown as VoiceOption[] }
  finally { voicesLoading.value = false }
}

async function loadSettings() {
  stopPreview()
  const res: any = await settingsApi.get()
  const data = res.data || {}
  settings.ttsEnabled = data.ttsEnabled ?? false
  settings.ttsProvider = data.ttsProvider ?? 'auto'
  settings.ttsFallbackEnabled = data.ttsFallbackEnabled ?? true
  settings.ttsAutoMode = data.ttsAutoMode ?? 'off'
  settings.ttsDefaultVoice = data.ttsDefaultVoice ?? ''
  settings.ttsSpeed = data.ttsSpeed ?? 1.0
}

async function onSaveSettings() {
  await settingsApi.update({
    ttsEnabled: settings.ttsEnabled,
    ttsProvider: settings.ttsProvider,
    ttsFallbackEnabled: settings.ttsFallbackEnabled,
    ttsAutoMode: settings.ttsAutoMode,
    ttsDefaultVoice: settings.ttsDefaultVoice,
    ttsSpeed: settings.ttsSpeed,
  })
  await loadSettings()
  showSavedTip(t('settings.messages.saveSuccess'))
}

function showSavedTip(message: string) {
  savedTip.value = message
  window.setTimeout(() => { savedTip.value = '' }, 2500)
}
</script>

<style scoped>
.settings-section { width: 100%; }
.settings-section.tts-section { max-width: none; }
.section-header { display: flex; flex-direction: column; gap: 6px; margin-bottom: 20px; }
.section-title { margin: 0; font-size: 22px; font-weight: 700; color: var(--mc-text-primary); }
.section-desc { margin: 0; font-size: 14px; color: var(--mc-text-secondary); }

.settings-card { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; padding: 18px; box-shadow: 0 8px 24px rgba(124, 63, 30, 0.04); width: 100%; }
.setting-item { display: flex; justify-content: space-between; gap: 20px; padding: 16px 0; border-bottom: 1px solid var(--mc-border-light); }
.setting-item:last-child { border-bottom: none; }
.setting-info { flex: 1; }
.setting-label { font-size: 15px; font-weight: 600; color: var(--mc-text-primary); margin-bottom: 4px; }
.setting-hint { font-size: 13px; color: var(--mc-text-secondary); }
.setting-control { width: 220px; display: flex; align-items: center; justify-content: flex-end; }
.form-input { width: 100%; border: 1px solid var(--mc-border); border-radius: 10px; padding: 10px 12px; font-size: 14px; background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.form-input:focus { outline: none; border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217, 119, 87, 0.1); }
.form-input:disabled { opacity: 0.5; cursor: not-allowed; }

.toggle-switch { position: relative; display: inline-flex; width: 44px; height: 24px; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; cursor: pointer; background: var(--mc-border); border-radius: 999px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; width: 18px; height: 18px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(20px); }
.toggle-switch input:disabled + .toggle-slider { opacity: 0.5; cursor: not-allowed; }

.voice-control { flex-direction: column; gap: 8px; align-items: stretch; }
.voice-error { color: var(--mc-danger, #c0392b); font-size: 13px; }
.speed-control { gap: 10px; }
.speed-slider { width: 140px; accent-color: var(--mc-primary); }
.speed-value { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); min-width: 36px; text-align: right; }

.provider-section { margin-top: 24px; }
.provider-header { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.provider-name { font-size: 16px; font-weight: 600; color: var(--mc-text-primary); }
.provider-tag { font-size: 12px; padding: 2px 8px; border-radius: 6px; background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }
.provider-tag.tag-free { background: #e8f5e9; color: #2e7d32; }

.save-bar { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }
.btn-primary, .btn-secondary { border: none; border-radius: 10px; padding: 9px 14px; font-size: 14px; cursor: pointer; transition: all 0.15s; }
.btn-primary { background: var(--mc-primary); color: white; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-secondary { background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

.save-tip { position: fixed; right: 24px; bottom: 24px; background: var(--mc-text-primary); color: var(--mc-text-inverse); padding: 10px 14px; border-radius: 10px; box-shadow: 0 10px 30px rgba(124, 63, 30, 0.22); }

@media (max-width: 900px) {
  .setting-item { flex-direction: column; }
  .setting-control { width: 100%; justify-content: flex-start; }
}
</style>
