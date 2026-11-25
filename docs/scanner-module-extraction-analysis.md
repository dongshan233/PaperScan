# 试卷识别功能抽离独立 Module 可行性分析

> 分析对象：`android/` 工程（PaperScan），17 个 Kotlin 文件，MVVM 架构（ViewModel + StateFlow + View 绑定）。
> 结论先行：**可行，且现状耦合度不高**。识别核心（`model` 包）与 UI 层已经通过 `ScanEngine` 门面 + `FrameResult`/`Snapshot` 数据类解耦得比较干净；主要工作量在 ① 解除 CameraX `ImageProxy` 依赖、② 从 `MainViewModel` 中剥离拍摄编排与文案、③ 把资产（ONNX 模型）与 OpenCV 初始化纳入 module 契约。

---

## 1. 现状代码结构与依赖关系

### 1.1 分层清单

| 层 | 文件 | 职责 | 对外部依赖 |
|---|---|---|---|
| **识别核心（model 包）** | `Geometry.kt` | `Quad` 类型 + 四边形几何工具 | 仅 `android.graphics.PointF`、OpenCV `Point` |
| | `DocDetector.kt` | 光照归一化 → Canny → 轮廓 → 四边形候选 → 亚像素直线精修（`refine`） | OpenCV |
| | `DocModelDetector.kt` | DocAligner fastvit_t8 角点模型兜底（ONNX） | **onnxruntime-android**、`context.assets` |
| | `DocTracker.kt` | EMA 平滑、综合评分、四态状态机（SEARCH/TRACKING/LOCKED/COUNTDOWN）、触发判定 | 无（纯 Kotlin + PointF） |
| | `MotionGate.kt` | 陀螺仪角速度 EMA | **`Context`（SENSOR_SERVICE）** |
| | `ImageOps.kt` | YUV_420_888/Rgba/Bitmap ↔ Mat 转换、清晰度、去阴影背景 | OpenCV、**`androidx.camera.core.ImageProxy`** |
| | `ScanCapture.kt` | 全分辨率精修 → A4 透视矫正 → 滤镜（COLOR/ENHANCE/GRAY）→ JPEG 落盘 | OpenCV、`DocDetector.refine` |
| | `ScanModels.kt` | `FrameResult` / `ModelLoadResult` / `SelfTestResult` | 仅内部类型 |
| | `ScanEngine.kt` | 门面：帧分析编排、模型加载、全分辨率处理、自检 | 以上全部 + `Context`、`ImageProxy` |
| **ViewModel** | `MainViewModel.kt`（22 KB，核心耦合点） | 帧驱动状态机、拍照编排（`capturing`/`frozen`/`acceptingFrames` 互锁）、虚拟模式/自检/强制拍照调试入口、文案映射 | `R.string.*`、model 包全部类型、`ImageAnalysis.Analyzer` |
| | `ScanEvent.kt` / `ScanUiState.kt` / `UiText.kt` | 一次性事件、渲染状态、资源无关文案 | `DocTracker.Snapshot`、`R.string.*` |
| **View（UI 包）** | `MainActivity.kt` | 权限、ViewBinding、状态收集、OpenCV 初始化（`OpenCVLoader.initLocal()`） | viewmodel、OpenCV |
| | `CameraController.kt` | CameraX 绑定/拍照/EXIF 旋转 | CameraX 全家桶 |
| | `OverlayView.kt` / `RectifyView.kt` | 检测框 HUD、扶正动画 | `DocTracker.Snapshot` |

### 1.2 依赖关系要点

```
CameraX(ImageProxy) ──► ScanEngine.analyze ──► ImageOps.yuvTo* ──► DocDetector/DocModelDetector
                                                        │                 │
陀螺仪 MotionGate(ω) ──────────────────────────────────┘                 ▼
                                                  DocTracker（评分/状态机）
                                                        │
                          MainViewModel（编排/互锁）◄───┘
                                                        │
                          CameraController.takePhoto ◄── ScanEvent.CapturePhoto
                                                        │
                          engine.processCapture(bitmap, quad, filter) ──► ScanCapture ──► File
```

关键观察：

1. **识别核心对 UI 无依赖**——`model` 包不 import 任何 view/viewmodel 类，方向是单向的。这是抽离的最大利好。
2. **唯一的反向平台依赖**是 CameraX：`ScanEngine.analyze(ImageProxy)` 和 `ImageOps.yuvToGray/yuvToBgr/imageToRgba` 直接吃 CameraX 帧。
3. **ViewModel 是第二耦合热点**：它同时承担"识别编排业务"（属于待抽离域）和"宿主 UI 状态/文案/调试入口"（属于宿主），`DocTracker.State` → `R.string` 的映射把它钉在了 app 的资源上。

---

## 2. 可抽离功能的边界划分

### 2.1 抽入 module（识别域）

| 内容 | 说明 |
|---|---|
| `model` 包全部 9 个文件 | 检测、跟踪、状态机、矫正、滤镜、几何工具 |
| 帧输入抽象（新增） | 见 §3.1，替代 `ImageProxy` 直连 |
| 拍摄编排器（从 `MainViewModel` 剥离） | `capturing`/`frozen`/`acceptingFrames` 互锁、"shouldCapture → 请求拍照 → 收到 bitmap → 全分辨率处理 → 产出结果"这套状态机 |
| `DocTracker.Snapshot` / `FrameResult` / `ScanCapture.Result` 等数据类型 | 对外契约的一部分 |
| 资产：`docaligner_fastvit_t8.onnx`（12.5 MB） | 放入 module 的 assets，或改为可配置的模型文件路径 |

### 2.2 留在宿主（宿主域）

| 内容 | 理由 |
|---|---|
| `CameraController.kt` | CameraX 绑定、PreviewView、权限后的生命周期管理是宿主展示层的职责 |
| 权限申请（CAMERA） | module 不应申请权限，宿主授权后才调用 `engine.startMotion()/绑定分析流` |
| `OverlayView` / `RectifyView` | HUD 与动画是展示细节，各项目 UI 差异大；module 只输出 `Snapshot` 坐标数据 |
| 文案（`UiText.Res(R.string.*)`） | module 内不引用宿主 `R`；改为枚举/原始文本（§3.3） |
| 结果页、滤镜按钮等交互 | 保留 `Filter` 枚举本身（它是处理参数），按钮循环逻辑留宿主 |
| OpenCV 初始化调用 | 可封装进 module 的 `Scanner.init()`，但初始化时机（Application/首次使用）由宿主决定 |

### 2.3 边界决策点（需产品确认）

- **调试入口**（`startVirtualMode` / `startSelfTest` / `startForceCaptureTest` + `selftest.jpg`）：建议作为 module 的 `@RestrictTo(LIBRARY)` API 或独立 debug 变体保留——它们是唯一的无真机回归手段，抽离后仍要能跑。
- **输出落盘策略**：`ScanCapture` 目前固定写 `getExternalFilesDir/scans`、JPEG 92、A4 1240×1754。抽离时输出目的地必须参数化（见 §5）。

---

## 3. 需解耦的耦合点

### 3.1 CameraX `ImageProxy`（必须解耦，工作量最大）

`ScanEngine.analyze(image: ImageProxy)` 与 `ImageOps` 的三个转换函数直接依赖 `androidx.camera.core`。解法：

```kotlin
// module 对外只认自己的帧描述
sealed interface ScanFrame {
    data class Yuv(val planes: List<Plane>, val width: Int, val height: Int,
                   val rotationDegrees: Int) : ScanFrame   // 宿主从 ImageProxy 摘数据后构造
    data class Bitmap(val bitmap: Bitmap) : ScanFrame      // 虚拟模式/单图路径已有
}
fun Scanner.analyze(frame: ScanFrame): FrameResult?
```

宿主侧提供约 30 行的 `ImageProxy→ScanFrame` 适配（可以随 module 附带一个可选的 `scanner-camerax` 适配器子模块，把适配代码也复用掉）。**所有权契约不变：谁 close `ImageProxy`、谁 recycle `Bitmap` 必须写进接口文档**——现有代码中 `engine.analyze` 的 finally 块负责 `image.close()`，抽离后这一责任移到适配层。

### 3.2 `MainViewModel` 编排逻辑（必须解耦，风险最高）

`MainViewModel` 中属于识别域、应随 module 走的逻辑：

- `onFrame`（帧 → 状态 → 判断 `shouldCapture` → 触发拍照）
- `requestCapture` / `processCapture` / `showResult` / `failCapture` / `prepareStill`
- `capturing`（AtomicBoolean）/ `frozen` / `acceptingFrames` 三把互斥锁及其组合语义
- 模型加载去重（`modelLoaded` / `modelLoading` 自旋等待）

剥离建议：在 module 内提供 `ScanCoordinator`（或直接并入 `Scanner` 门面），以**回调/Flow**对外：

```kotlin
val state: StateFlow<ScanSnapshot>          // 相当于 ScanUiState 的识别子集
val captureRequests: Flow<CaptureRequest>   // 相当于 ScanEvent.CapturePhoto
fun onPhotoCaptured(bitmap: Bitmap)         // 相当于现在的 View 回调
```

风险点：现有互锁语义非常细（如"拍照中丢帧""结果页冻结""失败回退到 previewActive"），`failCapture` 里 `acceptingFrames = !virtualMode && previewActive` 这种与宿主 UI 状态的交叉判断是**真正需要重新设计的耦合点**——抽离时要把"识别域冻结状态"与"宿主预览可见性"分成两个输入，由宿主告知后者。

### 3.3 资源文案（必须解耦，工作量小）

`MainViewModel` / `ScanUiState` 中约 20 处 `UiText.Res(R.string.*)`。module 内改为：
- 状态/错误用**枚举 + 参数**（`ScanStatus.SEARCHING(modelState)`, `ScanStatus.CAPTURE_FAILED(reason)`），宿主自行映射文案；或
- `UiText.Raw` 降级方案（快但损失多语言）。

### 3.4 `Context` 依赖（小解耦）

- `MotionGate(context)`：改为注入 `omegaProvider: () -> Float` 接口，module 默认实现用 `Context`，宿主也可注入伪 IMU（虚拟模式已在用这个能力）。
- `DocModelDetector(context)` / `ScanEngine.outputDir()`：模型路径、输出目录改为 `ScannerConfig` 注入（见 §5）。

### 3.5 OpenCV 初始化（契约化）

`OpenCVLoader.initLocal()` 目前在 `MainActivity.onCreate`。抽离后 module 应提供 `Scanner.ensureOpenCv(): Boolean` 并在文档中声明"必须先初始化"，否则宿主漏调会得到难排查的 native 崩溃。

---

## 4. module 对外接口设计建议

### 4.1 构件拆分

```
scanner-core        (android library)   纯识别：Scanner 门面 + 数据类型 + Config
   ├─ 依赖：org.opencv:opencv:4.9.0、onnxruntime-android:1.20.0
scanner-camerax     (可选适配器)         ImageProxy→ScanFrame 适配、拍照协议辅助
   ├─ 依赖：androidx.camera-core
scanner-debug       (可选, debugImplementation)   虚拟模式/自检入口 + selftest.jpg
```

关键原则：**`opencv`/`onnxruntime` 用 `implementation` 而非 `api`**，避免类型泄漏到宿主、引发依赖冲突；对外数据类型只用 `android.graphics.*` 和 Kotlin 标准库。

### 4.2 门面 API（建议签名）

```kotlin
class Scanner(
    appContext: Context,
    config: ScannerConfig = ScannerConfig(),
) : AutoCloseable {
    fun init(): Boolean                       // OpenCV init + 可选预热模型
    suspend fun loadModel(): ModelLoadResult  // 语义与现状一致：阻塞、幂等
    fun analyze(frame: ScanFrame): FrameResult?   // 分析线程调用，@Synchronized 语义保留
    fun analyzeBitmap(bitmap: Bitmap, omegaDeg: Float = 0f): FrameResult?
    fun currentQuad(): Quad?
    fun processCapture(source: Bitmap, quad: Quad,
                       filter: ScanFilter = ScanFilter.GRAY): ScanResult
    fun reset()
    fun setMotionSource(source: MotionSource)   // start/stop/omega，默认陀螺仪实现
    override fun close()
}

data class ScannerConfig(
    val tracker: TrackerConfig,       // §5 全部阈值
    val detector: DetectorConfig,     // lowContrast / minAreaRatio / minSupport
    val model: ModelConfig,           // asset 名或文件路径、推理线程数、每 N 帧跑一次
    val output: OutputConfig,         // 输出目录、JPEG 质量、输出尺寸（默认 A4 150dpi）
    val lowContrast: Boolean = false,
)
```

编排层（从 MainViewModel 剥离）作为 `ScanCoordinator` 与 `Scanner` 同包提供，产出 `StateFlow` + 一次性事件 Flow；宿主 ViewModel 只做转发和文案映射。这样"其他 MVVM 项目"只需：构造 `Scanner` → 建 `ScanCoordinator` → 订阅两个 Flow → 实现拍照回调。

---

## 5. 需保留的可配置项

现状里所有魔法数字散落在三个构造器中，抽离时应集中为 Config，**默认值必须原样保留**（这些是 Android 真机上调过的，与 Python 原型不同，不能"顺手对齐"）：

| 来源 | 参数 | 默认值 |
|---|---|---|
| `DocTracker` | `scoreOn` / `stableFrames` / `countdownMs` / `cooldownMs` / `imuGate` | 0.72 / 5 / 700ms / 1500ms / 25°/s |
| `DocTracker` 内部 | 评分权重（support .26 / area .18 / stability .22 / imu .10 / sharp .16 / geom .08）、EMA 系数、跳变门限 0.30×对角线、丢帧 3 帧清空 | 建议只暴露权重表，内部系数 internal |
| `DocDetector` | `lowContrast` / `minAreaRatio` / `minSupport`、Canny 阈值 35/105（宽松 8/24） | false / 0.15 / 0.40 |
| `DocModelDetector` | 资产名、`THRESHOLD` 0.15、`setIntraOpNumThreads(4)`、调度间隔（每 3 帧、缓存 6/9 帧） | 写进 `ModelConfig` |
| `ScanCapture` | `Filter` 枚举、A4 输出 1240×1754、JPEG 质量 92、锐化参数（1.6σ / 1.3~1.4 系数）、背景除法 232 | `OutputConfig` |
| `MainViewModel` | `prepareStill` 长边 1400px、虚拟模式 66ms 帧间隔 | 编排层常量，可入 Config |

---

## 6. 对其他 MVVM 项目的集成方式要求

1. **Gradle**：`settings.gradle.kts` include module（源码集成）或发布到 maven（AAR）；宿主需 `abiFilters`（native so 约 20MB+，必须能按 ABI 裁剪）、`minSdk ≥ 24`、`compileSdk ≥ 35`、JDK 17 工具链。
2. **依赖冲突收敛**：宿主若已依赖不同版本 CameraX/OpenCV，需在 module 用 `implementation` + 约束（`constraints {}`）声明支持区间；OpenCV 4.9.0 与宿主其他 native 库的 so 冲突需实测。
3. **资产体积**：12.5 MB ONNX 随 AAR 进 APK；对外提供 `ModelConfig` 允许改为下载/按需拷贝路径。
4. **权限契约**：module 不申请任何权限；宿主负责 CAMERA 授权，并在 `onResume/onPause` 驱动 `MotionGate`（省电语义保留）。
5. **拍照协议**：宿主实现"收到 `CaptureRequest` → CameraX `takePhoto` → EXIF 摆正 → `coordinator.onPhotoCaptured(bitmap)`"，module 不接触 CameraX（除非引入 scanner-camerax）。
6. **生命周期**：`Scanner` 是单实例长生命周期对象（持有 ORT session），建议宿主 Application 级持有或 `ViewModel` + `close()` 语义；文档必须写明 Bitmap/`ImageProxy` 所有权与线程约束（`analyze` 串行、`processCapture` 阻塞、需在 `Dispatchers.Default` 调用——沿用现有调用方式）。
7. **混淆**：宿主开启 minify 时需要 keep 规则（onnxruntime JNI、OpenCV JNI）；module 自带 `consumer-rules.pro`。

---

## 7. 兼容性考量与潜在风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 互锁语义破坏 | `capturing/frozen/acceptingFrames` 的组合行为（丢帧、回退、虚拟模式分支）只存在于 `MainViewModel` 的 22 KB 代码里，没有测试 | 抽离前先以自检/virtual 模式建立回归基线（见阶段 0） |
| Bitmap 所有权 | 现有代码多处 `isRecycled` 判断 + `keepSource` 参数；跨 module 后若宿主提前 recycle 会 native 崩溃 | 接口 Javadoc 明确所有权；result 返回前不 recycle 源图 |
| 线程模型 | `@Synchronized` 串行化是正确性前提；改造成 Flow 时容易引入竞态 | 保留 `analyze` 的同步签名；编排层内部仍用单线程串行 |
| OpenCV/ORT native 崩溃 | 初始化遗漏、ABI 缺失、多 OpenCV 版本 so 冲突都是崩溃而非编译错误 | `Scanner.init()` 显式校验；CI 覆盖 arm64 真机/模拟器 |
| 参数漂移 | 评分/门限是真机调参成果，重构中"清理魔法数字"会静默改变触发行为 | Config 默认值逐字保留 + 自检结果比对（检测 ms、support、areaRatio、输出文件） |
| 调试能力丢失 | virtual/selftest 依赖 asset 与宿主文案，抽离后可能没人维护 | 随 `scanner-debug` 发布；`selftest.jpg` 与 onnx 一起入 module assets |
| `Quad` 类型污染 | `typealias Quad = Array<PointF>` 裸数组是公共契约的一部分，后续若改 data class 会破坏兼容 | 首版定型为 `@JvmInline` 值类或保留 typealias + 扩展函数，冻结签名 |

---

## 8. 分阶段实施建议

**阶段 0 —— 回归基线（0.5 天）**
以 `selftest` / `virtual` 两条离线路径为锚点，记录：检出帧指标（support/areaRatio/detectionMs）、`processCapture` 输出像素与文件、virtual 模式触发帧号。抽离过程中任何阶段结束都要跑同一基线。

**阶段 1 —— 包内解耦（1~2 天，不建 module）**
- 新增 `ScanFrame` 抽象，`ScanEngine.analyze` 改收抽象帧；`ImageProxy` 适配移到 `CameraController` 旁。
- 把 `MainViewModel` 的编排逻辑抽成包内 `ScanCoordinator`（同 app，行为不变），`MainViewModel` 退化为转发层。
- 文案改枚举映射；`MotionGate` 改注入。
每步跑阶段 0 基线。

**阶段 2 —— 建立 `scanner-core` module（1 天）**
`com.android.library`，迁移 `model` 包 + `ScanCoordinator` + Config + assets；app 以 `implementation(project(":scanner-core"))` 依赖。debug 入口暂留 app。CI 同时构建两个变体。

**阶段 3 —— 接口硬化（1~2 天）**
- module 对外只暴露：`Scanner`、`ScanCoordinator`、数据类型、Config、`ScanFrame`；其余 `internal`。
- 拆出可选的 `scanner-camerax`（ImageProxy 适配 + 拍照协议辅助）与 `scanner-debug`（virtual/selftest）。
- 补 `consumer-rules.pro`、接口所有权/线程文档。

**阶段 4 —— 复用验证与发布（1 天 + 持续）**
用一个最小宿主 demo（非本项目）走一遍集成清单（§6），验证：权限流程、ABI 裁剪、minify 开启、模型资产路径外置。之后可发布 maven；宿主 app 的 `MainActivity/MainViewModel` 保留为参考实现。

总体工作量估计：**4~6 人日**，其中阶段 1 的编排剥离（3.2 节）占一半风险与工作量，建议严格按"先包内、后 module、全程基线"推进。
