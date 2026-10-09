const record = (value: unknown): value is Record<string, any> => !!value && typeof value === 'object' && !Array.isArray(value)

export function safeHttpUrl(value: unknown): string | undefined {
  if (typeof value !== 'string' || !/^https?:\/\//i.test(value) || /[\s\u0000-\u001f\u007f]/.test(value)) return
  try {
    const url = new URL(value)
    if (!url.username && !url.password && url.hostname) return value
  } catch { /* Invalid URLs are rendered without navigation or media. */ }
}

const CHART_KEYS = new Set(['title', 'legend', 'xAxis', 'yAxis', 'series', 'grid', 'color', 'dataset', 'radar', 'polar', 'angleAxis', 'radiusAxis', 'visualMap', 'tooltip'])
const UNSAFE_CHART_KEYS = new Set(['__proto__', 'constructor', 'prototype', 'formatter', 'valueFormatter', 'renderItem', 'link', 'sublink', 'target', 'sublinkTarget', 'image', 'graphic', 'rich', 'extraCssText', 'className', 'appendTo', 'appendToBody', 'renderMode', 'backgroundColor', 'borderColor'])
/** Use canvas text tooltips and remove all executable, HTML, navigation and image hooks. */
export function safeChartOption(input: Record<string, any>): Record<string, any> {
  const clean = (value: any): any => {
    if (Array.isArray(value)) return value.map(clean)
    if (record(value)) {
      const result: Record<string, any> = {}
      for (const [key, child] of Object.entries(value)) {
        if (UNSAFE_CHART_KEYS.has(key) || typeof child === 'function') continue
        if (key === 'tooltip') { result[key] = { renderMode: 'richText', confine: true }; continue }
        if (typeof child === 'string' && (child.includes('image://') || child.trimStart().startsWith('function'))) continue
        result[key] = clean(child)
      }
      return result
    }
    if (typeof value === 'string' && /image:\/\//i.test(value)) return ''
    return value
  }
  const option = clean(Object.fromEntries(Object.entries(input).filter(([key]) => CHART_KEYS.has(key))))
  option.tooltip = { renderMode: 'richText', confine: true }
  return option
}

