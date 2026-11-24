# 发版流程

这份清单是从实际踩过的坑里总结出来的（v4.3 带病发布、v4.4 关于页版本没更新）。发版前照着走一遍。

## 一、发布节奏（重要）

**不要每修一个 bug 就发一版。** v4.2 → v4.6 在几个小时内连发，每次 226MB，代价是：

- 用户在两百多 MB 的下载里反复消耗
- Release 列表变成"补丁流水账"，看不出重点
- 显得在把半成品往用户手里塞

### 什么时候发

满足**任意一条**才发：

1. 有用户能感知的新功能
2. 有阻塞性 bug 修复（装不上 / 打不开 / 崩溃 / 数据丢失）
3. 安全或隐私问题

### 什么时候不发

- 纯文档、注释、构建脚本改动 → 只提交，不打 tag
- 内部重构、不影响行为的修复 → 攒到下一次
- "顺手又改了个小东西" → 攒着

### 节奏

- **功能版**：间隔至少 3–7 天，让问题暴露出来
- **热修版**：只用于"用户已经装了但用不了"的情况，且只修那一个问题
- 同一批修复攒在一起，一次发完

## 二、发版前检查清单

### 1. 版本号

- [ ] `app/build.gradle.kts` 里 `versionCode` +1、`versionName` 更新
- [ ] 「关于 CodePad」显示的是新版本（已改为读 `PackageManager`，正常不用手改，但要**打开确认一次**）

### 2. 冒烟测试（真机，全部过一遍）

按"新用户第一次用"的顺序走：

- [ ] 冷启动：**不自动弹输入法**、底部终端/Git 可见、没有崩溃
- [ ] 打开文件：点一下就能编辑，语法高亮正常
- [ ] 运行 C：点 ▶ 出输出（走内置 TCC，应当秒出）
- [ ] 调试 C：点行号加断点 → 🐞 → 停在断点行，变量和调用栈有内容
  - 首次调试会解压工具链，确认**调试页显示进度**而不是空白
- [ ] 搜索：搜一个词，结果能点，跳转到正确行
- [ ] 设置：改字号立即生效
- [ ] 跳转到定义：光标停在函数名上，能跳（含跨文件）
- [ ] Git 面板：能初始化、看到改动
- [ ] 网页预览：打开 html，右侧出预览；改一行，预览跟着变

### 3. 构建与校验

```powershell
$env:CODEPAD_TOOLCHAIN = "<工具链目录>"
./build.ps1

(Get-FileHash dist\CodePad-X.Y-arm64.apk -Algorithm SHA256).Hash.ToLower()
(Get-Item dist\CodePad-X.Y-arm64.apk).Length
```

- [ ] 记下 SHA-256 和字节数，等下写进 Release 说明
- [ ] 顺手 `adb install -r` 装一次，确认这个包能装

### 4. 文档

- [ ] README 里的截图是否还和当前界面对得上
- [ ] README 里"已知限制"是否还成立（修掉的就删）
- [ ] 体积/耗时数字是否和 App 里的提示一致

### 5. 提交与 tag

- [ ] `git status` 干净，提交信息写清"这版修了什么"
- [ ] tag 用小写 `vX.Y`
- [ ] tag 指向 `main` 最新提交

### 6. Release

- [ ] Release 说明包含：改了什么、安装要求（arm64 / targetSdk 28）、SHA-256、许可证提示
- [ ] 上传 APK（注意 Windows 下 curl 要加 `--ssl-no-revoke`）

```powershell
curl.exe -sS --ssl-no-revoke -X POST `
  -H "Authorization: Bearer <token>" `
  -H "Content-Type: application/octet-stream" `
  --data-binary "@dist\CodePad-X.Y-arm64.apk" `
  "https://uploads.github.com/repos/<owner>/<repo>/releases/<id>/assets?name=CodePad-X.Y-arm64.apk"
```

### 7. 发布后验证

- [ ] `releases/latest` 指向这一版
- [ ] **附件真的在**——注意 Release 页面的附件列表是异步加载的，主页面 HTML 里看不到文件名：

```powershell
Invoke-WebRequest "https://github.com/<owner>/<repo>/releases/expanded_assets/vX.Y" -UseBasicParsing
```

- [ ] 下载直链返回 200，`Content-Length` 与本地一致
- [ ] 不删旧版本（留作回退）

## 三、已知的坑

| 坑 | 现象 | 处理 |
|---|---|---|
| Release 页面像没有附件 | 主页面 HTML 不含文件名 | 查 `expanded_assets` 接口，别急着重新上传 |
| curl 上传失败 | `schannel: CRYPT_E_NO_REVOCATION_CHECK` | 加 `--ssl-no-revoke` |
| 关于页版本不更新 | 之前硬编码 `CodePad 3.6` | 已改成读 `PackageManager`，仍需发版前打开确认 |
| raw.githubusercontent 内容陈旧 | 推完半天还是旧 README | CDN 缓存，按 commit SHA 取可绕过 |
| tag 大写 | `V4.2` 与文档里的 `v4.2` 不一致 | 统一小写 |

## 四、回滚

出问题时的顺序：

1. 把最新 Release 标为 **pre-release**（`releases/latest` 会自动退回上一个）
2. 在 Release 说明里写清问题和规避方式
3. 修好后发补丁版，不要直接改已发布的附件
