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
  const existingSessionId = sessionStorage.getItem(SESSION_ID_KEY);

  if (existingSessionId) {
    return existingSessionId;
  }

  const sessionId = createId();
  sessionStorage.setItem(SESSION_ID_KEY, sessionId);
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
  const rawMessages = sessionStorage.getItem(MESSAGES_KEY);

  if (!rawMessages) {
    return [];
  }

  try {
    const messages = JSON.parse(rawMessages);
    return Array.isArray(messages) ? messages : [];
  } catch {
    return [];
  }
};

export const saveMessages = (messages: ChatMessage[]) => {
  sessionStorage.setItem(MESSAGES_KEY, JSON.stringify(messages));
};

export const createMessageId = createId;
