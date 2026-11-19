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

- 正确命令：`gcc -O2 -o start.exe start.c -lurlmon`，漏掉 `-lurlmon` 会在链接阶段报 `URLDownloadToFile` 未定义。
- 坑：把 `%MinGW_HOME%\bin` 原样写进 PATH（例如通过脚本/进程环境注入）时，变量没有被展开，`gcc` 会提示“不是内部或外部命令”。改用绝对路径，或在 cmd 里先 `call` 展开，或直接用 `C:\...\mingw64\bin\gcc.exe` 全路径调用。
- 源文件里的中文在 MinGW 下要确认执行字符集；`start.c` 已用 `#pragma execution_character_set("utf-8")`（仅 MSVC 生效），MinGW 编译后仍要实际运行看中文是否乱码。
- `start.exe` 只认当前工作目录的 `HybridFileXfer.jar`；从别的目录启动会提示找不到 jar。发行包里必须把两者放在同一层。

### 1.4 Launcher 需要 .NET SDK

- `HybridFileXferLauncher` 是 C# 工程，构建前用 `dotnet --list-sdks` 确认 SDK 是否存在；只有 Runtime 不够。
- 本环境没有 .NET SDK，因此 Launcher 未构建、未纳入发行包；发行说明中要写清楚，避免用户以为漏文件。

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

错误码：`0 OK / 1 UNKNOWN_COMMAND / 2 UNSUPPORTED / 3 IO / 4 BUSY / 5 PERMISSION`。

GUI 的“新建文件夹/删除”直接复用 2、3，没有新增命令；失败时按 status 映射成人话。

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
  - `ERR_PERMISSION` → “手机端未允许电脑主动访问”；
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

---

## 5. 打包与提交

- 构建：`javac --release 8 -encoding UTF-8 -cp <annotations桩> -d classes @sources.txt`；`jar --create --file HybridFileXfer.jar --main-class top.weixiansen574.hybridfilexfer.Main -C classes .`。
- `messages_gui*.properties` 必须复制到 `classes` 的包目录后再打包，否则运行时找不到文案。
- 发行目录固定包含：jar、`start.exe`、adb 三件套、APK、bat、使用说明；版本号递增（beta3 → beta4），使用说明同步更新新增功能与已知限制。
- 打包后计算 zip 的 SHA-256 和字节数，写进交付说明；不要只给“最新包”。
- 提交信息用中文、动词开头（如“修复大文件传输进度闪烁并新增远端文件管理”）；临时探针、测试截图、构建目录不要入库。
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
