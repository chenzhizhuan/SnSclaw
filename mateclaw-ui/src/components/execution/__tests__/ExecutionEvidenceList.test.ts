import { createApp, h, nextTick, reactive } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ExecutionEvidenceList from '../ExecutionEvidenceList.vue'
import { executionEvidenceApi } from '@/api/executionEvidence'
import en from '@/i18n/locales/en-US'
vi.mock('@/api/executionEvidence', () => ({ executionEvidenceApi: { list: vi.fn() } }))
const apps: ReturnType<typeof createApp>[] = []
const row = (id: string) => ({ id, attemptId: '9223372036854775801', conversationId: 'one', toolName: 'execCommand', state: 'SUCCEEDED', effectOutcome: 'CONFIRMED', kind: 'COMMAND_EXIT', result: 'OBSERVED', sourceLevel: 'RUNTIME', validity: 'UNKNOWN', summary: 'Exit code: 0', observedAt: '2026-09-07T12:00:00Z', expiresAt: null, artifactRef: null, artifactDigest: null })
async function flush() { await Promise.resolve(); await Promise.resolve(); await nextTick() }
function mount() {
 const props = reactive({ conversationId: 'one', goalId: '9223372036854775802' })
 const host = document.createElement('div'); document.body.append(host)
 const app = createApp({ render: () => h(ExecutionEvidenceList, props) })
 app.use(createI18n({ legacy: false, locale: 'en', messages: { en } })); app.mount(host); apps.push(app)
 return { host, props }
}
afterEach(() => { apps.splice(0).forEach(a => a.unmount()); document.body.innerHTML = ''; vi.resetAllMocks() })
describe('execution evidence', () => {
 it('loads lazily, labels command exits as observations, and deduplicates exact string IDs across pages', async () => {
  vi.mocked(executionEvidenceApi.list).mockResolvedValueOnce({ data: { items: [row('9223372036854775803')], nextCursor: 'next' } } as never).mockResolvedValueOnce({ data: { items: [row('9223372036854775803'), row('9223372036854775804')], nextCursor: null } } as never)
  const { host } = mount(); expect(executionEvidenceApi.list).not.toHaveBeenCalled()
  host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  expect(host.textContent).toContain('Observed'); expect(host.textContent).toContain('does not verify'); expect(host.textContent).toContain('Exit code: 0')
  expect(host.textContent).not.toContain('Check passed')
  host.querySelector<HTMLButtonElement>('[data-evidence-more]')!.click(); await flush()
  expect(host.querySelectorAll('[data-evidence-item]')).toHaveLength(2)
  expect(host.textContent).toContain('9223372036854775804')
  expect(executionEvidenceApi.list).toHaveBeenLastCalledWith({ conversationId: 'one', goalId: '9223372036854775802', teamTaskId: undefined, cursor: 'next', limit: 20 })
 })
 it('shows permission failure separately from empty results', async () => {
  vi.mocked(executionEvidenceApi.list).mockRejectedValue({ code: 403 })
  const { host } = mount(); host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  expect(host.querySelector('[role="alert"]')?.textContent).toContain('permission')
  expect(host.textContent).not.toContain('No execution evidence')
 })
 it('ignores a stale response after the selected conversation changes', async () => {
  let resolve!: (value: unknown) => void
  vi.mocked(executionEvidenceApi.list).mockImplementationOnce(() => new Promise(r => { resolve = r }) as never).mockResolvedValueOnce({ data: { items: [row('new')], nextCursor: null } } as never)
  const { host, props } = mount(); host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  props.conversationId = 'two'; await flush()
  resolve({ data: { items: [row('old')], nextCursor: null } }); await flush()
  expect(host.querySelector('[data-evidence-item]')?.getAttribute('data-evidence-item')).toBe('new')
 })
})
