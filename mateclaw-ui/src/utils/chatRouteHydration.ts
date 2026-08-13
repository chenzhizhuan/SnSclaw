type IdLike = string | number

interface RouteHydrationAgent {
  id: IdLike
}

interface RouteHydrationConversation {
  conversationId: string
}

export function resolveRouteHydrationQuery(options: {
  routeAgentId?: string
  routeConversationId?: string
  agents: RouteHydrationAgent[]
  conversations: RouteHydrationConversation[]
}): { agentId: string; conversationId: string } {
  let agentId = options.routeAgentId || ''
  const conversationId = options.routeConversationId || ''

  if (agentId && options.agents.length > 0 && !options.agents.some(a => String(a.id) === agentId)) {
    agentId = ''
  }

  return { agentId, conversationId }
}

export function resolveConversationAgentSelection(options: {
  routeAgentId?: string
  conversationAgentId?: IdLike | null
  currentAgentId?: IdLike | null
}): string {
  if (options.routeAgentId) return options.routeAgentId
  if (options.conversationAgentId != null) return String(options.conversationAgentId)
  if (options.currentAgentId != null) return String(options.currentAgentId)
  return ''
}
