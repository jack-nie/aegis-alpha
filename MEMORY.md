# Memory

记录之前任务中发现的关键洞察、最佳实践和已知陷阱。
Claude 每次新任务开始前应读取此文件。

## Architecture Decisions

- 项目从 MarketMind 重命名为 Aegis Alpha，但代码中包名/变量名/CI 配置仍是旧名
- 自定义 Token 认证（HmacSHA256），不是标准 JWT — TokenService 是核心不可轻改
- 前端是 catch-all routing，所有页面逻辑集中在 App.jsx
- orchestrator 是单文件架构（server.mjs），高耦合

## Lessons Learned

- 修改 auth 模块前必须先理解 TokenService 的 Base64+HMAC 签名机制
- orchestrator 的 mock 模式是重要的离线测试手段，新功能必须兼容
- MyBatis mapper XML 和接口必须同步修改
- 环境变量使用 MARKETMIND\_\* 前缀（历史原因）

## Tooling

- Prettier 3.8.3 + ESLint 9 已配置，根目录 package.json
- Claude Code hooks 已配置（.claude/settings.json）：编辑 JS/JSX 后自动 prettier + eslint
- 格式规范：双引号、分号、尾逗号、120 字符行宽、2 空格缩进
- 前端 Tailwind CSS 类名排序由 prettier-plugin-tailwindcss 自动处理

## Active Issues

- CI workflow 引用旧目录名，可能需要更新
- 根目录存在多个临时 fix\_\*.py 脚本，应定期清理
