import { ref } from 'vue'

/**
 * 全局唯一的 TTS 播放通道。
 *
 * 朗读是"独占"语义：同一时刻只应有一个声音。此前每个 MessageBubble 各自持有
 * `ttsAudio`、自动朗读又用局部变量 new Audio()，彼此不知情，于是点第二条时两段
 * 语音叠着响、切会话或关面板后旧音频还在念。
 *
 * 这里把播放句柄收到模块作用域（跨组件实例共享），任何新播放先停掉在播的那个。
 * 语音模式（TalkMode）走 Web Audio API 解码，是另一条通道，通过 registerStopHook
 * 挂进来一起被停。
 */

/** 当前正在播放的音频；null 表示静默。 */
let current: HTMLAudioElement | null = null
/** 当前音频的 ObjectURL，停止/结束时释放，避免 blob 泄漏。 */
let currentUrl: string | null = null
/** 归属标识（消息 id / 'auto' 等），用于让发起方判断"在播的是不是我"。 */
const currentOwner = ref<string | null>(null)

/**
 * 非 HTMLAudioElement 的播放通道（如 TalkMode 的 AudioBufferSourceNode）
 * 注册停止回调，使 stopAll() 能一并静音。
 */
const stopHooks = new Set<() => void>()

export function registerTtsStopHook(hook: () => void): () => void {
  stopHooks.add(hook)
  return () => stopHooks.delete(hook)
}

/** 释放当前 blob URL。 */
function releaseUrl() {
  if (currentUrl) {
    URL.revokeObjectURL(currentUrl)
    currentUrl = null
  }
}

/**
 * 停止一切正在播放的 TTS（含其他通道注册的 hook）。
 * 幂等 —— 没有在播时调用是安全的空操作。
 */
export function stopAllTts(): void {
  if (current) {
    current.pause()
    current.onended = null
    current.onerror = null
    current = null
  }
  releaseUrl()
  currentOwner.value = null
  // hook 自己负责幂等；一个抛错不应阻断其余 hook
  stopHooks.forEach(hook => {
    try { hook() } catch { /* 单个通道停止失败不影响其他通道 */ }
  })
}

/** playTtsBlob 的结果：区分"被接替/被停止"与"真的播不出来"。 */
export type TtsPlayResult = 'played' | 'superseded' | 'failed'

/**
 * 播放一段音频 blob，先停掉在播的任何声音。
 *
 * @param blob   音频数据
 * @param owner  归属标识，供 isPlayingBy() 判断
 * @param onEnd  播放结束或失败时回调（用于调用方复位 UI 状态）
 * @returns 'played' 已开始播放；'superseded' 播放期间被接替或被停止（正常，
 *          调用方不应报错）；'failed' 解码/播放失败（调用方应提示）
 */
export async function playTtsBlob(
  blob: Blob,
  owner: string,
  onEnd?: () => void
): Promise<TtsPlayResult> {
  stopAllTts()

  const url = URL.createObjectURL(blob)
  const audio = new Audio(url)
  current = audio
  currentUrl = url
  currentOwner.value = owner

  // finish 可能被 onended / onerror / catch 多路触发，用它保证只跑一次
  let finished = false
  const finish = () => {
    if (finished) return
    finished = true
    // 只有仍是自己在播时才清理全局状态：晚到的 onended 不能踩掉后来者
    if (current === audio) {
      current = null
      releaseUrl()
      currentOwner.value = null
    } else {
      URL.revokeObjectURL(url)
    }
    onEnd?.()
  }

  audio.onended = finish
  audio.onerror = finish

  try {
    await audio.play()
    return 'played'
  } catch {
    // stopAllTts() 里的 pause() 会让尚未 resolve 的 play() promise 以
    // AbortError reject —— 那是"被接替/被停止"，不是失败。判据是本次的 audio
    // 已不再是当前播放者（被抢占则 current 指向新 audio，被停止则为 null）。
    // 不作区分会让一次正常的接替弹出"语音合成失败"。
    const superseded = current !== audio
    finish()
    return superseded ? 'superseded' : 'failed'
  }
}

/** 指定归属方是否正在播放。 */
export function isPlayingBy(owner: string): boolean {
  return currentOwner.value === owner
}

/** 当前归属方（响应式），供组件观察全局播放状态。 */
export function useTtsOwner() {
  return currentOwner
}
