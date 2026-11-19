# HybridFileXfer GUI 与双向协议实施设计

> 状态：已评审通过，分阶段实施中（阶段 1、2 已完成）
> 适用分支：`feature/gui`
> 本文档记录讨论结论与代码基线事实，后续实施以此为准，避免偏离。

---

## 1. 项目现状（基线事实）

### 1.1 角色与端口

- 手机端（Android App）是**服务端**：`HFXServer` 绑定端口、先接受控制通道，再为每张网卡接受一条传输通道。
- 电脑端（PC，Java）是**客户端**：`JdkHFXClient` 连接控制通道与各传输通道。
- 端口硬编码 **5740**：`JdkHFXClient(serverAddress, 5740, homeDir)`（`Main.java` 第 196-199 行）、`Config.getServerPort()` 返回 5740。
- 协议版本 `HFXService.VERSION_CODE = 300`；握手首包为魔数 `"HFXC"` + int 版本号。

### 1.2 控制通道是单向发起的半双工模型

- 命令**只由服务端发起**，客户端只读取并回复。
- `HFXClient.start()`（第 152-178 行）是客户端唯一的命令读取循环。
- 服务端 `HFXServer` **没有任何命令读取循环**，它只在自己写命令后同步读取回执（bool / int / UTF）。
- 因此客户端当前**无法**主动发送任何命令，服务端也**没有**对应的读取分支。
- 命令标识不区分方向、无保留位：`ControllerIdentifiers` 仅使用 `0=SHUTDOWN、1=LIST_FILES、2=DELETE_FILE、3=MKDIR、10=REQUEST_RECEIVE、11=REQUEST_SEND`，`4–9` 空号，`12+` 未使用。
- `DataByteChannel` 非线程安全：所有原始类型共用同一个 8 字节 `ByteBuffer` 字段，且无同步。任何多线程写必须自行加锁。

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
| Swing | 约 200–300KB jar（当前 91.8KB） | 采用 |
| JavaFX | 最小模块 20MB+，jlink 约 40MB+ | 排除：违背体积最小 |
| 本地 HTTP + 浏览器 | 约 100–200KB 资源，零依赖 | 排除：仍无法原生选目录、需额外端口与安全处理，体积收益不明显 |

补充：若后期需要更现代外观，可加单个约 700KB 的 FlatLaf；首版不引入。

---

## 4. 协议 301：双向帧

### 4.1 版本协商与 300 回退

- 新增 `VERSION_RPC = 301`，`VERSION_LEGACY = 300`。
- 服务端（新版手机 App）同时接受 300 与 301；旧版只接受 300。
- 客户端（新版 PC）策略：
  - `--protocol=300`：只走旧协议；
  - `--protocol=301`：只走 301，失败即报错；
  - `--protocol=auto`：先尝试 301，若被拒绝且对方版本为 300，则**重新建连**并以 300 完成握手。
- 阶段 1 默认仍为 300，避免新 PC + 新手机 App 时，手机 UI 中尚未迁移的传输命令把帧流打乱；GUI 完成后默认改为 `auto`。
- 旧服务端拒绝 301 的行为是现成的：`versionCode != VERSION_CODE` → 回写 `false` + 自身版本号并关闭连接。

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

命令（阶段 1 实现前三项；阶段 2 已迁移传输与信息查询）：

| 值 | 名称 | 方向 | 请求体 | 响应体 |
|---|---|---|---|---|
| 0 | SHUTDOWN | 双向 | 空 | 空 |
| 1 | LIST_FILES | 双向 | UTF path | int count（-1 表示失败）+ 条目 |
| 2 | DELETE_FILE | 双向 | UTF path | byte bool |
| 3 | MKDIR | 双向 | UTF parent + UTF child | byte bool |
| 10 | REQUEST_RECEIVE / RECEIVE_FILES | 双向（阶段 2 起） | 空 | OK；接收在 handler 线程内完成 |
| 11 | REQUEST_SEND / SEND_FILES | 双向（阶段 2 起） | int count + UTF path… + UTF 目标目录 + int 目标文件系统 + UTF 源目录 | OK；发送在 handler 线程内完成 |
| 100 | QUERY_INFO | 双向 | 空 | int 文件系统 + UTF 主目录 |
| 120 | CANCEL_JOB（预留） | 双向 | int jobId | byte bool |

目录条目沿用旧格式：`UTF name, UTF path, long lastModified, long size, boolean isDirectory`。

错误码（`byte status`）：

| 值 | 名称 | 含义 |
|---|---|---|
| 0 | OK | 成功 |
| 1 | ERR_UNKNOWN_COMMAND | 未知命令 |
| 2 | ERR_UNSUPPORTED | 该版本暂未实现 |
| 3 | ERR_IO | 本端 IO 异常 |
| 4 | ERR_BUSY | 传输进行中 |
| 5 | ERR_PERMISSION | 手机端未授权 PC 访问 |

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

### 4.6 会话生命周期与多网卡降级（阶段 2 一并修复）

- 服务端由"单会话"改为监听循环：一次握手完成后进入会话等待，会话结束清理缓冲、通道与 `RpcPeer` 后回到 accept，无需用户手动停止再启动。
- 301 会话结束信号来自 `RpcPeer` 读线程的 EOF；300 兼容模式没有读线程，由每隔 1s 的空闲探测线程以非阻塞方式读 1 字节判断（-1 即断开），探测与业务读写共用 `ioLock`，传输期间自动跳过。
- 会话结束时回调 `StartServerCallback.onDisconnected(reason)`：界面恢复"停止服务/等待连接"，传输按钮禁用，已连接网卡回"等待连接"，握手失败的网卡保留"连接失败"。
- 客户端每条通道建立连接都带 5s 超时；某张网卡不可达时只跳过该网卡（发 `false`+名称），其余网卡继续握手。服务端对称地跳过并在会话可用通道为空时才放弃本次会话，因此传输入口不会再被单张未就绪网卡长期阻塞。
- 原生缓冲区的释放必须串行化（`synchronized`），停止服务时会话线程与断开线程可能同时释放，重复 free 会直接使进程崩溃。

---

## 5. 连接面板规格

### 5.1 连接方式

- **ADB**：可选择设备；连接前执行端口转发 `adb forward tcp:<port> tcp:<port>`，再连 `127.0.0.1`。
- **局域网**：输入手机任一网卡 IP，端口取配置值。

### 5.2 adb 三级探测顺序（替代当前 `user.dir` 依赖）

1. 环境变量 `PATH` 中的 `adb`；
2. 程序所在目录（jar/exe 同级）的 `adb.exe` / `adb`；
3. 常见安装位置（如 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`）。

三级都失败时给出明确提示，并允许用户手动指定 adb 路径（保存到配置）。工作目录统一设为 adb 所在目录或程序目录，不再依赖 `user.dir`。

### 5.3 设备列表

- 使用 `adb devices -l` 解析：序列号、状态（`device` / `unauthorized` / `offline`）、型号。
- `unauthorized` 必须显式提示"请在手机上允许 USB 调试"，不得过滤掉后误报"无设备"。
- 多设备时按列表选择；选中后所有 adb 命令带 `-s <serial>`。

### 5.4 接收目录

- **必填**，默认 `${user.home}/HybridFileXfer`，首次启动时自动创建。
- 不再接受 `-d` 默认 `"/"` 的行为；GUI 不暴露空目录选项。

### 5.5 端口

- 可配置，默认 **5740**；同一端口同时用于监听（手机端）与转发/连接（PC 端）。
- PC 端新增 `-p/--port`；手机端改为读取 SharedPreferences（默认 5740），转发命令与连接必须使用同一端口。

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
| 权限不足 | 手机端未开启 PC 访问 | 提示在手机端开启授权 |
| 传输错误 | 读/写错误、通道中断、ERR_BUSY | 分通道展示，可重试 |
| 磁盘问题 | 接收目录不可写、空间不足 | 传输前预检查 |

---

## 7. 打包与遗留修复

1. artifact 产物统一命名为 `HybridFileXfer.jar`（含 `HybridFileXferLauncher`、`start.c`、bat、README 的引用）。
2. 修复 `start-by-adb.bat`、`start-by-network.bat` 的 `-jar` 参数顺序：`-D...` 必须在 `-jar` 之前。
3. 帮助文案与 README 的 `--device` 改为 `--switch-device`，或代码同时接受 `--device` 作为别名（推荐后者，兼容已写好的脚本）。
4. `start.exe` 不再直接拼 java 命令，改为调用 Launcher 或统一入口，避免参数分散。
5. artifact 配置中的 `HybirdFileXfer.jar` 笔误清理；MANIFEST 相对路径改为 `$PROJECT_DIR$/src/META-INF/MANIFEST.MF`，去掉对仓库目录名的隐式依赖。

---

## 8. 两份 core 的同步校验

- 共享范围：`core/**` 与 `nio/DataByteChannel`。
- 同步步骤：
  1. 只改一侧，另一侧用文件复制覆盖；
  2. 对 `core`、`nio` 逐文件计算 SHA-256 并比对，必须全部一致；
  3. PC 独有与 Android 独有目录不参与比对，但涉及接口签名的改动需人工确认。
- 建议在 CI 增加一步"core 哈希一致性检查"，阶段 2 落地。

---

## 9. 安全默认值

- 手机端"允许电脑主动访问"默认**关闭**；未开启时所有客户端发起的请求返回 `ERR_PERMISSION`。
- 首次连接时手机端弹出确认（可记住该电脑），提示可访问范围为共享根目录。
- 默认仅允许局域网访问；不自动开放公网。
- 删除等破坏性操作在 GUI 二次确认，手机端记录最近操作日志。

---

## 10. 分阶段实施计划

| 阶段 | 内容 | 验收 |
|---|---|---|
| 0 | 基线复现：现有 300 模式推/拉跑通，记录日志 | 行为与 README 一致 |
| 1（已完成） | 301 帧、seq、写锁、双端读分发、版本协商与 300 回退；仅 LIST_FILES/DELETE/MKDIR 双向可用 | 新 PC↔新 App 列表双向成功；新 PC↔旧 App 自动降级；旧 PC↔新 App 不回归 |
| 2（已完成） | 传输任务迁移到帧（job 化）、QUERY_INFO、端口可配置贯通手机端 | 301 模式下推/拉可用 |
| 3（已完成） | Swing 骨架：主窗口、连接面板（ADB/局域网）、日志、被动传输进度 | 无新依赖，jar < 300KB（实测 92KB） |
| 4（已完成） | 手机文件浏览、推送/拉取、队列与通道速度 | 完整闭环 |
| 5（进行中） | 授权开关、i18n、配置持久化、打包与脚本修复、文档 | 发布候选 |

---

## 11. 风险清单

1. 控制通道半双工改造若混入旧裸协议，会随机错位——必须整版切换（300 全旧 / 301 全新），禁止混用。
2. `DataByteChannel` 非线程安全，写必须单锁；读也只有读线程。
3. 版本硬校验：301 被旧服务端拒绝时必须重建连接，不能复用半关闭的 socket。
4. 两份 core 手工同步容易漏改，必须哈希校验。
5. 301 下列表与推/拉已迁移；PC 侧 GUI 未完成前，PC 端只能用 CLI 验证，别把"没有界面"误判为回归。
6. 直接内存缓冲大（256/512 × 1MB），并发任务会 OOM，必须单任务。
7. GUI 回调在非 EDT 线程，必须 `invokeLater` 并节流速度事件。
8. 大目录一次返回全量会卡顿，阶段 4 需增量加载或分页命令。
9. 手机路径不能用 `File` 解析，必须走 `Directory.generateTransferPath`。
10. 端口可配置后，adb forward、手机监听、PC 连接三处必须使用同一值。

---

## 12. 阶段 1 验收标准

1. `--protocol=301` 时：PC 能列出手机目录（客户端→服务端 LIST_FILES），手机端发起的 LIST_FILES 也能被 PC 正确应答（服务端→客户端）。
2. `--protocol=auto` 连接旧版服务端（版本 300）时：被拒后自动以 300 重连，旧功能（列表、推/拉）不回归。
3. 新旧协议下握手、缓冲区协商、目录条目字段完全兼容。
4. 两份 core 的共享文件哈希一致。
