# 验证与发布检查

## 1. 本地质量门禁

### Part A

```powershell
cd ai-designer
npm ci
npm run typecheck
npm test
npm run build
```

### Part B

```powershell
cd ai-developer
mvn -B clean test
```

### MySQL Schema

在干净的 MySQL 8 数据库执行：

```powershell
mysql -h127.0.0.1 -P3306 -uroot -p < ai-developer/sql/init.sql
```

仓库的 `.github/workflows/ci.yml` 会在 `main` 的 push 和 pull request 上执行同类检查。

## 2. 生成链路验收

一次完整回放至少确认：

- 主方案和所有子方案拥有当前有效的审核凭证；
- 契约哈希、bundle 哈希和签名一致；
- 所有必需交付物恰好覆盖一次，依赖 DAG 无环；
- `qualityErrorCount=0` 且 `qualityWarningCount=0`；
- `validation-report.json` 为空数组；
- manifest、数据库和磁盘文件数量、路径、字节数及 SHA-256 一致；
- 生成目录位于 `ai-generated-modules/<receptionId>`，没有写入真实参考项目。

## 3. 编译状态解释

| 状态 | 含义 |
|---|---|
| `PASSED_SOURCE_GATE_DEPENDENCIES_UNVERIFIED` | 依赖无关源码门禁通过，但没有在目标私有依赖环境执行完整编译 |
| `FAILED_SOURCE_GATE` | 生成源码存在门禁错误，不应交付 |
| 真实 Java/Maven 编译通过 | 在目标 JDK、框架版本和私有仓库中完成的最终编译验收 |

源码门禁不能代替真实依赖编译。准备交付时，应在一次性参考项目副本中覆盖生成文件并执行 Java/Maven 编译，禁止直接修改真实参考项目。

## 4. 发布前检查

- `git diff --check` 无空白错误；
- 工作区不包含 `.env`、日志、输出目录、Playwright 缓存或验证副本；
- 对待提交内容执行 token、密码、私钥和机器绝对路径扫描；
- Part A/Part B 测试、前端生产构建和 MySQL schema 检查全部通过；
- 确认提交目标和远程仓库，推送后检查 GitHub Actions。

## 5. 当前阶段边界

当前版本定位为初步可用版本。参考项目适配依赖现有索引摘要和符号信息；对于外部接口抽象方法、依赖 FQCN 存在性和复杂链式方法调用，最终交付仍必须以真实编译结果为准。