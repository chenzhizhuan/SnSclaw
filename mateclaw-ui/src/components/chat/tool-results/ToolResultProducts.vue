<script setup lang="ts">
import { computed } from 'vue'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'
import { safeHttpUrl } from './safety'
const props = defineProps<{ data: Record<string, unknown>[] }>()
const { renderMarkdown } = useMarkdownRenderer()
const html = computed(() => {
  const products = props.data.map(product => ({ ...product, url: safeHttpUrl(product.url), imageUrl: safeHttpUrl(product.imageUrl) }))
  return renderMarkdown('```product-cards\n' + JSON.stringify(products) + '\n```')
})
</script>
<template><div class="tool-result-products markdown-body" v-html="html" /></template>
