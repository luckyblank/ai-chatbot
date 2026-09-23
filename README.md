# 企业智能服务中心

一个面向真实企业客服场景的智能服务工作台，统一承载客户对话、企业知识、业务工具、链路追溯和工作流编排。

## 快速体验

1. 启动后端与前端。
2. 打开 `http://localhost:5173`。
3. 使用默认账号登录：`admin` / `Admin@123456`。
4. 直接进入会话中心即可普通聊天；需要企业知识时再选择知识库。

> 默认账号用于本地首次体验。正式使用前请通过环境变量修改账号和密码。

### Docker Compose 本地部署

Docker Desktop 已启动时，可使用项目名 `ai-chatbot` 启动 MySQL、后端和 Web：

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# 编辑 .env，至少替换数据库、Root 和管理员密码；真实 .env 不会进入 Git。
docker compose -f compose.yaml config
docker compose -f compose.yaml up -d --build
docker compose -f compose.yaml ps
```

访问 `http://localhost:15175`。Web、后端和 MySQL 的固定默认宿主端口分别为 `15175`、`18082`、`13318`；只有主动修改 `.env` 中的 `SERVICE_WEB_PORT`、`SERVICE_BACKEND_PORT`、`SERVICE_MYSQL_PORT` 才会变化。默认只绑定 `127.0.0.1`，不会发布到局域网；三个容器默认命名为 `ai-chatbot-mysql`、`ai-chatbot-backend`、`ai-chatbot-web`。不要使用 `down`、`prune`、`rm` 或 `rmi` 清理用户已有资源。

如果已有旧版 `.env`，请将其中的 `DEMO_*` 键逐一改为对应的 `SERVICE_*` 键，并保留原数据库名、密码和端口值。新环境的默认库名是 `ai_chatbot`；这不会自动重命名或复制旧数据库。复用旧数据卷时设置 `SERVICE_EXISTING_VOLUMES=true` 和两个旧卷名，且必须使用旧库实际的数据库名和有效凭据；不要让两个 MySQL 容器同时挂载同一个数据卷。迁移细节见[技术部署与维护](docs/技术部署与维护.md)。

`AI_ENABLED=false` 时仍可运行不依赖模型的“售后订单审批演示”工作流；要演示真实客服对话和模型准备草案，在本机环境中显式设置 `AI_ENABLED=true` 与 `AI_DASHSCOPE_API_KEY`。完整验收记录见 [客服 Agent 可靠业务闭环 A 实施记录](docs/客服Agent可靠业务闭环-A实施记录.md)。

## 文档入口

- [业务使用指南](docs/业务使用指南.md)：面向客服、运营、知识管理员和业务负责人，介绍实际使用方式与典型场景。
- [技术部署与维护](docs/技术部署与维护.md)：面向部署与维护人员，介绍启动、配置、数据表、接口与验证方法。
- [客服 Agent 可靠业务闭环 A 实施记录](docs/客服Agent可靠业务闭环-A实施记录.md)：本轮 A1～A4 的设计、迁移、实测证据、演示脚本和限制。

## 核心能力

- 无知识库时正常聊天，选择知识库后自动启用 RAG、引用与检索追溯。
- 支持 PDF、TXT、Markdown 知识文件；知识库名称和描述可编辑，可预览原文和查看分词片段。
- 会话回答实时流式呈现，支持常用 Markdown、表格与任务列表、代码块、图片预览和 KaTeX 公式；Mermaid 流程图、时序图会在流式回答完成后渲染。
- 长消息不会遮挡底部输入区；生成完成后可展开查看知识引用、工具调用和链路追溯。
- 会话支持首问智能命名、图片与文件附件、图片放大预览、手动改名和二次确认删除。
- 全局搜索覆盖会话、知识库、文档、业务场景和工作流。
- 客服工具覆盖客户权益、订单履约、售后资格、业务主体和服务工单；写操作先生成服务端草案，只有独立确认接口可创建正式工单。
- 工作流支持基于 Vue Flow 的独立全屏画布与列表编排，可拖拽、缩放、平移，并提供画布控件、缩略图、自动布局和位置保存；新建或打开设计器会直接进入新标签页。试运行接收真实输入并逐节点执行，画布展示节点状态与连线流向，人工节点需明确确认后继续。
- 切换浅色或深色主题时，左侧导航会与主内容区同步更新颜色。
- 登录采用 HttpOnly 会话，密钥和完整敏感信息不进入前端或追溯内容。

## 项目目录

- `ai-chatbot-web`：Vue 3 企业工作台。
- `ai-chatbot-service`：Spring Boot、Spring AI、MySQL、RAG 与业务工具。
- `docs`：业务介绍与技术文档。
