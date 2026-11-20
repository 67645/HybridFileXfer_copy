# HybridFileXfer GUI 与双向协议实施设计

> 状态：阶段 1-5 已实施完成；beta5 试运行包已发布（2026-11-20，新增握手授权、路径范围约束、断线检测与失败原因）
> 适用分支：`feature/gui`
> 本文档保留改造前的基线事实（第 1 节），其余章节以最终实现为准；可复用的经验条目见 `docs/gui-experience-notes.md`。

---

## 1. 项目现状（基线事实）

### 1.1 角色与端口

- 手机端（Android App）是**服务端**：`HFXServer` 绑定端口、先接受控制通道，再为每张网卡接受一条传输通道。
- 电脑端（PC，Java）是**客户端**：`JdkHFXClient` 连接控制通道与各传输通道。
- 端口硬编码 **5740**：`JdkHFXClient(serverAddress, 5740, homeDir)`（`Main.java` 第 196-199 行）、`Config.getServerPort()` 返回 5740。
- 协议版本 `HFXService.VERSION_CODE = 300`；握手首包为魔数 `"HFXC"` + int 版本号。
- GUI 默认端口 5740，可在连接面板修改并持久化；默认连接策略为 `connectAuto`（先 301，失败按对方版本回落 300）。

### 1.2 控制通道是单向发起的半双工模型（改造前基线）

- 命令**只由服务端发起**，客户端只读取并回复。
- `HFXClient.start()`（第 152-178 行）是客户端唯一的命令读取循环。
- 服务端 `HFXServer` **没有任何命令读取循环**，它只在自己写命令后同步读取回执（bool / int / UTF）。
- 因此客户端当前**无法**主动发送任何命令，服务端也**没有**对应的读取分支。
- 命令标识不区分方向、无保留位：`ControllerIdentifiers` 仅使用 `0=SHUTDOWN、1=LIST_FILES、2=DELETE_FILE、3=MKDIR、10=REQUEST_RECEIVE、11=REQUEST_SEND`，`4–9` 空号，`12+` 未使用。
- `DataByteChannel` 非线程安全：所有原始类型共用同一个 8 字节 `ByteBuffer` 字段，且无同步。任何多线程写必须自行加锁。

> 301 落地后该模型已双向化：客户端新增 RPC 读线程与主动帧请求，详见 4.3-4.5；写侧仍严格串行。

### 1.3 现有 PC 入口的调参事实

- `start.exe`（源码 `HybridFileXfer-PC/start.c`）：
  - 先检查系统 Java，缺失则下载 Dragonwell 21 并解压到**当前工作目录**；
  - 无条件执行 `system("adb devices")` 打印原始输出；
  - 询问`1=无线 / 回车=有线`；
  - 无线分支只问 IP（`is_valid_ip` 仅校验数字/点/四段/0–255）；
  - 有线分支**不做设备枚举与选择**，要求用户手动输入设备 ID，留空则不加 `-s`；
  - 只会拼出 `-c adb`、`-c adb -s <id>`、`-c <ip>` 三种形态；
  - **任何入口都不会传 `-d`**。
- `Main.java` 支持的参数：`-c/--connect`、`-s/--switch-device`、`-d/--dir`（默认 `"/"`）、`-h/--help`、`-v/--version`；纯位置参数被静默忽略。
- 文档与实现不一致：帮助与 README 写 `-s, --device=ID`，代码只认 `--switch-device`；使用 `--device` 会被判为未知选项并 `exit(1)`。
- adb 定位顺序：`exec("adb version")` 成功用 PATH 的 `adb`，否则用 `"./adb"`；工作目录取 `System.getProperty("user.dir")`，**不是 jar 所在目录**。PATH 无 adb 时 `exec` 直接抛 `IOException`，`./adb` 回退实际不可达。
- `adb forward tcp:5740 tcp:5740` 固定端口；失败只打印 stderr，`main` 静默返回（退出码 0）。
- `HybridFileXferLauncher`（C#）要求 exe 同目录存在 `HybridFileXfer.jar`，原样透传参数并转发输出。
- 两个 bat（`start-by-adb.bat`、`start-by-network.bat`）写成 `java -jar -Dfile.encoding=UTF-8 ...`，JVM 会把 `-D...` 当作 jar 路径，**当前不可用**。
- jar 命名不匹配：IDEA artifact 产物是 `HybridFileXfer-PC.jar`，而脚本、Launcher、`start.c`、README 全部期望 `HybridFileXfer.jar`；不匹配会导致 `Unable to access jarfile` / Launcher 报"未找到目标文件"。
- 异常表现：
  - 无系统 Java：`start.exe` 下载内置 JDK；Launcher 报 `未能启动 Java 进程`；bat 直接命令不存在。
  - adb 未安装：`start.exe` 路径抛异常并静默退出；`start-by-adb.bat` 有明确提示；USB 转发脚本不检查。
  - 设备未授权：`start.exe` 不识别状态，`adb forward` 报错后提示"操作失败"；`start-by-adb.bat` 会把未授权设备过滤掉，误报"未检测到任何已连接的设备"。
  - 5740 被占用：PC 不监听端口；手机端 `bind` 失败会 Toast"启动服务失败，端口：5740 被占用"。
- `start.exe`（beta4 现状）：仍只拼 `-c adb` / `-c adb -s <id>` / `-c <ip>`，**不会传 `--gui`**，因此进入 CLI 流程；GUI 通过 `启动图形界面.bat`（`javaw -jar`）或无参 `java -jar` 启动。启动前会检查同目录是否存在 `HybridFileXfer.jar`。

### 1.4 两份 core 的共享范围

- 共享（逐文件哈希一致）：`core/**`（`HFXService`、`HFXClient`、`FileBlock`、`Directory`、callbacks 等）与 `nio/DataByteChannel`。
- PC 独有：`Main`、`Strings`、`jdkcore/**`。
- Android 独有：`droidcore/**`（`HFXServer` 及各 `Droid*` 实现）、`aidl/**`、`uiactivity` 等。
- 结论：协议层修改必须先改共享 `core/**`，再同哈希同步到另一侧；`droidcore/HFXServer` 需单独改并单独校验。

---

## 2. 改动目标

1. PC 端新增美观、体积最小的 GUI；
2. PC 端具备主动文件管理能力：浏览手机目录、向手机推送、从手机拉取；
3. 端口可配置（默认 5740）；
4. 修复遗留问题：jar 命名、bat 参数顺序、`--device` 文案、adb 定位、错误提示；
5. 对旧版手机 App 保持兼容，不允许功能回归。

---

## 3. GUI 技术选型

**结论：Swing，零第三方依赖。**

| 方案 | 新增体积 | 结论 |
|---|---|---|
| Swing | 约 200–300KB jar（最终 beta4 为 137KB） | 采用 |
| JavaFX | 最小模块 20MB+，jlink 约 40MB+ | 排除：违背体积最小 |
| 本地 HTTP + 浏览器 | 约 100–200KB 资源，零依赖 | 排除：仍无法原生选目录、需额外端口与安全处理，体积收益不明显 |

补充：若后期需要更现代外观，可加单个约 700KB 的 FlatLaf；当前版本不引入。

### 3.1 主窗口结构（实现结果）

- `MainFrame extends JFrame implements WindowChrome.Host`，`setUndecorated(true)`，最小尺寸 960×640，默认 1120×760 居中。
- 自上而下：连接面板（端口/接收目录 + ADB/局域网 Tab）→ 文件面板（路径栏 + 文件表）→ 传输面板（队列 + 通道速度）→ 底部状态栏（状态标签 + 进度条 + 断开）。
- 统一 13pt 微软雅黑、30px 控件高、26px 表格行高、32px 表头；表格去掉竖向分隔线；“外观”菜单收进标题栏。
- 文件工具栏：上级目录、刷新、新建文件夹、删除、拉取到电脑、推送文件；文件表支持 Ctrl/Shift 多选。

### 3.2 自绘外壳与窗口行为（实现结果）

- `WindowChrome` 负责标题栏（标题、外观菜单、最小化、最大化/还原、关闭）、1px 边框和标题下方分隔线。
- 标题栏拖动使用屏幕坐标差值更新 `setLocation`；双击标题栏切换最大化/还原；最大化时只禁用拖动与缩放，双击仍可还原。
- 四周/四角各 5px 透明缩放区，`setBounds` 改窗口大小并限制最小尺寸；最大化时禁用缩放。
- 最大化使用 `GraphicsEnvironment.getMaximumWindowBounds()`，保存/恢复普通 bounds，避免拖出屏幕。
- 按钮悬停由 MouseAdapter 维护并重绘；关闭按钮悬停变红，其余变亮。

### 3.3 主题（实现结果）

- Metal LAF + 自定义 `DarkMetalTheme`，每次切换都显式重写 `UIDefaults` 调色板，避免旧颜色残留。
- 跟随系统时轮询注册表 `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme`，3 秒一次，运行中切换也能跟随。
- 主题初始化必须在 `new MainFrame()` 之前完成：控件字段在构造器体之前创建，晚于字段初始化会留下白底输入框/表格（已修复的既有缺陷）。
- 切换主题后重新应用表格行高、列宽、边框和自绘外壳颜色，避免 `updateComponentTreeUI` 重置自定义样式。

### 3.4 对话框（实现结果）

- 消息、确认、目录选择、文件选择、单行输入统一使用 `ThemedDialog`：undecorated + 同一套 `WindowChrome`，只保留关闭按钮，ESC 关闭。
- 内嵌 `JFileChooser` 递归重上色；输入框支持默认按钮、回车确认、自动聚焦，取消返回 `null`。
- 自绘外壳重写 `getPreferredSize()`，避免 null layout 导致 `pack()` 把对话框压成 0×0。

### 3.5 拖放（实现结果）

- 文件表注册 `DropTarget`，使用 `java.io.File` 列表 Flavor；文件夹由 core 递归展开。
- 拖动经过时加高亮边框，离开/释放恢复；未连接、正在传输、目标路径为空时弹提示，不静默丢弃。
- 拖放在 EDT 接收，推送提交给单线程传输执行器。

### 3.6 远端文件管理（实现结果）

- 复用 301 的 `MKDIR`、`DELETE_FILE`，未新增命令。
- 新建：校验空名、`.`/`..`、`/`、`\`、控制字符；同名项先提示。
- 删除：支持多选，禁止根目录和当前浏览目录；二次确认明确“删除后不可恢复”。
- 操作在后台线程执行，完成后回 EDT 刷新当前目录；成功写状态栏和日志，失败弹窗说明原因（已存在、无权限、目录非空、传输中、协议不支持等）。
- 300 回退模式下主动浏览/文件管理禁用，推送按钮也禁用，仅保留被动收发。

### 3.7 进度与速度模型（实现结果）

- 传输线程只把“文件 → 已完成字节”写入聚合表；多通道乱序上报按文件取最大值，进度不回退。
- EDT 上 100ms 定时刷新进度条，百分比不变不重绘；任务结束由队列统一置 100% 或“未完成”。
- 状态标签固定 300px 宽，路径只在切换文件时更新；速度每秒更新，避免文字宽度挤动进度条。
- 通道速度表按通道名原地更新单元格，不再每秒清空重建。
- 实测 512MB 单文件：进度回退 0 次，刷新约 9.5 次/秒（修复前约 15.2 次/秒的逐块刷新）。

---

## 4. 协议 301：双向帧

### 4.1 版本协商与 300 回退

- 新增 `VERSION_RPC = 301`，`VERSION_LEGACY = 300`。
- 服务端（新版手机 App）同时接受 300 与 301；旧版只接受 300。
- 客户端（新版 PC）策略：
  - `--protocol=300`：只走旧协议；
  - `--protocol=301`：只走 301，失败即报错；
  - `--protocol=auto`：先尝试 301，若被拒绝且对方版本为 300，则**重新建连**并以 300 完成握手。
- 实现结果：GUI 默认 `connectAuto`；CLI 默认 300，可显式 `--protocol=301` 或 `--protocol=auto`。300 回退后 GUI 只保留被动收发，主动浏览/文件管理/推送按钮禁用，并提示“兼容模式”。
- 旧服务端拒绝 301 的行为是现成的：`versionCode != VERSION_CODE` → 回写 `false` + 自身版本号并关闭连接。
- **beta5 顺序约定**：301 握手在“版本号确认”之后、网卡与缓冲协商之前插入一段授权交换（见 4.7）。300 完全跳过该段，字节序列与旧版逐字节一致。

### 4.2 帧格式

301 模式下控制通道只承载帧：

```
请求：[int seq][short cmd][int payloadLen][payload]
应答：[int seq][short RESPONSE][int payloadLen][byte status][body]
```

- `seq` 由发起方单调递增，应答原样带回；用于并发请求与超时。
- `payloadLen` 上限 64MB，越界视为协议错误并断开。
- 写侧必须串行：所有帧经过同一把写锁（`RpcPeer` 内 `synchronized`），规避 `DataByteChannel` 非线程安全。
- 读侧每端一个独立读线程：应答按 `seq` 完成对应的 `Future`；请求交给单线程 handler 执行器顺序处理。

### 4.3 命令与错误码

命令（最终实现：0/1/2/3/10/11/100/121 均已落地；120 预留）：

| 值 | 名称 | 方向 | 请求体 | 响应体 |
|---|---|---|---|---|
| 0 | SHUTDOWN | 双向 | 空 | 空 |
| 1 | LIST_FILES | 双向 | UTF path | int count（-1 表示失败）+ 条目 |
| 2 | DELETE_FILE | 双向 | UTF path | byte bool |
| 3 | MKDIR | 双向 | UTF parent + UTF child | byte bool |
| 10 | REQUEST_RECEIVE / RECEIVE_FILES | 双向 | 空 | OK；接收在 handler 线程内完成 |
| 11 | REQUEST_SEND / SEND_FILES | 双向 | int count + UTF path… + UTF 目标目录 + int 目标文件系统 + UTF 源目录 | OK；发送在 handler 线程内完成 |
| 100 | QUERY_INFO | 双向 | 空 | int 文件系统 + UTF 主目录 |
| 120 | CANCEL_JOB（预留） | 双向 | int jobId | byte bool |
| 121 | PING | 双向 | 空 | OK（读线程直接回，不进 handler 队列） |

GUI 的“新建文件夹/删除”直接使用 2、3，不额外加命令；`QUERY_INFO` 用于连接后取手机主目录与文件系统类型。

目录条目沿用旧格式：`UTF name, UTF path, long lastModified, long size, boolean isDirectory`。

错误码（`byte status`）：

| 值 | 名称 | 含义 |
|---|---|---|
| 0 | OK | 成功 |
| 1 | ERR_UNKNOWN_COMMAND | 未知命令 |
| 2 | ERR_UNSUPPORTED | 该版本暂未实现 |
| 3 | ERR_IO | 本端 IO 异常 |
| 4 | ERR_BUSY | 传输进行中 |
| 5 | ERR_PERMISSION | 未授权访问、路径超出允许范围、删除受保护的根目录等权限类失败 |

- beta5 起 `ERR_PERMISSION` 的 body 携带服务端可读原因（如“路径不在允许范围内”“不能删除允许范围的根目录”），GUI 优先展示该原因，不再一律显示“无权限”。
- 传输类失败在 301 下由 `TransferException` 归类（权限/路径/磁盘满/连接中断/IO），见 4.10。

### 4.4 传输期串行与 ERR_BUSY

- 同一时刻只允许一个传输任务（现有内存模型是 256/512 块 ×1MB 直接内存）。
- 传输任务进行期间，对端发来的控制请求一律回 `ERR_BUSY`；GUI 禁用浏览/删除按钮并排队。
- 实现方式：传输在 handler 线程内同步执行，`HFXService.transferBusy` 置位；`RpcPeer` 在读线程入队前与 handler 出队时各检查一次，命中即回 `ERR_BUSY`（`SHUTDOWN`、`QUERY_INFO` 除外）。读线程回忙时仍走同一把写锁，不与业务帧交错。
- 传输请求本身（10/11）也受 `ERR_BUSY` 约束：对端已有任务时新的推/拉会被拒绝。
- 发起方先启动本地读/发（或收/写）线程，再发出帧并等待应答；应答在传输结束后返回，因此 301 下不再有旧协议的"结束布尔值"往返，结果由应答状态码承载。

### 4.5 线程模型

| 线程 | 职责 |
|---|---|
| `HFX-RpcReader` | 读帧、分派应答/请求、EOF 时触发关闭回调 |
| `HFX-RpcHandler`（单线程） | 执行文件操作并写回应答 |
| 调用线程（GUI/CLI） | 通过 `Future.get(timeout)` 同步等待；传输应答不设超时 |
| `FramedPush` / `FramedPull` | 301 发起方在等待应答期间执行的本地发送/接收线程 |
| 原有传输线程 | 300 保持原样；301 由帧承载请求，数据仍走各自传输通道 |

- 所有响应写回必须走写锁；读线程仅允许经同一写锁写 `ERR_BUSY` 应答，其余应答一律由 handler 写。
- `pending` 用 `ConcurrentHashMap<Integer, CompletableFuture<RpcResult>>`；超时移除并抛 `IOException`。

### 4.6 会话生命周期与多网卡降级（已实现）

- 服务端由"单会话"改为监听循环：一次握手完成后进入会话等待，会话结束清理缓冲、通道与 `RpcPeer` 后回到 accept，无需用户手动停止再启动。
- 301 会话结束信号来自 `RpcPeer` 读线程的 EOF；300 兼容模式没有读线程，由每隔 1s 的空闲探测线程以非阻塞方式读 1 字节判断（-1 即断开），探测与业务读写共用 `ioLock`，传输期间自动跳过。
- 会话结束时回调 `StartServerCallback.onDisconnected(reason)`：界面恢复"停止服务/等待连接"，传输按钮禁用，已连接网卡回"等待连接"，握手失败的网卡保留"连接失败"。
- 客户端每条通道建立连接都带 5s 超时；某张网卡不可达时只跳过该网卡（发 `false`+名称），其余网卡继续握手。服务端对称地跳过并在会话可用通道为空时才放弃本次会话，因此传输入口不会再被单张未就绪网卡长期阻塞。
- 原生缓冲区的释放必须串行化（`synchronized`），停止服务时会话线程与断开线程可能同时释放，重复 free 会直接使进程崩溃。
- 实测：断开后无需重启服务即可再次连接；单张网卡未就绪只影响该通道，其余通道可正常传输。

### 4.7 握手阶段授权（beta5，仅 301）

- **时序**：`HFXC` 魔数 + 版本 → 服务端回 `true`/`false` + 版本 → 版本为 301 时，客户端立即发送授权请求，服务端校验后才继续网卡与缓冲区协商。300 不进入该段。
- **请求**（客户端 → 服务端）：`int AUTH_MAGIC(0x48465841 "HFXA")` + `UTF pcId` + `UTF pcName`。
- **应答**（服务端 → 客户端）：`int AUTH_RESP_MAGIC(0x48465852 "HFXR")` + `byte status`（0 允许 / 1 拒绝）+ `UTF reason`。
- **服务端兼容窗口**：服务端读取授权魔数时用 2s 超时（`readIntWithTimeout`，非阻塞 + Selector）。超时或读到旧版首个协商字段，则视为“旧 301 客户端”，跳过授权继续原流程；读到魔数但内容不匹配则主动断开。这样 beta4 与 beta5 的 301 可以单向兼容，但**推荐两端同时升级**（旧 301 客户端不受按电脑授权保护）。
- **拒绝处理**：服务端先写应答再关闭会话，客户端抛 `PcAuthException`，`getLastConnectError()` 保存可读原因；GUI 连接失败对话框显示该原因，CLI 在退出前打印。
- **电脑标识**（`core/auth/PcIdentity`）：首次运行生成随机 UUID，保存到 `~/.hybridfilexfer/pc-identity.properties`（不可写时退到 `java.io.tmpdir`），连同主机名（截断 64 字符）作为 `pcName`。重装 jar 不变，删除配置文件才会重新生成。
- **手机端允许列表**（`Config.getPcAuthStatus/rememberPc/touchPc/removePc/listPcAuth`）：SharedPreferences `pc_auth_list` 存 JSON 数组 `{id,name,status,last}`，`status` 为 0 未知 / 1 允许 / 2 拒绝。
  - 未知电脑：通过 `PcAuthPrompter` 请求主线程弹框（“允许 / 拒绝”，默认勾选“记住这台电脑”，60s 无操作视为拒绝），结果按是否记住写入列表。
  - 已允许：更新最后连接时间后放行；已拒绝：直接返回拒绝原因，不弹框。
- **与全局开关的关系**：菜单“允许电脑主动访问”仍是最高优先级总闸，关闭时无论列表是否允许都拒绝；开启后才进入按电脑判断。旧的 300 客户端没有授权帧，仍只受总闸约束——这是保留 300 兼容的取舍。
- **手机端管理界面**：菜单“已授权的电脑”列出名称、状态（已允许/已拒绝）、最后连接时间；点击条目二次确认后撤销（删除记录），下次连接重新询问。

### 4.8 路径范围约束（beta5，仅 301）

- **`core/security/PathGuard`**：构造时给出允许根目录列表与 `allowAll` 开关，提供 `checkReadable/checkWritable/checkDelete`；越界统一抛 `PathForbiddenException`（`IOException` 子类，消息可直接给用户）。
- **判定方式**：先拒绝包含 `..` 的路径段；再取“最近的存在祖先”做真实路径解析，最后拼回不存在的尾部。Windows 下 `File.getCanonicalPath` 不解析 junction，因此通过反射调用 `java.nio.file.Path.toRealPath`（以公开接口 `Path` 调用，避免 WindowsPath 访问权限问题）；Linux/Android 的 `getCanonicalPath` 本身解析符号链接。
- **根目录保护**：`checkDelete` 传入 `rejectRoot=true`，删除对象等于或位于根白名单本身时返回“不能删除允许范围的根目录”。
- **手机端根目录**：`Environment.getExternalStorageDirectory()`、`/storage/emulated/0`、`/sdcard`；菜单“允许访问全部存储”对应 `Config.isAllowAllPaths()`（默认 false），打开后 `PathGuard` 放行全部路径。该开关只影响 301 路径校验，不改变 300 行为。
- **电脑端根目录**：`HFXClient` 以接收/发送根目录（默认 `homeDir`，空则为 `/`）构造 `PathGuard`，限制 `rpcListFiles/rpcDeleteFile/rpcMkdir/rpcSendFiles` 与写文件回调。
- **300 兼容**：`JdkWriteFileCall`/`DroidWriteFileCall` 仅在 301 收到非空 `PathGuard` 时检查；300 传 `null`，行为不变。
- **符号链接结论**：早期静态分析担心“`getCanonicalPath` 漏掉链接逃逸”。实测 Android 上符号链接由 `getCanonicalPath` 正确解析；Windows 上目录联接（junction）确实绕过 canonical，改用 `toRealPath` 后 `root\escape\secret.txt` 被正确拒绝。因此保留双重解析策略，而不是额外加黑白名单。
- **Android 删除**：`IOServiceImpl.deleteFile` 对符号链接直接 `File.delete()`（`Os.lstat` + `S_ISLNK` 判断），避免顺着链接删到范围外。

### 4.9 覆盖同名文件（beta5，GUI）

- **冲突询问**：推送与拉取在真正传输前检查目标端是否已存在同名项，弹出“跳过 / 覆盖 / 重命名 / 取消”；一次多于一个冲突时可“全部覆盖 / 全部跳过”。
- **重命名载体**：`RemoteFile` 新增 `transferName` 字段，301 传输请求在每个文件条目后追加 `UTF transferName`（空串表示不改名）；`Directory.generateTransferPath(file, remote, transferName)` 只替换最后一段路径。
- **限制**：只对文件提供重命名；目录冲突只给跳过/覆盖，避免递归改名产生不可预期的大范围改动。
- **校验**：重命名输入拒绝空串、`.`、`..` 和路径分隔符。
- **兼容**：300 的传输请求不带 `transferName`，走原有路径生成逻辑。

### 4.10 心跳与传输失败原因（beta5，仅 301）

- **心跳**：`RpcPeer` 读线程记录 `lastReadAt`；`ScheduledExecutorService` 每 5s 检查，空闲超过 5s 主动发 `CMD_PING`，超过 20s 无任何帧则关闭连接，断开原因固定为“心跳超时，连接已断开”。
- **PING 直回**：读线程识别 `CMD_PING` 后直接写应答，不进入单线程 handler 队列，避免被长传输或大目录请求堵住。
- **客户端回调**：`ClientCallBack.onPeerClosed(reason)` 默认调用 `onExit()`；GUI 覆写后写日志“连接中断”并复位界面，不再长期显示已连接。
- **失败原因**：新增 `core/TransferException`，代码 `1 权限 / 2 路径 / 3 磁盘空间不足 / 4 连接中断 / 5 IO`，`from(Throwable)` 按异常链与消息归类；传输回调新增 `onTransferFailed(int code, String message)`，GUI 映射为“未授权 / 路径不可写 / 磁盘已满 / 连接中断”等可读文案。
- **服务端映射**：路径与权限类异常在 `RpcPeer` 转成 `ERR_PERMISSION` + 原因；`HFXService` 的帧式收/发捕获 `ExecutionException` 后归类并回调 `onTransferFailed`，再把异常抛给 RPC 层形成失败状态码。
- **300**：不启用心跳，仍用 1s 空闲探测判断断开（见 4.6）；传输失败仍走旧布尔回调。

---

## 5. 连接面板规格

### 5.1 连接方式（实现结果）

- GUI 用 Tab 区分两种方式：
  - **ADB**：设备下拉框 + 刷新；连接时按 serial 执行 `adb forward tcp:<port> tcp:<port>`，再连 `127.0.0.1`。
  - **局域网**：输入手机任一网卡 IP，端口取端口框的值。
- 均使用 `connectAuto`；301 成功后才启用主动浏览/文件管理。

### 5.2 adb 三级探测顺序（已实现）

1. 环境变量 `PATH` 中的 `adb`；
2. 程序所在目录（jar/exe 同级）的 `adb.exe` / `adb`；
3. 常见安装位置（如 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`）。

三级都失败时提示缺少 adb；允许手动指定路径并保存到 `Preferences`。工作目录统一设为 adb 所在目录或程序目录，不再依赖 `user.dir`。

### 5.3 设备列表（已实现）

- 使用 `adb devices -l` 解析：序列号、状态、型号；`unauthorized`/`offline` 原样展示并提示处理方式。
- 多设备时从下拉框选择；选中后所有 adb 命令带 `-s <serial>`。
- 连接失败/断开后设备列表可刷新，不需要重启 GUI。

### 5.4 接收目录（已实现）

- **必填**，默认 `${user.home}/HybridFileXfer`，首次使用自动创建；可点“浏览”选择。
- GUI 不暴露空目录选项；连接前会把输入框内容持久化并确保目录存在。

### 5.5 端口（已实现）

- GUI 用数字输入框，默认 **5740**，修改后立即持久化；连接时 adb forward、PC 连接使用同一值。
- CLI 新增 `-p/--port`；手机端从 SharedPreferences 读取（默认 5740），需要与 PC 端保持一致。

---

## 6. 错误分类清单

| 类别 | 触发场景 | 呈现要求 |
|---|---|---|
| 环境缺失 | 无 Java、无 adb、adb 路径无效 | 明确缺失项 + 修复指引，不静默退出 |
| 设备问题 | 无设备、多设备未选、未授权、offline | 区分四种状态分别提示 |
| 转发失败 | adb forward 非 0 | 展示 adb 原始 stderr |
| 网络失败 | 连接被拒、超时、IP 不可达 | 区分"服务端未启动"与"网络不可达" |
| 版本不兼容 | 301 被拒 | 自动降级并提示"已切换到兼容模式" |
| 协议异常 | 帧越界、seq 超时、未知命令 | 断开并记录，不静默 |
| 权限不足 | 手机端未开启 PC 访问、电脑被拒绝、路径越界 | 提示在手机端开启授权，或到“已授权的电脑”撤销拒绝；路径越界提示“路径不在允许范围内” |
| 传输错误 | 读/写错误、通道中断、ERR_BUSY | 分通道展示，可重试 |
| 传输失败原因 | 未授权、路径不可写、磁盘已满、连接中断 | 显示对应可读文案，不再只有“接收文件失败” |
| 磁盘问题 | 接收目录不可写、空间不足 | 传输前预检查；写入失败按 `TransferException` 归类 |
| 覆盖冲突 | 目标端已有同名文件/文件夹 | 弹窗询问跳过/覆盖/重命名，可批量应用 |
| 文件管理 | 新建重名、名称非法、根目录不可写、删除失败 | 弹窗说明具体原因，完成后刷新当前目录 |
| 危险操作 | 删除根目录或当前所在目录 | GUI 直接阻止并提示，不发送 RPC |
| 传输中操作 | 浏览/新建/删除/推送 | 按钮禁用，协议侧回 ERR_BUSY |

---

## 7. 打包与遗留修复

### 7.1 遗留修复结果

1. artifact 产物统一命名为 `HybridFileXfer.jar`。**已完成**：beta4 的 jar 为 140,503 字节。
2. `start-by-adb.bat`、`start-by-network.bat` 的 `-D...` 参数移到 `-jar` 之前。**已完成**。
3. 帮助文案与 README 的 `--device` 问题。**已完成**：代码同时接受 `--switch-device` 与 `--device`。
4. `start.exe` 统一入口。**部分完成**：启动前会检查同目录 `HybridFileXfer.jar`，可选择系统 Java 或内置 JDK；但仍只拼 CLI 参数，不拉起 GUI。发行包用 `启动图形界面.bat` 启动 GUI。
5. artifact 配置中的 `HybirdFileXfer.jar` 笔误。**无对应文件**：本仓库副本不含 `.idea/artifacts`。
6. `HybridFileXferLauncher`（C#）。**未纳入发行包**：本环境缺少 .NET SDK，无法构建；源码保留。

### 7.2 构建与发行流程（beta5 实际执行）

1. 编译：`javac --release 8 -encoding UTF-8 -cp <annotations桩> -d classes @sources.txt`。
2. 资源：把 `messages.properties` 系列复制到 `classes` 根目录，`messages_gui.properties`、`messages_gui_zh_CN.properties` 复制到 `classes` 的 gui 包目录（新版构建曾因缺少根目录 bundle 触发 `MissingResourceException`）。
3. 打包：`jar --create --file HybridFileXfer.jar --main-class top.weixiansen574.hybridfilexfer.Main -C classes .`（beta5 为 155,161 字节）。
4. 组装目录：jar、`start.exe`、adb 三件套（`adb.exe`/`AdbWinApi.dll`/`AdbWinUsbApi.dll`）、Android APK（beta5 debug，6,231,483 字节）、bat 脚本、使用说明，共 13 个文件。
5. 压缩：`HybridFileXfer-3.0.0-beta5-win-x64.zip`。
6. 记录校验值：8,210,979 字节，SHA-256 `E1838ABE5EB8881A840B8E69D649D7BA8593DD103350EE849908C4891A59A5C4`。
7. 冒烟：解压到干净目录 → `java -jar` 启动 → 观察 8s 进程存活、stderr 为空 → 结束并清理。
8. 提交：中文提交信息，推送 `origin/feature/gui`，用 `git log --oneline` 复核提交号。

---

## 8. 两份 core 的同步校验

- 共享范围：`core/**` 与 `nio/DataByteChannel`。
- 同步步骤：
  1. 只改一侧，另一侧用文件复制覆盖；
  2. 对 `core`、`nio` 逐文件计算 SHA-256 并比对，必须全部一致；
  3. PC 独有与 Android 独有目录不参与比对，但涉及接口签名的改动需人工确认。
- 建议在 CI 增加一步"core 哈希一致性检查"，阶段 2 落地。
- 实现结果：beta5 打包前对 `core`、`nio` 逐文件比对，32 个共享文件 SHA-256 全部一致；CI 步骤尚未添加，仍靠人工检查。

---

## 9. 安全默认值

- 手机端"允许电脑主动访问"默认**关闭**；未开启时所有客户端发起的请求返回 `ERR_PERMISSION`（已实现：菜单可勾选，实时生效）。
- 首次连接时手机端弹出确认（可勾选“记住这台电脑”）——**已实现（beta5，301）**：按电脑 UUID 记住允许/拒绝，菜单“已授权的电脑”可查看与撤销；电脑标识为持久 UUID + 主机名，换 IP 不影响判断。
- 全局开关仍是最高优先级总闸：关闭时任何电脑（含已允许）都会被拒绝；旧 300 客户端没有授权帧，只受总闸约束。
- 路径默认限制在外部存储根目录内，越界、`..`、符号链接/junction 逃逸均被拒绝；“允许访问全部存储”为显式开关，默认关闭。
- 默认仅允许局域网访问；不自动开放公网。
- 删除等破坏性操作在 GUI 中二次确认并提示“删除后不可恢复”（已实现）；允许范围的根目录本身禁止删除。

---

## 10. 分阶段实施计划

| 阶段 | 内容 | 验收 |
|---|---|---|
| 0 | 基线复现：现有 300 模式推/拉跑通，记录日志 | 行为与 README 一致 |
| 1（已完成） | 301 帧、seq、写锁、双端读分发、版本协商与 300 回退；仅 LIST_FILES/DELETE/MKDIR 双向可用 | 新 PC↔新 App 列表双向成功；新 PC↔旧 App 自动降级；旧 PC↔新 App 不回归 |
| 2（已完成） | 传输任务迁移到帧（job 化）、QUERY_INFO、端口可配置贯通手机端 | 301 模式下推/拉可用 |
| 3（已完成） | Swing 骨架：主窗口、连接面板（ADB/局域网）、日志、被动传输进度 | 无新依赖，jar < 300KB（beta4 实测 137KB） |
| 4（已完成） | 手机文件浏览、推送/拉取、队列与通道速度 | 完整闭环 |
| 5（已完成） | 授权开关、i18n、配置持久化、打包与脚本修复、文档 | 发布候选 |

最终 beta4 在阶段 5 基础上补齐了自绘窗口、深色主题、拖放推送、远端新建/删除与进度节流，已发布试运行包。

beta5 在同一 301 工作流内顺序完成三项加固：握手阶段按电脑授权（4.7）、路径范围约束（4.8）与覆盖冲突询问（4.9）、心跳与传输失败原因（4.10）。三项均限定在 301，300 路径保持不变。

---

## 11. 风险清单

1. 控制通道半双工改造若混入旧裸协议，会随机错位——必须整版切换（300 全旧 / 301 全新），禁止混用。
2. `DataByteChannel` 非线程安全，写必须单锁；读也只有读线程。
3. 版本硬校验：301 被旧服务端拒绝时必须重建连接，不能复用半关闭的 socket。
4. 两份 core 手工同步容易漏改，必须哈希校验。
5. 301 下列表与推/拉已迁移；GUI 完成后 PC 端已有界面验证，CLI 继续保留作回归与排障工具。
6. 直接内存缓冲大（256/512 × 1MB），并发任务会 OOM，必须单任务。
7. GUI 回调在非 EDT 线程，必须 `invokeLater` 并节流速度事件。
8. 大目录一次返回全量会卡顿，阶段 4 需增量加载或分页命令。
9. 手机路径不能用 `File` 解析，必须走 `Directory.generateTransferPath`。
10. 端口可配置后，adb forward、手机监听、PC 连接三处必须使用同一值。
11. 自绘窗口必须补齐拖动/双击/缩放/最大化边界，否则用户会明显感到“假窗口”。
12. 主题初始化必须早于控件字段创建；否则深色模式会留下白底输入框/表格。
13. 进度回调不能逐块刷 UI，必须聚合 + 节流 + 固定状态栏宽度，否则大文件必闪。
14. 授权交换必须严格插在“版本确认之后、网卡协商之前”，否则两端会把授权字节误读成网卡数；服务端用 2s 超时兼容旧 301 客户端。
15. `PathGuard` 在 Windows 必须走 `toRealPath`，只靠 `getCanonicalPath` 挡不住 junction；反射调用要以公开接口 `Path` 为目标类型。
16. 传输改名是协议字段（`transferName`），两端 core 必须同步；只改一端会导致文件条目错位。
17. 心跳只能在读线程直回 PING，走 handler 队列会被长任务拖到误判掉线。

以上 1-4、6、7、11-17 已在 beta5 处理；5 已完成；8-10 仍作为后续注意事项。

---

## 12. 阶段 1 验收标准

1. `--protocol=301` 时：PC 能列出手机目录（客户端→服务端 LIST_FILES），手机端发起的 LIST_FILES 也能被 PC 正确应答（服务端→客户端）。
2. `--protocol=auto` 连接旧版服务端（版本 300）时：被拒后自动以 300 重连，旧功能（列表、推/拉）不回归。
3. 新旧协议下握手、缓冲区协商、目录条目字段完全兼容。
4. 两份 core 的共享文件哈希一致。

验收结果（beta4）：以上 4 项均在模拟器 + Robot 实测通过；另完成大文件进度、窗口交互、深浅主题、拖放推送与远端新建/删除的回归。

beta5 追加实测（真机 MGFVB20629003959 + Windows 本机）：

- 授权：未知电脑弹框（名称 `DESKTOP-9BM38MT`、UUID `b8eba96e-…`），允许+记住后再次连接免询问；拒绝+记住后重连返回明确拒绝原因；“已授权的电脑”可撤销，撤销后列表清空。
- 路径：`/sdcard/../data`、`/data` 被拒；`/sdcard/Download` 正常；`/data/local/tmp` 删除被拒；删除 `/sdcard` 提示“不能删除允许范围的根目录”；Windows junction 目录外文件被拒。
- 心跳：静默 socket 探针在 20,042ms 收到“心跳超时，连接已断开”。
- 改名拉取：`transferName` 生效，目标文件按新名落地。

---

## 13. 当前状态与未完成事项

### 13.1 已修复的既有缺陷

- **单会话**：服务端改为监听循环，一次会话结束后自动回到 accept，无需手动停止再启动。
- **多网卡未就绪**：每条传输通道 5s 超时，未就绪的网卡单独跳过，不再拖住整个会话。
- **主题初始化顺序**：`ThemeManager.init()` 提前到 `new MainFrame()` 之前，修复深色启动时输入框/表格白底。
- **进度闪烁**：逐块 UI 刷新改为按文件聚合 + 100ms 节流；状态栏固定宽度，速度表原地更新；512MB 实测回退 0 次。
- **对话框尺寸**：自绘外壳补 `getPreferredSize()`，修复 `pack()` 后 0×0 不可见。
- **文件选择器主题**：内嵌 `JFileChooser` 递归上色，不再出现深色对话框里白底列表。
- **300 空闲探测**：无 RPC 读线程时用 1s 空闲探测判断断开，避免会话悬挂。
- **握手授权缺失**：301 现已在握手阶段按电脑校验，未知电脑弹框、已拒绝电脑直接返回原因，不再是“连上后才报无权限”。
- **路径越界**：`PathGuard` 统一拦截 `..`、绝对路径越界、符号链接/Windows junction 逃逸，并保护允许范围的根目录。
- **Wi-Fi 掉线后假在线**：301 增加 5s/20s 心跳，读线程直回 PING，掉线约 20s 内复位界面。
- **传输失败只有笼统文案**：`TransferException` 归类权限/路径/磁盘满/连接中断/IO，GUI 显示具体原因。

### 13.2 仍未做 / 已知限制

- `start.exe` 仍只走 CLI 参数，不会拉起 GUI；GUI 需用 `启动图形界面.bat` 或无参 `java -jar`。是否让 `start.exe` 默认启动 GUI 待定（上一轮相关改动被取消，尚未实现）。
- `HybridFileXferLauncher` 未纳入发行包：本环境无 .NET SDK，无法构建；源码保留。
- 301 授权对**旧 301 客户端**不生效：beta4 及更早的 301 客户端没有授权帧，服务端 2s 超时后按旧流程继续；按电脑授权需要两端同为 beta5 及以上。300 客户端同样只受全局总闸约束。
- 目录冲突不提供重命名，只支持跳过/覆盖；需要改名请先手动改名后再推/拉。
- 300 回退模式下主动浏览、新建、删除不可用，推送按钮也禁用；协议层仍可被动收发，需要主动功能必须升级手机端到 301。
- 大目录仍一次性返回全量列表，没有分页或增量加载，目录特别大时浏览会卡顿。
- 手机端删除是递归删除；超大目录可能超过 RPC 超时，界面会显示失败但手机端可能仍在继续执行。
- 验证环境为 Android 真机（MGFVB20629003959，Android 10）与 Windows 本机；GUI 覆盖冲突弹窗已实现但未做完整人工走查，APK 仍为 beta debug 包。
- PC 修改端口后，手机端也要改到同一端口；两端未做自动发现。
- CI 尚未加入 core 哈希一致性检查，仍靠打包前人工比对。
