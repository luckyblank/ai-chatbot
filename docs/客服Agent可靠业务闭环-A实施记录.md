# 客服 Agent 可靠业务闭环：A1～A4 实施记录

实施与复核日期：2026-09-24。

本记录只覆盖任务书“提示词 A：完成可靠的客服业务闭环”的 A1～A4。B（检索评测与优化）和 C（MCP）没有启动，也不在本次能力声明中。

## 交付结论

项目已经形成一条可演示、可回查的单机客服闭环：已认证操作员发起售后对话，模型可以查询获授权的真实订单事实并准备服务端工单草案；模型文本、`CONFIRMED`、`approved=true` 或普通工作流审批都不能创建正式工单。用户只能通过带 Session 与 CSRF 的独立确认接口批准，后端随后使用数据库中的草案快照重新校验身份、owner、场景白名单、业务范围、订单归属、版本、指纹和有效期，并在一个 MySQL 事务中创建一次工单、更新动作和写审计。

工作流沿用现有 `WorkflowNodeExecutor`、条件边、审批与 SSE：运行从创建开始持久化，节点/连线/等待/终态都有检查点；审批恢复使用运行时定义快照，当前身份和授权仍实时校验。服务重启后，等待审批记录可查询并继续；不安全的执行中检查点只收口为 `interrupted`，不会盲目重放。

## 实际修改与设计取舍

### 1. 工作流可靠性

关键实现位于：

- `WorkflowRun`：增加 owner、定义版本、下一节点、检查点和可解释状态信息。
- `WorkflowRepository`：运行创建即写入；主记录、步骤与状态 JSON 同步更新；审批领取使用条件更新；启动时恢复安全审批检查点并中断其他遗留 `running`。
- `WorkflowService`：节点前后、连线、等待和终态持久化；审批使用运行快照；恢复前重新检查当前工作流启用状态、actor、owner 和授权；领取后异常按“决定是否已持久化”恢复等待或中断。
- `WorkflowNodeExecutor`：把 Java record/List 统一转换为 JSON 兼容 Map/List，使 `result.orderStatus` 在实时执行和 JSON 恢复后语义一致；只读工具统一调用业务授权服务；知识/模型节点使用实际超时。
- `WorkflowController`：运行、流式运行、恢复、列表和单运行查询都显式传入可信 actor；新增单运行状态查询。
- 前端 `WorkflowDesigner.vue`：SSE 结束后按 runId 回查服务端状态，区分“本次连接结束”和“业务仍在 waiting”，刷新后恢复审批与终态。

新增稳定 code 为 `commerce-after-sale-reliable-demo` 的演示流程：

```text
input → queryOrder → condition(result.orderStatus) → approval → output
```

流程使用现有种子订单 `ORD-20260918-001`，不依赖外部模型；按 code 缺失时插入，不覆盖用户编辑过的定义。

设计取舍：本轮只保证明确安全检查点的单机恢复。执行中进程退出不自动重放模型或外部调用；它会留下可解释的 `interrupted` 终态。这比“猜测是否执行过然后自动重试”更适合当前没有分布式幂等协议的范围。

### 2. PendingAction 与正式写入边界

新增/修改的主要后端组件：

- `PendingActionController`、`PendingActionService`、`PendingActionRepository`。
- `PendingAction`、`PendingActionParameters`、`PendingActionStatus`、`PendingActionView`。
- `PendingActionFailureRecorder`、`TicketCreationFaultInjector`。
- `TrustedToolContext`、`BusinessAuthorizationService`、`BusinessScopeRepository`。
- `CustomerServiceTools`、`ScenarioToolPolicy`、`KnowledgeChatService`、`ConversationController`、`AuthInterceptor` 和 `AuthService`。

模型工具由“直接创建工单”改为 `prepareServiceTicket`：

- 工具 Schema 只有客户、订单、分类、优先级和摘要，没有 `confirmation`、`approved`、actor、conversationId 或 requestId。
- actor、会话、稳定 requestId、场景和政策证据状态由后端 `ToolContext` 传入，不接受模型或工作流输入覆盖。
- 普通工作流 tool 节点继续只读；工作流 `approved=true` 只决定流程分支，不授予工单写权限。
- 摘要在服务端拒绝密码、验证码、完整证件号、完整银行卡号、访问令牌和密钥等明确敏感模式。

稳定动作标识采用：

```text
stableRequestKey = SHA-256(actorId + conversation/run + requestId + actionType)
actionId = UUIDv3("pending-action:" + stableRequestKey)
```

因此 HTTP 重试、模型重复调用和同一消息重生成会命中同一动作。相同参数返回原动作；参数变化保留 actionId、递增 version、更新指纹，并把前后规范化参数和指纹保存到修订审计。旧卡携带的 expectedVersion 随即失效。已结束动作不会被同一请求改成另一张新草案；确需再次建单必须发起新的、用户可见的业务请求。

确认事务的顺序是：

```text
SELECT PendingAction FOR UPDATE
→ 重新校验当前身份/会话/场景/业务范围/订单归属/版本/有效期/指纹
→ INSERT ai_service_ticket(action_id UNIQUE)
→ UPDATE ai_pending_action = SUCCEEDED + 结果快照
→ INSERT ai_pending_action_audit(CONFIRMED)
→ COMMIT
```

重复确认已成功动作直接返回原 ticket 结果。数据库唯一键是最后一道并发保护，不以 Java 锁或按钮禁用代替。如果工单插入后发生异常，整个执行事务回滚；回滚完成后另一个短事务只记录不含内部异常细节的 `PENDING + lastError + EXECUTION_FAILED`，用户可刷新后安全重试。这里故意不使用终态 `FAILED`，因为该故障没有留下正式工单且允许同版本重试。

### 3. 身份、资源授权与 CSRF

- 会话和新工作流运行绑定 owner；会话列表/读取/附件/普通消息/流式消息/重生成、工作流运行和 PendingAction 都在服务端检查归属。
- 历史 owner 为空的数据只允许 `ADMIN` 走保守兼容，不会自动归给第一个访问者；规则同时位于 controller 和 service 边界。
- `AuthenticatedUser.id` 是客服操作员身份，不当作 customerNo。普通操作员必须在 `ai_operator_business_scope` 中拥有目标客户/业务主体范围；演示管理员的全范围能力由 `ADMIN_ALL_BUSINESS_SCOPE=true` 显式开启。
- `AuthService.authenticate()` 每个请求按 Session 中的用户 ID 回查 `ai_user`，使用当前角色；账号被禁用或删除会立即撤销会话，降权不会继续沿用登录时的 ADMIN。
- 登录下发 Session 与 CSRF Cookie；所有非安全 HTTP 方法都要求 `X-CSRF-Token`。确认接口不是模型工具。
- 默认移除会记录完整 Prompt/响应的 `SimpleLoggerAdvisor`，Spring AI 日志为 INFO。

### 4. RAG 与业务事实边界

- 未选择知识库、知识库无 READY 文档或向量空召回时，移除政策资格工具，并提示模型拒绝政策性断言；获授权的订单/客户/工单只读工具仍可返回数据库事实。
- `checkAfterSalesEligibility` 只接受退款、退货、换货。当前没有实现 claim-level 的“具体条款 → 具体订单”规则绑定，因此即使有召回也保守返回需要人工复核，绝不把“任意命中片段”当成自动通过依据。
- 引用保留 documentId、chunkId、文件名、页码和片段；回答编号按实际去重后的片段顺序生成。

### 5. 前端闭环

- 新增 `PendingActionCard.vue`，展示客户/业务主体、订单、分类、优先级、摘要、版本、有效期、动作 ID、等待/成功/取消/过期及可重试失败信息。
- `CustomerService.vue` 和 `SupportWidget.vue` 都会在打开/切换/刷新会话时查询动作列表；有 PENDING 动作时轮询；消息完成后再次以 API 状态对账。
- 确认/取消只提交 expectedVersion。接口异常后立即 GET 动作详情，覆盖“后端提交成功但响应丢失”。成功文案只读取 API 返回的正式 ticket，不根据模型自然语言判断。
- `api.js` 对认证写请求附加 CSRF，消息和重生成使用稳定 requestId。

### 6. Docker 演示交付

- 根目录 `compose.yaml`：MySQL 8.0.45、Spring Boot 后端、Nginx/Vue Web；当前默认项目名为 `ai-chatbot`。下文旧栈名称是当时的验收事实。
- `ai-chatbot-service/Dockerfile`：Maven/Temurin 17 多阶段构建，JRE 17 非 root 运行。
- `ai-chatbot-web/Dockerfile` 与 `docker/nginx.conf`：Node 22 构建、Nginx SPA fallback、同源 `/api` 反向代理、SSE 禁用代理缓冲。
- `.env.example` 只有占位值，真实 `.env` 被 Git 忽略。数据库、Root 与管理员密码为 Compose 必填项。
- 所有宿主端口默认绑定 `127.0.0.1`；本机 HTTP 部署的 Cookie Secure 为 false，HTTPS 部署可通过 `SERVICE_COOKIE_SECURE=true` 开启。

本次操作没有执行 `docker down`、`rm`、`rmi`、`network rm` 或 `prune`，没有删除用户已有镜像、容器、网络或卷。验证过程使用不同 Compose 项目名只新增资源。

## 接口说明

### PendingAction

| 方法 | 路径 | 请求/作用 |
| --- | --- | --- |
| GET | `/api/v1/pending-actions?conversationId={id}` | 查询当前 actor 在指定会话的动作，顺便收口过期状态 |
| GET | `/api/v1/pending-actions/{actionId}` | 查询动作及正式工单结果 |
| POST | `/api/v1/pending-actions/{actionId}/confirm` | 请求体仅 `{"expectedVersion":1}`；使用服务端参数快照确认 |
| POST | `/api/v1/pending-actions/{actionId}/cancel` | 请求体仅 `{"expectedVersion":1}`；取消后不能确认 |

两个 POST 都需要 Session Cookie 与 `X-CSRF-Token`。业务参数不能在确认请求中覆盖。

### 工作流补充

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/v1/workflows/{workflowId}/runs/{runId}` | SSE 中断、刷新或重启后查询数据库权威状态 |
| POST | `/api/v1/workflows/{workflowId}/runs/{runId}/resume` | 独立审批；只影响当次工作流运行，不授权工单写入 |

## 数据迁移说明

应用启动时执行非破坏式、幂等增量初始化：

| 对象 | 关键字段/约束 |
| --- | --- |
| `ai_pending_action` | `action_id` PK；`stable_request_key` UNIQUE；actor、conversation/run、scenario、action type、参数 JSON、指纹、version、status、expires、ticket/result、last_error、审计时间 |
| `ai_pending_action_audit` | action、事件、actor、version、from/to status、detail JSON、created_at |
| `ai_operator_business_scope` | `(operator_user_id, business_subject_no)` 联合主键 |
| `ai_service_ticket` | 新增可空 `action_id`、`created_by_user_id`；`uk_service_ticket_action_id` 唯一索引 |
| `ai_workflow_run_state` | 复用 state JSON 保存 owner、definitionVersion、workflowSnapshot、nextNodeId、checkpoint、statusMessage、输入/输出、已走边和恢复上下文 |

没有删除表、重建库或迁移现有运行数据。生产环境建议把这些启动期 DDL 固化为 Flyway/Liquibase 版本迁移；本轮按项目现有 JDBC 初始化风格保持最小改动。

## 验收结果

### 自动回归

| 验证 | 命令 | 实际结果 |
| --- | --- | --- |
| 后端全量 | `cd ai-chatbot-service; mvn test` | BUILD SUCCESS；73 tests，0 failures，0 errors，2 skipped。跳过的是需显式环境变量的真实 MySQL IT |
| 真实 MySQL 并发/回滚 | `MYSQL_IT_ENABLED=true ... mvn -Dtest=PendingActionMySqlTest test` | MySQL 8 隔离 schema；2 tests，0 failures/errors/skips |
| 前端行为 | `cd ai-chatbot-web; npm test` | 10/10 通过 |
| 前端类型 | `npm run type-check` | 退出码 0 |
| 前端生产构建 | `npm run build` | 成功；Vite 报告部分 Mermaid/ELK chunk 大于 500 kB 的非阻断警告 |

真实 MySQL 用例使用两个并发线程确认同一 action，断言两次返回同一 ticket、数据库只有一张按 actionId 查询到的工单和一条 CONFIRMED 审计；随后模拟“工单 INSERT 后、动作成功前”抛错，断言 ticket/action-success/audit 同时回滚、动作记录可刷新看到安全重试提示，再次确认成功。

### 关键行为覆盖

| 行为 | 证据与结果 |
| --- | --- |
| record 嵌套字段、JSON 恢复条件一致 | `WorkflowNodeExecutorTest`、`WorkflowServiceTest`、`WorkflowReliableDemoIntegrationTest` 通过 |
| 伪造 `CONFIRMED` / `approved` | 工具回调行为测试把额外字段传入，结果仍是 PENDING 草案且业务仓储零交互；工作流输入 `approved=true` 仍停在 approval |
| 重复准备、参数变化、旧版本 | 同 requestId 返回同 action；参数修订 version++ 并审计前后快照；旧 expectedVersion 被拒绝 |
| 正常/重复/并发确认、响应丢失重试 | H2 控制流、真实 MySQL 两线程和 UI/API 实操均返回同一工单 |
| 换用户、越权订单、订单客户不匹配 | 服务测试均拒绝；会话 owner/controller 测试通过 |
| 过期、取消后确认 | 返回 EXPIRED/CANCELLED，不创建工单 |
| CSRF | 拦截器测试通过；Docker 实操无 Header 的认证 POST 返回 403 |
| 账号撤销/降权 | AuthService 测试证明 Session 使用当前 DB 角色，禁用后立即失效且重新启用不会复活旧 Session |
| 事务故障 | 真实 MySQL 验证回滚、可见的 retryable lastError/audit 与安全重试 |
| 等待期编辑定义 | 集成测试和 Docker 实操都证明恢复使用运行快照 |
| 启动恢复 | H2 仓储启动恢复测试区分 approval-claimed → waiting 与其他 running → interrupted，并覆盖主运行存在但 state JSON 行缺失时的 upsert；另有真实 Docker 后端重启实操 |
| RAG 无证据 | 空召回、无 READY 文档两种测试都只向模型提供 queryOrder，移除资格工具并传 `policyEvidenceAvailable=false` |
| 缺编号与工具失败 | 提示构造测试验证缺少订单/客户编号时只能追问、禁止猜测及禁止用操作员 userId 代替客户号；真实模型会话验证只追问且未生成 PendingAction；订单工具数据源异常验证 failed trace、错误继续上抛且 trace session 被清理 |
| 外部节点/生成失败与 SSE 状态 | 工作流知识节点超时、工作流节点失败、聊天模型生成失败、SSE 顺序/中断、runId 回查和前端刷新恢复测试通过 |

### 实机演示证据

在 Docker Compose 环境中完成过以下操作：

- **原 release 验收栈（历史记录，现源码已提交）**：以项目 `heima-ai-chatbot-a1-release` 启动在 `127.0.0.1:15175/18082/13318`，当时 mysql/backend/web 三个容器均为 healthy；此前所有栈继续保留，未删除或替换。真实模型会话 `d114bf26-f236-4a1e-8783-e6fbcdbb8e8b` 准备动作 `80e6e54f-5e05-3dbd-ac96-233a42df970c`；确认得到工单 `TK10FDF2EC37D940978DA8`，重复确认返回相同编号。数据库复核为 `action_status=SUCCEEDED`、`ticket_rows=1`、`confirmed_audits=1`。
- **最终 release 栈的固定工作流**：运行 `20ee898a-ab7c-4d17-a19d-2f235b9ad8c6` 从订单查询、嵌套状态条件进入 waiting，经独立审批接口恢复并完成为 `terminal-completed`；共 5 个步骤，持久化输出中的订单状态为“已完成”。
- **最终 release 栈的缺字段实模验证**：会话 `6a7532cf-16e1-4614-a342-70ef7d0f21b4` 未提供客户号和订单号；模型明确拒绝猜测或代填并逐项追问，随后独立 `GET /pending-actions` 返回 `[]`，没有准备或执行任何动作。

- **前一验收栈（保留的重启证据）**：项目 `heima-ai-chatbot-a1-demo` 位于 `127.0.0.1:15174/18081/13317`。运行 `e00c7734-3a9c-4403-80c3-673326f11fb8` 在 backend 重启后从 waiting 恢复并完成为 `terminal-completed`；会话 `2ee89bda-9653-4f21-a205-53e31df8ad8` 的动作确认得到工单 `TK8740CEF4A1E74A139072`，无 CSRF Header 的重复确认返回 HTTP 403。

- **前序验证栈（仅保留历史证据，不是当前入口）**：示例工作流运行 `20cbaa23-b8af-430f-a613-3d124cfbb29a` 跨 backend 重启后完成；真实模型会话 `b62e83b3-f046-4eac-a97a-fdfcd88f30f3` 的动作 `45aa06fb-3969-3be9-94f0-e2787e829134` 确认得到工单 `TK8137ECB725484C63A89A`。当时为遵守“只新增、不删除”约束而保留该栈；它当前已不在 Docker Desktop 项目列表中。
- UI 已实际打开并操作会话中心草案卡与工作流审批。客服悬浮窗也已接入相同卡片与状态恢复，并由 3 条前端接线测试覆盖；流式 `complete` 只承载回答、引用和追溯，PendingAction 始终在流结束后通过独立 GET 查询对账。

上述 ID 只属于隔离演示数据，不代表生产业务。没有触发退款、通知或第三方写操作。

## 5 分钟演示脚本

下述“现场准备新草案”环节要求 `AI_ENABLED=true` 且模型密钥有效。当前重新部署的本机栈为 `AI_ENABLED=false`，只能现场回查旧动作 `80e6e54f-5e05-3dbd-ac96-233a42df970c` 的成功工单并运行不依赖模型的种子工作流；不能把这条历史动作说成本次新创建。

### 0:00～0:40：启动与边界

1. 展示 `docker compose -f compose.yaml ps`，确认 `ai-chatbot` 项目下 mysql/backend/web 都 healthy；当前本机使用原数据卷，配置了 `SERVICE_EXISTING_VOLUMES=true`。
2. 打开 `http://localhost:15175`；这也是全新环境的固定默认 Web 端口。登录后说明：浏览器只访问同源 Web，密码和模型 Key 不在前端；本演示端口只绑定 localhost。当前本机 AI 关闭，可展示旧动作回查和离线工作流；实时模型对话需配置有效密钥并启用 AI。
3. 点出本轮是单 Agent Tool Calling + 固定工作流编排，不宣称多 Agent 或分布式 exactly-once。

### 0:40～2:20：正常售后闭环

1. 在“会话中心”选择电商售后场景，输入：

   > 客户 CUST-10002 的订单 ORD-20260918-001 已签收但设备无法开机。先查订单；如果没有足够政策资料，不要判断退换资格。请按“其他 / 中优先级”准备工单草案，但不要替我确认。

2. 展示工具查询到的真实订单事实、政策证据不足提示，以及结构化草案卡。
3. 强调模型即使声称“已确认”也没有写权限；点击“确认创建”。
4. 展示 API 返回的 ticketNo、团队和状态；刷新浏览器，卡片从数据库恢复为同一工单。
5. 再点刷新状态或重复调用确认，展示仍返回同一 ticket，而不是新建。

### 2:20～3:20：拒绝与越权

1. 发起另一条可见请求准备草案，点击“取消”，再尝试确认，展示状态保持 CANCELLED 且没有正式工单。
2. 在开发者工具或预备脚本中演示三项之一：去掉 `X-CSRF-Token` 得到 403；换用户读取 action 得到 404；用旧 expectedVersion 得到 409。
3. 解释登录 userId 是操作员，不是 customerNo；普通操作员必须有显式业务主体范围。

### 3:20～4:35：工作流快照与重启恢复

1. 打开“售后订单审批演示”，输入种子订单并运行，展示 queryOrder → 条件 true → waiting approval 的节点与连线。
2. 刷新页面，展示 GET run 状态仍为 waiting。可提前准备一个“等待时修改流程定义”的运行，说明恢复仍采用该运行的定义版本。
3. 只重启本演示新建的 backend 容器，不执行 down/delete；由于登录 Session 位于 JVM 内存，重启后先重新登录，再刷新页面查询同一 waiting 记录。
4. 点击批准，展示原快照继续到 success，终态、步骤和分支轨迹落库。

### 4:35～5:00：数据库证据与收口

1. 展示同一 actionId 对应一张 `ai_service_ticket`、一个 SUCCEEDED action、一条 CONFIRMED 审计。
2. 展示测试报告中的真实 MySQL 并发和事务故障两项。
3. 主动说明限制：单实例、会话/附件 JSON 存储、政策结论保持人工复核、没有 B/C 的评测或 MCP。

## 可用于简历的表述（仅已验证能力）

- 在 Spring Boot/Spring AI 客服项目中落地服务端 PendingAction 审批边界，将模型写工具改为草案准备；通过可信 ToolContext、会话 owner、业务主体范围、CSRF 和确认时重授权阻止模型文本与提示注入直接写库。
- 基于 MySQL `SELECT ... FOR UPDATE`、稳定请求键和工单 `action_id` 唯一约束实现工单确认幂等；在 MySQL 8 隔离库中验证两线程并发确认、响应丢失重试只返回同一工单，以及插入后故障的事务回滚与安全重试。
- 增强现有工作流引擎的运行时定义快照、节点/连线检查点、审批 CAS 和启动恢复；验证等待期间修改流程、浏览器刷新及后端重启后仍按原运行快照完成，非安全执行中状态不自动重放。
- 在 Vue 3 会话中心与客服悬浮窗实现草案确认卡、取消、轮询和刷新恢复；成功状态只采用 API/数据库 ticket 结果，并覆盖 SSE 中断及响应丢失后的权威状态回查。
- 交付 MySQL、Java 17 后端和 Nginx/Vue 前端的 Docker Compose 本机演示栈；完成 73 项后端回归、10 项前端行为测试、类型检查/生产构建和 2 项真实 MySQL 并发/回滚测试。

## 尚未解决或尚未验证的限制

- 范围是单机/单实例演示。登录 Session 在 JVM 内存中，会话、附件和向量元数据仍有本地 JSON/文件存储；未实现多实例协调、分布式恢复或外部系统 exactly-once。
- 售后资格没有 claim-level 政策规则引擎；当前选择保守人工复核。不能把“检索到了片段”表述为已经证明某条政策适用于该订单。
- PendingAction 的可重试执行失败使用 `PENDING + lastError`，而不是单独的终态 FAILED；这是为了保证事务回滚后仍可同版本重试。内部异常细节只留在服务端异常链路，不返回卡片。
- 完整的提示注入红队集、系统化缺字段多轮模型评测、检索质量指标和效果提升未执行；本轮验证了工具 Schema/行为边界、一次真实模型闭环、一次真实缺编号追问、客服订单工具失败追踪、工作流外部节点/聊天生成失败和确定性回归。未覆盖部分不能写成已通过能力。
- 登录 Session 位于 JVM 内存，backend 重启后需要重新登录；MySQL 中的工作流、PendingAction、工单和审计，以及命名卷中的会话 JSON 不受影响。
- 工作流设计器刷新时自动选择最新 execution。更早的 waiting 运行若被后续运行覆盖，仍可按 runId 经 API 查询和恢复，但当前 UI 没有多运行历史选择器。
- `ORD-20260918-001` 只会在全新/订单表为空的演示库中自动补种；已有非空库运行默认演示前必须确认该订单存在。
- Compose 是 localhost HTTP 演示配置，不是公网方案。公网必须增加 TLS/反向代理、`AUTH_COOKIE_SECURE=true`、企业身份、密钥管理、备份和网络访问控制。
- 前端生产构建仍有 Mermaid/ELK 大 chunk 警告；本轮没有为此做框架升级或大规模拆包。
- Docker 构建中的 npm 依赖审计报告包含既有依赖漏洞；本轮为避免越过 A 的范围没有进行可能破坏兼容性的依赖大版本升级，正式发布前必须单独评估和修复。
- MySQL 隔离测试 schema 和本轮新增 Docker 资源按“禁止删除”要求保留，未自动清理。
