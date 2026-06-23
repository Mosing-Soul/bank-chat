import { useEffect, useMemo, useRef, useState } from 'react';
import {
  BankOutlined,
  ClearOutlined,
  LoadingOutlined,
  RobotOutlined,
  SendOutlined,
  UserOutlined,
} from '@ant-design/icons';
import {
  App as AntApp,
  Avatar,
  Button,
  Empty,
  Input,
  Layout,
  Space,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
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

const { Header, Content } = Layout;
const { Text, Title } = Typography;

const exampleQuestions = [
  '稳健增利180天最新净值是多少？',
  '黄金近一周走势怎么样？',
  '适合稳健型客户的产品有哪些？',
];

function App() {
  const { message, modal } = AntApp.useApp();
  const [sessionId, setSessionId] = useState(() => getOrCreateSessionId());
  const [messages, setMessages] = useState<ChatMessage[]>(() => loadMessages());
  const [question, setQuestion] = useState('');
  const [isSending, setIsSending] = useState(false);
  const endRef = useRef<HTMLDivElement>(null);

  const canSend = useMemo(() => question.trim().length > 0 && !isSending, [isSending, question]);

  useEffect(() => {
    saveMessages(messages);
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages]);

  const appendMessages = (...nextMessages: ChatMessage[]) => {
    setMessages((currentMessages) => [...currentMessages, ...nextMessages]);
  };

  const handleSubmit = async (value?: string) => {
    const normalizedQuestion = (value ?? question).trim();

    if (!normalizedQuestion || isSending) {
      return;
    }

    const userMessage: ChatMessage = {
      id: createMessageId(),
      role: 'user',
      content: normalizedQuestion,
      createdAt: new Date().toISOString(),
    };

    appendMessages(userMessage);
    setQuestion('');
    setIsSending(true);

    try {
      const response = await sendChatMessage({
        question: normalizedQuestion,
        sessionId,
      });

      if (response.sessionId && response.sessionId !== sessionId) {
        saveSessionId(response.sessionId);
        setSessionId(response.sessionId);
      }

      const assistantMessage: ChatMessage = {
        id: createMessageId(),
        role: 'assistant',
        content: response.answer || '未获取到回答',
        sources: response.sources,
        createdAt: new Date().toISOString(),
      };

      appendMessages(assistantMessage);
    } catch (error) {
      const errorText = error instanceof Error ? error.message : '请求失败，请稍后重试';
      appendMessages({
        id: createMessageId(),
        role: 'assistant',
        content: errorText,
        createdAt: new Date().toISOString(),
      });
      message.error(errorText);
    } finally {
      setIsSending(false);
    }
  };

  const handleReset = () => {
    modal.confirm({
      title: '清空当前会话？',
      content: '当前标签页里的聊天记录会被清空，并生成新的会话编号。',
      okText: '清空',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: () => {
        const nextSessionId = resetSession();
        setSessionId(nextSessionId);
        setMessages([]);
        setQuestion('');
      },
    });
  };

  return (
    <Layout className="app-shell">
      <Header className="app-header">
        <div className="brand">
          <span className="brand-mark">
            <BankOutlined />
          </span>
          <div>
            <Title level={1}>银行客户经理智能助手</Title>
            <Text type="secondary">会话 {sessionId.slice(0, 8)}</Text>
          </div>
        </div>
        <Tooltip title="清空会话">
          <Button icon={<ClearOutlined />} onClick={handleReset} />
        </Tooltip>
      </Header>

      <Content className="chat-layout">
        <main className="chat-panel">
          <div className="message-list">
            {messages.length === 0 ? (
              <div className="empty-state">
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="请问您想了解什么？"
                />
                <Space wrap className="examples">
                  {exampleQuestions.map((exampleQuestion) => (
                    <Button
                      key={exampleQuestion}
                      onClick={() => handleSubmit(exampleQuestion)}
                      disabled={isSending}
                    >
                      {exampleQuestion}
                    </Button>
                  ))}
                </Space>
              </div>
            ) : (
              messages.map((item) => <MessageBubble key={item.id} message={item} />)
            )}

            {isSending ? (
              <MessageBubble
                message={{
                  id: 'pending',
                  role: 'assistant',
                  content: '思考中...',
                  createdAt: new Date().toISOString(),
                }}
                pending
              />
            ) : null}
            <div ref={endRef} />
          </div>

          <div className="composer">
            <Input.TextArea
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              onPressEnter={(event) => {
                if (!event.shiftKey) {
                  event.preventDefault();
                  handleSubmit();
                }
              }}
              placeholder="请问您想了解什么？"
              autoSize={{ minRows: 1, maxRows: 5 }}
              disabled={isSending}
            />
            <Tooltip title="发送">
              <Button
                type="primary"
                icon={isSending ? <LoadingOutlined /> : <SendOutlined />}
                disabled={!canSend}
                onClick={() => handleSubmit()}
              />
            </Tooltip>
          </div>
        </main>
      </Content>
    </Layout>
  );
}

interface MessageBubbleProps {
  message: ChatMessage;
  pending?: boolean;
}

function MessageBubble({ message, pending = false }: MessageBubbleProps) {
  const isUser = message.role === 'user';

  return (
    <article className={`message-row ${isUser ? 'message-row-user' : ''}`}>
      <Avatar
        className={isUser ? 'avatar-user' : 'avatar-assistant'}
        icon={isUser ? <UserOutlined /> : <RobotOutlined />}
      />
      <div className={`message-card ${isUser ? 'message-card-user' : ''}`}>
        <div className="message-meta">
          <Text strong>{isUser ? '我' : '助手'}</Text>
          {pending ? <Tag icon={<LoadingOutlined />}>处理中</Tag> : null}
        </div>
        <div className="message-content">{message.content}</div>
        {message.sources?.length ? (
          <Space wrap className="source-list">
            {message.sources.map((source) => (
              <Tag key={source}>{source}</Tag>
            ))}
          </Space>
        ) : null}
      </div>
    </article>
  );
}

export default App;
