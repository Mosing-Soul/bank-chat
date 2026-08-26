import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import {
  AppstoreOutlined,
  ArrowLeftOutlined,
  BankOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  ClearOutlined,
  CloseOutlined,
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  GoldOutlined,
  IdcardOutlined,
  LoadingOutlined,
  LinkOutlined,
  LogoutOutlined,
  PlusOutlined,
  ProductOutlined,
  SaveOutlined,
  SearchOutlined,
  SendOutlined,
  SettingOutlined,
  StopOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { App as AntApp, Button, Input, Slider, Switch, Tooltip } from 'antd';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import logoUrl from '../asset/logo.jfif';
import { fetchSkillConfig, saveSkillConfig, sendChatMessage } from './api/chat';
import type { ChatMessage, ChatProgressEvent, ChatResponse, Citation, ExecutionTrace, SkillConfig, SkillExampleConfig } from './types/chat';
import {
  createMessageId,
  getOrCreateSessionId,
  loadMessages,
  resetSession,
  saveMessages,
  saveSessionId,
} from './utils/session';

interface QuickQuestion {
  icon: ReactNode;
  label: string;
  value: string;
  requestedSkill: string;
  forceWhenClicked: boolean;
}

interface GreetingConfig {
  text: string;
  prompt?: string;
  skill?: string;
  requestedSkill?: string;
}

type WorkspaceMode = 'chat' | 'config';

interface RuntimeTraceState {
  status: 'IDLE' | 'RUNNING' | 'SUCCESS' | 'ERROR';
  question?: string;
  progress: ChatProgressEvent[];
  trace?: ExecutionTrace;
  response?: ChatResponse;
  clientDurationMs?: number;
}

const iconByName = {
  gold: <GoldOutlined />,
  product: <ProductOutlined />,
  bank: <BankOutlined />,
};

const iconFor = (name?: string) => iconByName[(name || 'bank') as keyof typeof iconByName] || <BankOutlined />;

const defaultQuickQuestions: QuickQuestion[] = [
  {
    icon: <BankOutlined />,
    label: '白金资产门槛',
    value: '白金级客户的资产门槛是多少？',
    requestedSkill: 'RAG_QUERY',
    forceWhenClicked: true,
  },
  {
    icon: <ProductOutlined />,
    label: '被骗转账处置',
    value: '客户被骗转账后第一步做什么？',
    requestedSkill: 'RAG_QUERY',
    forceWhenClicked: true,
  },
  {
    icon: <BankOutlined />,
    label: '家属查询余额',
    value: '家属可以查询客户余额吗？',
    requestedSkill: 'RAG_QUERY',
    forceWhenClicked: true,
  },
  {
    icon: <GoldOutlined />,
    label: '实时金价',
    value: '今天黄金价格是多少？',
    requestedSkill: 'GOLD_PRICE',
    forceWhenClicked: true,
  },
  {
    icon: <ProductOutlined />,
    label: '贷款用途材料',
    value: '消费贷款需要什么用途材料？',
    requestedSkill: 'RAG_QUERY',
    forceWhenClicked: true,
  },
];

const placeholders = [
  '例如：消费贷款需要什么用途材料？',
  '例如：家属可以查询客户余额吗？',
  '例如：贷款用途材料疑似伪造怎么办？',
];

const defaultGreetings: GreetingConfig[] = [
  {
    text: '欢迎回来！您可以咨询客户分层与权益，例如“夫妻资产能合并计算客户等级吗？”，我会基于行内知识库给出口径。',
    prompt: '夫妻资产能合并计算客户等级吗？',
    skill: '客户分层问答',
    requestedSkill: 'RAG_QUERY',
  },
  {
    text: '遇到贷款业务疑问时，可以问我“审批通过后为什么还没放款？”，办理流程和补件要求都能快速查到。',
    prompt: '审批通过后为什么还没放款？',
    skill: '贷款业务问答',
    requestedSkill: 'RAG_QUERY',
  },
  {
    text: '涉及账户异常与应急处置时，试试“账户突然不能转账怎么办？”，帮您快速定位处置动作。',
    prompt: '账户突然不能转账怎么办？',
    skill: '账户异常问答',
    requestedSkill: 'RAG_QUERY',
  },
  {
    text: '需要实时公开信息时，可以问我“今天美元兑人民币汇率是多少？”，我会联网查询最新信息。',
    prompt: '今天美元兑人民币汇率是多少？',
    skill: '联网信息查询',
    requestedSkill: 'GOLD_PRICE',
  },
  {
    text: '合规与信息保护问题也可以直接问，例如“客户贷款用途材料疑似伪造怎么办？”。',
    prompt: '客户贷款用途材料疑似伪造怎么办？',
    skill: '合规问答',
    requestedSkill: 'RAG_QUERY',
  },
];

const quickQuestionFromExample = (example: SkillExampleConfig): QuickQuestion => ({
  icon: iconFor(example.icon),
  label: example.displayText || example.text,
  value: example.text,
  requestedSkill: example.skillCode,
  forceWhenClicked: example.forceWhenClicked,
});

const greetingFromExample = (example: SkillExampleConfig, skillName?: string): GreetingConfig => ({
  text: example.displayText || example.text,
  prompt: example.text,
  skill: skillName || example.skillCode,
  requestedSkill: example.skillCode,
});

function App() {
  const { message } = AntApp.useApp();
  const [sessionId, setSessionId] = useState(() => getOrCreateSessionId());
  const [messages, setMessages] = useState<ChatMessage[]>(() => loadMessages());
  const [workspaceMode, setWorkspaceMode] = useState<WorkspaceMode>(() =>
    window.location.hash === '#config' ? 'config' : 'chat',
  );
  const [skillConfigs, setSkillConfigs] = useState<SkillConfig[]>([]);
  const [activeSkillCode, setActiveSkillCode] = useState<string>('RAG_QUERY');
  const [quickQuestions, setQuickQuestions] = useState<QuickQuestion[]>(defaultQuickQuestions);
  const [greetings, setGreetings] = useState<GreetingConfig[]>(defaultGreetings);
  const [question, setQuestion] = useState('');
  const [pendingRequestedSkill, setPendingRequestedSkill] = useState<string | null>(null);
  const [isSending, setIsSending] = useState(false);
  const [isStreaming, setIsStreaming] = useState(false);
  const [placeholderIndex, setPlaceholderIndex] = useState(0);
  const [greetingIndex, setGreetingIndex] = useState(0);
  const [profileOpen, setProfileOpen] = useState(false);
  const [isSavingConfig, setIsSavingConfig] = useState(false);
  const [progressSteps, setProgressSteps] = useState<ChatProgressEvent[]>([]);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [runtimeTrace, setRuntimeTrace] = useState<RuntimeTraceState>({ status: 'IDLE', progress: [] });
  const [tracePanelOpen, setTracePanelOpen] = useState(() => window.innerWidth >= 1320);
  const endRef = useRef<HTMLDivElement>(null);
  const messageScrollRef = useRef<HTMLDivElement>(null);
  const profileRef = useRef<HTMLDivElement>(null);
  const abortControllerRef = useRef<AbortController | null>(null);
  const streamTimerRef = useRef<number | null>(null);
  const elapsedTimerRef = useRef<number | null>(null);
  const stopRequestedRef = useRef(false);
  const requestStartedAtRef = useRef(0);

  const canSend = useMemo(() => question.trim().length > 0 && !isSending, [isSending, question]);
  const activeGreeting = greetings[greetingIndex] ?? greetings[0] ?? defaultGreetings[0];
  const activeSkill = skillConfigs.find((skill) => skill.skillCode === activeSkillCode) ?? skillConfigs[0];

  const applySkillConfig = (config: { skills: SkillConfig[]; quickActions: SkillExampleConfig[]; greetings: SkillExampleConfig[] }) => {
    const enabledCodes = new Set(['RAG_QUERY', 'GOLD_PRICE']);
    const visibleSkills = config.skills.filter((skill) => enabledCodes.has(skill.skillCode));
    const skillNameByCode = new Map(visibleSkills.map((skill) => [skill.skillCode, skill.skillName]));
    const nextQuickQuestions = config.quickActions.filter((item) => enabledCodes.has(item.skillCode)).map(quickQuestionFromExample);
    const nextGreetings = config.greetings.filter((item) => enabledCodes.has(item.skillCode)).map((item) => greetingFromExample(item, skillNameByCode.get(item.skillCode)));
    setSkillConfigs(visibleSkills);
    if (visibleSkills.length > 0 && !visibleSkills.some((skill) => skill.skillCode === activeSkillCode)) {
      setActiveSkillCode(visibleSkills[0].skillCode);
    }
    if (nextQuickQuestions.length > 0) {
      setQuickQuestions(nextQuickQuestions);
    }
    if (nextGreetings.length > 0) {
      setGreetings(nextGreetings);
    }
  };

  useEffect(() => {
    document.querySelector<HTMLTextAreaElement>('.composer-input textarea')?.focus();

    fetchSkillConfig()
      .then((config) => {
        applySkillConfig(config);
        const firstVisibleSkill = config.skills.find((skill) => ['RAG_QUERY', 'GOLD_PRICE'].includes(skill.skillCode));
        if (firstVisibleSkill) {
          setActiveSkillCode(firstVisibleSkill.skillCode);
        }
      })
      .catch(() => {
        setQuickQuestions(defaultQuickQuestions);
        setGreetings(defaultGreetings);
      });

    const placeholderTimer = window.setInterval(() => {
      setPlaceholderIndex((current) => (current + 1) % placeholders.length);
    }, 2400);
    const handlePointerDown = (event: MouseEvent) => {
      if (!profileRef.current?.contains(event.target as Node)) {
        setProfileOpen(false);
      }
    };
    const handleHashChange = () => {
      setWorkspaceMode(window.location.hash === '#config' ? 'config' : 'chat');
    };
    document.addEventListener('mousedown', handlePointerDown);
    window.addEventListener('hashchange', handleHashChange);

    return () => {
      window.clearInterval(placeholderTimer);
      document.removeEventListener('mousedown', handlePointerDown);
      window.removeEventListener('hashchange', handleHashChange);
      clearAsyncTimers();
    };
  }, []);

  useEffect(() => {
    setGreetingIndex((current) => current % Math.max(greetings.length, 1));
    const greetingTimer = window.setInterval(() => {
      setGreetingIndex((current) => (current + 1) % Math.max(greetings.length, 1));
    }, 3600);
    return () => window.clearInterval(greetingTimer);
  }, [greetings.length]);

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
  }, [messages, isSending, isStreaming, progressSteps]);

  const clearAsyncTimers = () => {
    if (streamTimerRef.current) {
      window.clearInterval(streamTimerRef.current);
      streamTimerRef.current = null;
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
    setRuntimeTrace((current) => ({
      ...current,
      status: 'ERROR',
      clientDurationMs: Math.round(performance.now() - requestStartedAtRef.current),
    }));
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
    setProgressSteps([]);
    setElapsedSeconds(0);
    setRuntimeTrace({ status: 'IDLE', progress: [] });
    elapsedTimerRef.current = window.setInterval(() => {
      setElapsedSeconds((current) => current + 1);
    }, 1000);
  };

  const stopThinking = () => {
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
    requestStartedAtRef.current = performance.now();
    setRuntimeTrace({ status: 'RUNNING', question: content, progress: [] });
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
          // Typing/editing an example must still use LLM intent recognition.
          // Only an explicit action button may force a route.
          forceSkill,
        },
        requestController.signal,
        (progress) => {
          setRuntimeTrace((current) => ({
            ...current,
            progress: current.progress[current.progress.length - 1]?.code === progress.code
              ? current.progress
              : [...current.progress, progress].slice(-8),
          }));
          setProgressSteps((current) => {
            if (current[current.length - 1]?.code === progress.code) {
              return current;
            }
            return [...current, progress].slice(-5);
          });
        },
      );

      stopThinking();
      abortControllerRef.current = null;
      const responseCitations: Citation[] = response.citations?.length
        ? response.citations
        : (response.sources ?? []).map((source) => ({ source, title: source, type: 'INTERNAL' }));
      response.citations = responseCitations;
      const rawExecutionTrace = response.data?.executionTrace as ExecutionTrace | undefined;
      const fallbackRoute = response.intent === 'KNOWLEDGE_QA'
        ? 'RAG'
        : response.intent === 'EXTERNAL_API_QUERY' ? 'WEB SEARCH' : response.intent ? 'LLM DIRECT' : undefined;
      const executionTrace: ExecutionTrace | undefined = rawExecutionTrace
        ? {
          ...rawExecutionTrace,
          citations: rawExecutionTrace.citations?.length ? rawExecutionTrace.citations : responseCitations,
        }
        : (fallbackRoute || responseCitations.length ? {
          route: fallbackRoute,
          query: content,
          citations: responseCitations,
        } : undefined);
      setRuntimeTrace((current) => ({
        ...current,
        status: response.error ? 'ERROR' : 'SUCCESS',
        trace: executionTrace,
        response,
        clientDurationMs: Math.round(performance.now() - requestStartedAtRef.current),
      }));

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
      setRuntimeTrace((current) => ({
        ...current,
        status: 'ERROR',
        clientDurationMs: Math.round(performance.now() - requestStartedAtRef.current),
      }));
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

  const openConfigWorkspace = () => {
    window.location.hash = 'config';
    setWorkspaceMode('config');
    setProfileOpen(false);
  };

  const openChatWorkspace = () => {
    if (window.location.hash === '#config') {
      window.history.pushState('', document.title, window.location.pathname + window.location.search);
    }
    setWorkspaceMode('chat');
    window.setTimeout(() => {
      document.querySelector<HTMLTextAreaElement>('.composer-input textarea')?.focus();
    }, 0);
  };

  const updateActiveSkill = (patch: Partial<SkillConfig>) => {
    if (!activeSkill) {
      return;
    }
    setSkillConfigs((current) =>
      current.map((skill) => (skill.skillCode === activeSkill.skillCode ? { ...skill, ...patch } : skill)),
    );
  };

  const updateExample = (exampleId: string, patch: Partial<SkillExampleConfig>) => {
    if (!activeSkill) {
      return;
    }
    setSkillConfigs((current) =>
      current.map((skill) =>
        skill.skillCode === activeSkill.skillCode
          ? {
              ...skill,
              examples: skill.examples.map((example) =>
                example.exampleId === exampleId ? { ...example, ...patch } : example,
              ),
            }
          : skill,
      ),
    );
  };

  const addExample = () => {
    if (!activeSkill) {
      return;
    }
    const nextOrder = Math.max(0, ...activeSkill.examples.map((item) => item.sortOrder)) + 1;
    const nextExample: SkillExampleConfig = {
      exampleId: `draft-${Date.now()}`,
      skillCode: activeSkill.skillCode,
      text: '',
      displayText: '新示例问法',
      icon: 'bank',
      confidence: 0.82,
      showOnHome: true,
      quickAction: false,
      greeting: false,
      forceWhenClicked: activeSkill.forceWhenClicked,
      sortOrder: nextOrder,
    };
    updateActiveSkill({ examples: [...activeSkill.examples, nextExample] });
  };

  const deleteExample = (exampleId: string) => {
    if (!activeSkill) {
      return;
    }
    updateActiveSkill({
      examples: activeSkill.examples.filter((example) => example.exampleId !== exampleId),
    });
  };

  const saveConfigDraft = async () => {
    if (isSavingConfig) {
      return;
    }
    setIsSavingConfig(true);
    try {
      const saved = await saveSkillConfig({
        skills: skillConfigs,
        quickActions: [],
        greetings: [],
      });
      applySkillConfig(saved);
      message.success('技能配置已保存到中台');
    } catch (error) {
      const errorText = error instanceof Error ? error.message : '技能配置保存失败';
      message.error(errorText);
    } finally {
      setIsSavingConfig(false);
    }
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
    setProgressSteps([]);
    setElapsedSeconds(0);
    message.success('已清空当前会话');
    window.setTimeout(() => {
      document.querySelector<HTMLTextAreaElement>('.composer-input textarea')?.focus();
    }, 0);
  };

  if (workspaceMode === 'config') {
    return (
      <ConfigWorkspace
        skills={skillConfigs}
        activeSkill={activeSkill}
        activeSkillCode={activeSkillCode}
        onBack={openChatWorkspace}
        onSelectSkill={setActiveSkillCode}
        onUpdateSkill={updateActiveSkill}
        onUpdateExample={updateExample}
        onAddExample={addExample}
        onDeleteExample={deleteExample}
        onSaveDraft={saveConfigDraft}
        saving={isSavingConfig}
      />
    );
  }

  return (
    <main className="app-shell">
      <header className="chat-header">
        <div className="header-brand">
          <div className="brand-logo" aria-hidden="true">
            <img src={logoUrl} alt="" />
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
              <span className="greeting-icon">✓</span>
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

          <button
            className="config-shortcut-button"
            type="button"
            onClick={openConfigWorkspace}
            aria-label="进入技能运营配置"
          >
            <span className="config-shortcut-icon">
              <SettingOutlined />
            </span>
            <span className="config-shortcut-copy">
              <strong>配置平台</strong>
              <em>技能运营</em>
            </span>
          </button>

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
              onClick={() => setProfileOpen(true)}
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
                  onRevise={(content) => fillPrompt(`修改为：${content}`)}
                />
              ))}
              {isSending && !isStreaming ? (
                <ThinkingBubble steps={progressSteps} elapsedSeconds={elapsedSeconds} />
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
                sendMessage(item.value, item.requestedSkill, item.forceWhenClicked);
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
      <RuntimeTraceSidebar
        state={runtimeTrace}
        elapsedSeconds={elapsedSeconds}
        open={tracePanelOpen}
        onToggle={() => setTracePanelOpen((current) => !current)}
      />
    </main>
  );
}

function RuntimeTraceSidebar({
  state,
  elapsedSeconds,
  open,
  onToggle,
}: {
  state: RuntimeTraceState;
  elapsedSeconds: number;
  open: boolean;
  onToggle: () => void;
}) {
  const trace = state.trace;
  const retrieval = trace?.retrieval ?? [];
  const webResults = trace?.webResults ?? [];
  const citations = trace?.citations ?? state.response?.citations ?? [];
  const metrics = trace?.metrics;
  const durationMs = trace?.timing?.totalMs ?? state.clientDurationMs;
  const route = trace?.route ?? (state.status === 'RUNNING' ? '正在判断路径' : '等待提问');

  return (
    <>
      <button className={`trace-rail-button ${open ? 'trace-rail-button-open' : ''}`} type="button" onClick={onToggle}>
        <SearchOutlined />
        <span>运行追踪</span>
      </button>
      <aside className={`runtime-sidebar ${open ? 'runtime-sidebar-open' : ''}`} aria-label="本次运行追踪">
        <div className="runtime-sidebar-head">
          <div>
            <span className="trace-eyebrow">LIVE EXECUTION</span>
            <h2>本次运行追踪</h2>
          </div>
          <button type="button" onClick={onToggle} aria-label="收起运行追踪"><CloseOutlined /></button>
        </div>

        <div className={`trace-status trace-status-${state.status.toLowerCase()}`}>
          <span className="trace-status-dot" />
          <strong>{state.status === 'RUNNING' ? '运行中' : state.status === 'SUCCESS' ? '已完成' : state.status === 'ERROR' ? '已中止' : '等待请求'}</strong>
          <em>{state.status === 'RUNNING' ? `${elapsedSeconds}s` : durationMs != null ? `${(durationMs / 1000).toFixed(2)}s` : '--'}</em>
        </div>

        <section className="trace-section">
          <div className="trace-section-title"><SearchOutlined /><span>执行路径</span></div>
          <div className="trace-route-card">
            <span className={`trace-route-badge trace-route-${route.toLowerCase().split(' ').join('-').replace('+', 'combined')}`}>{route}</span>
            <p title={trace?.query ?? state.question}>{trace?.query ?? state.question ?? '发送问题后展示系统选择的处理路径'}</p>
          </div>
          {state.progress.length > 0 ? (
            <div className="trace-progress-list">
              {state.progress.map((step, index) => <span key={`${step.code}-${index}`} className={index === state.progress.length - 1 && state.status === 'RUNNING' ? 'active' : ''}>{step.title}</span>)}
            </div>
          ) : null}
        </section>

        {metrics ? (
          <section className="trace-section">
            <div className="trace-section-title"><AppstoreOutlined /><span>检索指标</span></div>
            <div className="trace-metric-grid">
              <div><strong>{metrics.retrievedCount ?? 0}</strong><span>Top-K 召回</span></div>
              <div><strong>{metrics.acceptedCount ?? 0}</strong><span>采用片段</span></div>
              <div><strong>{metrics.internalSourceCount ?? 0}</strong><span>去重文件</span></div>
              <div><strong>{metrics.webResultCount ?? 0}</strong><span>网页结果</span></div>
            </div>
            {(metrics.acceptedCount ?? 0) > 0 ? (
              <p className="trace-metric-note">
                已将全部 {metrics.acceptedCount} 个阈值内片段提供给回答模型；来源区按文件去重为 {metrics.internalSourceCount ?? 0} 个。
              </p>
            ) : null}
            {(metrics.bestSimilarity != null || metrics.averageSimilarity != null) ? (
              <div className="trace-score-summary">
                <span>最高相似度 <strong>{metrics.bestSimilarity != null ? `${(metrics.bestSimilarity * 100).toFixed(1)}%` : '--'}</strong></span>
                <span>平均相似度 <strong>{metrics.averageSimilarity != null ? `${(metrics.averageSimilarity * 100).toFixed(1)}%` : '--'}</strong></span>
              </div>
            ) : null}
          </section>
        ) : null}

        <section className="trace-section">
          <div className="trace-section-title"><SearchOutlined /><span>检索证据</span><em>{retrieval.length + webResults.length}</em></div>
          <div className="trace-evidence-list">
            {retrieval.map((item) => (
              <article className={`trace-evidence-card ${item.accepted ? '' : 'trace-evidence-muted'}`} key={`rag-${item.rank}-${item.source}`}>
                <div className="trace-evidence-meta">
                  <span>RAG · #{item.rank}</span>
                  {item.similarity != null ? <strong>{(item.similarity * 100).toFixed(1)}%</strong> : null}
                </div>
                <h3 title={item.source}>{item.source}</h3>
                <p title={item.snippet}>{item.snippet}</p>
                <footer>
                  <span>{item.sheet ? `Sheet: ${item.sheet}` : item.page != null ? `Page ${item.page}` : '向量检索'}</span>
                  {item.distance != null ? <span title="向量距离，越低越相关">distance {item.distance.toFixed(3)}</span> : null}
                </footer>
              </article>
            ))}
            {webResults.map((item) => (
              <article className="trace-evidence-card trace-web-card" key={`web-${item.rank}-${item.url}`}>
                <div className="trace-evidence-meta"><span>WEB · #{item.rank}</span><strong>公开来源</strong></div>
                <h3 title={item.title}>{item.title}</h3>
                <p title={item.snippet}>{item.snippet || item.url}</p>
              </article>
            ))}
            {retrieval.length === 0 && webResults.length === 0 ? <div className="trace-empty">运行完成后展示命中文档与摘要片段</div> : null}
          </div>
        </section>

        <section className="trace-section">
          <div className="trace-section-title"><LinkOutlined /><span>最终引用</span><em>{citations.length}</em></div>
          <div className="trace-citation-list">
            {citations.map((item, index) => item.url ? (
              <a href={item.url} target="_blank" rel="noreferrer noopener" key={`${item.source}-${index}`} title={item.title ?? item.source}>
                <LinkOutlined /><span>{item.title ?? item.source}</span>
              </a>
            ) : (
              <div key={`${item.source}-${index}`} title={item.title ?? item.source}><span className="trace-file-icon">DOC</span><span>{item.title ?? item.source}</span></div>
            ))}
            {citations.length === 0 ? <div className="trace-empty">暂无最终引用</div> : null}
          </div>
        </section>

        <section className="trace-section trace-timing-section">
          <div className="trace-section-title"><ClockCircleOutlined /><span>阶段耗时</span></div>
          {(trace?.timing?.stages ?? state.response?.skillCalls ?? []).map((stage) => (
            <div className="trace-timing-row" key={stage.skill}><span>{stage.skill}</span><strong>{stage.durationMs} ms</strong></div>
          ))}
          <div className="trace-timing-total"><span>请求总耗时</span><strong>{durationMs != null ? `${durationMs} ms` : state.status === 'RUNNING' ? `${elapsedSeconds * 1000}+ ms` : '--'}</strong></div>
        </section>
      </aside>
    </>
  );
}

function ConfigWorkspace({
  skills,
  activeSkill,
  activeSkillCode,
  onBack,
  onSelectSkill,
  onUpdateSkill,
  onUpdateExample,
  onAddExample,
  onDeleteExample,
  onSaveDraft,
  saving,
}: {
  skills: SkillConfig[];
  activeSkill?: SkillConfig;
  activeSkillCode: string;
  onBack: () => void;
  onSelectSkill: (skillCode: string) => void;
  onUpdateSkill: (patch: Partial<SkillConfig>) => void;
  onUpdateExample: (exampleId: string, patch: Partial<SkillExampleConfig>) => void;
  onAddExample: () => void;
  onDeleteExample: (exampleId: string) => void;
  onSaveDraft: () => void;
  saving: boolean;
}) {
  const examples = activeSkill?.examples ?? [];
  const quickCount = examples.filter((item) => item.quickAction).length;
  const greetingCount = examples.filter((item) => item.greeting).length;

  return (
    <main className="config-shell">
      <header className="config-header">
        <div className="config-title-group">
          <button className="config-back-button" type="button" onClick={onBack} aria-label="返回聊天工作台">
            <ArrowLeftOutlined />
          </button>
          <div className="config-mark" aria-hidden="true">
            <AppstoreOutlined />
          </div>
          <div>
            <p>运营维护工作台</p>
            <h1>智能客户经营配置中心</h1>
          </div>
        </div>
        <div className="config-header-actions">
          <div className="config-operator">
            <UserOutlined />
            <span>李敏 · 四川省分行</span>
          </div>
          <Button className="config-save-button" type="primary" icon={<SaveOutlined />} onClick={onSaveDraft} loading={saving}>
            保存草稿
          </Button>
        </div>
      </header>

      <section className="config-hero">
        <div>
          <h2>把标准问法沉淀成可运营资产</h2>
          <p>维护技能、Few-Shot 示例、快捷入口和问候语，让 Java 路由、Python 分类和前端引导使用同一份配置。</p>
        </div>
        <div className="config-metrics" aria-label="配置概览">
          <span>
            <strong>{skills.length}</strong>
            技能
          </span>
          <span>
            <strong>{skills.reduce((total, skill) => total + skill.examples.length, 0)}</strong>
            示例
          </span>
          <span>
            <strong>{skills.filter((skill) => skill.enabled).length}</strong>
            启用
          </span>
        </div>
      </section>

      <section className="config-layout">
        <aside className="skill-board" aria-label="技能列表">
          <div className="panel-heading">
            <span>技能编排</span>
            <em>Skill Catalog</em>
          </div>
          <div className="skill-card-list">
            {skills.map((skill) => (
              <button
                key={skill.skillCode}
                className={skill.skillCode === activeSkillCode ? 'skill-config-card skill-config-card-active' : 'skill-config-card'}
                type="button"
                onClick={() => onSelectSkill(skill.skillCode)}
              >
                <span className="skill-card-icon">{iconFor(skill.examples[0]?.icon)}</span>
                <span className="skill-card-copy">
                  <strong>{skill.skillName}</strong>
                  <em>{skill.skillCode}</em>
                </span>
                {skill.enabled ? <CheckCircleOutlined className="skill-enabled-icon" /> : null}
              </button>
            ))}
          </div>
        </aside>

        <section className="skill-detail-panel">
          {activeSkill ? (
            <>
              <div className="skill-detail-head">
                <div>
                  <span className="section-kicker">当前技能</span>
                  <h2>{activeSkill.skillName}</h2>
                  <p>{activeSkill.description}</p>
                </div>
                <div className="skill-switches">
                  <label>
                    <span>启用</span>
                    <Switch checked={activeSkill.enabled} onChange={(checked) => onUpdateSkill({ enabled: checked })} />
                  </label>
                  <label>
                    <span>前端可见</span>
                    <Switch
                      checked={activeSkill.frontendVisible}
                      onChange={(checked) => onUpdateSkill({ frontendVisible: checked })}
                    />
                  </label>
                </div>
              </div>

              <div className="skill-signal-row">
                <div>
                  <strong>{examples.length}</strong>
                  <span>示例问法</span>
                </div>
                <div>
                  <strong>{quickCount}</strong>
                  <span>快捷入口</span>
                </div>
                <div>
                  <strong>{greetingCount}</strong>
                  <span>问候语</span>
                </div>
              </div>

              <div className="example-toolbar">
                <div>
                  <span className="section-kicker">样本库</span>
                  <h3>标准问法与识别置信度</h3>
                </div>
                <Button icon={<PlusOutlined />} onClick={onAddExample}>
                  新增示例
                </Button>
              </div>

              <div className="example-grid">
                {examples.map((example) => (
                  <article className="example-card" key={example.exampleId}>
                    <div className="example-card-head">
                      <span className="example-icon">{iconFor(example.icon)}</span>
                      <Input
                        value={example.displayText}
                        onChange={(event) => onUpdateExample(example.exampleId, { displayText: event.target.value })}
                        placeholder="展示名称"
                      />
                      <button type="button" onClick={() => onDeleteExample(example.exampleId)} aria-label="删除示例">
                        <DeleteOutlined />
                      </button>
                    </div>
                    <Input.TextArea
                      value={example.text}
                      onChange={(event) => onUpdateExample(example.exampleId, { text: event.target.value })}
                      placeholder="输入用户可能提出的标准问法"
                      autoSize={{ minRows: 2, maxRows: 4 }}
                    />
                    <div className="confidence-row">
                      <span>置信度 {Math.round(example.confidence * 100)}%</span>
                      <Slider
                        min={0.5}
                        max={0.99}
                        step={0.01}
                        value={example.confidence}
                        onChange={(value) => onUpdateExample(example.exampleId, { confidence: value })}
                      />
                    </div>
                    <div className="example-flags">
                      <label>
                        <Switch
                          size="small"
                          checked={example.showOnHome}
                          onChange={(checked) => onUpdateExample(example.exampleId, { showOnHome: checked })}
                        />
                        首页展示
                      </label>
                      <label>
                        <Switch
                          size="small"
                          checked={example.quickAction}
                          onChange={(checked) => onUpdateExample(example.exampleId, { quickAction: checked })}
                        />
                        快捷问题
                      </label>
                      <label>
                        <Switch
                          size="small"
                          checked={example.greeting}
                          onChange={(checked) => onUpdateExample(example.exampleId, { greeting: checked })}
                        />
                        问候语
                      </label>
                      <label>
                        <Switch
                          size="small"
                          checked={example.forceWhenClicked}
                          onChange={(checked) => onUpdateExample(example.exampleId, { forceWhenClicked: checked })}
                        />
                        点击强制技能
                      </label>
                    </div>
                  </article>
                ))}
              </div>
            </>
          ) : (
            <div className="config-empty">
              <EditOutlined />
              <span>暂无技能配置，请先启动中台或完成默认配置初始化。</span>
            </div>
          )}
        </section>
      </section>
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
  onAction: (value: string, requestedSkill?: string, forceSkill?: boolean) => void;
  onRevise: (content: string) => void;
}) {
  const isUser = message.role === 'user';
  const messageCitations: Citation[] = message.citations?.length
    ? message.citations
    : (message.sources ?? []).map((source) => ({ source, title: source, type: 'INTERNAL' }));
  const internalCitations = messageCitations.filter((citation) => citation.type !== 'WEB' && !citation.url);
  const renderedContent = internalCitations.length > 0
    ? message.content.replace(/\[(\d+)\](?!\s*\()/g, (marker, rawIndex: string) => {
      const index = Number(rawIndex);
      return index >= 1 && index <= internalCitations.length
        ? `[${rawIndex}](#internal-citation-${rawIndex})`
        : marker;
    })
    : message.content;

  return (
    <article className={`message-row ${isUser ? 'message-row-user' : 'message-row-assistant'}`}>
      {!isUser ? (
        <div className="assistant-avatar" aria-hidden="true">
          <BankOutlined />
        </div>
      ) : null}
      <div className="message-bubble">
        {isUser ? (
          message.content
        ) : (
          <div className="markdown-content">
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={{
                a: ({ node: _node, href, ...props }) => href?.startsWith('#internal-citation-')
                  ? <a {...props} href={href} className="internal-citation-marker" />
                  : <a {...props} href={href} target="_blank" rel="noreferrer noopener" />,
              }}
            >
              {renderedContent}
            </ReactMarkdown>
            {internalCitations.length > 0 ? (
              <footer className="message-internal-sources" aria-label="行内引用来源">
                <span className="message-source-heading"><BankOutlined /> 引用来源</span>
                {internalCitations.map((citation, index) => (
                  <div id={`internal-citation-${index + 1}`} key={`${citation.source}-${index}`}>
                    <sup>{index + 1}</sup><span title={citation.title ?? citation.source}>{citation.title ?? citation.source}</span>
                  </div>
                ))}
              </footer>
            ) : null}
          </div>
        )}
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
  onAction: (value: string, requestedSkill?: string, forceSkill?: boolean) => void;
  onRevise: (content: string) => void;
}) {
  if (confirmation.type === 'INTENT_CLARIFICATION') {
    const originalMessage = confirmation.originalMessage || '';
    return (
      <div className="confirmation-card intent-confirmation-card">
        <div className="confirmation-title">{confirmation.title || '请选择办理方向'}</div>
        <div className="intent-candidate-list">
          {(confirmation.candidates || []).map((candidate) => (
            <button
              type="button"
              key={`${candidate.requestedSkill}-${candidate.displayText || candidate.label}`}
              className="intent-candidate-button"
              onClick={() => onAction(originalMessage || candidate.prompt || '', candidate.requestedSkill, true)}
            >
              <strong>{candidate.label || candidate.skillName || candidate.requestedSkill}</strong>
              <span>{candidate.description || candidate.displayText || candidate.prompt}</span>
            </button>
          ))}
        </div>
      </div>
    );
  }

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

function ThinkingBubble({ steps, elapsedSeconds }: { steps: ChatProgressEvent[]; elapsedSeconds: number }) {
  const current = steps[steps.length - 1];
  return (
    <article className="message-row message-row-assistant">
      <div className="assistant-avatar" aria-hidden="true">
        <BankOutlined />
      </div>
      <div className="thinking-card">
        <div className="thinking-head">
          <LoadingOutlined />
          <span>正在办理</span>
          <em>{elapsedSeconds}s</em>
        </div>
        <div className="thinking-stage">{current?.title || '正在连接业务服务'}</div>
        {current?.detail ? <div className="thinking-detail">{current.detail}</div> : null}
        <div className="thinking-steps">
          {steps.map((step, index) => (
            <span
              key={`${step.code}-${index}`}
              className={
                index < steps.length - 1 ? 'thinking-step thinking-step-done' : 'thinking-step thinking-step-active'
              }
            >
              {step.title}
            </span>
          ))}
        </div>
      </div>
    </article>
  );
}

export default App;
