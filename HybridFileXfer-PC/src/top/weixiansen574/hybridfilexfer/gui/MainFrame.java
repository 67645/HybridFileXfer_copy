package top.weixiansen574.hybridfilexfer.gui;

import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient;

import javax.swing.BorderFactory;
import javax.swing.Box;
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
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/** 阶段 3：Swing 骨架，连接面板 + 日志 + 被动传输进度。 */
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

    private volatile JdkHFXClient client;
    private Thread sessionThread;

    public MainFrame() {
        super("多轨快传 - HybridFileXfer");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(760, 520));
        setLayout(new BorderLayout(8, 8));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);

        disconnectButton.setEnabled(false);
        progressBar.setStringPainted(true);
        progressBar.setString("空闲");
        log("端口：" + GuiConfig.getPort() + "，接收目录：" + GuiConfig.getReceiveDir());

        refreshButton.addActionListener(e -> refreshDevices());
        connectAdbButton.addActionListener(e -> connectViaAdb());
        connectLanButton.addActionListener(e -> connectViaLan());
        disconnectButton.addActionListener(e -> disconnect());
        portSpinner.addChangeListener(e -> GuiConfig.setPort((Integer) portSpinner.getValue()));

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

    private JPanel buildCenterPanel() {
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("日志"));
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(scrollPane, BorderLayout.CENTER);
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
        sessionThread = new Thread(() -> {
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
                uiLater(() -> {
                    setConnectingState(false);
                    disconnectButton.setEnabled(true);
                    setStatus("已连接（协议 " + newClient.getProtocolVersion() + "）");
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
                    progressBar.setValue(0);
                    progressBar.setString("空闲");
                    setStatus("未连接");
                });
            }
        }, "HFX-GuiSession");
        sessionThread.setDaemon(true);
        sessionThread.start();
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
            uiLater(() -> setStatus("传输中 " + Utils.formatSpeed(speed)));
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
