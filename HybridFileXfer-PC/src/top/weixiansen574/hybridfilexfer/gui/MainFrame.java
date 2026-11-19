package top.weixiansen574.hybridfilexfer.gui;

import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcException;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcProtocol;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Swing 主界面：连接面板 + 手机文件浏览 + 传输队列 + 日志。 */
public class MainFrame extends JFrame {
    private final JTextArea logArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("未连接");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JButton disconnectButton = new JButton("断开");
    private final JComboBox<AdbUtil.AdbDevice> deviceBox = new JComboBox<>();
    private final JButton refreshButton = new JButton("刷新设备");
    private final JButton connectAdbButton = new JButton("连接（ADB）");
    private final JTextField hostField = new JTextField("192.168.1.100", 14);
    private final JButton connectLanButton = new JButton("连接（局域网）");
    private final JTextField receiveDirField = new JTextField(24);
    private final JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(GuiConfig.getPort(), 1, 65535, 1));

    private final JTextField remotePathField = new JTextField();
    private final JButton upButton = new JButton("上级目录");
    private final JButton refreshFilesButton = new JButton("刷新");
    private final JButton pullButton = new JButton("拉取到电脑");
    private final JButton pushButton = new JButton("推送文件…");
    private final DefaultTableModel fileModel = new DefaultTableModel(
            new Object[]{"名称", "类型", "大小", "修改时间"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable fileTable = new JTable(fileModel);
    private final List<RemoteFile> currentFiles = new ArrayList<>();

    private final DefaultTableModel queueModel = new DefaultTableModel(
            new Object[]{"操作", "路径", "状态"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable queueTable = new JTable(queueModel);
    private final DefaultTableModel speedModel = new DefaultTableModel(
            new Object[]{"通道", "速度"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable speedTable = new JTable(speedModel);

    private volatile JdkHFXClient client;
    private volatile boolean transferActive;
    private ExecutorService transferExecutor;
    private int remoteFileSystem = Directory.FILE_SYSTEM_UNIX;
    private String remoteHome = "/";
    private String currentRemotePath = "/";

    public MainFrame() {
        super("多轨快传 - HybridFileXfer");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(980, 680));
        setLayout(new BorderLayout(8, 8));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);

        disconnectButton.setEnabled(false);
        progressBar.setStringPainted(true);
        progressBar.setString("空闲");
        setBrowsingEnabled(false);
        log("端口：" + GuiConfig.getPort() + "，接收目录：" + GuiConfig.getReceiveDir());

        refreshButton.addActionListener(e -> refreshDevices());
        connectAdbButton.addActionListener(e -> connectViaAdb());
        connectLanButton.addActionListener(e -> connectViaLan());
        disconnectButton.addActionListener(e -> disconnect());
        portSpinner.addChangeListener(e -> GuiConfig.setPort((Integer) portSpinner.getValue()));
        upButton.addActionListener(e -> navigateUp());
        refreshFilesButton.addActionListener(e -> refreshRemoteFiles());
        pullButton.addActionListener(e -> pullSelected());
        pushButton.addActionListener(e -> pushFiles());
        fileTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && client != null) {
                    int row = fileTable.getSelectedRow();
                    if (row >= 0 && row < currentFiles.size()) {
                        RemoteFile file = currentFiles.get(row);
                        if (file.isDirectory()) {
                            navigateTo(file.getPath());
                        }
                    }
                }
            }
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                disconnect();
                dispose();
                System.exit(0);
            }
        });
        pack();
        setLocationRelativeTo(null);
        refreshDevices();
    }

    private JPanel buildTopPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JPanel portRow = new JPanel(new GridBagLayout());
        portRow.setBorder(BorderFactory.createTitledBorder("连接"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        portRow.add(new JLabel("端口："), c);
        c.gridx = 1;
        portRow.add(portSpinner, c);
        c.gridx = 2;
        portRow.add(new JLabel("接收目录："), c);
        c.gridx = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        receiveDirField.setText(GuiConfig.getReceiveDir());
        receiveDirField.addActionListener(e -> applyReceiveDir());
        portRow.add(receiveDirField, c);
        c.gridx = 4;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        JButton browseButton = new JButton("浏览…");
        browseButton.addActionListener(e -> chooseReceiveDir());
        portRow.add(browseButton, c);
        panel.add(portRow);

        JTabbedPane tabs = new JTabbedPane();
        JPanel adbPanel = new JPanel(new GridBagLayout());
        GridBagConstraints ac = new GridBagConstraints();
        ac.insets = new Insets(4, 4, 4, 4);
        ac.anchor = GridBagConstraints.WEST;
        ac.gridx = 0;
        adbPanel.add(new JLabel("设备："), ac);
        ac.gridx = 1;
        ac.weightx = 1;
        ac.fill = GridBagConstraints.HORIZONTAL;
        adbPanel.add(deviceBox, ac);
        ac.gridx = 2;
        ac.weightx = 0;
        ac.fill = GridBagConstraints.NONE;
        adbPanel.add(refreshButton, ac);
        ac.gridx = 3;
        adbPanel.add(connectAdbButton, ac);
        tabs.addTab("ADB", adbPanel);

        JPanel lanPanel = new JPanel(new GridBagLayout());
        GridBagConstraints lc = new GridBagConstraints();
        lc.insets = new Insets(4, 4, 4, 4);
        lc.anchor = GridBagConstraints.WEST;
        lc.gridx = 0;
        lanPanel.add(new JLabel("手机 IP："), lc);
        lc.gridx = 1;
        lc.weightx = 1;
        lc.fill = GridBagConstraints.HORIZONTAL;
        lanPanel.add(hostField, lc);
        lc.gridx = 2;
        lc.weightx = 0;
        lc.fill = GridBagConstraints.NONE;
        lanPanel.add(connectLanButton, lc);
        tabs.addTab("局域网", lanPanel);
        panel.add(tabs);
        return panel;
    }

    private JSplitPane buildCenterPanel() {
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("日志"));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("手机文件", buildFilePanel());
        tabs.addTab("传输", buildTransferPanel());

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, logScroll);
        splitPane.setResizeWeight(0.62);
        return splitPane;
    }

    private JPanel buildFilePanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        JPanel toolbar = new JPanel(new BorderLayout(4, 4));
        remotePathField.setEditable(false);
        remotePathField.setText("/");
        toolbar.add(remotePathField, BorderLayout.CENTER);

        JPanel buttons = new JPanel();
        buttons.add(upButton);
        buttons.add(refreshFilesButton);
        buttons.add(pullButton);
        buttons.add(pushButton);
        toolbar.add(buttons, BorderLayout.EAST);
        panel.add(toolbar, BorderLayout.NORTH);

        fileTable.setRowHeight(22);
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(340);
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(60);
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        fileTable.getColumnModel().getColumn(3).setPreferredWidth(150);
        panel.add(new JScrollPane(fileTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildTransferPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(new JScrollPane(queueTable), BorderLayout.CENTER);
        JScrollPane speedScroll = new JScrollPane(speedTable);
        speedScroll.setPreferredSize(new Dimension(240, 120));
        speedScroll.setBorder(BorderFactory.createTitledBorder("通道速度"));
        panel.add(speedScroll, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.add(statusLabel, BorderLayout.WEST);
        panel.add(progressBar, BorderLayout.CENTER);
        panel.add(disconnectButton, BorderLayout.EAST);
        return panel;
    }

    private void chooseReceiveDir() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setSelectedFile(new File(receiveDirField.getText()));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            receiveDirField.setText(chooser.getSelectedFile().getAbsolutePath());
            applyReceiveDir();
        }
    }

    private void applyReceiveDir() {
        String dir = receiveDirField.getText().trim();
        if (dir.isEmpty()) {
            JOptionPane.showMessageDialog(this, "接收目录不能为空", "提示", JOptionPane.WARNING_MESSAGE);
            receiveDirField.setText(GuiConfig.getReceiveDir());
            return;
        }
        File file = new File(dir);
        if (!file.exists() && !file.mkdirs()) {
            JOptionPane.showMessageDialog(this, "无法创建接收目录：" + dir, "提示", JOptionPane.ERROR_MESSAGE);
            return;
        }
        GuiConfig.setReceiveDir(dir);
        log("接收目录已设置为：" + dir);
    }

    private void refreshDevices() {
        refreshButton.setEnabled(false);
        new Thread(() -> {
            String adb = AdbUtil.findAdb();
            if (adb == null) {
                uiLater(() -> {
                    log("未找到 adb。请安装 platform-tools，或把 adb 放到程序目录，", true);
                    log("也可以设置环境变量 PATH 后重启程序。", true);
                    setStatus("未找到 adb");
                    refreshButton.setEnabled(true);
                });
                return;
            }
            try {
                List<AdbUtil.AdbDevice> devices = AdbUtil.listDevices(adb);
                uiLater(() -> {
                    deviceBox.removeAllItems();
                    for (AdbUtil.AdbDevice device : devices) {
                        deviceBox.addItem(device);
                    }
                    if (devices.isEmpty()) {
                        log("未检测到设备（adb：" + adb + "）。请连接数据线并打开 USB 调试。", true);
                    } else {
                        log("检测到 " + devices.size() + " 台设备（adb：" + adb + "）");
                    }
                    refreshButton.setEnabled(true);
                });
            } catch (Exception ex) {
                uiLater(() -> {
                    log("执行 adb devices 失败：" + ex, true);
                    refreshButton.setEnabled(true);
                });
            }
        }, "HFX-AdbDevices").start();
    }

    private void connectViaAdb() {
        AdbUtil.AdbDevice device = (AdbUtil.AdbDevice) deviceBox.getSelectedItem();
        if (device == null) {
            JOptionPane.showMessageDialog(this, "请先刷新并选择设备", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!"device".equals(device.state)) {
            JOptionPane.showMessageDialog(this,
                    "设备当前状态：" + device.stateText() + "\n请先在手机上允许 USB 调试后重试。",
                    "设备不可用", JOptionPane.WARNING_MESSAGE);
            return;
        }
        startConnect("127.0.0.1", device.serial);
    }

    private void connectViaLan() {
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入手机 IP", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        startConnect(host, null);
    }

    private void startConnect(String host, String adbSerial) {
        if (client != null) {
            return;
        }
        applyReceiveDirQuietly();
        int port = (Integer) portSpinner.getValue();
        GuiConfig.setPort(port);
        String receiveDir = receiveDirField.getText().trim();
        setConnectingState(true);
        setStatus("连接中…");
        new Thread(() -> {
            try {
                if (adbSerial != null) {
                    String adb = AdbUtil.findAdb();
                    if (adb == null) {
                        uiLog("未找到 adb，无法执行端口转发", true);
                        return;
                    }
                    uiLog("执行端口转发：tcp:" + port + " -> tcp:" + port);
                    String error = AdbUtil.forward(adb, adbSerial, port);
                    if (error != null) {
                        uiLog("端口转发失败：" + error, true);
                        return;
                    }
                }
                JdkHFXClient newClient = new JdkHFXClient(host, port, receiveDir);
                ConnectServerCallback callback = new GuiConnectCallback();
                if (!newClient.connectAuto(callback)) {
                    uiLog("连接失败", true);
                    return;
                }
                client = newClient;
                String[] info = null;
                if (newClient.getProtocolVersion() == RpcProtocol.VERSION_RPC) {
                    try {
                        info = newClient.queryInfo();
                        remoteFileSystem = Integer.parseInt(info[0]);
                        remoteHome = info[1];
                    } catch (Exception e) {
                        uiLog("查询手机主目录失败：" + e.getMessage(), true);
                    }
                }
                final String initialPath = remoteHome;
                uiLater(() -> {
                    setConnectingState(false);
                    disconnectButton.setEnabled(true);
                    setBrowsingEnabled(newClient.getProtocolVersion() == RpcProtocol.VERSION_RPC);
                    setStatus("已连接（协议 " + newClient.getProtocolVersion() + "）");
                    if (newClient.getProtocolVersion() != RpcProtocol.VERSION_RPC) {
                        log("当前为 300 兼容模式，手机文件浏览与主动推拉需要 301 协议", true);
                    } else if (initialPath != null) {
                        navigateTo(initialPath);
                    }
                });
                // start() 会一直阻塞到会话结束，被动传输也在其中处理
                newClient.start(new GuiTransferCallback());
            } catch (Exception e) {
                uiLog("连接异常：" + e, true);
            } finally {
                client = null;
                uiLater(() -> {
                    setConnectingState(false);
                    disconnectButton.setEnabled(false);
                    setBrowsingEnabled(false);
                    fileModel.setRowCount(0);
                    speedModel.setRowCount(0);
                    currentFiles.clear();
                    progressBar.setValue(0);
                    progressBar.setString("空闲");
                    setStatus("未连接");
                });
            }
        }, "HFX-GuiSession").start();
    }

    private void disconnect() {
        JdkHFXClient current = client;
        if (current != null) {
            log("正在断开连接…");
            new Thread(current::disconnect, "HFX-GuiDisconnect").start();
        }
        setConnectingState(false);
        disconnectButton.setEnabled(false);
        setStatus("未连接");
    }

    private void applyReceiveDirQuietly() {
        String dir = receiveDirField.getText().trim();
        if (dir.isEmpty()) {
            receiveDirField.setText(GuiConfig.getReceiveDir());
            return;
        }
        File file = new File(dir);
        if (!file.exists()) {
            file.mkdirs();
        }
        GuiConfig.setReceiveDir(dir);
    }

    private void setConnectingState(boolean connecting) {
        connectAdbButton.setEnabled(!connecting);
        connectLanButton.setEnabled(!connecting);
        refreshButton.setEnabled(!connecting);
        deviceBox.setEnabled(!connecting);
        portSpinner.setEnabled(!connecting);
        receiveDirField.setEnabled(!connecting);
    }

    private void setBrowsingEnabled(boolean enabled) {
        upButton.setEnabled(enabled);
        refreshFilesButton.setEnabled(enabled);
        pullButton.setEnabled(enabled);
        pushButton.setEnabled(enabled);
        fileTable.setEnabled(enabled);
    }

    private void navigateUp() {
        if (currentRemotePath.isEmpty() || "/".equals(currentRemotePath)) {
            return;
        }
        String path = currentRemotePath;
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int index = path.lastIndexOf('/');
        navigateTo(index <= 0 ? "/" : path.substring(0, index));
    }

    private void navigateTo(String path) {
        if (client == null) {
            return;
        }
        setBrowsingEnabled(false);
        new Thread(() -> {
            try {
                List<RemoteFile> files = client.requestListFiles(path);
                uiLater(() -> {
                    currentRemotePath = path;
                    remotePathField.setText(path);
                    fileModel.setRowCount(0);
                    currentFiles.clear();
                    if (files == null) {
                        log("目录不可读：" + path, true);
                    } else {
                        currentFiles.addAll(files);
                        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        for (RemoteFile file : files) {
                            String modified = file.lastModified() > 0
                                    ? format.format(new Date(file.lastModified())) : "";
                            fileModel.addRow(new Object[]{
                                    file.getName(),
                                    file.isDirectory() ? "目录" : "文件",
                                    file.isDirectory() ? "" : Utils.formatFileSize(file.getSize()),
                                    modified
                            });
                        }
                    }
                    setBrowsingEnabled(!transferActive && client != null);
                });
            } catch (RpcException e) {
                uiLater(() -> {
                    log("读取目录失败（status=" + e.status + "）：" + e.getMessage(), true);
                    if (e.status == RpcProtocol.ERR_BUSY) {
                        log("手机端正在传输，稍后再试", true);
                    }
                    setBrowsingEnabled(!transferActive && client != null);
                });
            } catch (Exception e) {
                uiLater(() -> {
                    log("读取目录失败：" + e, true);
                    setBrowsingEnabled(!transferActive && client != null);
                });
            }
        }, "HFX-Browse").start();
    }

    private void refreshRemoteFiles() {
        navigateTo(currentRemotePath);
    }

    private void pullSelected() {
        int[] rows = fileTable.getSelectedRows();
        if (rows.length == 0) {
            JOptionPane.showMessageDialog(this, "请先选择要拉取的文件或目录", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<RemoteFile> files = new ArrayList<>();
        for (int row : rows) {
            if (row >= 0 && row < currentFiles.size()) {
                files.add(currentFiles.get(row));
            }
        }
        applyReceiveDirQuietly();
        String receiveDir = receiveDirField.getText().trim();
        File localDir = new File(receiveDir);
        if (!localDir.exists() && !localDir.mkdirs()) {
            JOptionPane.showMessageDialog(this, "接收目录不可用：" + receiveDir, "提示", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String sourceDirPath = currentRemotePath;
        int remoteFs = remoteFileSystem;
        Directory local = new Directory(localDir.getAbsolutePath(), Directory.getCurrentFileSystem());
        Directory remote = new Directory(sourceDirPath, remoteFs);
        enqueue("拉取 " + files.size() + " 项", files.get(0).getPath(), () -> {
            client.pullFiles(files, local, remote, new GuiTransferCallback());
            return true;
        });
    }

    private void pushFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File[] selected = chooser.getSelectedFiles();
        if (selected == null || selected.length == 0) {
            return;
        }
        String targetPath = currentRemotePath;
        int remoteFs = remoteFileSystem;
        List<File> files = new ArrayList<>();
        for (File file : selected) {
            files.add(file);
        }
        enqueue("推送 " + files.size() + " 项", files.get(0).getAbsolutePath(), () -> {
            for (File file : files) {
                List<RemoteFile> remoteFiles = new ArrayList<>();
                remoteFiles.add(new RemoteFile(file));
                Directory local = new Directory(file.getParent(), Directory.getCurrentFileSystem());
                Directory remote = new Directory(targetPath, remoteFs);
                client.pushFiles(remoteFiles, local, remote, new GuiTransferCallback());
            }
            return true;
        });
    }

    private interface TransferJob {
        boolean run() throws Exception;
    }

    private void enqueue(String operation, String path, TransferJob job) {
        if (client == null) {
            return;
        }
        queueModel.addRow(new Object[]{operation, path, "排队中"});
        int row = queueModel.getRowCount() - 1;
        if (transferExecutor == null) {
            transferExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "HFX-GuiTransfer");
                thread.setDaemon(true);
                return thread;
            });
        }
        transferExecutor.execute(() -> {
            transferActive = true;
            uiLater(() -> {
                queueModel.setValueAt("进行中", row, 2);
                setBrowsingEnabled(false);
            });
            boolean ok;
            try {
                ok = job.run();
            } catch (Exception e) {
                ok = false;
                uiLog("传输失败：" + e, true);
            }
            boolean finalOk = ok;
            uiLater(() -> {
                queueModel.setValueAt(finalOk ? "完成" : "失败", row, 2);
                transferActive = false;
                if (client != null) {
                    setBrowsingEnabled(true);
                    navigateTo(currentRemotePath);
                }
            });
        });
    }

    private void uiLog(String message) {
        uiLog(message, false);
    }

    private void uiLog(String message, boolean error) {
        uiLater(() -> log(message, error));
    }

    private void log(String message) {
        log(message, false);
    }

    private void log(String message, boolean error) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        logArea.append("[" + time + "] " + (error ? "错误：" : "") + message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private void uiLater(Runnable runnable) {
        SwingUtilities.invokeLater(runnable);
    }

    private class GuiConnectCallback implements ConnectServerCallback {
        @Override
        public void onConnectingControlChannel(String address, int port) {
            uiLog("正在连接控制通道：" + address + ":" + port);
        }

        @Override
        public void onVersionMismatch(int localVersion, int remoteVersion) {
            uiLog("对端协议版本不匹配（本端 " + localVersion + "，对端 " + remoteVersion + "），已切换到兼容模式");
        }

        @Override
        public void onConnectControlFailed() {
            uiLog("控制通道连接失败，请确认手机端已启动服务、端口一致且网络可达", true);
        }

        @Override
        public void onConnectingTransferChannel(String name, InetAddress inetAddress, InetAddress bindAddress) {
            uiLog("正在连接传输通道：" + name + " " + inetAddress.getHostAddress());
        }

        @Override
        public void onConnectTransferChannelFailed(String name, InetAddress inetAddress, Exception e) {
            uiLog("传输通道连接失败：" + name + " " + inetAddress.getHostAddress() + "（" + e.getMessage() + "），已跳过该网卡", true);
        }

        @Override
        public void onOOM(int createdBuffers, int requiredBuffers, long maxMemoryMB, String osArch) {
            uiLog("本机内存不足，仅创建 " + createdBuffers + "/" + requiredBuffers + " 个缓冲区块", true);
        }

        @Override
        public void onRemoteOOM() {
            uiLog("手机端内存不足，请调小缓冲区块数", true);
        }

        @Override
        public void onConnectSuccess(List<String> channelNames) {
            uiLog("连接成功，通道：" + channelNames);
        }
    }

    private class GuiTransferCallback implements ClientCallBack {
        @Override
        public void onReceiving() {
            uiLog("手机端正在推送文件到电脑…");
            uiLater(() -> progressBar.setString("接收中"));
        }

        @Override
        public void onSending() {
            uiLog("手机端正在从电脑拉取文件…");
            uiLater(() -> progressBar.setString("发送中"));
        }

        @Override
        public void onExit() {
            uiLog("对端已断开连接");
        }

        @Override
        public void onFileUploading(String iName, String path, long targetSize, long totalSize) {
            updateProgress(path, targetSize, totalSize);
        }

        @Override
        public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {
            updateProgress(path, targetSize, totalSize);
        }

        @Override
        public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {
            long total = 0;
            for (TrafficInfo info : trafficInfoList) {
                total += info.uploadTraffic + info.downloadTraffic;
            }
            final long speed = total;
            uiLater(() -> {
                setStatus("传输中 " + Utils.formatSpeed(speed));
                speedModel.setRowCount(0);
                for (TrafficInfo info : trafficInfoList) {
                    speedModel.addRow(new Object[]{info.iName,
                            Utils.formatSpeed(info.uploadTraffic + info.downloadTraffic)});
                }
            });
        }

        @Override
        public void onChannelComplete(String iName, long traffic, long time) {
            uiLog("通道 " + iName + " 完成，用时 " + Utils.formatTime(time));
        }

        @Override
        public void onChannelError(String iName, int errorType, String message) {
            uiLog("通道 " + iName + " 出错：" + message, true);
        }

        @Override
        public void onReadFileError(String message) {
            uiLog("读取文件失败：" + message, true);
        }

        @Override
        public void onWriteFileError(String message) {
            uiLog("写入文件失败：" + message, true);
        }

        @Override
        public void onComplete(boolean isUpload, long traffic, long time) {
            uiLog((isUpload ? "发送" : "接收") + "完成，传输量 " + Utils.formatFileSize(traffic)
                    + "，用时 " + Utils.formatTime(time));
            uiLater(() -> {
                progressBar.setValue(100);
                progressBar.setString("完成");
            });
        }

        @Override
        public void onIncomplete() {
            uiLog("传输未完成", true);
            uiLater(() -> progressBar.setString("未完成"));
        }
    }

    private void updateProgress(String path, long targetSize, long totalSize) {
        uiLater(() -> {
            if (totalSize > 0) {
                int percent = (int) Math.min(100, targetSize * 100 / totalSize);
                progressBar.setValue(percent);
                progressBar.setString(percent + "%");
            }
            setStatus("传输中：" + path);
        });
    }

    /** 供启动入口使用。 */
    public static void showWindow() {
        SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));
    }
}
