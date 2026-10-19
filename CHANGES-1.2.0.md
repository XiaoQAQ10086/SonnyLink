# 1.2.0 改版说明

> 本文件描述本仓库相对上游 [XiaoQAQ10086/SonnyLink](https://github.com/XiaoQAQ10086/SonnyLink) 1.1.4 的改动。
> 上游作者：[@XiaoQAQ10086](https://github.com/XiaoQAQ10086)，遵循原项目 MIT 协议（上游版权声明在 LICENSE 中原样保留），
> 派生关系另见 [NOTICE](NOTICE)。与索尼公司无任何关联。

改动目标：把它从「能看」推到「能当监视器用」，再补上拍摄后的归档闭环。

---

## 一、监看辅助（取景页左上角「辅助」胶囊）

| 功能 | 说明 |
|---|---|
| **峰值对焦** | 关 / 低 / 中 / 高 四档，描边色可选 红 / 黄 / 白 / 青。做在 GLES 片元着色器里，在**原始 640×424 上**算梯度，不是放大后再算，所以合焦线不会糊成一片 |
| **构图网格** | 关 / 九宫 / 黄金分割 / 十字+中央框。只画在画面矩形内，不侵入上下黑边 |
| **亮度直方图** | 右上角浮层，每 3 帧统计一次（约 5Hz），纵向用 √ 压缩，避免单峰把其余档位压平 |
| **数码变焦** | 双指捏合 1×–6×，拖动平移，**双击复位**。只裁源矩形，画面在黑边里的位置不变，贴边控件不会跳 |
| **一键截图** | 把当前取景帧的**原始 JPEG 字节**存到 `相册/Pictures/SonnyLink`，不重新压缩 |

峰值档位怎么选：低档只描最强边缘（适合大光圈虚化场景），中档日常用，高档在低对比场景（逆光、雾）才开，否则噪点也会被描出来。

实现要点：峰值、变焦、直方图都不经过 Compose 重组 —— 峰值和变焦在 GLES 着色器里，直方图在取景线程上降采样统计（每 2 像素取 1，约 6.8 万样本），UI 只收 256 个 int。

## 二、NAS 自动归档（启动页「自动归档」）

下载完的照片会进入**持久化队列**，回到有外网的 Wi-Fi 时自动通过 WebDAV 上传到 NAS。

**为什么不是「下载完立刻传」**：手机连相机热点时是相机自己开的 AP，没有任何外网出口，
当场推 NAS 物理上做不到。所以设计成「先落地 + 排队 + 回网补传」。

- 协议：WebDAV（飞牛 / 群晖 / Nextcloud 都自带），填地址 + 账号 + 密码 + 远程目录即可
- 目录结构：`<远程目录>/<上传日期>/原文件名`
- 队列存 `filesDir/backup_queue.json`，杀进程不丢
- 单张最多重试 5 次；本地文件被删掉的话自动移出队列，不算失败
- 「下载后自动归档」关掉时只入队，需要手动点「立即上传」
- 判断「有外网」用的是 `NET_CAPABILITY_VALIDATED`，相机热点永远拿不到这个能力，所以不会误触发

> 队列只在 **App 进程活着**时推进（没有引入 WorkManager / 前台服务）。
> 换网络时会自动触发一次；App 被杀掉后台不会传，下次打开会接着传。

## 三、工程侧

| 项 | 原来 | 现在 |
|---|---|---|
| Gradle | 9.1.0 | **9.8.1** |
| AGP | 9.0.1 | **9.4.1** |
| Kotlin | 2.3.20 | **2.4.21** |
| Compose BOM | 2026.03.01 | 未升（见下） |
| androidx.core | 1.18.0 | 未升（见下） |
| androidx.lifecycle | 2.10.0 | 未升（见下） |
| androidx.navigation3 | 1.0.1 | 未升（见下） |

**为什么 androidx 那一组没升**：Compose BOM 2026.05 之后、core 1.19.0、lifecycle 2.11 都要求
`compileSdk >= 37`，而当前 SDK 仓库里 `platforms;android-37` 还没有稳定版（只有 `platform-37.2_r01.zip`
这类未进 packages 列表的包）。为了不把 `compileSdk` 强行推到半成品平台上，这一组保持在 36 兼容版本；
等 `android-37` 正式可装，把 `compileSdk` 和这几项一起升即可。

升级后 **77 个单元测试全部通过**（core-protocol / core-liveview / core-dlna）。

版本号：`versionCode 7` / `versionName 1.2.0`，包名仍为 `com.sonnyapp`。

## 四、安装

⚠️ **签名与上游发布包不同，覆盖安装会失败**：先卸载手机上原来的 SonnyLink，再装这个。
卸载会清掉 App 内的 Wi-Fi 记录和辅助开关设置（相册里已下载的照片不受影响）。

自签密钥请在本地自行生成（`keystore.properties.example` 是模板，`keystore/` 与
`keystore.properties` 已在 `.gitignore` 中，不会被提交）。**后续升级必须复用同一个密钥。**

```bash
cp keystore.properties.example keystore.properties
# 填入自己的 keystore 信息后
./gradlew :app:assembleRelease
```

## 五、已知限制（相机侧，改不掉）

- 不能传 RAW 原图（DLNA 只转码 JPEG，请求 `.ARW` 返回 406）
- 不能传视频（相机只声明 JPEG）
- 不能触摸对焦、不能拍视频（固件不支持）
- 取景固定 640×424，所以变焦超过约 3× 就是纯放大，没有新信息
- 取景帧率由相机决定（同类机型实测约 15fps），App 侧只能降低抖动，不能提高推流帧率

## 六、构建

环境：JDK 17、Android SDK（`compileSdk 36` + `build-tools 36.0.0`）。
`local.properties` 里写 `sdk.dir=<你的 SDK 路径>`；`gradle.properties` 里的
`org.gradle.java.installations.paths` 指向本机 JDK 17。

```bash
./gradlew :app:assembleDebug                                    # Debug
./gradlew :app:assembleRelease                                  # 发布包（需签名配置）
./gradlew :core-protocol:test :core-liveview:test :core-dlna:test   # 77 个单元测试
```

> Windows 提示：AGP 拒绝在**含非 ASCII 字符的路径**下构建。如果你的工程目录带中文，
> 构建前把源码复制到一个纯英文路径（例如 `D:\build\sonnylink`）再编译。
