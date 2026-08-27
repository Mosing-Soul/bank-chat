import React, { type ErrorInfo, type ReactNode } from 'react';
import { ReloadOutlined, WarningOutlined } from '@ant-design/icons';

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  failed: boolean;
}

export default class ErrorBoundary extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { failed: false };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('UI render failure', error, info.componentStack);
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <main className="fatal-error-page" role="alert">
        <section className="fatal-error-card">
          <span className="fatal-error-icon"><WarningOutlined /></span>
          <h1>页面暂时无法正常显示</h1>
          <p>您的会话仍保存在当前浏览器中，可以刷新页面后继续。</p>
          <button type="button" onClick={() => window.location.reload()}>
            <ReloadOutlined /> 刷新页面
          </button>
        </section>
      </main>
    );
  }
}
