<script setup lang="ts">
import { computed } from 'vue'
import { resolveToolResult } from './registry'
import ToolResultBlock from './ToolResultBlock.vue'
const props = defineProps<{ structuredContent: unknown }>()
const blocks = computed(() => resolveToolResult(props.structuredContent))
</script>
<template>
  <div class="tool-result-view">
    <section v-for="(block, index) in blocks" :key="index" class="tool-result-block">
      <h4 v-if="block.title">{{ block.title }}</h4>
      <ToolResultBlock :block="block" />
    </section>
  </div>
</template>
<style scoped>
.tool-result-view { padding: 8px 10px 12px 22px; min-width: 0; }
.tool-result-block + .tool-result-block { margin-top: 12px; }
h4 { margin: 0 0 8px; font-size: 14px; }
</style>
