import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import {
  AppstoreOutlined,
  ArrowLeftOutlined,
  BankOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  ClearOutlined,
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  GoldOutlined,
  IdcardOutlined,
  LoadingOutlined,
  LogoutOutlined,
  PlusOutlined,
  ProductOutlined,
  SaveOutlined,
  SendOutlined,
  SettingOutlined,
  StopOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { App as AntApp, Button, Input, Slider, Switch, Tooltip } from 'antd';
import { fetchSkillConfig, saveSkillConfig, sendChatMessage } from './api/chat';
import type { ChatMessage, SkillConfig, SkillExampleConfig } from './types/chat';
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

const iconByName = {
  team: <TeamOutlined />,
  gold: <GoldOutlined />,
  clock: <ClockCircleOutlined />,
  product: <ProductOutlined />,
  bank: <BankOutlined />,
};

const iconFor = (name?: string) => iconByName[(name || 'bank') as keyof typeof iconByName] || <BankOutlined />;

const defaultQuickQuestions: QuickQuestion[] = [
  {
    icon: <TeamOutlined />,
    label: '查询客户张伟AUM',
    value: '查询客户张伟AUM',
    requestedSkill: 'CUSTOMER_AUM',
    forceWhenClicked: true,
  },
  {
    icon: <GoldOutlined />,
    label: '黄金价格',
    value: '黄金价格',
    requestedSkill: 'GOLD_PRICE',
    forceWhenClicked: true,
  },
  {
    icon: <ClockCircleOutlined />,
    label: '产品到期提醒',
    value: '产品到期提醒',
    requestedSkill: 'MESSAGE_SEND',
    forceWhenClicked: true,
  },
  {
    icon: <ProductOutlined />,
    label: '提前赎回规则',
    value: '提前赎回规则',
    requestedSkill: 'RAG_QUERY',
    forceWhenClicked: true,
  },
];

const placeholders = ['例如：查询客户张伟的AUM', '黄金价格是多少？', '给张伟发送到期提醒'];

const defaultGreetings: GreetingConfig[] = [
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
  const [workspaceMode, setWorkspaceMode] = useState<WorkspaceMode>(() =>
    window.location.hash === '#config' ? 'config' : 'chat',
  );
  const [skillConfigs, setSkillConfigs] = useState<SkillConfig[]>([]);
  const [activeSkillCode, setActiveSkillCode] = useState<string>('CUSTOMER_AUM');
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
  const activeSkill = skillConfigs.find((skill) => skill.skillCode === activeSkillCode) ?? skillConfigs[0];

  const applySkillConfig = (config: { skills: SkillConfig[]; quickActions: SkillExampleConfig[]; greetings: SkillExampleConfig[] }) => {
    const skillNameByCode = new Map(config.skills.map((skill) => [skill.skillCode, skill.skillName]));
    const nextQuickQuestions = config.quickActions.map(quickQuestionFromExample);
    const nextGreetings = config.greetings.map((item) => greetingFromExample(item, skillNameByCode.get(item.skillCode)));
    setSkillConfigs(config.skills);
    if (config.skills.length > 0 && !config.skills.some((skill) => skill.skillCode === activeSkillCode)) {
      setActiveSkillCode(config.skills[0].skillCode);
    }
    if (nextQuickQuestions.length > 0) {
      setQuickQuestions(nextQuickQuestions);
    }
    if (nextGreetings.length > 0) {
      setGreetings([...nextGreetings, { text: '欢迎回来，您可以直接输入客户、产品或业务问题。' }]);
    }
  };

  useEffect(() => {
    document.querySelector<HTMLTextAreaElement>('.composer-input textarea')?.focus();

    fetchSkillConfig()
      .then((config) => {
        applySkillConfig(config);
        if (config.skills.length > 0) {
          setActiveSkillCode(config.skills[0].skillCode);
        }
      })
      .catch(() => {
        setQuickQuestions(defaultQuickQuestions);
        setGreetings(defaultGreetings);
      });

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
    const handleHashChange = () => {
      setWorkspaceMode(window.location.hash === '#config' ? 'config' : 'chat');
    };
    document.addEventListener('mousedown', handlePointerDown);
    window.addEventListener('hashchange', handleHashChange);

    return () => {
      window.clearInterval(placeholderTimer);
      window.clearInterval(greetingTimer);
      document.removeEventListener('mousedown', handlePointerDown);
      window.removeEventListener('hashchange', handleHashChange);
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
    setStageIndex(0);
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
    </main>
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
