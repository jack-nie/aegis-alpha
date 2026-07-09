# Memory

记录之前任务中发现的关键洞察、最佳实践和已知陷阱。
Claude 每次新任务开始前应读取此文件。

## Architecture Decisions

- 项目已从 MarketMind 完成重命名为 Aegis Alpha（包名、变量名、CI 配置均已更新）
- 自定义 Token 认证（HmacSHA256），不是标准 JWT — TokenService 是核心不可轻改
- 委托 token（typ=delegation）：`TokenService.issueServiceDelegation`；positions/summary 可读；用户 login token 无 typ 字段；`POST /_backend/workflow-runs/{runId}/delegated-token` 签发短时 portfolio:read
- orchestrator `ToolBackendClient`：Authorization 优先级 contextvar override > extra headers > AEGIS_ALPHA_DELEGATED_TOKEN > node_execution_token
- 前端是 catch-all routing，所有页面逻辑集中在 App.jsx
- orchestrator 已从 Node.js 重写为 Python FastAPI + LangGraph，分层架构
- orchestrator 已启用 LangGraph checkpointer，workflow 支持断点恢复
- orchestrator 已启用 LangGraph store（InMemoryStore），支持跨线程记忆
- orchestrator 已集成 ToolNode，Agent 可主动调用后端 API 查数据（行情/财务/新闻/组合）
- orchestrator Store 使用 SQLite 持久化（PersistentStore），支持 TTL 过期策略
- orchestrator Checkpointing 使用 SqliteSaver，workflow 可跨重启恢复
- ToolNode 已集成，6 个 @tool 封装后端 API（行情/财务/新闻/组合）
- SSE 流式支持 tool_call 事件类型，区分普通节点和工具调用

## Lessons Learned

- 前端 deep link：`/runs/:runId` → 运行中心详情；`/recommendations/:workflowRunId` → 推荐详情；由 `normalizePathname` + `extractDeepLink*` 解析，打开详情走 `navigate`/`pushState`，关闭回到列表 path
- 修改 auth 模块前必须先理解 TokenService 的 Base64+HMAC 签名机制
- orchestrator 的 mock 模式是重要的离线测试手段，新功能必须兼容
- MyBatis mapper XML 和接口必须同步修改
- 环境变量使用 AEGIS_ALPHA\_\* 前缀
- PersistentStore 的 TTL 是通过 _expires_at 字段实现的，读取时自动过期检查
- SqliteSaver 和 InMemoryStore 需要在 dependencies.py 中初始化，main.py 的 lifespan 中 close
- ticker insights TTL 30 分钟，用户偏好和 workflow pattern 永不过期（TTL=0）

## Tooling

- Prettier 3.8.3 + ESLint 9 已配置，根目录 package.json
- Claude Code hooks 已配置（.claude/settings.json）：编辑 JS/JSX 后自动 prettier + eslint
- 格式规范：双引号、分号、尾逗号、120 字符行宽、2 空格缩进
- 前端 Tailwind CSS 类名排序由 prettier-plugin-tailwindcss 自动处理

## Active Issues

- CI workflow 已重命名为 aegis-ci.yml
- 根目录存在多个临时 fix\_\*.py 脚本，应定期清理
