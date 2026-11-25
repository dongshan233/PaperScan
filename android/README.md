# PaperScan — 试卷边缘检测 + 自动拍照 + 透视矫正（Android）

Kotlin + CameraX + OpenCV 的端侧实现，算法是仓库根目录 Python 原型的直接移植。

## 识别原理（怎么认出一张纸）

识别走「**双通道检测 + 时序决策**」，全部在设备端完成：

1. **主通道：OpenCV 经典边缘检测**（每帧，3–8ms）。
   金字塔背景除法做光照归一化 → Canny(35,105) → 形态学闭运算 → 找轮廓 → 四点凸四边形近似 → 边缘支撑评分挑出纸张候选 → 沿四边法向搜梯度脊线、直线拟合求交做**亚像素精修**。
2. **兜底通道：DocAligner fastvit_t8 角点模型**（ONNX，onnxruntime-android 1.20 CPU 推理，约 150ms，每 3 帧一次）。
   白纸压白桌等低对比度场景边缘检测返回 0 候选时启用：模型输出 4×128×128 角点热力图，峰值 + 加权质心解码四个角点（不足 3 个则放弃，3 个用平行四边形约束补全）。
3. **时序决策：`DocTracker`**。
   对四边形做 EMA 平滑防抖，综合六项评分（边缘支撑率/面积/稳定度/IMU 角速度/清晰度/几何规整度），连续 5 帧过 0.72 分且陀螺仪角速度达标 → 进入 700ms 倒计时（逐帧复核）→ 触发自动拍摄。
4. **成像**：触发时回全分辨率 Still 图重新精修四边形 → `getPerspectiveTransform` + `warpPerspective` 矫正到 A4 画布 → 去阴影/锐化（三种色彩模式）→ 保存 JPEG。

| 环节 | 用到的库 / API |
|---|---|
| 相机取流与拍照 | CameraX 1.3.4（`ImageAnalysis` RGBA_8888 分析流 + `ImageCapture` 高清拍照，EXIF 方向校正） |
| 检测 / 精修 / 矫正 / 增强 | OpenCV 4.9.0（Maven `org.opencv:opencv`，纯 Java API，无 NDK 自编译） |
| 角点模型推理 | onnxruntime-android 1.20.0（CPU EP，4 线程） |
| 手持稳定判定 | Android `Sensor.TYPE_GYROSCOPE`（`MotionGate` 模长 EMA，喂给评分 IMU 项） |

```
预览 → 每帧检测纸张四边形 → 时序跟踪/平滑 → 评分 → 倒计时 → 自动拍照
     → 全分辨率直线精修 → 透视变换 → 去阴影 → 保存 JPEG
     → 停止识别（解绑相机）→ 斜纸扶正动画 → 全屏显示扫描件
```

识别成功一次就进入结果页：不再分析新帧、不再触发拍摄，点「重新扫描」才回到取景框。

## 构建 / 安装

```bash
cd android
gradle :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`（约 58 MB，主要是 OpenCV 原生库）。

扫描件保存到 `Android/data/com.dsh.scanner/files/scans/`。

## 工程配置

- `applicationId = com.dsh.scanner`，minSdk 24 / targetSdk 35，Java/Kotlin 目标 17，ViewBinding 开启。
- `ndk.abiFilters` 只保留 `arm64-v8a`（APK 从 141MB 降到 58MB）；release 构建用 debug 签名、不混淆，便于直接安装做性能测试。
- 依赖：CameraX 1.3.4、`org.opencv:opencv:4.9.0`（Maven，首次构建需联网）、onnxruntime-android 1.20.0。
- 权限仅 `CAMERA`（uses-feature `android.hardware.camera.any`）；`MainActivity` 锁定 `sensorLandscape`。

## 架构（MVVM）

```
┌──────────────── ui/（View，app）────────────────────────────┐
│ MainActivity       ViewBinding、权限、渲染 StateFlow、转发点击 │
│ CameraController   CameraX 绑定 / 拍照 / EXIF 方向 / 手电筒    │
│ CameraFrames       ImageProxy → ScanFrame 适配（唯一粘合点）  │
│ OverlayView        四边形、角点、评分、倒计时（纯绘制）        │
│ RectifyView        斜纸扶正动画 + 扫描件（纯绘制）             │
└──────────▲──────────────────────────────────┬──────────────┘
           │ StateFlow<ScanUiState>            │ 用户意图
           │ Channel<ScanEvent>                ▼
┌──────────┴──────── viewmodel/（app，薄壳）──────────────────┐
│ MainViewModel      只做映射/转发；识别决策在 ScanCoordinator  │
│ ScanUiState        module 快照 + 宿主状态（手电筒）合并       │
│ UiText             ScanStatus → strings.xml 文案映射          │
└──────────▲──────────────────────────────────┬──────────────┘
           │ ScanSnapshot / ScanEvent          │ 动作转发
┌──────────┴──────── :scanner-core（独立识别 module）─────────┐
│ Scanner            门面：帧分析、模型加载、全分辨率处理        │
│ ScanCoordinator    编排：帧驱动状态机、拍照互锁、结果交接      │
│ DocDetector        经典边缘检测 + 亚像素精修                  │
│ DocTracker         EMA 平滑、评分、倒计时                     │
│ DocModelDetector   DocAligner 角点模型兜底                    │
│ ScanCapture        透视矫正 + 去阴影 + JPEG                   │
│ ScanFrame/Config   帧抽象与全部可配置项                      │
└─────────────────────────────────────────────────────────────┘
```

依赖方向始终是 `ui → viewmodel → scanner-core`：

- **View** 不持有检测器/跟踪器，也不拼状态文案；点击只调用 ViewModel 方法。
- **ViewModel** 是薄壳：识别域决策（何时收帧、何时触发拍照、失败回退）都在
  module 的 `ScanCoordinator` 里；ViewModel 只做 `ScanStatus → UiText` 映射和转发。
- **scanner-core** 不依赖 CameraX、不引用宿主资源、不申请权限；相机帧经
  `ScanFrame` 抽象进入，拍照以 `ScanEvent.CapturePhoto` 事件委托宿主执行。
- 状态文案全部在 `res/values/strings.xml`，由 `UiText` 携带参数，View 负责解析。

### 复用 scanner-core 到其他 MVVM 项目

1. `include(":scanner-core")`（或发布 AAR 后 `implementation("com.dsh.scanner:scanner-core:x.y.z")`）。
2. 构造 `Scanner(context, ScannerConfig(...))`（长生命周期持有）+ `ScanCoordinator(viewModelScope, scanner)`。
3. 订阅 `coordinator.state` / `coordinator.events`，实现 `ScanEvent.CapturePhoto` 的拍照回调，
   相机帧用 `CameraFrames.analyzer {}`（或自行实现 `ScanFrame` 构造）喂给 `coordinator.onCameraFrame`。
4. OpenCV 初始化内置于 module：宿主在自定义 `Application.onCreate` 中调用
   `ScannerCore.init()`（幂等；漏调时 `Scanner` 构造也会兜底）。宿主不再需要直接依赖 OpenCV。
5. 宿主负责 CAMERA 权限与 ABI 过滤（`ndk.abiFilters`）；
   模型与自检资产已随 module 的 assets 合并进 APK。

## 代码结构

| 文件 | 职责 |
|---|---|
| `ui/MainActivity.kt` | View：ViewBinding、权限、观察状态、渲染与事件分发 |
| `ui/CameraController.kt` | CameraX 绑定、拍照、EXIF 旋转、手电筒、分析流暂停/恢复；分析流 `RGBA_8888` 输出 + `KEEP_ONLY_LATEST`，拍照缓存 `cacheDir/shot.jpg` 用后即删，`MINIMIZE_LATENCY` 模式 |
| `ui/CameraFrames.kt` | ImageProxy → `ScanFrame` 适配器（识别核心与 CameraX 解耦的唯一粘合点） |
| `viewmodel/MainViewModel.kt` | 薄壳 ViewModel：转发用户动作、合并宿主状态、托管 Scanner 生命周期 |
| `viewmodel/ScanUiState.kt` | `ScanSnapshot`/`ScanStatus` → 可渲染状态与 `strings.xml` 文案的映射 |
| `viewmodel/UiText.kt` | 字符串资源/原始文本，View 端 `resolve()` |
| `scanner-core/core/Scanner.kt` | 门面：`ScanFrame` 帧分析、模型加载、全分辨率处理、自检，串行化全部操作 |
| `scanner-core/core/ScanCoordinator.kt` | 编排层：帧驱动状态机、拍照互锁（capturing/frozen/acceptingFrames）、结果交接、调试入口 |
| `scanner-core/core/DocDetector.kt` | 光照归一化 → Canny → 轮廓 → 四点凸四边形 → 边缘支撑评分 → 亚像素直线精修 |
| `scanner-core/core/DocTracker.kt` | EMA 平滑、评分（支撑/面积/稳定度/IMU/清晰度/规整度）、倒计时状态机 |
| `scanner-core/core/ScanCapture.kt` | 全分辨率精修 → 透视变换到 A4（竖版 1240×1754 / 横版 1754×1240）→ 去阴影 + 锐化 → JPEG（默认质量 92，`scan_<时间戳>.jpg`） |
| `scanner-core/core/ScanStatus.kt` | 对外契约：`ScanSnapshot`、`ScanStatus`（语义枚举）、`ScanEvent`、`OverlayFrame` |
| `scanner-core/core/ScannerConfig.kt` | 全部可配置项：Tracker/Detector/Model/Output，默认值为真机调参结果 |
| `scanner-core/core/ScanFrame.kt` | 帧输入抽象（RGBA 字节流 / Bitmap），module 不依赖 CameraX |
| `scanner-core/core/MotionSource.kt` | 手持稳定度来源接口 + 硬件陀螺仪默认实现 |
| `scanner-core/core/ScanModels.kt` | 数据模型：`FrameResult`、`ModelLoadResult`、`SelfTestResult` |
| `scanner-core/core/Geometry.kt` | `Quad` 几何工具：角点排序、面积、矩形度等 |
| `ui/OverlayView.kt` | 四边形、角点、评分条、倒计时环、状态文案 |
| `ui/RectifyView.kt` | 结果页：把检测到的斜四边形插值到正视矩形做透视动画，再叠化到扫描件 |
| `scanner-core/core/ImageOps.kt` | RGBA 字节→Mat、金字塔背景、Laplacian 方差、Bitmap 互转 |

## 关键参数

`DocTracker` 构造参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `scoreOn` | 0.72 | 综合分阈值 |
| `stableFrames` | 5 | 连续过线帧数才进入倒计时 |
| `countdownMs` | 700 | 倒计时时长，期间掉线即中止 |
| `cooldownMs` | 1500 | 拍完冷却，避免连拍 |
| `imuGate` | 25 | 陀螺角速度上限（°/s） |

评分公式（`DocTracker.update()` 内）：

```
raw = 0.26·边缘支撑率 + 0.18·面积 + 0.22·稳定度 + 0.10·IMU + 0.16·清晰度 + 0.08·几何规整度
scoreEma = 0.45·上一帧EMA + 0.55·raw
```

惩罚项：丢帧时 raw×0.6；面积 <0.15 或支撑率 <0.35 时 raw×0.35。

跟踪细节：EMA 平滑系数 α=0.22（角速度 >30°/s 时取 0.5 加快收敛）；相邻帧四边形位移超过 0.30×对角线判定为跳变；连续丢帧 >3 清空跟踪；倒计时期间掉线按 3×dt 回退计数。状态机为 `SEARCH → TRACKING → LOCKED → COUNTDOWN`。

`DocDetector` 构造参数：`lowContrast`（默认 false，合并宽松 Canny(8,24)，白桌白纸用）、`minAreaRatio`（默认 0.15）、`minSupport`（默认 0.40）。候选打分 `0.55·支撑率 + 0.45·min(面积比/0.55, 1)`，全图框（面积比 >0.92）与贴画框（margin <0.01）额外降权；检测到候选后用 `refine()`（band=6）做亚像素直线精修。

## 真机验证（本机实测）

设备：MediaTek W888（Android），USB 调试。

| 项 | 结果 |
|---|---|
| 编译 | ✅ `BUILD SUCCESSFUL` |
| 安装运行 | ✅ 无崩溃，相机绑定正常 |
| 实时性能 | **7 ms/帧**（640×480 检测，含跟踪与评分） |
| 自检（内置测试图） | ✅ 支撑率 1.00、面积 0.446，矫正输出正确 |
| 横屏输出 | ✅ analysis 640×480、still 3264×2448、矫正件 1754×1240（横版） |
| 自动触发 | ✅ 虚拟相机模式：评分 0.99 → 倒计时 → 自动拍摄 → 保存 |
| CameraX 拍照链路 | ✅ 真机拍照 → 解码 → 精修 → 透视 → 保存 |
| MVVM 重构回归 | ✅ 自检 / 虚拟模式 / 强制拍照 / 自动拍照 / 手动拍照 / 重新扫描 |

## 调试入口（Intent extra）

```bash
# 用内置图片跑一遍 检测→精修→透视→保存
adb shell am start -n com.dsh.scanner/.ui.MainActivity --ez selftest true

# 虚拟相机：把内置图片当视频流喂进实时循环，验证自动触发
adb shell am start -n com.dsh.scanner/.ui.MainActivity --ez virtual true

# 不依赖检测，用中心 80% 的框走一遍真实 CameraX 拍照链路
adb shell am start -n com.dsh.scanner/.ui.MainActivity --ez forcecapture true
```


## 识别成功后的结果页

识别成功（自动触发或手动「拍照」）后，`MainViewModel.showResult()` 做三件事：

1. 置 `acceptingFrames = false`，后续帧不再检测/触发拍摄；
2. 把 `previewActive` 置为 `false`——View 观察到状态变化后 `CameraController.unbind()`，
   预览和分析流一起解绑；
3. 裁剪动画源图（长边 ~1400px），通过 `Channel<ScanEvent>` 发出
   `ShowResult(原图, 精修四边形, 扫描件)`；`MainActivity` 收到后调用
   `RectifyView.play(...)` 播放动画并全屏显示结果。

动画分两段（`RectifyView`，总长 1250ms，`DecelerateInterpolator(1.9)`）：

- **0.08–0.70**：四个角点在屏幕坐标里从检测位置插值到目标 A4 矩形，每帧用
  `Matrix.setPolyToPoly` 把原图映射到当前四边形、裁掉四边形以外的部分——
  视觉上就是那张斜着的纸被慢慢扶正；背景原图同步压暗。
- **0.70–1.00**：交叉叠化到去阴影后的扫描件。

动画结束后底部出现「重新扫描」，点击才 `MainViewModel.onRescan()`：
`ScanEngine.reset()` 清空跟踪器，状态切回取景，View 重新绑定相机。

## 角点模型（低对比度兜底）

白纸压在白桌上时经典检测器返回 0 个候选（实测 `contours=150 fourPoint=0`），此时自动降级到
[DocAligner](https://github.com/DocsaidLab/DocAligner) 的 **fastvit_t8** 角点模型：

| 项 | 值 |
|---|---|
| 模型 | DocAligner fastvit_t8（ONNX，Apache-2.0） |
| 体积 | 13.2 MB（`assets/docaligner_fastvit_t8.onnx`） |
| 输入 | 256×256 BGR，/255，**无 mean/std 归一化** |
| 输出 | 4×128×128 角点热力图 |
| 解码 | 峰值 + 加权质心（±3 窗口）→ 映射回原图 |
| 角点置信度阈值 | 0.15（少于 3 个角点直接返回 null） |
| 运行库 | onnxruntime-android 1.20（CPU EP，4 线程；NNAPI 实测更慢已弃用） |

### 集成要点

1. **ROI 放大**：有上一帧框时，按框外扩 25% 裁剪成正方形再送入模型，让页面填满 256 输入——置信度从 0.3 提到 0.8+。
2. **3 点补全**：模型偶尔只给出 3 个角点，用平行四边形约束 `p_i = p_{i+1} + p_{i+3} - p_{i+2}` 补第 4 点，再交给全分辨率直线精修。
3. **缓存复用**：模型 150ms，由 `ScanEngine` 每 3 帧调度一次，中间帧复用上次结果（`model-cached`，最多复用 6 帧）；ROI 缓存最多沿用 9 帧。
4. **降级顺序**：`edge`（3–8ms）→ `model`（150ms）→ 无。

### 实测（MediaTek W888）

| 场景 | 经典 CV | + 模型 |
|---|---|---|
| 白桌白纸 | 0 候选 | **锁定，评分 0.91，自动拍摄成功** |
| 木桌 | 锁定 | 不变（走 edge，不跑模型） |

![模型兜底：白底白纸场景锁定并自动拍摄](docs/model_capture.png)

模型推理 150–160ms；APK 从 141MB 降到 **58MB**（`abiFilters` 只保留 arm64-v8a）。


### 性能：release 构建是关键

同一个模型、同一台设备，debug 和 release 差了 4 倍——开销不在推理，在 debug 构建的 Kotlin/JIT：

| 指标 | debug | release |
|---|---|---|
| 单次模型调用 | 583–632 ms | **151–178 ms** |
| 其中 ORT 推理 | 145 ms | 145 ms |
| 每帧平均 | 199–303 ms（~4 fps） | **52–75 ms（~19 fps）** |
| 角点 | 2–4/4，抖动大 | **4/4 稳定，0.83–0.90** |

所以性能测试一律用 release 构建，debug 的数字没有参考价值。

### int8 量化：试过，否决了

| 方案 | 体积 | x86 推理 | 设备推理 | 精度（白纸场景峰值） |
|---|---|---|---|---|
| fp32 | 13.2 MB | 11.2 ms | 145 ms | 0.83–0.90 ✅ |
| 动态 int8 | 3.6 MB | 222 ms ❌ | — | 0.00 ❌ |
| 静态 QDQ int8 | 3.9 MB | 39 ms ❌ | 130 ms | 0.03–0.18 ❌ |

- 动态量化对卷积网络会插入低效的 DynamicQuantizeLinear，慢 20 倍且输出归零。
- 静态 QDQ 体积降到 30%，但精度崩了：低对比度场景本来余量就小，量化误差直接吃掉了。
- 唯一收益是体积（-9 MB），速度几乎没变（145→130 ms）。

结论：这个模型和场景不适合 int8。要提速应该换更小的模型或降低调用频率，而不是量化。

### 已知问题

- **NNAPI 反而更慢**（628ms vs 147ms），已改回 CPU EP。
- 角点偶发只有 2–3 个（另一角置信度 ~0.05），靠补全 + 精修兜住，但框会轻微外扩。
- 帧率约 19 fps（release 构建，模型每 3 帧一次），适合“端稳后自动拍”的节奏，不适合高速连拍。
- 下一步：换更小的模型或降低模型调用频率；int8 已验证不可行（见上）。


## 输出：透视矫正，不是裁剪

`ScanCapture.process()` 做的是**四点透视变换**，不是矩形裁剪：

```
原图四个角点 → getPerspectiveTransform → warpPerspective
             → A4 画布（竖版 1240×1754 / 横版 1754×1240）
```

所以斜拍、梯形、边缘不平行都会被拉正；输出尺寸跟随纸张朝向。

### 色彩模式

底部“色彩”按钮循环三种：

| 模式 | 处理 |
|---|---|
| 原彩 | 只做透视变换，不调色 |
| 增强 | LAB 空间只压平 L 通道（去阴影/光照不均），保留 a/b 色度，轻微锐化 |
| 黑白（默认） | 转灰度 → 背景除法 → 锐化（传统扫描件观感） |

默认走黑白（`ScanUiState.filter` 与 `ScanCapture.process()` 的 `filter` 默认值两处保持一致）；点按「色彩」按钮按 **原彩 → 增强 → 黑白** 循环（`Filter.next()`）。

## 已知限制

- **低对比度场景**：白纸压在白色桌面/白色笔记本上时，边界只有 0–5 个灰阶，检测器返回空（与 Python 原型一致）。产品上应引导用户换深色背景，或开启 `lowContrast`。
- **OpenCV 依赖**：使用 Maven 的 `org.opencv:opencv:4.9.0`，首次构建需联网下载。
- **横屏**：Activity 锁定横屏（`sensorLandscape`），预览用 `fitCenter` 显示完整 4:3 画面（左右留黑边），HUD 用同样的缩放置信映射。
- **输出比例**：矫正后的尺寸跟随纸张朝向——横版试卷输出 1754×1240，竖版输出 1240×1754，不会被拉伸。
- **性能**：7ms 是分析流 640×480 的实测结果；代码里只设了 4:3 的 `ResolutionSelector`（`RATIO_4_3_FALLBACK_AUTO_STRATEGY`），分辨率由相机决定，若换 720p 需调整策略并复测。

## 与 Python 原型的对应关系

| Python | Kotlin |
|---|---|
| `pipeline.detect()` | `DocDetector.detect()` |
| `pipeline.refine_quad_lines()` | `DocDetector.refine()` |
| `pipeline.Tracker.step()` | `DocTracker.update()` |
| `capture.capture_scan()` | `ScanCapture.process()` |
| `viz.draw_hud()` | `OverlayView` |
