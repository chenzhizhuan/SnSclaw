<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { version } from '../package.json'

const appVersion = version

const status = ref<'starting' | 'ready' | 'language-select' | 'initializing' | 'timeout' | 'crashed' | 'restarting' | 'connection-select'>('starting')
const errorMessage = ref('')
const isDark = ref(true)
const selectedLanguage = ref<'zh-CN' | 'en-US' | null>(null)

// ─── Connection chooser state ──────────────────────────
const connectionMode = ref<'local' | 'remote' | null>(null)
const connView = ref<'choose' | 'remote-form'>('choose')
const remoteUrlInput = ref('')
const testing = ref(false)
const testResult = ref<{ ok: boolean; msg: string } | null>(null)
const recentServers = ref<RemoteServer[]>([])
// Build variant: 'local' = full (bundles JRE+JAR), 'remote' = lite (connect to remote server only)
const buildMode = ref<'local' | 'remote'>('local')

let BACKEND_URL = ''

let unsubStatus: (() => void) | null = null
let unsubCrashed: (() => void) | null = null
let unsubUpdater: (() => void) | null = null

// ─── Updater state ─────────────────────────────────────
const updater = ref<UpdaterState>({ status: 'idle' })

const showUpdateBanner = computed(() =>
  ['available', 'downloading', 'downloaded', 'error'].includes(updater.value.status)
)

const downloadPercent = computed(() =>
  Math.round(updater.value.progress?.percent ?? 0)
)

function handleDownloadUpdate() {
  window.mateClawAPI?.downloadUpdate()
}

function handleInstallUpdate() {
  window.mateClawAPI?.installUpdate()
}

// Step progress
const currentStep = computed(() => {
  switch (status.value) {
    case 'starting':        return 1
    case 'restarting':      return 0
    case 'language-select': return 2
    case 'initializing':    return 2
    case 'ready':           return 4
    case 'timeout':
    case 'crashed':         return -1
    default:                return 0
  }
})

const progressWidth = computed(() => {
  if (status.value === 'crashed' || status.value === 'timeout') return '100%'
  if (status.value === 'ready') return '100%'
  if (status.value === 'restarting') return '15%'
  if (status.value === 'language-select') return '60%'
  if (status.value === 'initializing') return '80%'
  return '45%'
})

const steps = [
  { label: 'Environment' },
  { label: 'Starting' },
  { label: 'Language' },
  { label: 'Ready' },
]

function stepClass(index: number) {
  if (status.value === 'crashed' || status.value === 'timeout') return index === 0 ? 'done' : ''
  if (currentStep.value > index) return 'done'
  if (currentStep.value === index) return 'active'
  return ''
}

function toggleTheme() {
  isDark.value = !isDark.value
}

async function checkSetupStatus() {
  try {
    const res = await fetch(`${BACKEND_URL}/api/v1/setup/status`)
    const data = await res.json()
    if (data.data?.initialized) {
      // Already initialized — skip language selection, go directly to app
      status.value = 'ready'
      navigateToApp()
    } else {
      // First run — show language selection
      status.value = 'language-select'
    }
  } catch (e) {
    console.error('Failed to check setup status:', e)
    // If API fails, default to language selection
    status.value = 'language-select'
  }
}

async function selectLanguage(lang: 'zh-CN' | 'en-US') {
  selectedLanguage.value = lang
  status.value = 'initializing'

  try {
    const res = await fetch(`${BACKEND_URL}/api/v1/setup/init`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ language: lang }),
    })

    if (res.ok || res.status === 409) {
      // 409 means already initialized — that's fine too
      status.value = 'ready'
      setTimeout(() => navigateToApp(), 600)
    } else {
      const err = await res.text()
      throw new Error(err)
    }
  } catch (e: any) {
    console.error('Failed to initialize:', e)
    errorMessage.value = e.message || 'Initialization failed'
    status.value = 'crashed'
  }
}

function navigateToApp() {
  if (window.mateClawAPI?.navigateToApp) {
    window.mateClawAPI.navigateToApp()
  }
}

// ─── Backend-ready dispatch ────────────────────────────
async function handleBackendReady() {
  if (connectionMode.value === 'remote') {
    // A remote server is already initialized by its administrator — skip the
    // local setup/language flow and enter the application directly.
    if (window.mateClawAPI) {
      BACKEND_URL = await window.mateClawAPI.getBackendUrl()
    }
    status.value = 'ready'
    setTimeout(() => navigateToApp(), 300)
  } else {
    checkSetupStatus()
  }
}

// ─── Connection chooser actions ────────────────────────
async function chooseLocal() {
  connectionMode.value = 'local'
  status.value = 'starting'
  await window.mateClawAPI?.useLocalConnection()
  if (window.mateClawAPI) {
    BACKEND_URL = await window.mateClawAPI.getBackendUrl()
  }
}

function openRemoteForm() {
  connView.value = 'remote-form'
  testResult.value = null
}

function backToChoose() {
  connView.value = 'choose'
  testResult.value = null
}

function describeConnError(r: { error?: string }): string {
  switch (r.error) {
    case 'invalid-url': return '地址格式无效'
    case 'timeout':     return '连接超时，请检查地址与网络'
    default:            return r.error ? `连接失败: ${r.error}` : '连接失败'
  }
}

async function testRemote() {
  if (!remoteUrlInput.value.trim() || !window.mateClawAPI) return
  testing.value = true
  testResult.value = null
  try {
    const r = await window.mateClawAPI.testConnection(remoteUrlInput.value)
    testResult.value = r.ok
      ? { ok: true, msg: '连接成功' }
      : { ok: false, msg: describeConnError(r) }
  } finally {
    testing.value = false
  }
}

async function connectRemote(url?: string) {
  const target = (url ?? remoteUrlInput.value).trim()
  if (!target || !window.mateClawAPI) return
  remoteUrlInput.value = target
  const r = await window.mateClawAPI.useRemoteConnection(target)
  if (!r.ok) {
    testResult.value = { ok: false, msg: describeConnError(r) }
    return
  }
  connectionMode.value = 'remote'
  testResult.value = null
  status.value = 'starting'
}

onMounted(async () => {
  // Detect system dark mode preference
  const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
  isDark.value = prefersDark

  createParticles()

  if (window.mateClawAPI) {
    // Get dynamic backend URL from main process
    BACKEND_URL = await window.mateClawAPI.getBackendUrl()

    unsubStatus = window.mateClawAPI.onBackendStatus((s: string) => {
      if (s === 'choose') {
        status.value = 'connection-select'
      } else if (s === 'ready') {
        handleBackendReady()
      } else {
        status.value = s as typeof status.value
      }
    })
    unsubCrashed = window.mateClawAPI.onBackendCrashed((msg: string) => {
      status.value = 'crashed'
      errorMessage.value = msg
    })

    // Decide the initial screen from saved connection configuration.
    try {
      const cfg = await window.mateClawAPI.getConnectionConfig()
      recentServers.value = cfg.servers || []
      remoteUrlInput.value = cfg.remoteUrl || ''
      buildMode.value = cfg.buildMode || 'local'

      // Remote (lite) builds: skip the mode chooser, go straight to the
      // remote server form — the "local" option is not available.
      if (buildMode.value === 'remote') {
        if (cfg.forceChoose || !cfg.mode || cfg.mode === 'local') {
          status.value = 'connection-select'
          connView.value = 'remote-form'
        } else {
          connectionMode.value = cfg.mode
          if (await window.mateClawAPI.isBackendReady()) {
            handleBackendReady()
          }
        }
      } else if (cfg.forceChoose || !cfg.mode) {
        status.value = 'connection-select'
        connView.value = 'choose'
      } else {
        connectionMode.value = cfg.mode
        // The backend may have become ready before listeners attached.
        if (await window.mateClawAPI.isBackendReady()) {
          handleBackendReady()
        }
      }
    } catch (e) {
      console.error('Failed to load connection config:', e)
    }

    unsubUpdater = window.mateClawAPI.onUpdaterState((state: UpdaterState) => {
      updater.value = state
    })
    window.mateClawAPI.getUpdaterState().then((state) => {
      if (state) updater.value = state
    })
  }
})

onUnmounted(() => {
  unsubStatus?.()
  unsubCrashed?.()
  unsubUpdater?.()
})

function handleRestart() {
  status.value = 'restarting'
  errorMessage.value = ''
  window.mateClawAPI?.restartBackend()
}

function createParticles() {
  const container = document.getElementById('particles')
  if (!container) return
  for (let i = 0; i < 12; i++) {
    const p = document.createElement('div')
    p.className = 'particle'
    const size = Math.random() * 3 + 1.5
    p.style.width = size + 'px'
    p.style.height = size + 'px'
    p.style.left = Math.random() * 100 + '%'
    p.style.bottom = '-10px'
    p.style.animationDuration = (Math.random() * 6 + 5) + 's'
    p.style.animationDelay = (Math.random() * 8) + 's'
    container.appendChild(p)
  }
}
</script>

<template>
  <div :class="{ 'theme-dark': isDark, 'theme-light': !isDark }">
    <!-- Background layers -->
    <div class="bg-layer">
      <div class="orb orb-1"></div>
      <div class="orb orb-2"></div>
      <div class="orb orb-3"></div>
    </div>
    <div class="bg-noise"></div>
    <div class="bg-grid"></div>

    <!-- Particles -->
    <div class="particles" id="particles"></div>

    <!-- Main splash content -->
    <div class="splash">
      <!-- Logo -->
      <div class="logo-section fade-enter">
        <div class="logo-wrap">
          <div class="logo-glow"></div>
          <img class="logo-img" src="/logo/snsclaw_logo_s.png" alt="SnSclaw" />
        </div>
        <div class="brand-name">
          <!-- <span class="mate">SnS</span><span class="claw">claw</span> -->
           <span class="mate">智屿</span>
        </div>
        <div class="brand-tagline">AI Personal Assistant</div>
      </div>

      <!-- Connection Selection Card -->
      <div
        v-if="status === 'connection-select'"
        class="status-card fade-enter"
      >
        <!-- Mode choice -->
        <template v-if="connView === 'choose'">
          <div class="lang-title">选择连接方式 / Connection</div>
          <div class="lang-options">
            <button
              v-if="buildMode === 'local'"
              class="lang-card"
              @click="chooseLocal"
            >
              <span class="lang-flag">💻</span>
              <span class="lang-label">本地运行</span>
              <span class="lang-desc">在本机内嵌运行服务</span>
            </button>
            <button class="lang-card" @click="openRemoteForm">
              <span class="lang-flag">🌐</span>
              <span class="lang-label">连接远程</span>
              <span class="lang-desc">接入集中部署的服务器</span>
            </button>
          </div>
          <!-- Remote (lite) build notice -->
          <div v-if="buildMode === 'remote'" class="remote-build-notice">
            当前为轻量版客户端，仅支持连接远程服务器
          </div>
        </template>

        <!-- Remote server form -->
        <template v-else>
          <div class="lang-title">连接远程服务器</div>
          <div class="conn-form">
            <input
              class="conn-input"
              v-model.trim="remoteUrlInput"
              type="text"
              placeholder="https://server.example.com"
              spellcheck="false"
              autocapitalize="off"
              @keyup.enter="connectRemote()"
            />
            <div
              v-if="testResult"
              class="conn-result"
              :class="{ ok: testResult.ok, bad: !testResult.ok }"
            >
              {{ testResult.msg }}
            </div>
            <div v-if="recentServers.length" class="conn-recent">
              <div class="conn-recent-title">最近使用</div>
              <button
                v-for="s in recentServers"
                :key="s.url"
                class="conn-recent-item"
                @click="connectRemote(s.url)"
              >{{ s.url }}</button>
            </div>
            <div class="conn-actions">
              <button class="conn-btn ghost" @click="backToChoose">返回</button>
              <button
                class="conn-btn ghost"
                :disabled="testing || !remoteUrlInput"
                @click="testRemote"
              >{{ testing ? '测试中…' : '测试连接' }}</button>
              <button
                class="conn-btn primary"
                :disabled="!remoteUrlInput"
                @click="connectRemote()"
              >连接</button>
            </div>
          </div>
        </template>
      </div>

      <!-- Language Selection Card -->
      <div
        v-else-if="status === 'language-select'"
        class="status-card fade-enter"
      >
        <div class="lang-title">Choose Language / 选择语言</div>
        <div class="lang-options">
          <button class="lang-card" @click="selectLanguage('zh-CN')">
            <span class="lang-flag">🇨🇳</span>
            <span class="lang-label">中文</span>
            <span class="lang-desc">简体中文界面</span>
          </button>
          <button class="lang-card" @click="selectLanguage('en-US')">
            <span class="lang-flag">🇺🇸</span>
            <span class="lang-label">English</span>
            <span class="lang-desc">English interface</span>
          </button>
        </div>

        <!-- Steps (compact) -->
        <div class="steps" style="margin-top: 8px;">
          <div
            v-for="(step, i) in steps"
            :key="i"
            class="step"
            :class="stepClass(i)"
          >
            <div class="step-dot">
              <svg v-if="stepClass(i) === 'done'" width="10" height="10" viewBox="0 0 10 10">
                <path d="M2 5.2l2.2 2.3L8 3" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span v-else class="step-num">{{ Number(i) + 1 }}</span>
            </div>
            <span class="step-label">{{ step.label }}</span>
          </div>
        </div>
      </div>

      <!-- Status Card (starting / initializing / ready / error) -->
      <div
        v-else
        class="status-card fade-enter"
        :class="{
          'ready': status === 'ready',
          'error-state': status === 'crashed' || status === 'timeout'
        }"
      >
        <!-- Steps -->
        <div class="steps">
          <div
            v-for="(step, i) in steps"
            :key="i"
            class="step"
            :class="stepClass(i)"
          >
            <div class="step-dot">
              <!-- Done: checkmark -->
              <svg v-if="stepClass(i) === 'done'" width="10" height="10" viewBox="0 0 10 10">
                <path d="M2 5.2l2.2 2.3L8 3" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <!-- Step 0: gear/cog -->
              <svg v-else-if="i === 0" width="12" height="12" viewBox="0 0 16 16" fill="none">
                <path d="M8 5.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z" stroke="currentColor" stroke-width="1.2"/>
                <path d="M7 1.5h2l.3 1.6a5 5 0 0 1 1.3.7l1.5-.6.9 1.6-1.2 1a5 5 0 0 1 0 1.6l1.2 1-.9 1.6-1.5-.6a5 5 0 0 1-1.3.7L9 14.5H7l-.3-1.6a5 5 0 0 1-1.3-.7l-1.5.6-.9-1.6 1.2-1a5 5 0 0 1 0-1.6l-1.2-1 .9-1.6 1.5.6a5 5 0 0 1 1.3-.7L7 1.5z" stroke="currentColor" stroke-width="1.1" stroke-linejoin="round"/>
              </svg>
              <!-- Step 1: rocket -->
              <svg v-else-if="i === 1" width="12" height="12" viewBox="0 0 16 16" fill="none">
                <path d="M8 2C8 2 4.5 4 4 8c-.3 2.5.5 4 .5 4M8 2c0 0 3.5 2 4 6 .3 2.5-.5 4-.5 4M8 2v9" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>
                <circle cx="8" cy="7" r="1.3" stroke="currentColor" stroke-width="1" fill="none"/>
                <path d="M6 13l2 1.5L10 13" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <!-- Step 2: globe/language -->
              <svg v-else-if="i === 2" width="12" height="12" viewBox="0 0 16 16" fill="none">
                <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.2"/>
                <path d="M2 8h12M8 2c-2 2-2 4-2 6s0 4 2 6M8 2c2 2 2 4 2 6s0 4-2 6" stroke="currentColor" stroke-width="1" stroke-linecap="round"/>
              </svg>
              <!-- Step 3: shield-check / ready -->
              <svg v-else-if="i === 3" width="12" height="12" viewBox="0 0 16 16" fill="none">
                <path d="M8 1.5L3 4v4c0 3.3 2.2 5.5 5 6.5 2.8-1 5-3.2 5-6.5V4L8 1.5z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>
                <path d="M5.8 8l1.6 1.6L10.2 6" stroke="currentColor" stroke-width="1.2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <span class="step-label">{{ step.label }}</span>
          </div>
        </div>

        <!-- Progress bar -->
        <div class="progress-wrap">
          <div class="progress-bar" :style="{ width: progressWidth }"></div>
        </div>

        <!-- Status text -->
        <div class="status-row">
          <template v-if="status === 'starting' || status === 'restarting'">
            <div class="spinner"></div>
            <span class="status-text">{{ status === 'restarting' ? 'Restarting service...' : 'Starting backend service...' }}</span>
          </template>

          <template v-else-if="status === 'initializing'">
            <div class="spinner"></div>
            <span class="status-text">Initializing{{ selectedLanguage === 'zh-CN' ? ' (中文)' : ' (English)' }}...</span>
          </template>

          <template v-else-if="status === 'ready'">
            <div class="icon-check">
              <svg width="10" height="10" viewBox="0 0 10 10"><path d="M2 5.2l2.2 2.3L8 3" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </div>
            <span class="status-text success">Ready, entering application...</span>
          </template>

          <template v-else-if="status === 'timeout' || status === 'crashed'">
            <div class="icon-error">
              <svg width="10" height="10" viewBox="0 0 10 10"><path d="M3 3l4 4M7 3l-4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
            </div>
            <span class="status-text error">{{ status === 'timeout' ? 'Startup timeout' : 'Service error' }}</span>
          </template>
        </div>

        <!-- Error detail -->
        <div v-if="errorMessage && (status === 'crashed' || status === 'timeout')" class="error-detail">
          {{ errorMessage }}
        </div>

        <!-- Retry button -->
        <button
          v-if="status === 'crashed' || status === 'timeout'"
          class="retry-btn"
          @click="handleRestart"
        >
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
            <path d="M1.5 7a5.5 5.5 0 1 1 1.1 3.3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
            <path d="M1 4v3.5h3.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          Restart
        </button>
      </div>
    </div>

    <!-- Bottom bar -->
    <div class="bottom-bar">
      <div class="version-tag">
        <span class="version-dot"></span>
        SnSclaw Desktop v{{ appVersion }}
      </div>

      <!-- Update banner -->
      <div v-if="showUpdateBanner" class="update-banner">
        <template v-if="updater.status === 'available'">
          <span class="update-text">v{{ updater.version }} available</span>
          <button class="update-btn" @click="handleDownloadUpdate">Download</button>
        </template>
        <template v-else-if="updater.status === 'downloading'">
          <div class="update-progress-bar">
            <div class="update-progress-fill" :style="{ width: downloadPercent + '%' }"></div>
          </div>
          <span class="update-text muted">{{ downloadPercent }}%</span>
        </template>
        <template v-else-if="updater.status === 'downloaded'">
          <span class="update-text">v{{ updater.version }} ready</span>
          <button class="update-btn restart" @click="handleInstallUpdate">Restart</button>
        </template>
        <template v-else-if="updater.status === 'error'">
          <span class="update-text error-text">Update failed</span>
        </template>
      </div>

      <!-- Theme toggle -->
      <div class="theme-toggle">
        <button class="theme-btn" :class="{ active: isDark }" @click="isDark || toggleTheme()">
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none">
            <path d="M13.5 8.5a5.5 5.5 0 1 1-6-6 4.5 4.5 0 0 0 6 6z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>
          </svg>
        </button>
        <button class="theme-btn" :class="{ active: !isDark }" @click="isDark && toggleTheme()">
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none">
            <circle cx="8" cy="8" r="3" stroke="currentColor" stroke-width="1.2"/>
            <path d="M8 2v1.5M8 12.5V14M2 8h1.5M12.5 8H14M3.7 3.7l1 1M11.3 11.3l1 1M12.3 3.7l-1 1M4.7 11.3l-1 1" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ══════════════════════════════════════════════════
   Design Tokens — Dark Theme  (aligned to mateclaw-ui)
══════════════════════════════════════════════════ */
.theme-dark {
  --mc-primary:        #33c192;
  --mc-primary-light:  #5ed4ad;
  --mc-primary-hover:  #27b082;
  --mc-primary-bg:     rgba(51, 193, 146, 0.15);
  --mc-accent:         #5ed4ad;

  --bg-base:           #11201f;
  --bg-elevated:       #172b2a;
  --bg-card:           #172b2a;
  --bg-muted:          #142524;
  --mc-surface-overlay: rgba(23, 43, 42, 0.78);
  --mc-panel-raised:   rgba(23, 43, 42, 0.9);

  --bg-glow-1:         rgba(51, 193, 146, 0.18);
  --bg-glow-2:         rgba(94, 212, 173, 0.12);
  --bg-glow-3:         rgba(0, 136, 120, 0.08);

  --text-primary:      #e6f0ee;
  --text-secondary:    #a6beb9;
  --text-tertiary:     #6f8f89;
  --text-muted:        #4f6b66;
  --text-inverse:      #11201f;

  --border:            #223635;
  --border-light:      #1a2a29;
  --border-strong:     #2d4442;

  --success:           #33c192;
  --success-bg:        rgba(51, 193, 146, 0.12);
  --success-border:    rgba(51, 193, 146, 0.3);
  --error:             #f14c4c;
  --error-bg:          rgba(241, 76, 76, 0.15);
  --error-border:      rgba(241, 76, 76, 0.4);

  --card-shadow:       rgba(0, 0, 0, 0.28);
  --card-inset:        rgba(255, 255, 255, 0.04);

  --mc-shadow-soft:    0 14px 32px rgba(0, 0, 0, 0.22);
  --mc-glow:           radial-gradient(circle at top, rgba(51, 193, 146, 0.12), transparent 55%);

  color-scheme: dark;
}

/* ══════════════════════════════════════════════════
   Design Tokens — Light Theme (aligned to mateclaw-ui)
══════════════════════════════════════════════════ */
.theme-light {
  --mc-primary:        #008878;
  --mc-primary-light:  #d5f0e9;
  --mc-primary-hover:  #006e61;
  --mc-primary-bg:     #e8f6f2;
  --mc-accent:         #006e61;

  --bg-base:           #f6f7f8;
  --bg-elevated:       #ffffff;
  --bg-card:           #ffffff;
  --bg-muted:          #f3f4f6;
  --mc-surface-overlay: rgba(255, 255, 255, 0.72);
  --mc-panel-raised:   rgba(255, 255, 255, 0.86);

  --bg-glow-1:         rgba(0, 136, 120, 0.12);
  --bg-glow-2:         rgba(0, 110, 97, 0.08);
  --bg-glow-3:         rgba(213, 240, 233, 0.5);

  --text-primary:      #111827;
  --text-secondary:    #667085;
  --text-tertiary:     #7a8494;
  --text-muted:        #98a2b3;
  --text-inverse:      #ffffff;

  --border:            #e5e7eb;
  --border-light:      #eaecf0;
  --border-strong:     #e5e7eb;

  --success:           #12805c;
  --success-bg:        rgba(18, 128, 92, 0.12);
  --success-border:    rgba(18, 128, 92, 0.25);
  --error:             #c0392b;
  --error-bg:          #fee2e2;
  --error-border:      #fca5a5;

  --card-shadow:       rgba(17, 24, 39, 0.12);
  --card-inset:        rgba(255, 255, 255, 0.6);

  --mc-shadow-soft:    0 2px 10px rgba(17, 24, 39, 0.06);
  --mc-glow:           radial-gradient(circle at top, rgba(0, 136, 120, 0.18), transparent 55%);

  color-scheme: light;
}

* { box-sizing: border-box; margin: 0; padding: 0; }

/* ══════════════════════════════════════════════════
   Background — Layered Glow Orbs
══════════════════════════════════════════════════ */
.bg-layer {
  position: fixed; inset: 0; z-index: 0;
  pointer-events: none;
}
.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  animation: orb-drift 8s ease-in-out infinite;
  transition: background .4s;
}
.orb-1 {
  width: 500px; height: 500px;
  top: -120px; left: -80px;
  background: var(--bg-glow-1);
}
.orb-2 {
  width: 400px; height: 400px;
  bottom: -100px; right: -60px;
  background: var(--bg-glow-2);
  animation-delay: -3s;
}
.orb-3 {
  width: 300px; height: 300px;
  top: 50%; left: 50%;
  transform: translate(-50%, -50%);
  background: var(--bg-glow-3);
  animation-delay: -5s;
  animation-name: orb-drift-center;
}
@keyframes orb-drift {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33%       { transform: translate(30px, -20px) scale(1.05); }
  66%       { transform: translate(-20px, 15px) scale(0.95); }
}
@keyframes orb-drift-center {
  0%, 100% { transform: translate(-50%, -50%) scale(1); }
  50%       { transform: translate(-50%, -55%) scale(1.1); }
}

.bg-noise {
  position: fixed; inset: 0; z-index: 1;
  opacity: .03;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
  pointer-events: none;
}
.bg-grid {
  position: fixed; inset: 0; z-index: 1;
  background-image:
    linear-gradient(var(--border-light) 1px, transparent 1px),
    linear-gradient(90deg, var(--border-light) 1px, transparent 1px);
  background-size: 60px 60px;
  opacity: .4;
  pointer-events: none;
  mask-image: radial-gradient(ellipse 80% 80% at 50% 50%, black 30%, transparent 100%);
  -webkit-mask-image: radial-gradient(ellipse 80% 80% at 50% 50%, black 30%, transparent 100%);
  transition: opacity .4s;
}
.theme-light .bg-grid { opacity: .25; }

/* ══════════════════════════════════════════════════
   Particles
══════════════════════════════════════════════════ */
.particles {
  position: fixed; inset: 0; z-index: 2;
  pointer-events: none;
  overflow: hidden;
}
:deep(.particle) {
  position: absolute;
  border-radius: 50%;
  background: var(--mc-primary);
  opacity: 0;
  animation: particle-rise linear infinite;
}
@keyframes particle-rise {
  0%   { opacity: 0;   transform: translateY(0) scale(0); }
  10%  { opacity: .15; transform: translateY(-20px) scale(1); }
  90%  { opacity: .08; transform: translateY(-200px) scale(.5); }
  100% { opacity: 0;   transform: translateY(-240px) scale(0); }
}
.theme-light :deep(.particle) { opacity: 0; animation: particle-rise-light linear infinite; }
@keyframes particle-rise-light {
  0%   { opacity: 0;   transform: translateY(0) scale(0); }
  10%  { opacity: .1;  transform: translateY(-20px) scale(1); }
  90%  { opacity: .05; transform: translateY(-200px) scale(.5); }
  100% { opacity: 0;   transform: translateY(-240px) scale(0); }
}

/* ══════════════════════════════════════════════════
   Main Layout
══════════════════════════════════════════════════ */
.splash {
  position: relative; z-index: 10;
  width: 100vw; height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0;
  font-family: 'Inter', 'Avenir Next', 'SF Pro Display', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  background: var(--bg-base);
  color: var(--text-primary);
  overflow: hidden;
  transition: background .4s, color .4s;
}

/* ══════════════════════════════════════════════════
   Logo Section
══════════════════════════════════════════════════ */
.logo-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
  margin-bottom: 48px;
}
.logo-wrap {
  position: relative;
  width: 100px; height: 100px;
}
.logo-glow {
  position: absolute;
  top: 50%; left: 50%;
  transform: translate(-50%, -50%);
  width: 160px; height: 160px;
  border-radius: 50%;
  background: radial-gradient(circle, var(--mc-primary-bg) 0%, transparent 70%);
  animation: glow-pulse 3s ease-in-out infinite;
  pointer-events: none;
}
@keyframes glow-pulse {
  0%, 100% { opacity: .6; transform: translate(-50%, -50%) scale(1); }
  50%       { opacity: 1;  transform: translate(-50%, -50%) scale(1.15); }
}
.logo-img {
  width: 100px; height: 100px;
  object-fit: contain;
  position: relative;
  z-index: 1;
  animation: logo-float 4s ease-in-out infinite;
  filter: drop-shadow(0 12px 32px var(--mc-primary-bg));
}
@keyframes logo-float {
  0%, 100% { transform: translateY(0px); }
  25%       { transform: translateY(-6px); }
  75%       { transform: translateY(-3px); }
}

.brand-name {
  font-size: 32px;
  font-weight: 800;
  letter-spacing: -0.04em;
}
.brand-name .mate { color: var(--text-primary); transition: color .4s; }
.brand-name .claw { color: var(--mc-primary); }
.brand-tagline {
  font-size: 13px;
  color: var(--text-tertiary);
  letter-spacing: 0.02em;
  transition: color .4s;
}

/* ══════════════════════════════════════════════════
   Status Card — mateclaw-ui glass-card style
══════════════════════════════════════════════════ */
.status-card {
  position: relative;
  background: var(--mc-surface-overlay);
  backdrop-filter: blur(12px) saturate(1.1);
  -webkit-backdrop-filter: blur(12px) saturate(1.1);
  border: 1px solid var(--border);
  border-radius: 24px;
  padding: 28px 28px 24px;
  width: 420px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  box-shadow: var(--mc-shadow-soft);
  transition: all .4s ease;
}
.status-card::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: var(--mc-glow);
  opacity: 0.8;
  pointer-events: none;
}
.status-card > * { position: relative; z-index: 1; }

.theme-light .status-card {
  box-shadow:
    0 1px 0 var(--card-inset) inset,
    0 4px 24px var(--card-shadow),
    0 1px 3px rgba(0,0,0,.06);
}
.status-card.ready {
  border-color: var(--success-border);
}
.theme-dark .status-card.ready {
  box-shadow: var(--mc-shadow-soft), 0 0 40px var(--success-bg);
}
.status-card.error-state {
  border-color: var(--error-border);
}
.theme-dark .status-card.error-state {
  box-shadow: var(--mc-shadow-soft), 0 0 40px var(--error-bg);
}

/* ══════════════════════════════════════════════════
   Language / Connection Selection
══════════════════════════════════════════════════ */
.lang-title {
  font-size: 16px;
  font-weight: 800;
  letter-spacing: -0.02em;
  color: var(--text-primary);
  text-align: center;
  margin-bottom: 4px;
}
.lang-options {
  display: flex;
  gap: 12px;
  width: 100%;
}
.lang-card {
  -webkit-app-region: no-drag;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 20px 12px 18px;
  border-radius: 16px;
  border: 1px solid var(--border-light);
  background: var(--bg-muted);
  cursor: pointer;
  transition: all .2s ease;
  position: relative;
  overflow: hidden;
}
.lang-card::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(180deg, transparent 60%, var(--mc-primary-bg) 100%);
  opacity: 0;
  transition: opacity .25s ease;
  pointer-events: none;
}
.lang-card:hover {
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
  transform: translateY(-2px);
  box-shadow: var(--mc-shadow-soft);
}
.lang-card:hover::after { opacity: 0.6; }
.lang-card:active {
  transform: translateY(0) scale(.98);
}
.lang-flag {
  font-size: 28px;
  line-height: 1;
  position: relative;
  z-index: 1;
}
.lang-label {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
  position: relative;
  z-index: 1;
}
.lang-desc {
  font-size: 11px;
  color: var(--text-tertiary);
  position: relative;
  z-index: 1;
}
.remote-build-notice {
  font-size: 11px;
  color: var(--text-secondary);
  text-align: center;
  padding: 10px 14px;
  border-radius: 12px;
  background: var(--bg-muted);
  border: 1px solid var(--border-light);
  width: 100%;
}

/* ══════════════════════════════════════════════════
   Remote Connection Form
══════════════════════════════════════════════════ */
.conn-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
}
.conn-input {
  -webkit-app-region: no-drag;
  width: 100%;
  padding: 11px 14px;
  border-radius: 12px;
  border: 1px solid var(--border);
  background: var(--bg-elevated);
  color: var(--text-primary);
  font-size: 13px;
  font-family: inherit;
  outline: none;
  transition: all .2s ease;
}
.conn-input:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 4px var(--mc-primary-bg);
}
.conn-input::placeholder { color: var(--text-muted); }

.conn-result {
  font-size: 12px;
  padding: 8px 12px;
  border-radius: 10px;
  line-height: 1.4;
}
.conn-result.ok {
  color: var(--success);
  background: var(--success-bg);
  border: 1px solid var(--success-border);
}
.conn-result.bad {
  color: var(--error);
  background: var(--error-bg);
  border: 1px solid var(--error-border);
}

.conn-recent {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.conn-recent-title {
  font-size: 10px;
  color: var(--text-tertiary);
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.conn-recent-item {
  -webkit-app-region: no-drag;
  text-align: left;
  padding: 9px 12px;
  border-radius: 10px;
  border: 1px solid var(--border-light);
  background: var(--bg-elevated);
  color: var(--text-secondary);
  font-size: 12px;
  cursor: pointer;
  transition: all .15s ease;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.conn-recent-item:hover {
  border-color: var(--mc-primary);
  color: var(--text-primary);
  background: var(--mc-primary-bg);
}

.conn-actions {
  display: flex;
  gap: 8px;
  margin-top: 2px;
}
.conn-btn {
  -webkit-app-region: no-drag;
  flex: 1;
  padding: 10px 0;
  border-radius: 12px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all .2s ease;
  border: 1px solid transparent;
  font-family: inherit;
}
.conn-btn:disabled {
  opacity: .45;
  cursor: not-allowed;
}
.conn-btn.ghost {
  border-color: var(--border);
  background: transparent;
  color: var(--text-secondary);
}
.conn-btn.ghost:hover:not(:disabled) {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
  background: var(--mc-primary-bg);
}
.conn-btn.primary {
  border-color: var(--mc-primary);
  background: var(--mc-primary);
  color: var(--text-inverse);
  box-shadow: 0 2px 8px var(--mc-primary-bg);
}
.conn-btn.primary:hover:not(:disabled) {
  background: var(--mc-primary-hover);
  border-color: var(--mc-primary-hover);
}
.conn-btn.primary:active:not(:disabled) {
  transform: scale(.98);
}

/* ── Progress Steps ── */
.steps {
  display: flex;
  align-items: center;
  width: 100%;
  margin-bottom: 4px;
}
.step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  flex: 1;
  position: relative;
}
.step:not(:last-child)::after {
  content: '';
  position: absolute;
  top: 11px;
  left: calc(50% + 12px);
  right: calc(-50% + 12px);
  height: 1.5px;
  background: var(--border-light);
  transition: background .4s;
}
.step.done:not(:last-child)::after { background: var(--mc-primary); }
.step.active:not(:last-child)::after { background: linear-gradient(90deg, var(--mc-primary), var(--border-light)); }

.step-dot {
  width: 24px; height: 24px;
  border-radius: 50%;
  border: 1.5px solid var(--border);
  background: var(--bg-elevated);
  display: flex; align-items: center; justify-content: center;
  color: var(--text-muted);
  transition: all .3s ease;
  position: relative; z-index: 1;
}
.step-num {
  font-size: 10px;
  font-weight: 700;
}
.step.done .step-dot {
  background: var(--mc-primary);
  border-color: var(--mc-primary);
  color: var(--text-inverse);
  box-shadow: 0 0 0 4px var(--mc-primary-bg);
}
.step.active .step-dot {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
  box-shadow: 0 0 0 4px var(--mc-primary-bg);
  animation: step-pulse 1.5s ease-in-out infinite;
}
@keyframes step-pulse {
  0%, 100% { box-shadow: 0 0 0 4px var(--mc-primary-bg); }
  50%       { box-shadow: 0 0 0 8px color-mix(in srgb, var(--mc-primary-bg) 50%, transparent); }
}
.step-label {
  font-size: 10px;
  color: var(--text-tertiary);
  white-space: nowrap;
  transition: color .3s ease;
  font-weight: 500;
}
.step.done .step-label,
.step.active .step-label {
  color: var(--text-secondary);
}

/* ── Progress Bar ── */
.progress-wrap {
  width: 100%;
  height: 4px;
  background: var(--border-light);
  border-radius: 100px;
  overflow: hidden;
}
.progress-bar {
  height: 100%;
  border-radius: 100px;
  background: linear-gradient(90deg, var(--mc-primary-hover), var(--mc-primary), var(--mc-primary-light));
  background-size: 200% 100%;
  transition: width .6s cubic-bezier(.4,0,.2,1);
  animation: shimmer 2s linear infinite;
}
@keyframes shimmer {
  0%   { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

/* ── Status Row ── */
.status-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 28px;
}
.spinner {
  width: 18px; height: 18px;
  border: 2px solid var(--border-strong);
  border-top-color: var(--mc-primary);
  border-radius: 50%;
  animation: spin .7s linear infinite;
  flex-shrink: 0;
}
@keyframes spin { to { transform: rotate(360deg); } }

.icon-check {
  width: 22px; height: 22px;
  border-radius: 50%;
  background: var(--success-bg);
  border: 1.5px solid var(--success-border);
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  color: var(--success);
}
.icon-error {
  width: 22px; height: 22px;
  border-radius: 50%;
  background: var(--error-bg);
  border: 1.5px solid var(--error-border);
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  color: var(--error);
}

.status-text {
  font-size: 13px;
  color: var(--text-secondary);
  transition: color .3s ease;
}
.status-text.success { color: var(--success); font-weight: 600; }
.status-text.error   { color: var(--error); font-weight: 600; }

/* ── Error Detail ── */
.error-detail {
  width: 100%;
  background: var(--error-bg);
  border: 1px solid var(--error-border);
  border-radius: 12px;
  padding: 10px 12px;
  font-size: 11px;
  color: var(--error);
  font-family: 'JetBrains Mono', 'SF Mono', Consolas, monospace;
  line-height: 1.5;
  word-break: break-all;
}

/* ── Retry Button ── */
.retry-btn {
  -webkit-app-region: no-drag;
  padding: 8px 20px;
  border-radius: 10px;
  border: 1.5px solid var(--success-border);
  background: var(--success-bg);
  color: var(--success);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all .2s ease;
  display: flex;
  align-items: center;
  gap: 6px;
  font-family: inherit;
}
.retry-btn:hover {
  filter: brightness(1.05);
  box-shadow: 0 0 0 3px var(--success-bg);
}
.retry-btn:active { transform: scale(.97); }

/* ══════════════════════════════════════════════════
   Bottom Bar
══════════════════════════════════════════════════ */
.bottom-bar {
  position: fixed;
  bottom: 0; left: 0; right: 0;
  padding: 16px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  z-index: 20;
}
.version-tag {
  font-size: 11px;
  color: var(--text-tertiary);
  display: flex;
  align-items: center;
  gap: 6px;
  transition: color .4s;
  font-weight: 500;
}
.version-dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  background: var(--mc-primary);
  opacity: .8;
  box-shadow: 0 0 0 3px var(--mc-primary-bg);
}

/* ── Theme Toggle — exactly matches MainLayout ── */
.theme-toggle {
  display: flex;
  align-items: center;
  gap: 3px;
  background: var(--bg-muted);
  border-radius: 999px;
  padding: 2px;
  border: 1px solid var(--border-light);
  transition: background .4s, border-color .4s;
}
.theme-btn {
  -webkit-app-region: no-drag;
  width: 30px;
  height: 30px;
  padding: 0;
  border-radius: 999px;
  border: none;
  background: transparent;
  color: var(--text-tertiary);
  cursor: pointer;
  transition: all 0.15s ease;
  display: flex;
  align-items: center;
  justify-content: center;
  line-height: 0;
}
.theme-btn.active {
  background: var(--bg-elevated);
  color: var(--text-primary);
  box-shadow: var(--mc-shadow-soft);
}
.theme-btn:hover:not(.active) { color: var(--text-secondary); }

/* ══════════════════════════════════════════════════
   Update Banner
══════════════════════════════════════════════════ */
.update-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px;
  background: var(--bg-muted);
  border: 1px solid var(--border-light);
  border-radius: 12px;
  font-size: 11px;
  animation: fade-in .3s ease forwards;
}
.update-text {
  color: var(--text-secondary);
  white-space: nowrap;
  font-weight: 500;
}
.update-text.muted {
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
  min-width: 32px;
  text-align: right;
}
.update-text.error-text {
  color: var(--error);
}
.update-btn {
  -webkit-app-region: no-drag;
  padding: 4px 12px;
  border-radius: 8px;
  border: 1px solid var(--mc-primary);
  background: var(--mc-primary);
  color: var(--text-inverse);
  cursor: pointer;
  font-size: 11px;
  font-weight: 600;
  transition: all .2s ease;
  white-space: nowrap;
  font-family: inherit;
}
.update-btn:hover {
  background: var(--mc-primary-hover);
  border-color: var(--mc-primary-hover);
}
.update-btn.restart {
  border-color: var(--success-border);
  background: var(--success-bg);
  color: var(--success);
}
.update-btn.restart:hover {
  filter: brightness(1.05);
}
.update-progress-bar {
  width: 80px;
  height: 4px;
  background: var(--border-light);
  border-radius: 2px;
  overflow: hidden;
}
.update-progress-fill {
  height: 100%;
  background: var(--mc-primary);
  border-radius: 2px;
  transition: width .3s ease;
}

/* ══════════════════════════════════════════════════
   Animations
══════════════════════════════════════════════════ */
.fade-enter {
  animation: fade-in .5s ease forwards;
}
@keyframes fade-in {
  from { opacity: 0; transform: translateY(10px); }
  to   { opacity: 1; transform: translateY(0); }
}
</style>
