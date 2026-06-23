export type ChatRole = 'user' | 'assistant';

export interface ChatMessage {
  id: string;
  role: ChatRole;
  content: string;
  sources?: string[];
  createdAt: string;
}

export interface ChatRequest {
  question: string;
  sessionId: string;
}

export interface ChatResponse {
  answer?: string;
  sources?: string[];
  sessionId?: string;
}
