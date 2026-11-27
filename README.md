# 顺言提词器

顺言是独立的 Android 悬浮提词器。台本保存在设备上，普通滚动和离线中文跟读均可离线使用。

## 安装和使用

1. 从 [GitHub Releases](https://github.com/shirenchuang/shunyan-teleprompter/releases/latest) 下载 APK，安装到 Android 10 或更新版本的设备。安装包包含 arm64-v8a、armeabi-v7a 和 x86_64。也可以按下方说明从源码构建。
2. 点首页右上角的「新建台本」，输入或粘贴正文后保存。悬浮窗内也可以点顶部稿名，在台本列表中选择「新建台本」。选择「匀速提词」或「离线跟读」，点「预览」查看实际悬浮窗和显示设置，或点「开始提词」直接打开悬浮窗。首次使用需要允许显示在其他应用上。AI 跟读需要麦克风权限。
3. 打开相机或其他应用，点悬浮窗里的「播放」。播放时菜单和按钮会隐藏，轻点稿窗即可暂停并恢复控件。暂停时可从标题栏任意位置拖动窗口，轻点按钮可执行对应操作。拖右下角调整尺寸，点「−」收为可吸边的浮球。
4. 暂停时点「设置」，可实时调整稿窗效果。竖屏时预览和设置上下同宽，横屏时左侧预览、右侧设置。点「完成」松开这两块区域，回到正常稿窗。「播放跟读」页可切换「匀速提词」和「离线跟读」。切换时保留当前读到的位置。
5. 默认使用黑底白字，背景不透明度为 100%。点「恢复黑底白字」可恢复这套配色。预览和播放时，背景跟随稿列宽度。不透明度可设为 0–100%，低于 100% 时稿字会自动加深色或浅色描边。字号可设为 8–64 sp。文字、已读文字和背景均可选色。点「完成」或「返回提词」可收起设置。
6. 暂停时可拖动正文任一侧的细竖线，向内收窄、向外加宽。正文居中，拖动时实时换行，松手后保存宽度。也可以在「每行字数」里调节。字号、窗口宽度和稿件标点会影响实际换行。
7. 在「设置」中选择自动跟随持机方向，或手动选择 0°、90°、180°、270°。标题栏的「↻」每点一次手动转 90°。旋转作用于提词悬浮窗。
8. 拖动正文浏览台本，点字会暂停并定位到该字。也可以拖动进度条定位。重新点「播放」可从选中位置继续。读稿位置按台本和模式分别保存。「设置」里也可以调整倒计时、速度、循环与镜像，或回到开头和切换台本。
9. 暂停时上下拖动正文旁的「↕」把手，可以移动阅读线。把阅读线放在中间或偏下，读过的内容会在上方多保留几行。「显示设置」里也可调节「阅读线位置」。调整会自动保存。

离线跟读在播放时采集麦克风输入，用内置中文模型解码并定位正文，暂停时释放麦克风。相机录像等应用可能获得麦克风优先权。系统静音跟读输入时，窗口会显示「麦克风不可用」，此时可以使用匀速播放。[Android 音频输入规则](https://developer.android.com/media/platform/sharing-audio-input)

## 从源码构建

需要 JDK 17 或 21、Android SDK 平台 35 和可用的 Android Build Tools。用 `ANDROID_HOME` 或本机 `local.properties` 指定 SDK。在项目根目录执行以下命令。

```sh
./gradlew :app:assembleDebug
```

输出位于 `app/build/outputs/apk/debug/app-debug.apk`。Gradle 包装器固定为 8.9，首次执行会下载构建工具和 Kotlin 标准库。Sherpa-ONNX 二进制和模型已经放在项目中。

## 验证

Android 测试覆盖同音匹配、重复转写、列宽、点字定位、阅读线、设置面板避让和旋转补偿。测试进程不访问麦克风或扬声器。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.shunyan.prompter.test/com.shunyan.prompter.ReplayInstrumentation
```

如需同时测试离线解码，可在构建测试 APK 前，把朗读「今天我们测试悬浮提词器。请跟着文字慢慢朗读。」的中文单声道、16 kHz、16 位 PCM WAV 放在 `app/src/androidTest/assets/replay.wav`。

## 语音组件

APK 内置 Sherpa-ONNX 1.12.20 和官方发布的中文模型 `sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23`。项目中的 Java 界面、悬浮窗口和稿件匹配器为独立实现。许可证收录在 `app/src/main/assets/licenses`。[Sherpa-ONNX 发布页](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.20) · [中文模型与许可](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23)

## 许可证

项目源码使用 [MIT 许可证](LICENSE)。内置第三方组件使用各自的许可证，随包声明见 [app/src/main/assets/licenses](app/src/main/assets/licenses)。
