# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/)，所有值得关注的变更记录在此文件中。

## [0.1.0] - 2026-07-24

### Added

- React 可视化方案设计端、Node.js BFF 和 Spring Boot 代码生成引擎。
- Canonical Plan Contract v2、持久化审核凭证和不可绕过的审核门禁。
- 基于 `deliverableIds` 的子方案拆分、依赖 DAG 和类型闭包校验。
- Part A / Part B bundle SHA-256 与 HMAC-SHA256 一致性校验。
- BladeX 参考项目扫描、隔离代码生成、跨文件校验和确定性修复。
- Windows 一键启动、MySQL 自动初始化、状态恢复和执行时间线。
- GitHub Actions 前端/BFF、后端和 MySQL Schema 三项质量门禁。

### Fixed

- 统一 Markdown 内容哈希的 LF/CRLF 规则，消除 Windows 与 Linux 的跨语言 fixture 漂移。
- 使用 Node.js 直接加载 TSX 测试，消除 shell glob 和 Windows `.cmd` 启动差异。

### Known limitations

- `SOURCE_GATE` 是依赖无关源码门禁，不能替代目标私有 Maven 依赖环境中的真实编译。
- 默认仅面向本机或可信网络运行；公网部署必须增加统一认证和可信网络边界。
- 修复前基于 CRLF 内容生成的旧审核哈希需要重新审核后再传输。

[0.1.0]: https://github.com/CheeseKirby/Bladex/releases/tag/v0.1.0
