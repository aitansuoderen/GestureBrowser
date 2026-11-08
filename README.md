# GestureBrowser 手势浏览器

一个为手机看视频、下载而生的 Android 浏览器。基于系统 WebView，用 Kotlin 编写，界面和交互参考 UC / Firefox，重点做了**全屏视频手势**、**网页内置播放器**和**多线程下载**。

- 当前版本：**3.6**（versionCode 26）
- 系统要求：Android 10（API 29）及以上
- 语言：Kotlin，无第三方 UI 框架，界面全部代码绘制

## 功能

### 浏览
- UC 风格主页：常用网站一行三个的圆角胶囊，长按编辑 / 删除 / 添加；网页可"添加到主页"
- 搜索栏贴在底栏上方，点开进入搜索页（搜索历史 + 输入法上方网址快捷栏）；搜索引擎可在 Bing / Google 之间切换
- 多标签页，全屏两列卡片式标签管理（带缩略图）
- 历史记录、书签、密码管理（Android Keystore AES-GCM 加密，查看需解锁设备，网页自动填充）
- 长按网页向下拖动刷新；长按链接弹出菜单（新标签页 / 后台打开等）
- 透明玻璃风格的底栏与搜索栏，Android 12+ 实时模糊
- 可设为系统默认浏览器

### 视频
- **网页内置播放器**（`assets/player.js`）：视频开始播放后接管画面，盖上自己的控制层，点击不会再触发网站的跳转广告；点播放后 2 秒内拦截外站跳转和弹窗
- **全屏播放器**（`VideoGestureOverlay.kt`）：返回、标题、时间电量、锁定、下载、倍速、画面比例、静音、旋转
- 手势：

| 手势 | 作用 |
|---|---|
| 左半屏上下滑 | 亮度 |
| 右半屏上下滑 | 音量 |
| 左右滑 / 拖动进度条 | 快进快退，进度条上方实时显示目标画面预览，松手才跳转 |
| 双击左侧 / 右侧 | 后退 / 快进 10 秒 |
| 双击中间 | 播放 / 暂停 |
| 长按 | 2 倍速，松手恢复 |
| 单击 | 显示 / 隐藏控制栏 |

- 拖动预览：m3u8 用网站自带或内置 hls.js 低画质预取，直链用 canvas 截图，跨域时回退到原生 `MediaMetadataRetriever`

### 下载
- **自研分段下载引擎**（`SegEngine.kt`）：
  - 动态分段 + 工作窃取，速度稳定后自动加连接（最多 32），增益不足即停止
  - 卡住 6 秒自动从断点重连，指数退避；403 / 429 自动降连接并按域名记忆 24 小时
  - 断点续传前校验 ETag / Last-Modified，服务器不支持分段时自动降级单线程
- **普通文件下载**（`FileDownloader.kt`）：直接写入 `下载/GestureBrowser`，最多 3 个任务并行，失败自动重试，App 被杀后重启自动续传
- **视频下载**（`StreamDownloader.kt`）：支持直链、HLS（m3u8）、DASH，暂停 / 继续，取消时立即清理临时分片
- **下载管理页**（`DownloadsPage.kt`）：仿 UC 下载管理，顶部显示总网速和任务数，每个任务有进度条、网速、剩余时间、线程数；长按可重新下载、更新链接、查看详情、删除（可选同时删除文件）

## 项目结构

```
app/src/main/
├── java/com/example/gesturebrowser/
│   ├── MainActivity.kt          主界面、标签页、菜单、下载页数据
│   ├── HomeView.kt              主页
│   ├── SearchOverlay.kt         搜索页
│   ├── TabTrayView.kt           标签页管理
│   ├── VideoGestureOverlay.kt   全屏播放器与手势
│   ├── SegEngine.kt             分段下载引擎
│   ├── FileDownloader.kt        普通文件下载
│   ├── StreamDownloader.kt      视频（直链 / HLS / DASH）下载
│   ├── DownloadsPage.kt         下载管理页
│   ├── PullRefresh.kt           长按下拉刷新
│   ├── PasswordStore.kt         密码保存与自动填充
│   ├── HistoryStore.kt / BookmarkStore.kt / ShortcutStore.kt
│   ├── IconLoader.kt            网站图标
│   ├── GlassLayout.kt / Motion.kt / Ui.kt   玻璃效果、动效、通用界面
└── assets/
    ├── player.js                网页内置播放器
    └── hls.light.min.js         hls.js（预览用）
```

## 编译

1. 安装 Android Studio（或 JDK 17 + Android SDK 35），打开本项目，等待 Gradle 同步。
2. 调试运行：手机打开 USB 调试，点 Run。
3. 打正式包：
   ```bash
   gradle assembleRelease
   ```
   输出在 `app/build/outputs/apk/release/app-release.apk`。

### 签名
正式包默认用调试密钥签名。想用自己的密钥，在项目根目录新建 `keystore.properties`（已在 `.gitignore` 中，不会上传）：

```properties
storeFile=/path/to/your.keystore
storePassword=******
keyAlias=******
keyPassword=******
```

注意：换了签名的 APK 无法覆盖安装旧签名的版本，需要先卸载。

## 说明
- 下载引擎的设计参考了 FluxDown 和 Ghost Downloader 的公开文档，代码为 Kotlin 独立实现，未复制其源码。
- `hls.light.min.js` 来自 [hls.js](https://github.com/video-dev/hls.js)，Apache-2.0 许可。
- 为了性能，WebView 的安全浏览检查默认关闭。
- 本项目是个人使用的浏览器，尚未在大量机型上测试，欢迎提 Issue。
