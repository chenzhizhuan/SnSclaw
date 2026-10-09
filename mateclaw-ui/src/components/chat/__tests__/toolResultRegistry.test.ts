import { describe, expect, it } from 'vitest'
import { defineComponent } from 'vue'
import { resolveToolResult, registerToolResultRenderer, safeHttpUrl, safeChartOption } from '../tool-results/registry'
const payload = (type: string, data: unknown) => ({ mateclawUi: { version: 1, blocks: [{ type, data }] } })
describe('tool result contract', () => {
  it('recognizes table data and trusted registered components', () => {
    expect(resolveToolResult(payload('table', { columns: [{ key: 'name', label: 'Name' }], rows: [{ name: 'A' }] }))[0].component).toBeTruthy()
    const component = defineComponent({ template: '<span />' })
    registerToolResultRenderer('custom-test', { component, validate: data => data === 42 })
    expect(resolveToolResult(payload('custom-test', 42))[0].component).toBe(component)
    expect(resolveToolResult(payload('custom-test', 41))[0].fallback).toContain('41')
  })
  it('falls back for unknown versions, types, malformed and oversized payloads', () => {
    for (const input of [{ mateclawUi: { version: 2, blocks: [] } }, payload('unknown', '<script>bad</script>'), payload('table', {}), payload('table', { columns: [], rows: Array(201).fill({}) }), { mateclawUi: { version: 1, blocks: Array(9).fill({ type: 'unknown' }) } }, payload('unknown', '汉'.repeat(40000))]) {
      const blocks = resolveToolResult(input)
      expect(blocks[0].fallback).toBeTruthy()
      expect(blocks.every(b => !b.component)).toBe(true)
    }
  })
  it('allows only absolute http(s) product URLs', () => {
    for (const url of ['javascript:alert(1)', 'data:image/svg+xml,x', '//evil.test', 'https:\n//evil.test', 'https://user:pass@evil.test']) expect(safeHttpUrl(url)).toBeUndefined()
    expect(safeHttpUrl('https://shop.test/a')).toBe('https://shop.test/a')
  })
  it('removes chart HTML, links, graphics, image symbols and formatter features recursively', () => {
    const option = safeChartOption({ title: { text: '<img src=x>', link: 'javascript:alert(1)' }, tooltip: { renderMode: 'html', formatter: '<img onerror=alert(1)>', extraCssText: 'bad' }, graphic: { type: 'image' }, visualMap: { inRange: { symbol: ['image://https://bad.test/x'] } }, series: [{ type: 'bar', symbol: 'image://https://bad.test/x', data: [1], tooltip: { formatter: '<script>' } }] })
    expect(option.tooltip).toEqual({ renderMode: 'richText', confine: true })
    expect(JSON.stringify(option)).not.toMatch(/javascript:|image:\/\/|onerror|extraCssText|formatter|graphic/)
    expect(option.title.text).toBe('<img src=x>')
  })
})
