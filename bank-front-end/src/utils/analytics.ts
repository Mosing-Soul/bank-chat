const CLIENT_ID_KEY = 'bank-chat-client-id';
const INTERNAL_VISITOR_KEY = 'bank-chat-internal-visitor';

const createId = () => {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

/**
 * 站长白名单：首次用 ?internal=1 打开页面后写入本地标记，
 * 之后该浏览器的访问与对话都不计入埋点；?internal=0 可清除。
 */
export const syncInternalVisitorFlag = () => {
  const internal = new URLSearchParams(window.location.search).get('internal');
  if (internal === '1') {
    localStorage.setItem(INTERNAL_VISITOR_KEY, 'true');
  } else if (internal === '0') {
    localStorage.removeItem(INTERNAL_VISITOR_KEY);
  }
};

export const isInternalVisitor = () => localStorage.getItem(INTERNAL_VISITOR_KEY) === 'true';

export const getOrCreateClientId = () => {
  const existing = localStorage.getItem(CLIENT_ID_KEY);
  if (existing) {
    return existing;
  }
  const clientId = createId();
  localStorage.setItem(CLIENT_ID_KEY, clientId);
  return clientId;
};

export const analyticsHeaders = (): Record<string, string> => {
  const headers: Record<string, string> = {
    'X-Client-Id': getOrCreateClientId(),
  };
  if (isInternalVisitor()) {
    headers['X-Internal-Visitor'] = 'true';
  }
  return headers;
};

export const reportPageView = (sessionId: string) => {
  try {
    fetch('/api/analytics/event', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...analyticsHeaders(),
      },
      body: JSON.stringify({ sessionId }),
      keepalive: true,
    }).catch(() => undefined);
  } catch {
    // 埋点失败不影响主流程
  }
};
