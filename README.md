# 默词

一个本地运行的 Android 英语默写单词 MVP。界面以中文显示释义，用户输入英文单词；词表、复习等级和作答记录保存在设备本地。

## v0.3 词库管理与学习统计

- 新增“学习、词库、统计”主页，可手动添加、编辑、搜索、筛选、排序、批量删除单词并查看单词作答历史。
- 新增错词专项练习和可设置的每日目标。今日学习量按练习过的不同单词数计算。
- 新增掌握情况分布、每日学习量与正确率图表、学习日历。
- 支持导出单词 TXT 及完整 JSON 备份；恢复前校验并预览，确认后整体覆盖学习数据。
- 当前版本：`versionName 0.3`、`versionCode 3`。此版本延续 v0.2 的应用更新方式，正式 APK 必须使用与现有版本相同的发布证书，方可覆盖更新。

详细需求与功能说明见 [`docs/feature-expansion-report.md`](docs/feature-expansion-report.md)。

## 开始使用

1. 用 Android Studio 打开项目并同步 Gradle。
2. 运行 `app` 配置，安装到 Android 8.0（API 26）或更新版本的设备。
3. 在首页选择“导入 TXT”，选择 UTF-8 编码的文本文件。每行填写一个英文单词；空行会跳过，重复词按大小写不敏感的方式合并。
4. 导入后逐个点击词条填写中文释义。释义保存后，单词即可进入待复习列表。
5. 开始默写，输入英文并检查答案。答错的词会在本轮队尾再次出现，直到答对。

## 本地词库与学习数据

- “学习”“词库”“统计”三个页面分别提供到期复习、错词专项、手动管理与学习趋势。单词支持添加、改名、删除、搜索筛选、排序和批量删除；编辑会保留原有复习进度和作答记录。
- 单词详情显示复习安排、正确率和作答历史。重置进度会重新开始间隔复习，但保留累计学习历史；删除时会同时删除该词的作答记录。
- 每日目标默认 20 个当天练习过的不同单词，可在设置中调整。每日学习量、正确率趋势和学习日历仅根据本机作答记录生成。
- 词库页可导出 UTF-8 单词 TXT；设置页支持导出完整 JSON 备份与预览后覆盖恢复。恢复不会覆盖每日目标或更新设置。
- 完整的中文功能需求、数据口径和验收说明见 [`docs/feature-expansion-report.md`](docs/feature-expansion-report.md)。

本机已有 `.test-tools/avd/MociUpdate35.avd` 虚拟设备时，可运行以下命令打开模拟器，默认启用 CPU 硬件虚拟化加速和本机 GPU 驱动：

```powershell
./scripts/Start-Emulator.ps1
```

脚本使用 `ANDROID_HOME` 或当前用户的默认 Android SDK，启动前检查加速器可用性；启动日志保存在 `.test-tools/`。

## v0.2 客户端更新

- 首页右上角“设置”中可以手动检查更新，并开关“启动时检查更新”（默认开启）。每次应用冷启动自动检查一次；无新版本或网络失败时不弹窗，手动检查会显示结果。
- 更新检查读取 `lin04-24/Word-App` 的 GitHub 最新正式 Release；支持 `v0.2`、`0.2.0` 和 SemVer 预发布排序。发现更新后展示更新日志、版本和文件大小，点击“下载更新”后才下载。
- 安装包由 Android 下载服务在后台下载，客户端显示进度，支持取消、重新下载和应用重启后继续查看任务。无需打开浏览器或在公共下载目录寻找 APK。
- 下载后检查 SHA-256、包名、签名和版本。前台下载弹窗保持打开时，校验通过后进入系统安装界面；后台完成时返回客户端点击“立即安装”。首次更新需在系统页面允许默词安装应用，取消授权或安装后可再次继续。
- 只访问 GitHub API、GitHub Release 资产及其 HTTPS 下载服务，不上传词库或作答记录。关闭启动检查后，仅手动检查及用户确认下载时联网；无需 GitHub Token 或公共存储访问权限。
- v0.1 没有客户端更新模块，需要先从 GitHub Release 安装一次 v0.2，之后即可在客户端获取后续版本。覆盖安装保留词库和复习记录，发布时必须沿用相同包名及签名证书。

## 正式构建与后续发布

当前版本：`versionName 0.3`、`versionCode 3`，包名 `com.example.moci`，最低 Android 8.0。

在本机配置以下环境变量，再执行 `./gradlew.bat :app:assembleRelease`：

- `WORD_APP_RELEASE_STORE_FILE`：发布 keystore 的路径
- `WORD_APP_RELEASE_STORE_PASSWORD`：密钥库密码
- `WORD_APP_RELEASE_KEY_ALIAS`：发布密钥别名
- `WORD_APP_RELEASE_KEY_PASSWORD`：私钥密码

签名材料仅在本机配置，不提交 keystore 或密码。每次发布递增 `versionCode` 和版本号，创建正式标签及 Release，上传单个通用 APK `Word-App-v<版本>.apk` 与 `SHA256SUMS`。客户端优先使用 GitHub 资产的 SHA-256 digest；缺失时读取同一 Release 的校验文件，校验信息缺失时不安装。

## 复习规则

成功回忆后按 12 小时、1 天、2 天、4 天、7 天、15 天递增复习间隔；之后每升一级增加 3 天。答错会降低一级，并在当前练习轮次稍后重试。单词保存当前等级和下次复习时间，每次作答另行记录正误、耗时和时间戳；耗时目前不参与间隔计算。

## 构建和测试

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
./gradlew.bat compileDebugAndroidTestKotlin
./gradlew.bat lintDebug
```

设备端 Room 持久化与首页界面测试位于 `app/src/androidTest`，可在连接 Android 设备或启动模拟器后通过 Android Studio 运行。

更新下载与系统安装测试使用本机回环 HTTP 服务及独立签名夹具，不发布测试 Release。先运行 `./scripts/Prepare-UpdateFixtures.ps1`，再运行 `./gradlew.bat connectedDebugAndroidTest`；脚本需要 SDK 35、build-tools 36.0.0 和已有的 Android debug keystore。生成的测试 APK、测试 keystore 和 `.test-tools` 不纳入 Git。仅 debug 构建允许回环地址的 HTTP，正式构建始终限制明文网络。
