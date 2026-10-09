<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useThemeStore } from '@/stores/useThemeStore'
import { safeChartOption } from './safety'
const props = defineProps<{ data: Record<string, unknown> }>()
const theme = useThemeStore()
const host = ref<HTMLElement | null>(null)
const error = ref(false)
let chart: import('echarts').ECharts | undefined
let observer: ResizeObserver | undefined
let generation = 0
async function render() {
  const current = ++generation
  chart?.dispose()
  chart = undefined
  error.value = false
  try {
    const echarts = await import('echarts')
    if (current !== generation || !host.value) return
    chart = echarts.init(host.value, theme.isDark ? 'dark' : undefined, { renderer: 'canvas' })
    chart.setOption(safeChartOption(props.data))
  } catch {
    chart?.dispose()
    chart = undefined
    error.value = true
  }
}
onMounted(() => {
  observer = new ResizeObserver(() => chart?.resize())
  if (host.value) observer.observe(host.value)
  void render()
})
watch(() => [props.data, theme.isDark], () => { void render() }, { deep: true })
onBeforeUnmount(() => { generation++; observer?.disconnect(); chart?.dispose() })
</script>
<template>
  <div class="tool-result-chart" ref="host" role="img" aria-label="Tool result chart" />
  <pre v-if="error">{{ JSON.stringify(data, null, 2) }}</pre>
</template>
<style scoped>.tool-result-chart { width: 100%; height: 350px; }</style>
