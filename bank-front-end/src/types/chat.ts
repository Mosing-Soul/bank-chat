export type ChatRole = 'user' | 'assistant';

export interface Citation {
  source: string;
  title?: string;
}

export interface ChatConfirmation {
  type?: string;
  title?: string;
  operationId?: string;
  customerName?: string;
  content?: string;
  status?: string;
  mock?: boolean;
  originalMessage?: string;
  reason?: string;
  candidates?: Array<{
    requestedSkill: string;
    skillCode?: string;
    skillName?: string;
    label?: string;
    description?: string;
    prompt?: string;
    displayText?: string;
    confidence?: number;
  }>;
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

export interface SkillExampleConfig {
  exampleId: string;
  skillCode: string;
  text: string;
  displayText: string;
  icon?: string;
  confidence: number;
  showOnHome: boolean;
  quickAction: boolean;
  greeting: boolean;
  forceWhenClicked: boolean;
  sortOrder: number;
}

export interface SkillConfig {
  skillCode: string;
  skillName: string;
  description: string;
  enabled: boolean;
  frontendVisible: boolean;
  forceWhenClicked: boolean;
  fallbackPriority: number;
  clarificationText?: string;
  examples: SkillExampleConfig[];
}

export interface SkillConfigResponse {
  skills: SkillConfig[];
  quickActions: SkillExampleConfig[];
  greetings: SkillExampleConfig[];
}
