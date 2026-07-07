import { useEffect, useMemo, useRef, useState } from 'react';
import {
  BankOutlined,
  ClockCircleOutlined,
  ClearOutlined,
  DownOutlined,
  GoldOutlined,
  IdcardOutlined,
  LoadingOutlined,
  LogoutOutlined,
  ProductOutlined,
  SendOutlined,
  StopOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { App as AntApp, Button, Input, Tooltip } from 'antd';
import { sendChatMessage } from './api/chat';
import type { ChatMessage } from './types/chat';
import {
  createMessageId,
  getOrCreateSessionId,
  loadMessages,
  resetSession,
  saveMessages,
  saveSessionId,
} from './utils/session';

const quickQuestions = [
  {
    icon: <TeamOutlined />,
    label: '查询客户张伟AUM',
    value: '查询客户张伟AUM',
    requestedSkill: 'CUSTOMER_AUM',
  },
  {
    icon: <GoldOutlined />,
    label: '黄金价格',
    value: '黄金价格',
    requestedSkill: 'GOLD_PRICE',
  },
  {
    icon: <ClockCircleOutlined />,
    label: '产品到期提醒',
    value: '产品到期提醒',
    requestedSkill: 'MESSAGE_SEND',
  },
  {
    icon: <ProductOutlined />,
    label: '提前赎回规则',
    value: '提前赎回规则',
    requestedSkill: 'RAG_QUERY',
  },
];

const placeholders = ['例如：查询客户张伟的AUM', '黄金价格是多少？', '给张伟发送到期提醒'];

const greetings: Array<{ text: string; prompt?: string; skill?: string; requestedSkill?: string }> = [
  {
    text: '今日可跟进高资产客户的资产变化，点击填入查询指令。',
    prompt: '帮我查询高资产客户的AUM变化',
    skill: '客户资产查询',
    requestedSkill: 'CUSTOMER_AUM',
  },
  {
    text: '黄金价格波动较快，可随时查询当前参考价。',
    prompt: '黄金价格是多少？',
    skill: '市场价格查询',
    requestedSkill: 'GOLD_PRICE',
  },
  {
    text: '今日有客户存在产品到期机会，建议及时触达。',
    prompt: '给张伟发送到期提醒',
    skill: '到期提醒',
    requestedSkill: 'MESSAGE_SEND',
  },
  {
    text: '遇到赎回咨询时，可先确认产品规则和确认日。',
    prompt: '提前赎回规则',
    skill: '规则问答',
    requestedSkill: 'RAG_QUERY',
  },
  {
    text: '欢迎回来，您可以直接输入客户、产品或业务问题。',
  },
];

const thinkingStages = [
  '识别问题意图',
  '选择可用技能',
  '调用业务接口或检索知识',
  '整理结果并生成回答',
];

function App() {
  const { message } = AntApp.useApp();
  const [sessionId, setSessionId] = useState(() => getOrCreateSessionId());
  const [messages, setMessages] = useState<ChatMessage[]>(() => loadMessages());
  const [question, setQuestion] = useState('');
  const [pendingRequestedSkill, setPendingRequestedSkill] = useState<string | null>(null);
  const [isSending, setIsSending] = useState(false);
  const [isStreaming, setIsStreaming] = useState(false);
  const [placeholderIndex, setPlaceholderIndex] = useState(0);
  const [greetingIndex, setGreetingIndex] = useState(0);
  const [profileOpen, setProfileOpen] = useState(false);
  const [stageIndex, setStageIndex] = useState(0);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const endRef = useRef<HTMLDivElement>(null);
  const messageScrollRef = useRef<HTMLDivElement>(null);
  const profileRef = useRef<HTMLDivElement>(null);
  const abortControllerRef = useRef<AbortController | null>(null);
  const streamTimerRef = useRef<number | null>(null);
  const stageTimerRef = useRef<number | null>(null);
  const elapsedTimerRef = useRef<number | null>(null);
  const stopRequestedRef = useRef(false);

  const canSend = useMemo(() => question.trim().length > 0 && !isSending, [isSending, question]);
  const activeGreeting = greetings[greetingIndex];

  useEffect(() => {
    document.querySelector<HTMLTextAreaElement>('.composer-input textarea')?.focus();

    const placeholderTimer = window.setInterval(() => {
      setPlaceholderIndex((current) => (current + 1) % placeholders.length);
    }, 2400);
    const greetingTimer = window.setInterval(() => {
      setGreetingIndex((current) => (current + 1) % greetings.length);
    }, 3600);

    const handlePointerDown = (event: MouseEvent) => {
      if (!profileRef.current?.contains(event.target as Node)) {
        setProfileOpen(false);
      }
    };
    document.addEventListener('mousedown', handlePointerDown);

    return () => {
      window.clearInterval(placeholderTimer);
      window.clearInterval(greetingTimer);
      document.removeEventListener('mousedown', handlePointerDown);
      clearAsyncTimers();
    };
  }, []);

  useEffect(() => {
    saveMessages(messages);
    if (messageScrollRef.current) {
      messageScrollRef.current.scrollTo({
        top: messageScrollRef.current.scrollHeight,
        behavior: 'smooth',
      });
    } else {
      endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
    }
  }, [messages, isSending, isStreaming, stageIndex]);

  const clearAsyncTimers = () => {
    if (streamTimerRef.current) {
      window.clearInterval(streamTimerRef.current);
      streamTimerRef.current = null;
    }
    if (stageTimerRef.current) {
      window.clearInterval(stageTimerRef.current);
      stageTimerRef.current = null;
    }
    if (elapsedTimerRef.current) {
      window.clearInterval(elapsedTimerRef.current);
      elapsedTimerRef.current = null;
    }
  };

  const stopGeneration = () => {
    if (!isSending) {
      return;
    }

    stopRequestedRef.current = true;
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    clearAsyncTimers();
    setIsSending(false);
    setIsStreaming(false);
    setMessages((current) => {
      const lastMessage = current[current.length - 1];
      if (lastMessage?.role === 'assistant' && lastMessage.content) {
        return current;
      }

      return [
        ...current,
        {
          id: createMessageId(),
          role: 'assistant',
          content: '已停止生成。',
          createdAt: new Date().toISOString(),
        },
      ];
    });
  };

  const startThinking = () => {
    setStageIndex(0);
    setElapsedSeconds(0);
    stageTimerRef.current = window.setInterval(() => {
      setStageIndex((current) => Math.min(current + 1, thinkingStages.length - 1));
    }, 1400);
    elapsedTimerRef.current = window.setInterval(() => {
      setElapsedSeconds((current) => current + 1);
    }, 1000);
  };

  const stopThinking = () => {
    if (stageTimerRef.current) {
      window.clearInterval(stageTimerRef.current);
      stageTimerRef.current = null;
    }
    if (elapsedTimerRef.current) {
      window.clearInterval(elapsedTimerRef.current);
      elapsedTimerRef.current = null;
    }
  };

  const streamAssistantMessage = (messageId: string, fullText: string) => {
    setIsStreaming(true);
    let index = 0;
    streamTimerRef.current = window.setInterval(() => {
      index += fullText.length > 160 ? 4 : 2;
      const nextText = fullText.slice(0, index);
      setMessages((current) =>
        current.map((item) => (item.id === messageId ? { ...item, content: nextText } : item)),
      );

      if (index >= fullText.length) {
        if (streamTimerRef.current) {
          window.clearInterval(streamTimerRef.current);
          streamTimerRef.current = null;
        }
        setIsStreaming(false);
        setIsSending(false);
        stopRequestedRef.current = false;
      }
    }, 24);
  };

  const fillPrompt = (value: string, requestedSkill?: string) => {
    setQuestion(value);
    setPendingRequestedSkill(requestedSkill ?? null);
    document.querySelector<HTMLTextAreaElement>('.composer-input textarea')?.focus();
  };

  const sendMessage = async (value?: string, requestedSkill?: string, forceSkill = false) => {
    const content = (value ?? question).trim();
    if (!content || isSending) {
      return;
    }
    const skillToRequest = requestedSkill ?? pendingRequestedSkill ?? undefined;

    const userMessage: ChatMessage = {
      id: createMessageId(),
      role: 'user',
      content,
      createdAt: new Date().toISOString(),
    };

    setMessages((current) => [...current, userMessage]);
    setQuestion('');
    setPendingRequestedSkill(null);
    setIsSending(true);
    setIsStreaming(false);
    stopRequestedRef.current = false;
    const requestController = new AbortController();
    abortControllerRef.current = requestController;
    startThinking();

    try {
      const response = await sendChatMessage(
        {
          message: content,
          question: content,
          sessionId,
          requestedSkill: skillToRequest,
          forceSkill: forceSkill || Boolean(skillToRequest),
        },
        requestController.signal,
      );

      stopThinking();
      abortControllerRef.current = null;

      if (response.sessionId && response.sessionId !== sessionId) {
        saveSessionId(response.sessionId);
        setSessionId(response.sessionId);
      }

      const assistantMessageId = createMessageId();
      const answer = response.answer || response.error?.message || '未获取到回答，请稍后重试。';
      const assistantMessage: ChatMessage = {
        id: assistantMessageId,
        role: 'assistant',
        content: '',
        sources: response.sources,
        citations: response.citations,
        data: response.data,
        requiresConfirmation: response.requiresConfirmation,
        confirmation: response.confirmation,
        createdAt: new Date().toISOString(),
      };

      setMessages((current) => [...current, assistantMessage]);
      streamAssistantMessage(assistantMessageId, answer);
    } catch (error) {
      stopThinking();
      abortControllerRef.current = null;
      if (stopRequestedRef.current) {
        stopRequestedRef.current = false;
        return;
      }
      const errorText = error instanceof Error ? error.message : '请求失败，请稍后重试。';
      setMessages((current) => [
        ...current,
        {
          id: createMessageId(),
          role: 'assistant',
          content: errorText,
          createdAt: new Date().toISOString(),
        },
      ]);
      setIsSending(false);
      setIsStreaming(false);
      message.error(errorText);
    }
  };

  const handleLogout = () => {
    setProfileOpen(false);
    message.info('已退出当前演示账号');
  };

  const handleClearConversation = () => {
    stopRequestedRef.current = true;
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    clearAsyncTimers();
    const nextSessionId = resetSession();
    setSessionId(nextSessionId);
    setMessages([]);
    setQuestion('');
    setPendingRequestedSkill(null);
    setIsSending(false);
    setIsStreaming(false);
    setStageIndex(0);
    setElapsedSeconds(0);
    message.success('已清空当前会话');
    window.setTimeout(() => {
      document.querySelector<HTMLTextAreaElement>('.composer-input textarea')?.focus();
    }, 0);
  };

  return (
    <main className="app-shell">
      <header className="chat-header">
        <div className="header-brand">
          <div className="brand-logo" aria-hidden="true">
            <BankOutlined />
          </div>
          <div className="header-copy">
            <h1>今日会话</h1>
            <button
              className={activeGreeting.prompt ? 'greeting-line greeting-action' : 'greeting-line'}
              key={greetingIndex}
              type="button"
              onClick={() => {
                if (activeGreeting.prompt) {
                  fillPrompt(activeGreeting.prompt, activeGreeting.requestedSkill);
                }
              }}
              disabled={!activeGreeting.prompt}
              title={activeGreeting.prompt ? '点击填入对话框' : undefined}
            >
              <span className="greeting-icon">✦</span>
              <span className="greeting-text">{activeGreeting.text}</span>
              {activeGreeting.skill ? <span className="greeting-skill">{activeGreeting.skill}</span> : null}
            </button>
          </div>
        </div>

        <div className="header-actions">
          <Tooltip title="清空当前会话">
            <button
              className="clear-conversation-button"
              type="button"
              onClick={handleClearConversation}
              aria-label="清空当前会话"
            >
              <ClearOutlined />
            </button>
          </Tooltip>

          <div
            ref={profileRef}
            className={profileOpen ? 'profile-menu-wrap profile-menu-wrap-open' : 'profile-menu-wrap'}
            onMouseEnter={() => setProfileOpen(true)}
          >
            <button
              className="user-profile"
              type="button"
              aria-label="登录人信息"
              aria-expanded={profileOpen}
              onClick={() => setProfileOpen((current) => !current)}
            >
              <div className="user-avatar" aria-hidden="true">
                <UserOutlined />
              </div>
              <div className="user-profile-copy">
                <strong>李敏</strong>
                <span>
                  <IdcardOutlined /> 客户经理
                </span>
              </div>
              <DownOutlined className="profile-chevron" />
            </button>

            <div className="profile-dropdown" role="menu">
              <div className="profile-detail">
                <span>所属机构</span>
                <strong>四川省分行 · 成都高新支行</strong>
              </div>
              <div className="profile-detail">
                <span>岗位</span>
                <strong>客户经理</strong>
              </div>
              <button className="logout-button" type="button" role="menuitem" onClick={handleLogout}>
                <LogoutOutlined />
                退出登录
              </button>
            </div>
          </div>
        </div>
      </header>

      <section className="chat-body" aria-label="聊天消息">
        <div className="tip-banner">💡 小提示：您可以直接说“查询...”或“发送...”</div>

        <div className="message-scroll" ref={messageScrollRef}>
          {messages.length === 0 ? (
            <div className="empty-state">
              <div className="empty-card">
                <div className="welcome-art" aria-hidden="true">
                  <span className="art-bubble art-bubble-main" />
                  <span className="art-bubble art-bubble-side" />
                  <span className="art-emoji">👋💬</span>
                </div>
                <p className="empty-title">欢迎回来！试试下方快捷问题或直接输入。</p>
                <p className="empty-copy">我可以帮您快速查询客户资产、市场价格、产品到期和业务规则。</p>
              </div>
            </div>
          ) : (
            <div className="message-list">
              {messages.map((item) => (
                <MessageBubble
                  key={item.id}
                  message={item}
                  streaming={isStreaming && item.role === 'assistant' && item.content.length > 0}
                  onAction={sendMessage}
                  onRevise={(content) => fillPrompt(`修改为${content}`)}
                />
              ))}
              {isSending && !isStreaming ? (
                <ThinkingBubble stageIndex={stageIndex} elapsedSeconds={elapsedSeconds} />
              ) : null}
            </div>
          )}
          <div ref={endRef} />
        </div>
      </section>

      <footer className="composer-panel">
        <div className="quick-actions" aria-label="快捷问题">
          {quickQuestions.map((item) => (
            <button
              className="quick-action"
              key={item.value}
              type="button"
              onClick={() => {
                fillPrompt(item.value, item.requestedSkill);
                sendMessage(item.value, item.requestedSkill, true);
              }}
              disabled={isSending}
            >
              {item.icon}
              <span>{item.label}</span>
            </button>
          ))}
        </div>

        <form
          className="composer"
          onSubmit={(event) => {
            event.preventDefault();
            sendMessage();
          }}
        >
          <Input.TextArea
            className="composer-input"
            value={question}
            onChange={(event) => {
              setQuestion(event.target.value);
              setPendingRequestedSkill(null);
            }}
            onPressEnter={(event) => {
              if (!event.shiftKey) {
                event.preventDefault();
                sendMessage();
              }
            }}
            placeholder={placeholders[placeholderIndex]}
            autoSize={{ minRows: 1, maxRows: 5 }}
            disabled={isSending}
          />
          <Tooltip title={isSending ? '停止生成' : '发送'}>
            <Button
              className={isSending ? 'send-button send-button-stop' : canSend ? 'send-button send-button-ready' : 'send-button'}
              htmlType={isSending ? 'button' : 'submit'}
              shape="circle"
              icon={isSending ? <StopOutlined /> : <SendOutlined />}
              disabled={!isSending && !canSend}
              onClick={isSending ? stopGeneration : undefined}
              aria-label="发送消息"
            />
          </Tooltip>
        </form>
      </footer>
    </main>
  );
}

function MessageBubble({
  message,
  streaming,
  onAction,
  onRevise,
}: {
  message: ChatMessage;
  streaming?: boolean;
  onAction: (value: string) => void;
  onRevise: (content: string) => void;
}) {
  const isUser = message.role === 'user';

  return (
    <article className={`message-row ${isUser ? 'message-row-user' : 'message-row-assistant'}`}>
      {!isUser ? (
        <div className="assistant-avatar" aria-hidden="true">
          <BankOutlined />
        </div>
      ) : null}
      <div className="message-bubble">
        {message.content}
        {streaming ? <span className="stream-caret" /> : null}
        {!isUser && message.confirmation ? (
          <ConfirmationCard confirmation={message.confirmation} onAction={onAction} onRevise={onRevise} />
        ) : null}
      </div>
      {isUser ? (
        <div className="message-user-avatar" aria-hidden="true">
          <UserOutlined />
        </div>
      ) : null}
    </article>
  );
}

function ConfirmationCard({
  confirmation,
  onAction,
  onRevise,
}: {
  confirmation: NonNullable<ChatMessage['confirmation']>;
  onAction: (value: string) => void;
  onRevise: (content: string) => void;
}) {
  return (
    <div className="confirmation-card">
      <div className="confirmation-title">消息发送确认</div>
      <div className="confirmation-field">
        <span>客户</span>
        <strong>{confirmation.customerName || '-'}</strong>
      </div>
      <div className="confirmation-field">
        <span>内容</span>
        <p>{confirmation.content || '-'}</p>
      </div>
      <div className="confirmation-actions">
        <button type="button" className="confirm-primary" onClick={() => onAction('确认发送')}>
          确认发送
        </button>
        <button type="button" onClick={() => onRevise(confirmation.content || '')}>
          修改
        </button>
        <button type="button" onClick={() => onAction('取消')}>
          取消
        </button>
      </div>
    </div>
  );
}

function ThinkingBubble({ stageIndex, elapsedSeconds }: { stageIndex: number; elapsedSeconds: number }) {
  return (
    <article className="message-row message-row-assistant">
      <div className="assistant-avatar" aria-hidden="true">
        <BankOutlined />
      </div>
      <div className="thinking-card">
        <div className="thinking-head">
          <LoadingOutlined />
          <span>思考中</span>
          <em>{elapsedSeconds}s</em>
        </div>
        <div className="thinking-stage">{thinkingStages[stageIndex]}</div>
        <div className="thinking-steps">
          {thinkingStages.map((stage, index) => (
            <span
              key={stage}
              className={
                index < stageIndex ? 'thinking-step thinking-step-done' : index === stageIndex ? 'thinking-step thinking-step-active' : 'thinking-step'
              }
            >
              {stage}
            </span>
          ))}
        </div>
      </div>
    </article>
  );
}

export default App;
