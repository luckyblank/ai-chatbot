# 企业智能服务中心

一个面向真实企业客服场景的智能服务工作台，统一承载客户对话、企业知识、业务工具、链路追溯和工作流编排。

## 快速体验

开发环境和 Docker 部署环境分开配置：后端默认使用 `dev` profile，Docker Compose 显式传入 `SPRING_PROFILES_ACTIVE=docker`。两套环境各自读取自己的配置；默认都关闭模型能力，离线订单工作流仍可演示。

### 本地开发

```powershell
cd ai-chatbot-service
if (-not (Test-Path .env.development)) { Copy-Item .env.development.example .env.development }
# 编辑 .env.development 的数据库连接和密码；需要模型时再设置 AI_ENABLED=true 和 AI_DASHSCOPE_API_KEY。
mvn spring-boot:run
```

另开终端在 `ai-chatbot-web` 运行 `npm install`、`npm run dev`，访问 `http://localhost:5173`。开发环境的 `.env.development` 只在从后端目录启动时自动导入；它不会进入 Git，也不会被 Compose 读取。

> 默认账号用于本地首次体验。正式使用前请通过环境变量修改账号和密码。

### Docker Compose 本地部署

Docker Desktop 已启动时，可使用项目名 `ai-chatbot` 启动 MySQL、后端和 Web：

```powershell
if (-not (Test-Path .env.docker)) { Copy-Item .env.docker.example .env.docker }
# 编辑 .env.docker，至少替换数据库、Root 和管理员密码；真实文件不会进入 Git。
docker compose --env-file .env.docker -f compose.yaml config --quiet
docker compose --env-file .env.docker -f compose.yaml up -d --build
docker compose --env-file .env.docker -f compose.yaml ps
```

以上 `up` 命令用于全新部署。本机当前 MySQL 容器还使用忽略的 `.env.recovery.compose.yaml`；在这台机器再次执行 `up` 时须同时加 `-f .env.recovery.compose.yaml`，避免无意重建 MySQL。该恢复文件不用于新环境。

访问 `http://localhost:15175`。Web、后端和 MySQL 的固定默认宿主端口分别为 `15175`、`18082`、`13318`；只有主动修改 `.env.docker` 中的 `SERVICE_WEB_PORT`、`SERVICE_BACKEND_PORT`、`SERVICE_MYSQL_PORT` 才会变化。默认只绑定 `127.0.0.1`，不会发布到局域网；三个容器默认命名为 `ai-chatbot-mysql`、`ai-chatbot-backend`、`ai-chatbot-web`。不要使用 `down`、`prune`、`rm` 或 `rmi` 清理用户已有资源。

如需从另一台物理机连接 Docker MySQL，可只设置 `SERVICE_MYSQL_BIND_ADDRESS`（例如宿主机局域网 IPv4，或 `0.0.0.0`）；Web 和后端仍由 `SERVICE_BIND_ADDRESS=127.0.0.1` 限制为本机访问。修改 MySQL 的宿主机端口绑定需要重建 MySQL 容器，但不会删除数据卷。详见[局域网 MySQL 连接](docs/技术部署与维护.md#局域网-mysql-连接可选)。

Docker 与本机开发 MySQL 的业务库名现统一为 `ai_chatbot`（下划线）；Compose 项目和容器名前缀仍是 `ai-chatbot`（连字符）。本机独立 MySQL80 的旧 `ai-demo` 已用 [数据库迁移脚本](database/001-migrate-local-ai-demo-to-ai_chatbot.sql) 复制当前项目需要的 9 张表，新后端启动后共 12 张表；Docker 业务库也已迁移到 `ai_chatbot`。核验后，两个实例中的冗余旧业务库已删除，隔离测试库保留，完整旧库备份保存在 Git 忽略目录。这里只统一**库名**，没有在本机与 Docker 之间同步数据；其他已有数据卷不能只改环境变量，须先备份、迁移和核对。旧版 `.env` 的 `DEMO_*` 键需改为对应的 `SERVICE_*`；Docker AI 仅从 `SERVICE_AI_ENABLED`、`SERVICE_AI_DASHSCOPE_API_KEY` 显式配置。现有 MySQL 容器的首次初始化变量由 `SERVICE_MYSQL_INIT_DATABASE` 保持原值，后端使用 `SERVICE_DB_NAME=ai_chatbot`；全新部署无需设置初始化覆盖值。复用旧数据卷时设置 `SERVICE_EXISTING_VOLUMES=true` 和两个旧卷名，并避免两个 MySQL 容器同时挂载同一卷。细节见[技术部署与维护](docs/技术部署与维护.md)。

`SERVICE_AI_ENABLED=false` 时仍可运行不依赖模型的“售后订单审批演示”工作流；要演示真实客服对话和模型准备草案，在 Docker `.env.docker` 中显式设置 `SERVICE_AI_ENABLED=true` 与 `SERVICE_AI_DASHSCOPE_API_KEY`。页面左下角显示后端的 AI 配置状态；“参数已配置”只代表开关和密钥已填写，不代表外部模型已连通。完整验收记录见 [客服 Agent 可靠业务闭环 A 实施记录](docs/客服Agent可靠业务闭环-A实施记录.md)。

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
- `database`：本机旧库迁移 SQL 与使用说明；真实数据备份不进入 Git。
- `docs`：业务介绍与技术文档。
