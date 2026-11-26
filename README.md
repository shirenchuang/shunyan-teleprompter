# 顺言提词器

顺言是独立的 Android 悬浮提词器。台本保存在设备上，普通滚动和离线中文跟读均可离线使用。

## 安装和使用

1. 按下方说明构建 APK，再安装到 Android 10 或更新版本的设备。构建包含 arm64-v8a、armeabi-v7a 和 x86_64。
2. 打开内置稿件或新建台本，选择「匀速提词」或「离线跟读」，点「开始提词」。首次使用需要允许显示在其他应用上。AI 跟读需要麦克风权限。
3. 打开相机或其他应用，点悬浮窗里的「播放」。拖标题栏移动窗口，拖右下角调整尺寸。「−」收为可吸边的浮球。
4. 在悬浮窗「设置」的「播放跟读」页也可切换「匀速提词」和「离线跟读」。切换时保留当前读到的位置，回到正文后点「播放」继续。
5. 「背景颜色」作用于整个悬浮窗。顶部按钮栏、设置页和暂停时使用不透明背景。点击「播放」后，正文区和底栏立即按「播放时背景透明度」显示，倒计时和 AI 跟读加载期间也如此。「文字颜色」用于未读文字，「已读颜色」标出已经读过的文字。在「显示设置」中可直接点色块，也可自定义红、绿、蓝三个通道。点标题栏的「返回提词」回到正文。
6. 在提词画面拖动正文任一侧的「↔」把手，向内收窄、向外加宽。正文居中，拖动时实时换行，松手后保存宽度。也可以在「每行字数」里调节。字号、窗口宽度和稿件标点会影响实际换行。
7. 在「设置」中选择自动跟随持机方向，或手动选择 0°、90°、180°、270°。标题栏的「↻」每点一次手动转 90°。旋转作用于提词悬浮窗。
8. 拖动正文浏览台本，点字会暂停并定位到该字。也可以拖动进度条定位。重新点「播放」可从选中位置继续。读稿位置按台本和模式分别保存。「设置」里也可以调整倒计时、速度、循环与镜像，或回到开头和切换台本。

离线跟读在播放时采集麦克风输入，用内置中文模型解码并定位正文，暂停时释放麦克风。相机录像等应用可能获得麦克风优先权。系统静音跟读输入时，窗口会显示「麦克风不可用」，此时可以使用匀速播放。[Android 音频输入规则](https://developer.android.com/media/platform/sharing-audio-input)

## 从源码构建

需要 JDK 17 或 21、Android SDK 平台 35 和可用的 Android Build Tools。用 `ANDROID_HOME` 或本机 `local.properties` 指定 SDK。在项目根目录执行以下命令。

```sh
./gradlew :app:assembleDebug
```

输出位于 `app/build/outputs/apk/debug/app-debug.apk`。Gradle 包装器固定为 8.9，首次执行会下载构建工具和 Kotlin 标准库。Sherpa-ONNX 二进制和模型已经放在项目中。

## 验证

Android 测试覆盖同音匹配、重复转写、列宽、点字定位和旋转补偿。测试进程不访问麦克风或扬声器。

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
