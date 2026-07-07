import type { ChatRequest, ChatResponse } from '../types/chat';

const REQUEST_TIMEOUT_MS = 30_000;

export const sendChatMessage = async (payload: ChatRequest, signal?: AbortSignal): Promise<ChatResponse> => {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  const abortFromCaller = () => controller.abort();

  signal?.addEventListener('abort', abortFromCaller, { once: true });

  try {
    const response = await fetch('/api/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });

    if (!response.ok) {
      throw new Error(`服务异常：${response.status}`);
    }

    return response.json();
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
