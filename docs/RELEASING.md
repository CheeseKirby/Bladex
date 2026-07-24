# 发布与主分支保护

## 1. 发布前质量门禁

在干净工作区执行：

```powershell
cd ai-designer
npm ci
npm run typecheck
npm test
npm run build
npm run audit

cd ../ai-developer
mvn -B clean test
```

MySQL 8 Schema 还必须在干净数据库中成功执行 `ai-developer/sql/init.sql`。GitHub Actions 中对应的必需检查为：

- `frontend-bff`
- `backend`
- `mysql-schema`

## 2. `main` 分支保护

仓库管理员应在 GitHub 中为 `main` 启用以下规则：

1. 必须通过 Pull Request 合并；
2. 合并前要求上述三项状态检查成功；
3. 要求分支在合并前更新到最新 `main`；
4. 要求所有审查对话已解决；
5. 禁止 force push 和删除 `main`；
6. 不允许管理员绕过发布门禁，除非处置安全事件。

分支保护属于 GitHub 仓库设置，不会由仓库内文件自动启用。

## 3. 版本规则

项目使用 Semantic Versioning。前端 `package.json`、Maven Parent 和模块 Parent 版本必须保持一致。首个公开版本为 `0.1.0`。

## 4. 创建 Release

1. 确认受保护的 `main` 最新 CI 全绿；
2. 确认 `CHANGELOG.md` 中存在对应版本和发布日期；
3. 从该提交创建带注释 tag：`v0.1.0`；
4. 推送 tag；
5. 在 GitHub 创建同名 Release，并使用 `CHANGELOG.md` 对应章节作为版本说明；
6. 发布后重新确认 Release 指向的提交与绿色 CI 提交一致。

示例命令：

```powershell
git tag -a v0.1.0 -m "Release v0.1.0"
git push origin v0.1.0
```

创建 tag 和 GitHub Release 前必须由仓库维护者确认版本号、许可证和发布提交。

## 5. 哈希协议兼容性

从 `0.1.0` 起，Markdown 内容在计算 SHA-256 前统一将 CRLF/CR 转换为 LF。升级前保存的旧审核凭证如果基于 CRLF 内容生成，应重新审核并重新传输，不应绕过哈希或签名校验。
