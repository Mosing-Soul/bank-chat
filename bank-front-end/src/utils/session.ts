import type { ChatMessage } from '../types/chat';

const SESSION_ID_KEY = 'bank-chat-session-id';
const MESSAGES_KEY = 'bank-chat-messages';

const createId = () => {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }

  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

export const getOrCreateSessionId = () => {
  const sessionId = createId();
  sessionStorage.setItem(SESSION_ID_KEY, sessionId);
  sessionStorage.removeItem(MESSAGES_KEY);
  return sessionId;
};

export const saveSessionId = (sessionId: string) => {
  sessionStorage.setItem(SESSION_ID_KEY, sessionId);
};

export const resetSession = () => {
  const sessionId = createId();
  sessionStorage.setItem(SESSION_ID_KEY, sessionId);
  sessionStorage.removeItem(MESSAGES_KEY);
  return sessionId;
};

export const loadMessages = (): ChatMessage[] => {
  sessionStorage.removeItem(MESSAGES_KEY);
  return [];
};

export const saveMessages = (messages: ChatMessage[]) => {
  if (messages.length === 0) {
    sessionStorage.removeItem(MESSAGES_KEY);
  }
};

export const createMessageId = createId;
