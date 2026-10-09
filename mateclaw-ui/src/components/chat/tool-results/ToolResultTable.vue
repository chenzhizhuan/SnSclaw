<script setup lang="ts">
defineProps<{ data: { columns: { key: string; label: string }[]; rows: Record<string, unknown>[] } }>()
const cellText = (value: unknown) => value == null ? '' : typeof value === 'object' ? JSON.stringify(value) : String(value)
</script>
<template>
  <div class="tool-result-table"><table>
    <thead><tr><th v-for="column in data.columns" :key="column.key" scope="col">{{ column.label }}</th></tr></thead>
    <tbody><tr v-for="(row, i) in data.rows" :key="i"><td v-for="column in data.columns" :key="column.key">{{ cellText(Object.hasOwn(row, column.key) ? row[column.key] : null) }}</td></tr></tbody>
  </table></div>
</template>
<style scoped>
.tool-result-table { overflow: auto; max-height: 420px; }
table { border-collapse: collapse; width: 100%; font-size: 13px; }
th, td { text-align: left; padding: 8px 12px; border: 1px solid var(--mc-border-light); white-space: pre-wrap; overflow-wrap: anywhere; }
th { background: var(--mc-bg-muted); }
</style>
