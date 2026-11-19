package top.weixiansen574.hybridfilexfer.gui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.File;

/**
 * 与主窗口一致的无边框对话框：自绘标题栏 + 主题配色，
 * 同时提供消息框、确认框和嵌入 JFileChooser 的文件选择框。
 */
public class ThemedDialog extends JDialog {
    private final WindowChrome chrome;

    public ThemedDialog(Window owner, String title, boolean resizable) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        setUndecorated(true);
        chrome = new WindowChrome(title, new WindowChrome.Host() {
            @Override
            public void minimize() {
            }

            @Override
            public void toggleMaximize() {
            }

            @Override
            public void close() {
                dispose();
            }

            @Override
            public boolean isMaximized() {
                return false;
            }
        }, false, resizable);
        setContentPane(chrome);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        chrome.getBody().setBorder(BorderFactory.createEmptyBorder());
    }

    /** 内容区域，调用方自行加入组件。 */
    public JPanel getContent() {
        return chrome.getBody();
    }

    public void refreshTheme() {
        chrome.refreshTheme();
    }

    public static void info(Component parent, String title, String message) {
        showMessage(parent, title, message, false);
    }

    public static void warn(Component parent, String title, String message) {
        showMessage(parent, title, message, false);
    }

    public static void error(Component parent, String title, String message) {
        showMessage(parent, title, message, false);
    }

    public static boolean confirm(Component parent, String title, String message) {
        return showMessage(parent, title, message, true);
    }

    private static boolean showMessage(Component parent, String title, String message, boolean confirm) {
        ThemedDialog dialog = new ThemedDialog(ownerOf(parent), title, false);
        JPanel content = dialog.getContent();
        content.setBorder(BorderFactory.createEmptyBorder(20, 24, 18, 24));
        content.setLayout(new BorderLayout(0, 18));

        JLabel text = new JLabel(toHtml(message));
        text.setForeground(ThemeManager.palette().text);
        content.add(text, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton ok = new JButton(GuiStrings.get("dialogOk"));
        buttons.add(ok);
        final boolean[] result = {false};
        if (confirm) {
            JButton cancel = new JButton(GuiStrings.get("dialogCancel"));
            cancel.addActionListener(e -> dialog.dispose());
            ok.addActionListener(e -> {
                result[0] = true;
                dialog.dispose();
            });
            buttons.add(cancel);
        } else {
            ok.addActionListener(e -> dialog.dispose());
        }
        content.add(buttons, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(320, dialog.getHeight()));
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return result[0];
    }

    /** 选择单个目录；取消返回 null。 */
    public static File chooseDirectory(Component parent, String title, File initial) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        if (initial != null && initial.isDirectory()) {
            chooser.setCurrentDirectory(initial);
        }
        File[] result = new File[1];
        showChooser(parent, title, chooser, () -> {
            File file = chooser.getSelectedFile();
            if (file != null) {
                result[0] = file;
            }
        });
        return result[0];
    }

    /** 选择多个文件/目录；取消返回空数组。 */
    public static File[] chooseFiles(Component parent, String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(true);
        File[][] result = {new File[0]};
        showChooser(parent, title, chooser, () -> {
            File[] files = chooser.getSelectedFiles();
            if (files != null && files.length > 0) {
                result[0] = files;
            } else if (chooser.getSelectedFile() != null) {
                result[0] = new File[]{chooser.getSelectedFile()};
            }
        });
        return result[0];
    }

    private static void showChooser(Component parent, String title, JFileChooser chooser, Runnable onApprove) {
        ThemedDialog dialog = new ThemedDialog(ownerOf(parent), title, true);
        chooser.setBackground(ThemeManager.palette().background);
        chooser.setForeground(ThemeManager.palette().text);
        themeComponents(chooser);
        dialog.getContent().add(chooser, BorderLayout.CENTER);
        chooser.addActionListener(e -> {
            String command = e.getActionCommand();
            if (JFileChooser.APPROVE_SELECTION.equals(command)) {
                onApprove.run();
                dialog.dispose();
            } else if (JFileChooser.CANCEL_SELECTION.equals(command)) {
                dialog.dispose();
            }
        });
        Dimension preferred = chooser.getPreferredSize();
        int width = Math.max(760, preferred.width + 40);
        int height = Math.max(520, preferred.height + 80);
        dialog.setSize(width, height);
        dialog.setMinimumSize(new Dimension(560, 380));
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    /** JFileChooser 内部部分组件不读取 UIDefaults，这里递归统一配色。 */
    private static void themeComponents(Component component) {
        ThemeManager.Palette p = ThemeManager.palette();
        if (component instanceof JList || component instanceof JTable) {
            component.setBackground(p.surface);
            component.setForeground(p.text);
        } else if (component instanceof JTextField || component instanceof JTextArea
                || component instanceof JComboBox || component instanceof JSpinner) {
            component.setBackground(p.field);
            component.setForeground(p.text);
        } else if (component instanceof JButton) {
            component.setBackground(p.button);
            component.setForeground(p.text);
        } else if (component instanceof JLabel || component instanceof JCheckBox
                || component instanceof JRadioButton || component instanceof JToggleButton) {
            component.setForeground(p.text);
        } else if (component instanceof JPanel || component instanceof JScrollPane
                || component instanceof JViewport) {
            component.setBackground(p.background);
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                themeComponents(child);
            }
        }
    }

    private static Window ownerOf(Component parent) {
        if (parent == null) {
            return null;
        }
        return parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent);
    }

    private static String toHtml(String message) {
        String escaped = message == null ? "" : message
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
        return "<html><body style='width:380px'>" + escaped + "</body></html>";
    }
}
