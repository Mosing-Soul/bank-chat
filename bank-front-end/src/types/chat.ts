export type ChatRole = 'user' | 'assistant';

export interface Citation {
  source: string;
  title?: string;
}

export interface ChatConfirmation {
  operationId?: string;
  customerName?: string;
  content?: string;
  status?: string;
  mock?: boolean;
}

export interface ChatMessage {
  id: string;
  role: ChatRole;
  content: string;
  sources?: string[];
  citations?: Citation[];
  data?: Record<string, unknown>;
  requiresConfirmation?: boolean;
  confirmation?: ChatConfirmation | null;
  createdAt: string;
}

export interface ChatRequest {
  message: string;
  question?: string;
  sessionId: string;
  requestedSkill?: string;
  forceSkill?: boolean;
}

export interface ChatResponse {
  traceId?: string;
  sessionId?: string;
  intent?: string;
  confidence?: number;
  answer?: string;
  data?: Record<string, unknown>;
  citations?: Citation[];
  sources?: string[];
  requiresConfirmation?: boolean;
  confirmation?: ChatConfirmation | null;
  error?: {
    code: string;
    message: string;
  } | null;
}
