# 默词

一个本地运行的 Android 英语默写单词 MVP。界面以中文显示释义，用户输入英文单词；词表、复习等级和作答记录保存在设备本地。

## 开始使用

1. 用 Android Studio 打开项目并同步 Gradle。
2. 运行 `app` 配置，安装到 Android 8.0（API 26）或更新版本的设备。
3. 在首页选择“导入 TXT”，选择 UTF-8 编码的文本文件。每行填写一个英文单词；空行会跳过，重复词按大小写不敏感的方式合并。
4. 导入后逐个点击词条填写中文释义。释义保存后，单词即可进入待复习列表。
5. 开始默写，输入英文并检查答案。答错的词会在本轮队尾再次出现，直到答对。

## 复习规则

成功回忆后按 12 小时、1 天、2 天、4 天、7 天、15 天递增复习间隔；之后每升一级增加 3 天。答错会降低一级，并在当前练习轮次稍后重试。单词保存当前等级和下次复习时间，每次作答另行记录正误、耗时和时间戳；耗时目前不参与间隔计算。

## 构建和测试

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
./gradlew.bat compileDebugAndroidTestKotlin
```

设备端 Room 持久化与首页界面测试位于 `app/src/androidTest`，可在连接 Android 设备或启动模拟器后通过 Android Studio 运行。
