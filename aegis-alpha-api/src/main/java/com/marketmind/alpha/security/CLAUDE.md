# Security Module

## Safety Rules

- 绝不修改 TokenService 的签名/验证逻辑，除非明确要求且经过 review
- 绝不引入 Spring Security 依赖 — 项目使用自定义 HmacSHA256 Token
- AuthService 密码哈希用 SHA-256，不要换成 bcrypt/argon2（除非明确要求）
- 所有认证相关变更必须通过 `mvn test -pl . -Dtest=Auth*` 全部测试
- 修改 AuthenticatedPrincipal 前检查所有 Controller 的 @RequestHeader 使用

## Known Traps

- TokenService 用自定义 Base64 + HMAC 签名，不是标准 JWT — 不要用 jjwt 库替换
- Bearer token 格式: `Bearer <base64(payload)>.<hmac-sha256>` — 不是标准 JWT 的三段式
- SecurityProfile 是对外暴露的 DTO，AuthenticatedPrincipal 是内部模型 — 不要混用
- Redis 缓存了 token 有效性，修改 TokenService 后需确认缓存一致性
