import type { ChatProgressEvent, ChatRequest, ChatResponse, SkillConfigResponse } from '../types/chat';
import { analyticsHeaders } from '../utils/analytics';

export class ChatRequestError extends Error {
  code: string;
  traceId?: string;
  retryable?: boolean;

  constructor(message: string, code = 'CLIENT_REQUEST_FAILED', traceId?: string, retryable = true) {
    super(message);
    this.name = 'ChatRequestError';
    this.code = code;
    this.traceId = traceId;
    this.retryable = retryable;
  }
}

const REQUEST_TIMEOUT_MS = 120_000;

export const sendChatMessage = async (
  payload: ChatRequest,
  signal?: AbortSignal,
  onProgress?: (progress: ChatProgressEvent) => void,
): Promise<ChatResponse> => {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  const abortFromCaller = () => controller.abort();

  signal?.addEventListener('abort', abortFromCaller, { once: true });

  try {
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...analyticsHeaders(),
      },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });

    if (!response.ok) {
      const payload = await response.json().catch(() => undefined) as {
        code?: string; message?: string; traceId?: string; retryable?: boolean;
      } | undefined;
      throw new ChatRequestError(
        payload?.message || '服务暂时不可用，请稍后重试。',
        payload?.code || `HTTP_${response.status}`,
        payload?.traceId || response.headers.get('X-Trace-Id') || undefined,
        payload?.retryable ?? response.status >= 500,
      );
    }

    if (!response.body) {
      throw new Error('服务未返回可读取的响应');
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let result: ChatResponse | undefined;
    const consume = (block: string) => {
      const lines = block.split(/\r?\n/);
      const event = lines.find((line) => line.startsWith('event:'))?.slice(6).trim();
      const data = lines.filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trim()).join('\n');
      if (!event || !data) return;
      const parsed = JSON.parse(data);
      if (event === 'progress') onProgress?.(parsed as ChatProgressEvent);
      if (event === 'result') result = parsed as ChatResponse;
    };

    while (true) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      const blocks = buffer.split(/\r?\n\r?\n/);
      buffer = blocks.pop() || '';
      blocks.forEach(consume);
      if (done) break;
    }
    if (buffer.trim()) consume(buffer);
    if (!result) throw new Error('服务未返回最终结果');
    return result;
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new Error('请求超时，请稍后重试');
    }

    if (error instanceof Error) {
      throw error;
    }

    throw new Error('请求失败，请稍后重试');
  } finally {
    window.clearTimeout(timeoutId);
    signal?.removeEventListener('abort', abortFromCaller);
  }
};

export const fetchSkillConfig = async (): Promise<SkillConfigResponse> => {
  const response = await fetch('/api/skills/config');
  if (!response.ok) {
    throw new Error(`技能配置加载失败：${response.status}`);
  }
  return response.json();
};

export const saveSkillConfig = async (payload: SkillConfigResponse): Promise<SkillConfigResponse> => {
  const response = await fetch('/api/skills/config', {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    throw new Error(`技能配置保存失败：${response.status}`);
  }

  return response.json();
};
