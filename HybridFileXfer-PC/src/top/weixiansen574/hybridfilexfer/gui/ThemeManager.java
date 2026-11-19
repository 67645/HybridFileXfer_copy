package top.weixiansen574.hybridfilexfer.gui;

import javax.swing.BorderFactory;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.plaf.metal.OceanTheme;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 主题管理：浅色 / 深色 / 跟随系统。
 * 通过 Metal LAF 的 UI 默认值与自定义主题实现，不引入第三方依赖。
 * 每次切换都会显式写入对应调色板，避免上一次主题的颜色残留在 UIDefaults 中。
 */
public final class ThemeManager {
    public static final String SYSTEM = "system";
    public static final String LIGHT = "light";
    public static final String DARK = "dark";

    private static final List<Runnable> LISTENERS = new ArrayList<>();
    private static String currentMode = SYSTEM;
    private static boolean darkApplied;
    private static boolean initialized;
    private static ScheduledExecutorService watcher;

    private ThemeManager() {
    }

    /** 供界面在主题切换后恢复表格列宽、行高等自定义设置。 */
    public interface Listener {
        void onThemeApplied();
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        apply(GuiConfig.getTheme());
        startSystemWatch();
    }

    public static String getMode() {
        return currentMode;
    }

    public static void setMode(String mode) {
        GuiConfig.setTheme(mode);
        apply(mode);
    }

    public static boolean isDark() {
        return darkApplied;
    }

    public static synchronized void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    /** 立即按指定模式刷新所有窗口。 */
    public static void apply(final String mode) {
        final String normalized = DARK.equals(mode) || LIGHT.equals(mode) ? mode : SYSTEM;
        boolean dark = DARK.equals(normalized) || (SYSTEM.equals(normalized) && isSystemDark());
        currentMode = normalized;
        darkApplied = dark;
        Palette palette = dark ? darkPalette() : lightPalette();
        installLookAndFeel(dark);
        putDefaults(palette, dark);
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
            window.repaint();
        }
        for (Runnable listener : new ArrayList<>(LISTENERS)) {
            listener.run();
        }
    }

    private static void startSystemWatch() {
        watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "HFX-ThemeWatch");
            thread.setDaemon(true);
            return thread;
        });
        watcher.scheduleWithFixedDelay(() -> {
            if (!SYSTEM.equals(currentMode)) {
                return;
            }
            boolean dark = isSystemDark();
            if (dark != darkApplied) {
                SwingUtilities.invokeLater(() -> apply(SYSTEM));
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    /** Windows 下读取应用主题（AppsUseLightTheme=0 表示深色）。其他系统默认浅色。 */
    public static boolean isSystemDark() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (java.io.BufferedReader reader =
                         new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line);
                }
            }
            process.waitFor();
            int index = out.indexOf("AppsUseLightTheme");
            if (index < 0) {
                return false;
            }
            return out.substring(index).contains("0x0");
        } catch (Exception e) {
            return false;
        }
    }

    private static void installLookAndFeel(boolean dark) {
        try {
            MetalLookAndFeel.setCurrentTheme(dark ? new DarkMetalTheme() : new OceanTheme());
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (Exception e) {
            // Metal 始终随 JDK 提供，这里只在极端情况下失败，保持当前外观即可
        }
    }

    /** 调色板：浅色与深色各一套，切换时整表覆盖。 */
    private static final class Palette {
        ColorUIResource background;
        ColorUIResource surface;
        ColorUIResource field;
        ColorUIResource text;
        ColorUIResource dim;
        ColorUIResource border;
        ColorUIResource selection;
        ColorUIResource selectedText;
        ColorUIResource accent;
        ColorUIResource scroll;
        ColorUIResource thumbHighlight;
        ColorUIResource thumbShadow;
        ColorUIResource track;
        ColorUIResource button;
        ColorUIResource tooltip;
    }

    private static Palette darkPalette() {
        Palette p = new Palette();
        p.background = color(0x2B2B2B);
        p.surface = color(0x313131);
        p.field = color(0x3C3F41);
        p.text = color(0xE6E6E6);
        p.dim = color(0x8C8C8C);
        p.border = color(0x4A4A4A);
        p.selection = color(0x2F65CA);
        p.selectedText = color(0xFFFFFF);
        p.accent = color(0x3D74D0);
        p.scroll = color(0x555555);
        p.thumbHighlight = color(0x6A6A6A);
        p.thumbShadow = color(0x3A3A3A);
        p.track = color(0x262626);
        p.button = color(0x3C3F41);
        p.tooltip = color(0x3A3A3A);
        return p;
    }

    private static Palette lightPalette() {
        Palette p = new Palette();
        p.background = color(0xF2F2F2);
        p.surface = color(0xFFFFFF);
        p.field = color(0xFFFFFF);
        p.text = color(0x1F1F1F);
        p.dim = color(0x8C8C8C);
        p.border = color(0xB8B8B8);
        p.selection = color(0x2F65CA);
        p.selectedText = color(0xFFFFFF);
        p.accent = color(0x3D74D0);
        p.scroll = color(0xC0C0C0);
        p.thumbHighlight = color(0xE0E0E0);
        p.thumbShadow = color(0x909090);
        p.track = color(0xE6E6E6);
        p.button = color(0xECECEC);
        p.tooltip = color(0xFFFFD8);
        return p;
    }

    private static void putDefaults(Palette p, boolean dark) {
        put(new String[]{"control", "Panel.background", "OptionPane.background", "ScrollPane.background",
                "Viewport.background", "MenuBar.background", "Menu.background", "PopupMenu.background",
                "ToolBar.background", "TabbedPane.background", "SplitPane.background", "Spinner.background",
                "FileChooser.background", "Separator.background", "RootPane.background", "Label.background",
                "CheckBox.background", "RadioButton.background", "ToggleButton.background"}, p.background);
        put(new String[]{"Label.foreground", "OptionPane.messageForeground",
                "MenuBar.foreground", "Menu.foreground", "MenuItem.foreground", "JMenu.foreground",
                "PopupMenu.foreground", "ToolBar.foreground", "TabbedPane.foreground",
                "SplitPane.foreground", "Separator.foreground", "CheckBox.foreground",
                "RadioButton.foreground", "Button.foreground", "ToggleButton.foreground"}, p.text);

        put(new String[]{"Label.disabledForeground", "Button.disabledText", "MenuItem.disabledForeground",
                "Menu.disabledForeground"}, p.dim);
        UIManager.put("Button.background", p.button);
        UIManager.put("Button.select", p.field);

        UIManager.put("Menu.selectionBackground", p.selection);
        UIManager.put("Menu.selectionForeground", p.selectedText);
        UIManager.put("MenuItem.selectionBackground", p.selection);
        UIManager.put("MenuItem.selectionForeground", p.selectedText);
        UIManager.put("MenuItem.acceleratorForeground", p.dim);
        UIManager.put("MenuItem.acceleratorSelectionForeground", p.selectedText);
        UIManager.put("Menu.acceleratorForeground", p.dim);
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(p.border));

        UIManager.put("Table.background", p.surface);
        UIManager.put("Table.foreground", p.text);
        UIManager.put("Table.selectionBackground", p.selection);
        UIManager.put("Table.selectionForeground", p.selectedText);
        UIManager.put("Table.gridColor", p.border);
        UIManager.put("Table.focusCellBackground", p.selection);
        UIManager.put("Table.focusCellForeground", p.selectedText);
        UIManager.put("Table.dropLineColor", p.accent);
        UIManager.put("Table.dropCellBackground", p.field);
        UIManager.put("TableHeader.background", p.background);
        UIManager.put("TableHeader.foreground", p.text);
        UIManager.put("TableHeader.selectionBackground", p.selection);
        UIManager.put("TableHeader.selectionForeground", p.selectedText);

        UIManager.put("List.background", p.surface);
        UIManager.put("List.foreground", p.text);
        UIManager.put("List.selectionBackground", p.selection);
        UIManager.put("List.selectionForeground", p.selectedText);

        for (String prefix : new String[]{"TextArea", "TextField", "TextPane", "EditorPane", "PasswordField",
                "FormattedTextField", "Spinner"}) {
            UIManager.put(prefix + ".background", p.field);
            UIManager.put(prefix + ".foreground", p.text);
            UIManager.put(prefix + ".caretForeground", p.text);
            UIManager.put(prefix + ".selectionBackground", p.selection);
            UIManager.put(prefix + ".selectionForeground", p.selectedText);
            UIManager.put(prefix + ".inactiveBackground", p.field);
            UIManager.put(prefix + ".inactiveForeground", p.text);
            UIManager.put(prefix + ".disabledText", p.dim);
        }

        UIManager.put("ComboBox.background", p.button);
        UIManager.put("ComboBox.foreground", p.text);
        UIManager.put("ComboBox.selectionBackground", p.selection);
        UIManager.put("ComboBox.selectionForeground", p.selectedText);
        UIManager.put("ComboBox.buttonBackground", p.button);
        UIManager.put("ComboBox.disabledBackground", p.background);
        UIManager.put("ComboBox.disabledForeground", p.dim);

        UIManager.put("ScrollBar.background", p.background);
        UIManager.put("ScrollBar.foreground", p.dim);
        UIManager.put("ScrollBar.thumb", p.scroll);
        UIManager.put("ScrollBar.thumbHighlight", p.thumbHighlight);
        UIManager.put("ScrollBar.thumbShadow", p.thumbShadow);
        UIManager.put("ScrollBar.thumbDarkShadow", p.thumbShadow);
        UIManager.put("ScrollBar.track", p.track);
        UIManager.put("ScrollBar.trackHighlight", p.thumbHighlight);

        UIManager.put("ProgressBar.background", p.field);
        UIManager.put("ProgressBar.foreground", p.accent);
        UIManager.put("ProgressBar.selectionBackground", p.text);
        UIManager.put("ProgressBar.selectionForeground", p.selectedText);

        UIManager.put("TabbedPane.selected", p.background);
        UIManager.put("TabbedPane.selectionBackground", p.background);
        UIManager.put("TabbedPane.selectionForeground", p.text);
        UIManager.put("TabbedPane.highlight", p.border);
        UIManager.put("TabbedPane.light", p.border);
        UIManager.put("TabbedPane.shadow", p.border);
        UIManager.put("TabbedPane.darkShadow", p.border);
        UIManager.put("TabbedPane.focus", p.accent);

        UIManager.put("SplitPane.dividerBackground", p.background);
        UIManager.put("SplitPane.highlight", p.border);
        UIManager.put("SplitPane.shadow", p.border);
        UIManager.put("SplitPane.darkShadow", p.border);

        UIManager.put("TitledBorder.titleColor", p.text);
        UIManager.put("TitledBorder.border", BorderFactory.createLineBorder(p.border));

        UIManager.put("ToolTip.background", p.tooltip);
        UIManager.put("ToolTip.foreground", p.text);
        UIManager.put("ToolTip.border", BorderFactory.createLineBorder(p.border));

        UIManager.put("FileChooser.listViewBackground", p.surface);
        UIManager.put("FileChooser.foreground", p.text);

        UIManager.put("Tree.background", p.surface);
        UIManager.put("Tree.foreground", p.text);
        UIManager.put("Tree.selectionBackground", p.selection);
        UIManager.put("Tree.selectionForeground", p.selectedText);

        if (dark) {
            UIManager.put("OptionPane.messageForeground", p.text);
        }
    }

    private static void put(String[] keys, ColorUIResource value) {
        for (String key : keys) {
            UIManager.put(key, value);
        }
    }

    private static ColorUIResource color(int rgb) {
        return new ColorUIResource(rgb);
    }

    /** 深色 Metal 主题：控件、菜单、标题栏与文本色都从深色调色板取值。 */
    private static final class DarkMetalTheme extends DefaultMetalTheme {
        private final ColorUIResource primary1 = color(0x2F65CA);
        private final ColorUIResource primary2 = color(0x3D74D0);
        private final ColorUIResource primary3 = color(0x4F86E0);
        private final ColorUIResource secondary1 = color(0x505050);
        private final ColorUIResource secondary2 = color(0x606060);
        private final ColorUIResource secondary3 = color(0x2B2B2B);

        @Override
        public String getName() {
            return "HybridFileXfer Dark";
        }

        @Override
        protected ColorUIResource getPrimary1() {
            return primary1;
        }

        @Override
        protected ColorUIResource getPrimary2() {
            return primary2;
        }

        @Override
        protected ColorUIResource getPrimary3() {
            return primary3;
        }

        @Override
        protected ColorUIResource getSecondary1() {
            return secondary1;
        }

        @Override
        protected ColorUIResource getSecondary2() {
            return secondary2;
        }

        @Override
        protected ColorUIResource getSecondary3() {
            return secondary3;
        }

        @Override
        public ColorUIResource getControl() {
            return color(0x2B2B2B);
        }

        @Override
        public ColorUIResource getControlHighlight() {
            return color(0x3A3A3A);
        }

        @Override
        public ColorUIResource getControlShadow() {
            return color(0x1F1F1F);
        }

        @Override
        public ColorUIResource getControlDarkShadow() {
            return color(0x151515);
        }

        @Override
        public ColorUIResource getControlInfo() {
            return color(0xE6E6E6);
        }

        @Override
        public ColorUIResource getControlTextColor() {
            return color(0xE6E6E6);
        }

        @Override
        public ColorUIResource getInactiveControlTextColor() {
            return color(0x8C8C8C);
        }

        @Override
        public ColorUIResource getSystemTextColor() {
            return color(0xE6E6E6);
        }

        @Override
        public ColorUIResource getInactiveSystemTextColor() {
            return color(0x8C8C8C);
        }

        @Override
        public ColorUIResource getUserTextColor() {
            return color(0xE6E6E6);
        }

        @Override
        public ColorUIResource getTextHighlightColor() {
            return color(0x2F65CA);
        }

        @Override
        public ColorUIResource getHighlightedTextColor() {
            return color(0xFFFFFF);
        }

        @Override
        public ColorUIResource getWindowBackground() {
            return color(0x2B2B2B);
        }

        @Override
        public ColorUIResource getMenuBackground() {
            return color(0x2B2B2B);
        }

        @Override
        public ColorUIResource getMenuForeground() {
            return color(0xE6E6E6);
        }

        @Override
        public ColorUIResource getMenuSelectedBackground() {
            return color(0x2F65CA);
        }

        @Override
        public ColorUIResource getMenuSelectedForeground() {
            return color(0xFFFFFF);
        }

        @Override
        public ColorUIResource getMenuDisabledForeground() {
            return color(0x8C8C8C);
        }

        @Override
        public ColorUIResource getWindowTitleBackground() {
            return color(0x2B2B2B);
        }

        @Override
        public ColorUIResource getWindowTitleForeground() {
            return color(0xE6E6E6);
        }

        @Override
        public ColorUIResource getWindowTitleInactiveBackground() {
            return color(0x2B2B2B);
        }

        @Override
        public ColorUIResource getWindowTitleInactiveForeground() {
            return color(0x8C8C8C);
        }
    }
}
