# Audit Module

## Safety Rules

- AuditAspect 拦截所有 @RestController 调用 — 修改切点表达式前必须理解影响范围
- AuditEvent 的 principal 字段可为 null（未认证请求）— 查询时注意 null check
- 审计日志写入是异步的，不要依赖写入顺序做业务逻辑
