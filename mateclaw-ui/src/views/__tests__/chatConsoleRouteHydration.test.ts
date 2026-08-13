// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { resolveConversationAgentSelection, resolveRouteHydrationQuery } from '@/utils/chatRouteHydration'

const agents = [{ id: 'agent-visible' }]
const conversations = [{ conversationId: 'conv-listed' }]

describe('resolveRouteHydrationQuery', () => {
  it('keeps a deep-linked child conversation even when it is not in the sidebar list', () => {
    const result = resolveRouteHydrationQuery({
      routeAgentId: 'agent-deleted-or-hidden',
      routeConversationId: 'team-task-finished',
      agents,
      conversations,
    })

    expect(result).toEqual({
      agentId: '',
      conversationId: 'team-task-finished',
    })
  })

  it('keeps valid route agent and conversation ids unchanged', () => {
    const result = resolveRouteHydrationQuery({
      routeAgentId: 'agent-visible',
      routeConversationId: 'conv-listed',
      agents,
      conversations,
    })

    expect(result).toEqual({
      agentId: 'agent-visible',
      conversationId: 'conv-listed',
    })
  })
})

describe('resolveConversationAgentSelection', () => {
  it('keeps a valid route agent when opening an existing conversation with stale metadata', () => {
    const selected = resolveConversationAgentSelection({
      routeAgentId: '20798621241343139868',
      conversationAgentId: '2079862124313986',
      currentAgentId: '',
    })

    expect(selected).toBe('20798621241343139868')
  })

  it('uses the conversation agent when no route agent is provided', () => {
    const selected = resolveConversationAgentSelection({
      routeAgentId: '',
      conversationAgentId: '2079862124313986',
      currentAgentId: 'fallback',
    })

    expect(selected).toBe('2079862124313986')
  })
})
