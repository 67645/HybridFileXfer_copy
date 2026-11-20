# PC 端 GUI 改造经验总结

> 适用项目：HybridFileXfer（PC Java 客户端 + Android 服务端）
> 记录范围：阶段 1-5，自协议双向化到 beta4 发行包
> 写法约定：只记可复用的做法与真实踩过的坑，不写流水账。

---

## 1. 环境与工具链

### 1.1 构建环境固定为 JDK 17 + Gradle 8.0

- Android 工程使用 Gradle 8.0 时，`JAVA_HOME` 必须是 JDK 17。换成 21/25 常见结果是 Gradle 启动即报 JVM/class 版本不兼容。
- 机器上同时装了多个 JDK 时，不要只改 `PATH`：在 `gradle.properties` 里显式写 `org.gradle.java.home=<jdk17>`，或在调用前覆盖 `JAVA_HOME`，否则 IDE 与命令行可能各用一套。
- PC 端 jar 用 `javac --release 8` 编译，保证在老 JRE 上可运行；编译注解依赖时把仓库里的 `annotations` 桩类放在 `-cp`，不要为几个注解引入完整 jar。

### 1.2 Android SDK 位置必须显式配置

- `local.properties` 写 `sdk.dir=...`（或设置 `ANDROID_HOME`），否则构建直接失败。
- 命令行构建前先确认 `platform-tools/adb.exe` 存在；发行包里的 `adb.exe` 就是从 SDK 复制的，版本要与测试用的一致。

### 1.3 用 MinGW 编译 start.exe

- 正确命令：`gcc -O2 -s -finput-charset=UTF-8 -fexec-charset=UTF-8 -o start.exe start.c`。beta6 起已移除下载逻辑，不需要 `-lurlmon`。
- 坑：把 `%MinGW_HOME%\bin` 原样写进 PATH（例如通过脚本/进程环境注入）时，变量没有被展开，`gcc` 会提示“不是内部或外部命令”。改用绝对路径，或在 cmd 里先 `call` 展开，或直接用 `C:\...\mingw64\bin\gcc.exe` 全路径调用。
- 源文件里的中文在 MinGW 下要确认执行字符集；`start.c` 已用 `#pragma execution_character_set("utf-8")`（仅 MSVC 生效），MinGW 编译后仍要实际运行看中文是否乱码（运行时已 `SetConsoleOutputCP(CP_UTF8)`）。
- `start.exe` 只认当前工作目录的 `HybridFileXfer.jar`；从别的目录启动会提示找不到 jar。发行包里必须把两者放在同一层。
- 查找同目录 JDK 时注意：`FindFirstFileA("jdk*\\bin\\java.exe")` 不生效——Windows 的查找接口不支持目录段通配符。正确做法是先 `FindFirstFileA("jdk*")` 枚举目录，再拼 `目录\bin\java.exe` 检查是否存在。
- `start.exe` 不再自动下载 JDK：Dragonwell 21 包 200.8MiB、解压 351MiB，代价过大；无 Java 时只输出下载指引并返回 1。验证三条路径：系统 Java、同目录 JDK、两者都没有（需要临时清空 PATH）。

### 1.4 Launcher：已决定不使用 Native AOT

- `HybridFileXferLauncher` 是 C# 工程，`net9.0` + `PublishAot`。用 .NET 11 SDK 可以还原并编译到 IL，但链接阶段必须有 MSVC：`Microsoft.NETCore.Native.Windows.targets` 固定调用 `link.exe`，参数是 `/SUBSYSTEM`、`/ENTRY`、`/NODEFAULTLIB` 等，输入是 `.lib`；`findvcvarsall.bat` 也只会通过 `vswhere` 找 VS。
- 实测把 `CppLinker` 指向 MinGW 的 `ld.exe`（配合 `IlcUseEnvironmentalTools=true`）会直接失败：`cannot find ole32.lib`、`cannot find /NOLOGO`……因此 MinGW 不能替代 MSVC。
- 最小可用工具链是 VS 2022 Build Tools 的 `VC.Tools.x86.x64` + Windows SDK，约 2–3GB 下载、5–7GB 安装。**已决定不为一款 65KB 的启动器承担这份安装成本**，Launcher 不纳入发行；启动入口固定为 `start.exe` 与 `启动图形界面.bat`，源码保留但不再讨论。

### 1.5 PowerShell 下的几个命令行坑

- 传 JVM 参数要加引号：`java "-Dfile.encoding=UTF-8" -cp ...`；直接写 `-Dfile.encoding=UTF-8` 会被 PowerShell 当成自己的参数。
- `.bat` 文件必须是 CRLF；LF 换行的 bat 在 cmd 下会报错或只执行第一行。
- 批量构建/打包脚本放临时目录，不要提交进仓库；仓库只保留最终脚本与文档。

---

## 2. 协议双向化（300 → 301）

### 2.1 版本协商要“整版切换”，不能混用

- 301 是全新的帧协议；控制通道一旦进入 301，就不能再夹杂 300 的裸类型读写，否则会随机错位。
- 旧服务端拒绝 301 时会回写 `false` + 自身版本号并关闭连接；客户端收到后必须**重新建连**再走 300，不能复用半关闭的 socket。
- 发行时默认策略：
  - GUI：`connectAuto`，先 301，失败且对方为 300 时自动降级；
  - CLI：默认 300，需要时显式 `--protocol=301` 或 `--protocol=auto`。
- 300 回退后，GUI 只保留被动收发能力；主动浏览、新建、删除、推送按钮禁用，避免发出对方不认识的帧。

### 2.2 DataByteChannel 非线程安全

- `DataByteChannel` 的所有原始类型共用一个 8 字节 `ByteBuffer`，没有同步。多线程同时写会把帧头和数据交错，表现为“偶尔解析出巨大长度/随机命令”。
- 做法：301 的所有写操作走 `RpcPeer` 内的同一把写锁；每端只有一个读线程。读线程唯一允许的写操作是回 `ERR_BUSY`，而且也必须拿同一把锁。
- 排查经验：出现“帧错位”先看是否有第二个线程在写控制通道，而不是先怀疑序列化格式。

### 2.3 ERR_BUSY 串行约定

- 传输任务占用大块直接内存（256/512 × 1MB），同一时刻只能有一个任务。
- 传输期间，对端发来的文件操作请求统一回 `ERR_BUSY`；`SHUTDOWN`、`QUERY_INFO` 例外。
- 检查点有两处：读线程收到请求入队前、handler 出队执行前。只查一处会留下竞态窗口。
- GUI 侧同时把浏览/新建/删除/推送按钮置灰，形成“界面预防 + 协议兜底”的双保险。

### 2.4 两份 core 的同步

- 共享范围：`core/**` 与 `nio/DataByteChannel`；PC 的 `jdkcore/**`、Android 的 `droidcore/**` 各自独立。
- 流程：只改一侧 → 整文件复制到另一侧 → 对 `core` 和 `nio` 逐文件算 SHA-256 比对，全部一致才算同步完成。
- `droidcore/HFXServer` 不在共享范围，协议改动要单独改、单独验证。
- 打包前再跑一次哈希比对，避免“文档说同步了、实际漏了一个文件”。

### 2.5 301 命令与错误码（最终实现）

| 值 | 名称 | 方向 | 请求体 | 响应体 |
|---|---|---|---|---|
| 0 | SHUTDOWN | 双向 | 空 | 空 |
| 1 | LIST_FILES | 双向 | UTF path | int count（-1 失败）+ 条目 |
| 2 | DELETE_FILE | 双向 | UTF path | byte bool |
| 3 | MKDIR | 双向 | UTF parent + UTF child | byte bool |
| 10 | REQUEST_RECEIVE | 双向 | 空 | OK 后进入接收 |
| 11 | REQUEST_SEND | 双向 | int count + UTF 路径… + 目标目录 + 目标文件系统 + 源目录 | OK 后进入发送 |
| 100 | QUERY_INFO | 双向 | 空 | int 文件系统 + UTF 主目录 |
| 120 | CANCEL_JOB（预留） | 双向 | int jobId | byte bool |
| 121 | PING | 双向 | 空 | OK（读线程直接回，不排队） |

错误码：`0 OK / 1 UNKNOWN_COMMAND / 2 UNSUPPORTED / 3 IO / 4 BUSY / 5 PERMISSION`。

GUI 的“新建文件夹/删除”直接复用 2、3，没有新增命令；失败时按 status 映射成人话。

beta5 起 `ERR_PERMISSION` 的 body 带服务端原因（路径越界、不能删根目录、被拒绝授权），GUI 优先显示原文，不要再统一改写成“无权限”。

### 2.6 握手授权只能插在“版本之后、网卡之前”

- 301 握手顺序：`HFXC` + 版本 → 服务端 `true` + 版本 → **授权交换** → 网卡数量/名称 → 缓冲协商。
- 授权帧：客户端 `int AUTH_MAGIC("HFXA") + UTF pcId + UTF pcName`；服务端 `int AUTH_RESP_MAGIC("HFXR") + byte status + UTF reason`。
- 服务端必须用**带超时的读**（非阻塞 `configureBlocking(false)` + `Selector`，2s）读授权魔数：
  - 超时 / 读到旧版首个字段 → 当作旧 301 客户端，跳过授权继续；
  - 魔数不匹配 → 主动断开，避免把后续协商字节当成授权内容。
- 第一次实现失败的现象：服务端一直等授权魔数，客户端却以为授权已完成，接着把“网卡数量”读成“授权应答魔数”，表现为握手莫名失败且没有明确异常。**只要两端对“下一个字节是什么”理解不一致，就会整体错位**，所以顺序必须写进文档、两端 core 同步改。
- 拒绝时先写应答再关连接；客户端抛 `PcAuthException` 并把原因存到 `getLastConnectError()`，GUI/CLI 才能显示“被拒绝”的具体理由。

### 2.7 按电脑记住授权：标识与存储

- PC 标识不能每次启动重新生成，否则“记住”失效；用持久文件保存随机 UUID（`~/.hybridfilexfer/pc-identity.properties`，不可写退到 tmp），配主机名给人看。
- Android 侧用 SharedPreferences 存 JSON 数组，不要每台电脑一个 key：列表、撤销、排序都方便。
- 必须区分“未知 / 允许 / 拒绝”三态：未知才弹框，允许免询问，拒绝直接回原因并**不再骚扰用户**。
- 弹框结果要区分“仅本次”和“记住”：不记住时不能落盘，否则用户拒绝一次就被永久拉黑。
- 全局“允许电脑主动访问”仍然是总闸，先判总闸再判单机列表；这样旧行为和“一键全关”都保留。

### 2.8 路径范围校验：canonical 不够，Windows 要看 junction

- 不要在字符串层面拼路径后直接比较前缀；先规范化，再判断“是否在根目录之内”。
- 明确拒绝含 `..` 的路径段，比事后 canonical 比较更直观，也避免不同平台对 `..` 处理差异。
- **Windows 的坑**：`File.getCanonicalPath()` 不解析目录联接（junction），`root\escape\secret.txt` 会被误判为在根内。改用 `Path.toRealPath()`。
- **反射的坑**：`path.getClass()` 得到 `sun.nio.fs.WindowsPath`（非公开类），反射调用会 `IllegalAccessException`。要 `Class.forName("java.nio.file.Path")` 取公开接口再 `getMethod("toRealPath", LinkOption[].class)`。
- Android/Linux 上 `getCanonicalPath()` 本身会解析符号链接，实测足够；删除符号链接要直接删链接本身（`Os.lstat` + `S_ISLNK`），不要顺着链接递归。
- 目标不存在时，取“最近的存在祖先”做真实路径解析，再把剩余段拼回去；否则 `toRealPath` 直接抛 `NoSuchFileException`。
- 允许范围的根目录要单独保护：删除操作命中根本身直接拒绝，避免“删了根就等于删了全部”。

### 2.9 传输改名的线格式与心跳

- 改名不能只存在本地：301 传输请求在每个文件条目后追加 `UTF transferName`（空串表示不改），对端 `Directory.generateTransferPath` 只替换最后一段。两端 core 必须同步，否则条目错位。
- 目录不提供改名：递归改名会让子项路径全部变化，风险和实现成本都不成比例，UI 只给跳过/覆盖。
- 心跳由读线程直接处理 `CMD_PING`（读到就写应答），不要进 handler 队列；长传输或大目录会把队列堵住，心跳一旦排队就会误判掉线。
- 判定参数：空闲 5s 发 ping，20s 无任何帧判掉线；阈值要明显大于正常的请求间隔，避免大文件传输期间误断。
- 读线程记录 `lastReadAt` 必须覆盖所有帧（含应答和 ping），只看业务帧会在大传输中误判。

---

## 3. GUI 实现

### 3.1 选 Swing，不引入第三方

- 选型结论：Swing + Metal LAF，零第三方依赖。beta4 的 `HybridFileXfer.jar` 约 137KB；JavaFX 最小模块也要 20MB+，与“体积最小”目标冲突。
- 需要现代外观时优先用 UIDefaults 调色，不要为了圆角引入整套 UI 框架。
- 全部界面代码放 `gui` 包，协议/传输仍走 `core`，GUI 只做编排。

### 3.2 主窗口结构

- `MainFrame extends JFrame implements WindowChrome.Host`，`setUndecorated(true)`。
- 内容区自上而下：连接面板（端口/接收目录 + ADB/局域网 Tab）→ 文件面板（路径栏 + 表格）→ 传输面板（队列 + 通道速度）→ 底部状态栏（状态 + 进度条 + 断开）。
- 表格统一 26px 行高、32px 表头；输入框/按钮 30px 高；字体统一微软雅黑 13pt，避免控件参差。
- 菜单“外观”放进自绘标题栏，不再单独占一行。

### 3.3 自绘标题栏必须补齐的窗口行为

只画按钮不够，以下都要真实可用：

- **拖动**：在标题栏按下后记录 `getLocationOnScreen()`，移动时用屏幕坐标差值 `setLocation`。用组件坐标会在跨屏/高 DPI 时跳。
- **双击最大化/还原**：`clickCount == 2` 时切换；最大化状态下仍允许双击还原，只禁用拖动和缩放。
- **八方向缩放**：四周与四角各放 5px 透明 JPanel，MouseAdapter 里用 `setBounds` 改窗口；限制最小尺寸（本项目 960×640 主窗、360×260 对话框）。
- **最大化边界**：用 `GraphicsEnvironment.getMaximumWindowBounds()`，保存/恢复 `normalBounds`；最大化时禁用拖动与缩放，防止拖出屏幕。
- **按钮反馈**：MouseAdapter 维护 hover 状态并 repaint；关闭按钮悬停变红，其余按钮悬停变亮。
- **坑**：把 `JMenuBar` 直接放标题栏 `BorderLayout.CENTER` 会盖住拖动区域；应把“标题 + 菜单”放进 WEST 的 FlowLayout，中间留空给拖动。
- **坑**：自绘外壳用 null layout 时 `getPreferredSize()` 默认是 0，`pack()` 会把对话框压成 0×0；必须重写返回“标题栏 + body”的合适尺寸。

### 3.4 主题

- 做法：自定义 `DarkMetalTheme` + 每次切换显式重写 `UIDefaults` 调色板。只换 LookAndFeel 不重写 palette，会出现旧颜色残留。
- 系统深色检测：轮询注册表 `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme`（3 秒一次），跟随系统时运行中也会变。
- **最大的坑：控件字段的初始化顺序**。`MainFrame` 的 `JTextField/JTable/...` 作为实例字段，在构造器体之前就创建；如果 `ThemeManager.init()` 写在构造器第一行，字段已经用浅色 LAF 建好了，结果就是“标题栏/面板是深色，输入框和表格还是白底”。正确做法是在 `new MainFrame()` 之前调用主题初始化（本项目在 `showWindow()` 里先 init 再建窗口）。
- 主题切换后 `updateComponentTreeUI` 会重置代码里设置的行高、列宽、边框；切换回调里要重新应用表格样式和自绘外壳颜色。
- `JFileChooser` 不会自动跟随深色，需要递归给列表、按钮、输入框重新上色；`JTextField` 的底色也必须显式设置，不能只靠父面板背景。

### 3.5 对话框

- 统一用 `ThemedDialog`：undecorated + 同一个 `WindowChrome`，只保留关闭按钮，ESC 关闭。
- 消息、确认、目录选择、文件选择、单行输入都走同一套外壳，避免“自绘标题栏 + 系统标题栏”混搭。
- 输入框对话框要设置默认按钮、回车确认、打开时聚焦输入框；取消返回 `null`。
- 删除确认必须把“删除后不可恢复”写进正文，而不是只放在标题里。

### 3.6 拖放接入

- `DropTarget` 使用 `DataFlavor.javaFileListFlavor`；收到 `List<File>` 后交给 core 递归处理文件夹。
- 拖动经过时给文件表加高亮边框，离开/释放后恢复；未连接、正在传输、路径为空时给出明确提示，不静默吞掉。
- 拖放回调在 EDT，真正的推送放到单线程传输执行器，避免卡界面。

### 3.7 远端文件管理

- 协议层已有 `DELETE_FILE`/`MKDIR`，GUI 直接复用，不新增命令。
- 新建：校验空名、`.`/`..`、`/`、`\`、控制字符；当前目录已有同名项时先提示，减少一次 RPC。
- 删除：支持 Ctrl/Shift 多选；禁止删除根目录和当前浏览目录；确认框列出完整路径。
- 操作在后台线程执行，完成后回 EDT：刷新当前目录 + 成功写状态栏/日志，失败弹窗说明原因。
- 失败分类：
  - `ERR_BUSY` → “手机端正在传输，稍后再试”；
  - `ERR_PERMISSION` → 优先显示服务端原因（“路径不在允许范围内”“不能删除允许范围的根目录”“此电脑已被手机端拒绝”），原因为空才退回“手机端未允许电脑主动访问”；
  - `ERR_UNSUPPORTED` → “当前协议版本不支持”；
  - 返回 `false` → “不存在、目录非空或没有删除权限”。
- **提醒**：不要用脚本直接对 `/` 调删除命令做测试。Android 端会递归遍历，RPC 很容易超时，且可能造成误删；GUI 已禁止该操作。

### 3.8 进度与速度模型

- 不要在每个 64KB 块的回调里碰 Swing。512MB 会产生约 8000 次 `invokeLater`，EDT 被刷爆，进度条和状态栏一起闪。
- 正确模型：
  1. 传输线程只把“文件路径 → 已完成字节”写入聚合表；
  2. 多通道按块尾上报且顺序不固定，按文件取 `max`，避免进度回跳；
  3. EDT 上 100ms 定时刷新，百分比不变就不重绘；
  4. 传输结束由队列任务统一置 100% 或“未完成”。
- 状态栏不要每块都 `setText`：`JLabel.setText` 会触发 `revalidate/repaint`，文字宽度变化还会挤动旁边的进度条。做法是固定状态栏宽度（本项目 300px），路径只在切换文件时更新一次，速度每秒更新。
- 速度表不要每秒 `setRowCount(0)` 重建；通道名不变时只 `setValueAt` 更新速度单元格。
- 实测指标（512MB 单文件）：修复前约 15.2 次/秒进度变更，修复后约 9.5 次/秒、回退 0 次、最终 100%。

### 3.9 覆盖冲突：先问，再传

- 冲突检测放在“真正发起传输之前”：列出目标端已有同名项，一次性收集，避免每个文件都弹一次框。
- 单个冲突给 跳过 / 覆盖 / 重命名 / 取消；多个冲突额外提供“全部覆盖 / 全部跳过”，减少重复点击。
- 重命名只替换最后一段路径（`transferName` 字段随传输请求下发），不要拼接完整路径；子项沿用父目录，避免递归改名。
- 目录冲突不提供重命名，只给跳过/覆盖；这是刻意的取舍，不是遗漏。
- `ThemedDialog.choice` 返回按钮下标，取消返回 -1；后台线程弹框要用 `invokeAndWait`，否则对话框可能在错误线程创建。
- 重命名输入校验与新建文件夹一致：非空、非 `.`/`..`、不含路径分隔符。

### 3.10 小文件推送：先量化，再决定改不改

- 测量方法（可复用）：
  - 用自定义 `EventQueue` 覆写 `dispatchEvent`，累计 EDT 忙时间与事件数；`postEvent` 统计投递数。
  - 给队列模型加 `TableModelListener`、给进度条加 `ChangeListener`、给日志文档加 `DocumentListener`，分别统计更新次数。
  - 客户端 CPU 用 `com.sun.management.OperatingSystemMXBean#getProcessCpuTime`；手机 CPU 用 `adb shell cat /proc/<pid>/stat` 的 utime+stime（100Hz，约 10ms/tick）。
  - 文件集设计成“总字节相同、文件个数不同”（4000×16KB vs 1000×64KB vs 1×62.5MB），才能把“每文件固定成本”从吞吐里分离出来。
- 结果（模拟器 Android 14 x86_64）：
  - UI 开销真实存在：4000 文件逐文件推送时 EDT 忙 36.4s（占墙钟 16%），客户端 CPU 65.9s。
  - 但 UI 不在关键路径：优化后 EDT 忙降到 5.0s（2.2%）、客户端 CPU 降到 26.9s，墙钟只从 228.1s 变到 224.9s。
  - 真正的瓶颈是“每文件一个 RPC 任务”：同样数据单次批量只需 34.0s（约 6.6 倍差距），折合每文件约 48ms；1000×64KB 也是每文件约 56ms，说明与文件大小无关。
  - 48ms 高度符合 Nagle + 延迟确认（约 40ms）的特征，但**加了两端 `setTcpNoDelay(true)` 后并无改善**（4000 文件仍约 230s），说明推断不成立；两端 CPU 都不高，是在等。下一步应排除 adb forward 中转链路（其 relay socket 不受应用控制），需要真实 Wi-Fi 环境复测。
  - 立竿见影的改法是批量推送：GUI 把同一父目录、单文件 ≤8MB 的普通文件合成一次 `pushFiles(List)`，单请求上限 100 文件或 16MB，4000×16KB 从 229.8s 降到 38.9s（约 9.7ms/文件），1000×64KB 从 58.0s 降到 10.4s；单文件 62.5MB 维持 74MiB/s，无回退。
  - 批量失败的处理：整批抛错时自动退化为逐个重试，日志写“推送 <文件名> 失败：原因”，否则用户只能看到笼统失败；实测推到越界目录 5 个文件，407ms 内完成重试并逐条报错。
  - 上限依据：不希望一个请求跑太久或失败影响面过大；按实测 8.7ms/文件，100 个文件约 1-2 秒，16MB 上限对应约 9 秒，二者先到先分段。
- 具体改法（beta6）：
  - 文件数 > 20 时，`onComplete` 只累计、不写日志，`onChannelComplete` 直接跳过，循环结束写一条汇总；≤20 个文件保持逐条日志。
  - 状态文字切换加 100ms 节流（最多 10 次/秒），错误日志不受影响。
- 测量时的坑：
  - `adb shell ps -A | Select-String 包名` 的输出行有前导空格，按 `\s+` 分割后 `[1]` 才是 PID，`[2]` 是 PPID；一开始取错，导致手机 CPU 恒为 0。
  - 批量模式（直接调 `client.pushFiles`）不会产生队列行，等待“队列完成”会一直卡住；同步调用返回即完成。
  - 控制台输出中文可能因编码显示成乱码，但 JVM 内部的字符串仍正确，按中文匹配状态不受影响；需要在报告里引用时以日志文件为准。

---

## 4. 验证方法

### 4.1 没有真机时用模拟器

- 模拟器 + `adb forward tcp:5740 tcp:5740` + 手机 App 的“允许电脑主动访问”开关，可以覆盖连接、浏览、推送、拖放、文件管理。
- 连接目标写 `127.0.0.1` + serial；GUI 内部会按 serial 重新 forward，避免手抄端口。
- 模拟器测不出真实 USB 断连、厂商权限弹窗等，要在结论里注明。

### 4.2 Robot 驱动窗口操作

- 用 `java.awt.Robot` 做真实的鼠标拖动、边缘缩放、双击标题栏、点击最小化/还原/关闭；断言窗口 bounds 与状态，而不是只看截图。
- 窗口被测试代码移到屏幕外后，后续点击会落空；每个用例开始前重置位置，结束后再点关闭。
- 模态对话框可能挡住关闭按钮：先 `dispose` 所有对话框，再测关闭。
- 悬停反馈单独移动鼠标并截图验证；ESC 关闭对话框用 Robot 按键验证。

### 4.3 大文件实测

- 准备 ≥512MB 文件，采样 `JProgressBar.getValue()`（例如 50ms 一次），统计：变更次数、回退次数、最大回退、最终值、状态栏变更频率。
- 只看小文件会漏掉节流和乱序问题；小文件几毫秒就结束，来不及暴露。
- 同时截取传输中的界面，确认状态栏、速度表、进度条没有互相挤压。

### 4.4 高 DPI 环境的截图陷阱

- 本次验证机是 200% 缩放。Java Robot 的截图会按用户坐标缩放，文字边缘容易出现红蓝彩边，看起来像渲染 bug；用 DPI-aware 的外部截图（`SetProcessDpiAwarenessContext(-4)` + `CopyFromScreen`）复核，实际界面是正常的。
- 不要根据缩放后的截图判断颜色/字号；要看 1:1 的物理像素截图。

### 4.5 发行包冒烟

- 解压 zip 到干净目录 → 校验 jar 哈希与源目录一致 → `java -jar` 启动 → 检查进程存活和窗口标题 → 结束后清理目录。
- 同时验证 `启动图形界面.bat` 能找到 `javaw` 和 jar。
- beta5 实测：解压后 `java -jar` 运行 8s 进程仍存活、stderr 为空，即视为冒烟通过。

### 4.6 授权、路径与心跳怎么单独验证

- **授权**：先用 CLI 连接触发弹框，在手机上分别选“允许+记住”“拒绝+记住”，再重连；允许应免询问，拒绝应返回明确中文原因。直接改 SharedPreferences（`run-as` 或 adb 写 `pc_auth_list`）可以免去反复弹框，验证“预置拒绝”和“撤销后重新询问”。
- **路径**：用 CLI 的 `--list /sdcard/../data`、`--list /data`、对 `/sdcard` 发删除，断言返回“路径不在允许范围内 / 不能删除允许范围的根目录”。
- **Windows junction**：必须在真实目录（不在沙箱重定向目录）里创建 junction，否则 Java 看到的目标不存在，测试会假通过；创建后对 `root\escape\secret.txt` 断言 DENY。
- **心跳**：客户端连上后只发一次握手就保持静默（不读不写），20s 左右应收到“心跳超时，连接已断开”；探针要打时间戳，避免把 socket 自身超时误当成心跳。
- **改名**：拉取时指定 `transferName`，断言目标文件以新名落地且大小与原文件一致。

---

## 5. 打包与提交

- 构建：`javac --release 8 -encoding UTF-8 -cp <annotations桩> -d classes @sources.txt`；`jar --create --file HybridFileXfer.jar --main-class top.weixiansen574.hybridfilexfer.Main -C classes .`。
- `messages_gui*.properties` 必须复制到 `classes` 的包目录后再打包，否则运行时找不到文案；`messages*.properties`（根 bundle）也必须复制到 `classes` 根，否则新版构建会抛 `MissingResourceException: Can't find bundle for base name messages`。
- 发行目录固定包含：jar、`start.exe`、adb 三件套、APK、bat、使用说明；版本号递增（beta4 → beta5），使用说明同步更新新增功能与已知限制。
- 打包后计算 zip 的 SHA-256 和字节数，写进交付说明；不要只给“最新包”。beta5：8,210,979 字节，SHA-256 `E1838ABE5EB8881A840B8E69D649D7BA8593DD103350EE849908C4891A59A5C4`。
- 提交信息用中文、动词开头（如“新增 301 握手授权与路径约束”）；临时探针、测试截图、构建目录不要入库。
- 推送前确认分支是 `feature/gui`，推送后用 `git log --oneline` 复核远端与本地的提交号一致。

---

## 6. 快速检查清单

1. `JAVA_HOME` 是否 JDK 17？Gradle 是否指向它？
2. `local.properties` 的 SDK 路径是否存在？
3. `start.exe` 与 jar 是否同目录？PATH 里的 MinGW 是否已展开？
4. 301 降级是否重新建连？300 下主动功能是否已禁用？
5. 控制通道是否只有一个写线程/一把写锁？
6. GUI 是否在 `new MainFrame()` 之前完成主题初始化？
7. 进度是否只在 EDT 定时刷新，且按文件取最大偏移？
8. 状态栏宽度是否固定，速度表是否原地更新？
9. 新建/删除是否覆盖成功、重复、非法、无权限、非空、传输中？
10. 大文件实测是否有回退？发行包是否做过解压启动与哈希校验？
11. 301 授权交换是否在版本之后、网卡之前？旧 301 客户端超时后能否继续？
12. 未知/允许/拒绝三态是否都覆盖？“记住”取消时是否不落盘？
13. 路径校验是否实测了 `..`、绝对越界和 Windows junction？
14. 心跳 PING 是否在读线程直回？静默 20s 是否判掉线？
15. 覆盖冲突的跳过/覆盖/重命名是否都有对应结果？目录是否只给跳过/覆盖？
