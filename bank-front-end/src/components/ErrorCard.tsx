import { ReloadOutlined, WarningOutlined } from '@ant-design/icons';
import type { ChatError } from '../types/chat';

export default function ErrorCard({ error, onRetry }: { error: ChatError; onRetry?: () => void }) {
  return (
    <section className="request-error-card" role="alert">
      <span className="request-error-icon"><WarningOutlined /></span>
      <div className="request-error-copy">
        <strong>本次请求未完成</strong>
        <p>{error.message || '服务暂时不可用，请稍后重试。'}</p>
        {error.traceId ? <span title={error.traceId}>追踪编号：{error.traceId}</span> : null}
      </div>
      {error.retryable !== false && onRetry ? (
        <button type="button" onClick={onRetry}><ReloadOutlined /> 重试</button>
      ) : null}
    </section>
  );
}
