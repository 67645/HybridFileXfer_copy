package top.weixiansen574.hybridfilexfer.gui;

import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcException;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcProtocol;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
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
public class MainFrame extends JFrame implements WindowChrome.Host {
    private final JTextArea logArea = new JTextArea();
    private final JLabel statusLabel = new JLabel(GuiStrings.get("statusNotConnected"));
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JButton disconnectButton = new JButton(GuiStrings.get("disconnect"));
    private final JComboBox<AdbUtil.AdbDevice> deviceBox = new JComboBox<>();
    private final JButton refreshButton = new JButton(GuiStrings.get("refreshDevices"));
    private final JButton connectAdbButton = new JButton(GuiStrings.get("connectAdb"));
    private final JTextField hostField = new JTextField("192.168.1.100", 14);
    private final JButton connectLanButton = new JButton(GuiStrings.get("connectLan"));
    private final JTextField receiveDirField = new JTextField(24);
    private final JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(GuiConfig.getPort(), 1, 65535, 1));

    private final JTextField remotePathField = new JTextField();
    private final JButton upButton = new JButton(GuiStrings.get("up"));
    private final JButton refreshFilesButton = new JButton(GuiStrings.get("refresh"));
    private final JButton pullButton = new JButton(GuiStrings.get("pull"));
    private final JButton pushButton = new JButton(GuiStrings.get("push"));
    private final DefaultTableModel fileModel = new DefaultTableModel(
            new Object[]{GuiStrings.get("colName"), GuiStrings.get("colType"),
                    GuiStrings.get("colSize"), GuiStrings.get("colModified")}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable fileTable = new JTable(fileModel);
    private final JScrollPane fileScroll = new JScrollPane(fileTable);
    private final List<RemoteFile> currentFiles = new ArrayList<>();
    private static final Color DROP_HIGHLIGHT = new Color(0x3D74D0);

    private Border normalFileScrollBorder;
    private String statusBeforeDrag;
    private boolean dropHighlightActive;
    private WindowChrome chrome;
    private boolean maximized;
    private Rectangle normalBounds;

    private final DefaultTableModel queueModel = new DefaultTableModel(
            new Object[]{GuiStrings.get("colOperation"), GuiStrings.get("colPath"),
                    GuiStrings.get("colStatus")}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable queueTable = new JTable(queueModel);
    private final DefaultTableModel speedModel = new DefaultTableModel(
            new Object[]{GuiStrings.get("colChannel"), GuiStrings.get("colSpeed")}, 0) {
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
        super(GuiStrings.get("title"));
        ThemeManager.init();
        setUndecorated(true);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(960, 640));

        chrome = new WindowChrome(GuiStrings.get("title"), this, true, true);

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(buildTopPanel(), BorderLayout.NORTH);
        content.add(buildCenterPanel(), BorderLayout.CENTER);
        content.add(buildBottomPanel(), BorderLayout.SOUTH);

        chrome.getBody().add(content, BorderLayout.CENTER);
        chrome.setMenuBar(buildMenuBar());
        setContentPane(chrome);

        disconnectButton.setEnabled(false);
        progressBar.setStringPainted(true);
        progressBar.setString(GuiStrings.get("idle"));
        setBrowsingEnabled(false);
        log(GuiStrings.get("port") + " " + GuiConfig.getPort()
                + ", " + GuiStrings.get("receiveDir") + " " + GuiConfig.getReceiveDir());

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
                handleClose();
            }
        });
        applyFileTableLayout();
        ThemeManager.addListener(() -> {
            applyFileTableLayout();
            chrome.refreshTheme();
            repaint();
        });
        setSize(1120, 760);
        setLocationRelativeTo(null);
        refreshDevices();
    }

    @Override
    public void minimize() {
        setState(JFrame.ICONIFIED);
    }

    @Override
    public void toggleMaximize() {
        if (!maximized) {
            normalBounds = getBounds();
            setBounds(GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds());
            maximized = true;
        } else {
            if (normalBounds != null) {
                setBounds(normalBounds);
            }
            maximized = false;
        }
        chrome.setMaximized(maximized);
    }

    @Override
    public void close() {
        handleClose();
    }

    @Override
    public boolean isMaximized() {
        return maximized;
    }

    private void handleClose() {
        disconnect();
        dispose();
        System.exit(0);
    }

    /** 主题切换会重建表格 UI，这里统一恢复列宽、行高与边框。 */
    private void applyFileTableLayout() {
        styleTable(fileTable);
        styleTable(queueTable);
        styleTable(speedTable);
        if (fileTable.getColumnModel().getColumnCount() >= 4) {
            fileTable.getColumnModel().getColumn(0).setPreferredWidth(400);
            fileTable.getColumnModel().getColumn(1).setPreferredWidth(70);
            fileTable.getColumnModel().getColumn(2).setPreferredWidth(100);
            fileTable.getColumnModel().getColumn(3).setPreferredWidth(170);
        }
        if (queueTable.getColumnModel().getColumnCount() >= 3) {
            queueTable.getColumnModel().getColumn(0).setPreferredWidth(150);
            queueTable.getColumnModel().getColumn(1).setPreferredWidth(520);
            queueTable.getColumnModel().getColumn(2).setPreferredWidth(90);
        }
        if (speedTable.getColumnModel().getColumnCount() >= 2) {
            speedTable.getColumnModel().getColumn(0).setPreferredWidth(140);
            speedTable.getColumnModel().getColumn(1).setPreferredWidth(140);
        }
        Border border = BorderFactory.createLineBorder(ThemeManager.palette().border);
        normalFileScrollBorder = border;
        if (!dropHighlightActive) {
            fileScroll.setBorder(border);
        }
    }

    private void styleTable(JTable table) {
        table.setRowHeight(26);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setPreferredSize(new Dimension(0, 32));
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ThemeManager.palette().border));
    }

    /** 外观菜单：跟随系统 / 浅色 / 深色，选择持久化到 GuiConfig。 */
    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        JMenu menu = new JMenu(GuiStrings.get("menuAppearance"));
        ButtonGroup group = new ButtonGroup();
        String mode = ThemeManager.getMode();
        JRadioButtonMenuItem system = new JRadioButtonMenuItem(GuiStrings.get("themeSystem"),
                ThemeManager.SYSTEM.equals(mode));
        JRadioButtonMenuItem light = new JRadioButtonMenuItem(GuiStrings.get("themeLight"),
                ThemeManager.LIGHT.equals(mode));
        JRadioButtonMenuItem dark = new JRadioButtonMenuItem(GuiStrings.get("themeDark"),
                ThemeManager.DARK.equals(mode));
        system.addActionListener(e -> ThemeManager.setMode(ThemeManager.SYSTEM));
        light.addActionListener(e -> ThemeManager.setMode(ThemeManager.LIGHT));
        dark.addActionListener(e -> ThemeManager.setMode(ThemeManager.DARK));
        group.add(system);
        group.add(light);
        group.add(dark);
        menu.add(system);
        menu.add(light);
        menu.add(dark);
        bar.add(menu);
        return bar;
    }

    private JPanel buildTopPanel() {
        JPanel connect = new JPanel(new GridBagLayout());
        connect.setOpaque(false);
        connect.setBorder(BorderFactory.createTitledBorder(GuiStrings.get("connectGroup")));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 8, 6, 8);
        c.anchor = GridBagConstraints.WEST;
        c.gridy = 0;

        c.gridx = 0;
        connect.add(new JLabel(GuiStrings.get("port")), c);

        c.gridx = 1;
        portSpinner.setPreferredSize(new Dimension(96, 30));
        connect.add(portSpinner, c);

        c.gridx = 2;
        connect.add(new JLabel(GuiStrings.get("receiveDir")), c);

        c.gridx = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        receiveDirField.setText(GuiConfig.getReceiveDir());
        receiveDirField.setMargin(new Insets(4, 8, 4, 8));
        receiveDirField.setPreferredSize(new Dimension(360, 30));
        receiveDirField.addActionListener(e -> applyReceiveDir());
        connect.add(receiveDirField, c);

        c.gridx = 4;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        JButton browseButton = new JButton(GuiStrings.get("browse"));
        sizeButton(browseButton, 92);
        browseButton.addActionListener(e -> chooseReceiveDir());
        connect.add(browseButton, c);

        c.gridy = 1;
        c.gridx = 0;
        c.gridwidth = 5;
        c.weightx = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(2, 8, 8, 8);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(GuiStrings.get("tabAdb"), buildAdbPanel());
        tabs.addTab(GuiStrings.get("tabLan"), buildLanPanel());
        connect.add(tabs, c);
        return connect;
    }

    private JPanel buildAdbPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 8);
        c.anchor = GridBagConstraints.WEST;
        c.gridy = 0;
        c.gridx = 0;
        panel.add(new JLabel(GuiStrings.get("device")), c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        deviceBox.setPreferredSize(new Dimension(320, 30));
        panel.add(deviceBox, c);

        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        sizeButton(refreshButton, 110);
        panel.add(refreshButton, c);

        c.gridx = 3;
        sizeButton(connectAdbButton, 132);
        panel.add(connectAdbButton, c);
        return panel;
    }

    private JPanel buildLanPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 8);
        c.anchor = GridBagConstraints.WEST;
        c.gridy = 0;
        c.gridx = 0;
        panel.add(new JLabel(GuiStrings.get("phoneIp")), c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        hostField.setMargin(new Insets(4, 8, 4, 8));
        hostField.setPreferredSize(new Dimension(260, 30));
        panel.add(hostField, c);

        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        sizeButton(connectLanButton, 132);
        panel.add(connectLanButton, c);
        return panel;
    }

    /** 统一按钮高度，宽度不低于给定值。 */
    private static void sizeButton(JButton button, int minWidth) {
        Dimension preferred = button.getPreferredSize();
        button.setPreferredSize(new Dimension(Math.max(preferred.width, minWidth), 30));
    }

    private JSplitPane buildCenterPanel() {
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setMargin(new Insets(8, 10, 8, 10));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder(GuiStrings.get("logTitle")));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(GuiStrings.get("tabFiles"), buildFilePanel());
        tabs.addTab(GuiStrings.get("tabTransfer"), buildTransferPanel());

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, logScroll);
        splitPane.setResizeWeight(0.62);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        return splitPane;
    }

    private JPanel buildFilePanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 2, 2, 2));

        JPanel toolbar = new JPanel(new BorderLayout(10, 0));
        toolbar.setOpaque(false);
        remotePathField.setEditable(false);
        remotePathField.setText("/");
        remotePathField.setMargin(new Insets(4, 8, 4, 8));
        remotePathField.setPreferredSize(new Dimension(200, 30));
        remotePathField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeManager.palette().border),
                BorderFactory.createEmptyBorder(0, 6, 0, 6)));
        toolbar.add(remotePathField, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        sizeButton(upButton, 100);
        sizeButton(refreshFilesButton, 86);
        sizeButton(pullButton, 110);
        sizeButton(pushButton, 110);
        buttons.add(upButton);
        buttons.add(refreshFilesButton);
        buttons.add(pullButton);
        buttons.add(pushButton);
        toolbar.add(buttons, BorderLayout.EAST);
        panel.add(toolbar, BorderLayout.NORTH);

        fileScroll.setToolTipText(GuiStrings.get("dropTooltip"));
        installDropTarget();
        panel.add(fileScroll, BorderLayout.CENTER);
        return panel;
    }

    /** 接收资源管理器拖入的文件/文件夹：拖入时高亮，松开后推送到当前目录。 */
    private void installDropTarget() {
        normalFileScrollBorder = fileScroll.getBorder();
        DropTargetAdapter adapter = new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    statusBeforeDrag = statusLabel.getText();
                    normalFileScrollBorder = fileScroll.getBorder();
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                    updateDropHighlight(true, targetPathAt(dtde.getLocation()));
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void dragOver(DropTargetDragEvent dtde) {
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                    updateDropHighlight(true, targetPathAt(dtde.getLocation()));
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void dragExit(DropTargetEvent dte) {
                updateDropHighlight(false, null);
            }

            @Override
            public void drop(DropTargetDropEvent dtde) {
                updateDropHighlight(false, null);
                if (!dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.rejectDrop();
                    showDropMessage(GuiStrings.get("dropUnsupported"));
                    return;
                }
                dtde.acceptDrop(DnDConstants.ACTION_COPY);
                Point point = dtde.getLocation();
                List<File> files = new ArrayList<>();
                try {
                    Transferable transferable = dtde.getTransferable();
                    Object data = transferable.getTransferData(DataFlavor.javaFileListFlavor);
                    if (data instanceof List) {
                        for (Object item : (List<?>) data) {
                            if (item instanceof File) {
                                files.add((File) item);
                            }
                        }
                    }
                } catch (Exception e) {
                    dtde.dropComplete(false);
                    showDropMessage(GuiStrings.get("dropUnsupported"));
                    return;
                }
                dtde.dropComplete(true);
                handleDropFiles(files, targetPathAt(point));
            }
        };
        new DropTarget(fileScroll, DnDConstants.ACTION_COPY, adapter, true);
    }

    /** 拖放时的目标目录：光标所在（或已选中）的手机目录，否则为当前目录。 */
    private String targetPathAt(Point location) {
        if (location != null && client != null) {
            Point tablePoint = SwingUtilities.convertPoint(fileScroll, location, fileTable);
            int row = fileTable.rowAtPoint(tablePoint);
            if (row >= 0 && row < currentFiles.size() && currentFiles.get(row).isDirectory()) {
                return currentFiles.get(row).getPath();
            }
        }
        int[] rows = fileTable.getSelectedRows();
        if (rows.length == 1 && rows[0] < currentFiles.size() && currentFiles.get(rows[0]).isDirectory()) {
            return currentFiles.get(rows[0]).getPath();
        }
        return currentRemotePath;
    }

    private void updateDropHighlight(boolean active, String targetPath) {
        dropHighlightActive = active;
        if (active) {
            fileScroll.setBorder(BorderFactory.createLineBorder(DROP_HIGHLIGHT, 2));
            setStatus(client == null ? GuiStrings.get("dropNotConnected")
                    : GuiStrings.get("dropHint", targetPath));
        } else {
            fileScroll.setBorder(normalFileScrollBorder);
            setStatus(statusBeforeDrag != null ? statusBeforeDrag
                    : (client == null ? GuiStrings.get("statusNotConnected")
                    : GuiStrings.get("statusConnected", RpcProtocol.VERSION_RPC)));
        }
        fileScroll.repaint();
    }

    /** 拖入的路径校验与推送；未连接、忙或内容非法时给出明确提示。 */
    private void handleDropFiles(List<File> files, String targetPath) {
        if (client == null) {
            showDropMessage(GuiStrings.get("dropNotConnected"));
            return;
        }
        if (transferActive) {
            showDropMessage(GuiStrings.get("dropBusy"));
            return;
        }
        if (files == null || files.isEmpty()) {
            showDropMessage(GuiStrings.get("dropEmpty"));
            return;
        }
        List<File> valid = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (File file : files) {
            if (file != null && file.exists() && file.canRead()) {
                valid.add(file);
            } else if (file != null) {
                invalid.add(file.getAbsolutePath());
            }
        }
        if (!invalid.isEmpty()) {
            log(GuiStrings.get("dropInvalid", invalid.size(), String.join(", ", invalid)), true);
        }
        if (valid.isEmpty()) {
            showDropMessage(GuiStrings.get("dropEmpty"));
            return;
        }
        log(GuiStrings.get("dropAccepted", valid.size(), targetPath));
        pushLocalFiles(valid, targetPath);
    }

    private void showDropMessage(String message) {
        ThemedDialog.warn(this, GuiStrings.get("tabFiles"), message);
    }

    private JPanel buildTransferPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 2, 2, 2));
        JScrollPane queueScroll = new JScrollPane(queueTable);
        queueScroll.setBorder(BorderFactory.createLineBorder(ThemeManager.palette().border));
        panel.add(queueScroll, BorderLayout.CENTER);
        JScrollPane speedScroll = new JScrollPane(speedTable);
        speedScroll.setPreferredSize(new Dimension(240, 130));
        speedScroll.setBorder(BorderFactory.createTitledBorder(GuiStrings.get("channelSpeed")));
        panel.add(speedScroll, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(2, 4, 0, 4));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
        progressBar.setPreferredSize(new Dimension(0, 22));
        sizeButton(disconnectButton, 96);
        panel.add(statusLabel, BorderLayout.WEST);
        panel.add(progressBar, BorderLayout.CENTER);
        panel.add(disconnectButton, BorderLayout.EAST);
        return panel;
    }

    private void chooseReceiveDir() {
        File selected = ThemedDialog.chooseDirectory(this, GuiStrings.get("chooseDirTitle"),
                new File(receiveDirField.getText()));
        if (selected != null) {
            receiveDirField.setText(selected.getAbsolutePath());
            applyReceiveDir();
        }
    }

    private void applyReceiveDir() {
        String dir = receiveDirField.getText().trim();
        if (dir.isEmpty()) {
            ThemedDialog.warn(this, GuiStrings.get("tabTransfer"), GuiStrings.get("receiveDirEmpty"));
            receiveDirField.setText(GuiConfig.getReceiveDir());
            return;
        }
        File file = new File(dir);
        if (!file.exists() && !file.mkdirs()) {
            ThemedDialog.error(this, GuiStrings.get("tabTransfer"),
                    GuiStrings.get("receiveDirCreateFailed", dir));
            return;
        }
        GuiConfig.setReceiveDir(dir);
        log(GuiStrings.get("receiveDirSet", dir));
    }

    private void refreshDevices() {
        refreshButton.setEnabled(false);
        new Thread(() -> {
            String adb = AdbUtil.findAdb();
            if (adb == null) {
                uiLater(() -> {
                    log(GuiStrings.get("adbMissing1"), true);
                    log(GuiStrings.get("adbMissing2"), true);
                    setStatus(GuiStrings.get("statusNoAdb"));
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
                        log(GuiStrings.get("noDevice", adb), true);
                    } else {
                        log(GuiStrings.get("devicesFound", devices.size(), adb));
                    }
                    refreshButton.setEnabled(true);
                });
            } catch (Exception ex) {
                uiLater(() -> {
                    log(GuiStrings.get("adbDevicesFailed", ex), true);
                    refreshButton.setEnabled(true);
                });
            }
        }, "HFX-AdbDevices").start();
    }

    private void connectViaAdb() {
        AdbUtil.AdbDevice device = (AdbUtil.AdbDevice) deviceBox.getSelectedItem();
        if (device == null) {
            ThemedDialog.warn(this, GuiStrings.get("tabAdb"), GuiStrings.get("selectDevice"));
            return;
        }
        if (!"device".equals(device.state)) {
            ThemedDialog.warn(this, GuiStrings.get("tabAdb"),
                    GuiStrings.get("deviceNotReady", device.stateText()) + "\n"
                            + GuiStrings.get("deviceNotReadyHint"));
            return;
        }
        startConnect("127.0.0.1", device.serial);
    }

    private void connectViaLan() {
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            ThemedDialog.warn(this, GuiStrings.get("tabLan"), GuiStrings.get("phoneIpEmpty"));
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
        setStatus(GuiStrings.get("statusConnecting"));
        new Thread(() -> {
            try {
                if (adbSerial != null) {
                    String adb = AdbUtil.findAdb();
                    if (adb == null) {
                        uiLog(GuiStrings.get("forwardNoAdb"), true);
                        return;
                    }
                    uiLog(GuiStrings.get("forwarding", port, port));
                    String error = AdbUtil.forward(adb, adbSerial, port);
                    if (error != null) {
                        uiLog(GuiStrings.get("forwardFailed", error), true);
                        return;
                    }
                }
                JdkHFXClient newClient = new JdkHFXClient(host, port, receiveDir);
                ConnectServerCallback callback = new GuiConnectCallback();
                if (!newClient.connectAuto(callback)) {
                    uiLog(GuiStrings.get("connectFailed"), true);
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
                        uiLog(GuiStrings.get("queryHomeFailed", e.getMessage()), true);
                    }
                }
                final String initialPath = remoteHome;
                uiLater(() -> {
                    setConnectingState(false);
                    disconnectButton.setEnabled(true);
                    setBrowsingEnabled(newClient.getProtocolVersion() == RpcProtocol.VERSION_RPC);
                    setStatus(GuiStrings.get("statusConnected", newClient.getProtocolVersion()));
                    if (newClient.getProtocolVersion() != RpcProtocol.VERSION_RPC) {
                        log(GuiStrings.get("protocol300Hint"), true);
                    } else if (initialPath != null) {
                        navigateTo(initialPath);
                    }
                });
                // start() 会一直阻塞到会话结束，被动传输也在其中处理
                newClient.start(new GuiTransferCallback());
            } catch (Exception e) {
                uiLog(GuiStrings.get("connectError", e), true);
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
                    progressBar.setString(GuiStrings.get("idle"));
                    setStatus(GuiStrings.get("statusNotConnected"));
                });
            }
        }, "HFX-GuiSession").start();
    }

    private void disconnect() {
        JdkHFXClient current = client;
        if (current != null) {
            log(GuiStrings.get("disconnecting"));
            new Thread(current::disconnect, "HFX-GuiDisconnect").start();
        }
        setConnectingState(false);
        disconnectButton.setEnabled(false);
        setStatus(GuiStrings.get("statusNotConnected"));
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
                        log(GuiStrings.get("browseFailed", path, "null"), true);
                    } else {
                        currentFiles.addAll(files);
                        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        for (RemoteFile file : files) {
                            String modified = file.lastModified() > 0
                                    ? format.format(new Date(file.lastModified())) : "";
                            fileModel.addRow(new Object[]{
                                    file.getName(),
                                    file.isDirectory() ? GuiStrings.get("dir") : GuiStrings.get("file"),
                                    file.isDirectory() ? "" : Utils.formatFileSize(file.getSize()),
                                    modified
                            });
                        }
                    }
                    setBrowsingEnabled(!transferActive && client != null);
                });
            } catch (RpcException e) {
                uiLater(() -> {
                    log(GuiStrings.get("browseFailed", path, "status=" + e.status + " " + e.getMessage()), true);
                    if (e.status == RpcProtocol.ERR_BUSY) {
                        log(GuiStrings.get("busyRetry"), true);
                    }
                    setBrowsingEnabled(!transferActive && client != null);
                });
            } catch (Exception e) {
                uiLater(() -> {
                    log(GuiStrings.get("browseFailed", path, e), true);
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
            ThemedDialog.warn(this, GuiStrings.get("tabFiles"), GuiStrings.get("selectToPull"));
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
            ThemedDialog.error(this, GuiStrings.get("tabFiles"),
                    GuiStrings.get("receiveDirUnavailable", receiveDir));
            return;
        }
        String sourceDirPath = currentRemotePath;
        int remoteFs = remoteFileSystem;
        Directory local = new Directory(localDir.getAbsolutePath(), Directory.getCurrentFileSystem());
        Directory remote = new Directory(sourceDirPath, remoteFs);
        enqueue(GuiStrings.get("pullTitle", files.size()), files.get(0).getPath(), () -> {
            client.pullFiles(files, local, remote, new GuiTransferCallback());
            return true;
        });
    }

    private void pushFiles() {
        File[] selected = ThemedDialog.chooseFiles(this, GuiStrings.get("chooseFileTitle"));
        if (selected == null || selected.length == 0) {
            return;
        }
        List<File> files = new ArrayList<>();
        for (File file : selected) {
            files.add(file);
        }
        pushLocalFiles(files, currentRemotePath);
    }

    /** 把本机文件/文件夹推送到目标目录；文件夹由传输层递归展开。 */
    private void pushLocalFiles(List<File> files, String targetPath) {
        if (files.isEmpty() || client == null) {
            return;
        }
        int remoteFs = remoteFileSystem;
        enqueue(GuiStrings.get("pushTitle", files.size()), targetPath, () -> {
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
        queueModel.addRow(new Object[]{operation, path, GuiStrings.get("queueWaiting")});
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
                queueModel.setValueAt(GuiStrings.get("queueRunning"), row, 2);
                setBrowsingEnabled(false);
            });
            boolean ok;
            try {
                ok = job.run();
            } catch (Exception e) {
                ok = false;
                uiLog(GuiStrings.get("transferFailed", e), true);
            }
            boolean finalOk = ok;
            uiLater(() -> {
                queueModel.setValueAt(GuiStrings.get(finalOk ? "queueDone" : "queueFailed"), row, 2);
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
        logArea.append("[" + time + "] " + (error ? "! " : "") + message + "\n");
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
            uiLog(GuiStrings.get("connectingControl", address, port));
        }

        @Override
        public void onVersionMismatch(int localVersion, int remoteVersion) {
            uiLog(GuiStrings.get("versionMismatch", localVersion, remoteVersion));
        }

        @Override
        public void onConnectControlFailed() {
            uiLog(GuiStrings.get("controlFailed"), true);
        }

        @Override
        public void onConnectingTransferChannel(String name, InetAddress inetAddress, InetAddress bindAddress) {
            uiLog(GuiStrings.get("connectingTransfer", name, inetAddress.getHostAddress()));
        }

        @Override
        public void onConnectTransferChannelFailed(String name, InetAddress inetAddress, Exception e) {
            uiLog(GuiStrings.get("transferChannelFailed", name, inetAddress.getHostAddress(), e.getMessage()), true);
        }

        @Override
        public void onOOM(int createdBuffers, int requiredBuffers, long maxMemoryMB, String osArch) {
            uiLog(GuiStrings.get("localOom", createdBuffers, requiredBuffers), true);
        }

        @Override
        public void onRemoteOOM() {
            uiLog(GuiStrings.get("remoteOom"), true);
        }

        @Override
        public void onConnectSuccess(List<String> channelNames) {
            uiLog(GuiStrings.get("connectSuccess", channelNames));
        }
    }

    private class GuiTransferCallback implements ClientCallBack {
        @Override
        public void onReceiving() {
            uiLog(GuiStrings.get("peerPushing"));
            uiLater(() -> progressBar.setString(GuiStrings.get("statusReceiving")));
        }

        @Override
        public void onSending() {
            uiLog(GuiStrings.get("peerPulling"));
            uiLater(() -> progressBar.setString(GuiStrings.get("statusSending")));
        }

        @Override
        public void onExit() {
            uiLog(GuiStrings.get("disconnected"));
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
                setStatus(GuiStrings.get("statusTransferring", Utils.formatSpeed(speed)));
                speedModel.setRowCount(0);
                for (TrafficInfo info : trafficInfoList) {
                    speedModel.addRow(new Object[]{info.iName,
                            Utils.formatSpeed(info.uploadTraffic + info.downloadTraffic)});
                }
            });
        }

        @Override
        public void onChannelComplete(String iName, long traffic, long time) {
            uiLog(GuiStrings.get("channelDone", iName, Utils.formatTime(time)));
        }

        @Override
        public void onChannelError(String iName, int errorType, String message) {
            uiLog(GuiStrings.get("channelError", iName, message), true);
        }

        @Override
        public void onReadFileError(String message) {
            uiLog(GuiStrings.get("readFileError", message), true);
        }

        @Override
        public void onWriteFileError(String message) {
            uiLog(GuiStrings.get("writeFileError", message), true);
        }

        @Override
        public void onComplete(boolean isUpload, long traffic, long time) {
            uiLog(GuiStrings.get(isUpload ? "sendComplete" : "receiveComplete",
                    Utils.formatFileSize(traffic), Utils.formatTime(time)));
            uiLater(() -> {
                progressBar.setValue(100);
                progressBar.setString(GuiStrings.get("done"));
            });
        }

        @Override
        public void onIncomplete() {
            uiLog(GuiStrings.get("notCompleted"), true);
            uiLater(() -> progressBar.setString(GuiStrings.get("incomplete")));
        }
    }

    private void updateProgress(String path, long targetSize, long totalSize) {
        uiLater(() -> {
            if (totalSize > 0) {
                int percent = (int) Math.min(100, targetSize * 100 / totalSize);
                progressBar.setValue(percent);
                progressBar.setString(percent + "%");
            }
            setStatus(GuiStrings.get("statusTransferPath", path));
        });
    }

    /** 供启动入口使用。 */
    public static void showWindow() {
        SwingUtilities.invokeLater(() -> {
            // 必须在创建 MainFrame 之前应用主题：界面控件的底色在字段初始化时
            // 就从 UIDefaults 读取，晚于此步会留下白底输入框/表格。
            ThemeManager.init();
            new MainFrame().setVisible(true);
        });
    }
}
