<script setup lang="ts">
import { onErrorCaptured, ref, watch } from 'vue'
import type { ResolvedToolBlock } from './registry'
const props = defineProps<{ block: ResolvedToolBlock }>()
const failed = ref(false)
onErrorCaptured(() => { failed.value = true; return false })
watch(() => props.block, () => { failed.value = false })
</script>
<template>
  <component v-if="block.component && !failed" :is="block.component" :data="block.data" />
  <pre v-else>{{ block.fallback }}</pre>
</template>
<style scoped>pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 350px; overflow: auto; font-size: 12px; }</style>
