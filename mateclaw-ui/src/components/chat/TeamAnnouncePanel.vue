<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowDown, UserFilled } from '@element-plus/icons-vue'
import type { Message } from '@/types'

const { t } = useI18n()

const props = defineProps<{
  message: Message
}>()

// Collapsed by default: the settlement note is orchestration bookkeeping, not
// conversation content. Users who want the per-task detail expand on demand.
const expanded = ref(false)

const meta = computed<Record<string, any>>(() => {
  const raw = (props.message as any).metadata
  if (!raw) return {}
  if (typeof raw === 'object') return raw
  try { return JSON.parse(raw) || {} } catch { return {} }
})

const label = computed(() => {
  const count = Number(meta.value.taskCount) || 0
  return count > 0
    ? t('chat.teamAnnounce', { count })
    : t('chat.teamAnnounceGeneric')
})

// Show the per-task result blocks but trim the trailing model-facing
// orchestration instructions ("Review these results ... team_tasks(...)") —
// they stay in the stored content for the LLM turn, only display drops them.
const displayText = computed(() => {
  const content = props.message.content || ''
  const cut = content.indexOf('\n\nReview these results')
  return (cut > 0 ? content.slice(0, cut) : content).trim()
})
</script>

<template>
  <div class="team-announce">
    <button class="team-announce__header" type="button" @click="expanded = !expanded">
      <span class="team-announce__icon"><el-icon :size="13"><UserFilled /></el-icon></span>
      <span class="team-announce__label">{{ label }}</span>
      <el-icon class="team-announce__arrow" :class="{ 'is-open': expanded }" :size="12"><ArrowDown /></el-icon>
    </button>
    <Transition name="team-announce-slide">
      <div v-if="expanded" class="team-announce__body">{{ displayText }}</div>
    </Transition>
  </div>
</template>

<style scoped>
.team-announce {
  margin: 8px 0;
  border-radius: 8px;
  border: 1px dashed var(--mc-border, rgba(0, 0, 0, 0.12));
  background: transparent;
  overflow: hidden;
}
.team-announce__header {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 12px;
  font-size: 12px;
  color: var(--mc-text-tertiary);
  background: none;
  border: none;
  cursor: pointer;
  user-select: none;
  text-align: left;
}
.team-announce__header:hover {
  color: var(--mc-text-secondary);
}
.team-announce__icon {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.team-announce__label {
  flex: 1;
  font-weight: 500;
}
.team-announce__arrow {
  transition: transform 0.2s;
}
.team-announce__arrow.is-open {
  transform: rotate(180deg);
}
.team-announce__body {
  margin: 0 12px 8px;
  padding: 6px 0 6px 10px;
  border-left: 2px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  font-size: 12px;
  line-height: 1.7;
  color: var(--mc-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.team-announce-slide-enter-active, .team-announce-slide-leave-active {
  transition: all 0.2s ease;
}
.team-announce-slide-enter-from, .team-announce-slide-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
