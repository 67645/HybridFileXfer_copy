package top.weixiansen574.hybridfilexfer.gui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

/**
 * 无边框窗口的自绘外壳：标题栏（标题 + 菜单 + 最小化/最大化/关闭）、
 * 标题栏拖动、双击最大化，以及四周/四角的拖拽缩放。
 */
public class WindowChrome extends JPanel {
    private static final int TITLE_HEIGHT = 40;
    private static final int DIALOG_TITLE_HEIGHT = 36;
    private static final int EDGE = 5;

    /** 窗口动作回调，由 JFrame/JDialog 实现。 */
    public interface Host {
        void minimize();

        void toggleMaximize();

        void close();

        boolean isMaximized();
    }

    private final Host host;
    private final JPanel titleBar = new JPanel(new BorderLayout());
    private final JPanel buttonPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
    private final JLabel titleLabel;
    private final TitleBarButton minimizeButton;
    private final TitleBarButton maximizeButton;
    private final TitleBarButton closeButton;
    private final JPanel body = new JPanel(new BorderLayout());
    private final JPanel menuHolder = new JPanel(new BorderLayout());
    private final boolean showMinMax;
    private final boolean resizable;
    private final int titleHeight;
    private boolean maximized;

    private final JPanel north = createZone(Cursor.N_RESIZE_CURSOR, 0, -1);
    private final JPanel south = createZone(Cursor.S_RESIZE_CURSOR, 0, 1);
    private final JPanel west = createZone(Cursor.W_RESIZE_CURSOR, -1, 0);
    private final JPanel east = createZone(Cursor.E_RESIZE_CURSOR, 1, 0);
    private final JPanel northWest = createZone(Cursor.NW_RESIZE_CURSOR, -1, -1);
    private final JPanel northEast = createZone(Cursor.NE_RESIZE_CURSOR, 1, -1);
    private final JPanel southWest = createZone(Cursor.SW_RESIZE_CURSOR, -1, 1);
    private final JPanel southEast = createZone(Cursor.SE_RESIZE_CURSOR, 1, 1);

    public WindowChrome(String title, Host host, boolean showMinMax, boolean resizable) {
        this.host = host;
        this.showMinMax = showMinMax;
        this.resizable = resizable;
        this.titleHeight = showMinMax ? TITLE_HEIGHT : DIALOG_TITLE_HEIGHT;
        setLayout(null);
        setOpaque(true);
        setBackground(ThemeManager.palette().background);

        titleLabel = new JLabel(title) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                super.paintComponent(g2);
                g2.dispose();
            }
        };
        titleLabel.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        titleBar.setOpaque(false);

        menuHolder.setOpaque(false);
        menuHolder.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        JPanel leftGroup = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 10, 0));
        leftGroup.setOpaque(false);
        leftGroup.add(titleLabel);
        leftGroup.add(menuHolder);

        buttonPanel.setOpaque(false);
        titleBar.add(leftGroup, BorderLayout.WEST);
        titleBar.add(buttonPanel, BorderLayout.EAST);

        minimizeButton = new TitleBarButton(TitleBarButton.MINIMIZE);
        maximizeButton = new TitleBarButton(TitleBarButton.MAXIMIZE);
        closeButton = new TitleBarButton(TitleBarButton.CLOSE);
        minimizeButton.setToolTipText(GuiStrings.get("windowMinimize"));
        maximizeButton.setToolTipText(GuiStrings.get("windowMaximize"));
        closeButton.setToolTipText(GuiStrings.get("windowClose"));
        if (showMinMax) {
            buttonPanel.add(minimizeButton);
            buttonPanel.add(maximizeButton);
        }
        buttonPanel.add(closeButton);

        minimizeButton.setAction(this.host::minimize);
        maximizeButton.setAction(this.host::toggleMaximize);
        closeButton.setAction(this.host::close);

        installTitleDrag();

        add(titleBar);
        add(body);
        if (resizable) {
            add(north);
            add(south);
            add(west);
            add(east);
            add(northWest);
            add(northEast);
            add(southWest);
            add(southEast);
        }
        refreshTheme();
    }

    public JPanel getBody() {
        return body;
    }

    /** 把菜单栏放到标题旁，其余标题栏区域保持可拖动。 */
    public void setMenuBar(JComponent menuBar) {
        menuBar.setOpaque(false);
        menuBar.setBorder(BorderFactory.createEmptyBorder());
        menuHolder.removeAll();
        menuHolder.add(menuBar, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    public void setMaximized(boolean maximized) {
        this.maximized = maximized;
        maximizeButton.setIconType(maximized ? TitleBarButton.RESTORE : TitleBarButton.MAXIMIZE);
        maximizeButton.setToolTipText(GuiStrings.get(maximized ? "windowRestore" : "windowMaximize"));
        repaint();
    }

    /** 切换主题后刷新自绘颜色。 */
    public void refreshTheme() {
        ThemeManager.Palette p = ThemeManager.palette();
        setBackground(p.background);
        titleLabel.setForeground(p.text);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        titleBar.setBackground(p.background);
        body.setBackground(p.background);
        minimizeButton.refresh();
        maximizeButton.refresh();
        closeButton.refresh();
        repaint();
    }

    private void installTitleDrag() {
        MouseAdapter adapter = new MouseAdapter() {
            private java.awt.Point startScreen;
            private java.awt.Point windowStart;

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1 || host.isMaximized()) {
                    startScreen = null;
                    return;
                }
                startScreen = e.getLocationOnScreen();
                windowStart = getWindowPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (startScreen == null || host.isMaximized() || windowStart == null) {
                    return;
                }
                Window window = SwingUtilities.getWindowAncestor(WindowChrome.this);
                if (window == null) {
                    return;
                }
                java.awt.Point now = e.getLocationOnScreen();
                window.setLocation(windowStart.x + now.x - startScreen.x,
                        windowStart.y + now.y - startScreen.y);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                startScreen = null;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2 && showMinMax) {
                    host.toggleMaximize();
                }
            }
        };
        titleBar.addMouseListener(adapter);
        titleBar.addMouseMotionListener(adapter);
        titleLabel.addMouseListener(adapter);
        titleLabel.addMouseMotionListener(adapter);
    }

    private java.awt.Point getWindowPoint() {
        Window window = SwingUtilities.getWindowAncestor(this);
        return window == null ? null : window.getLocationOnScreen();
    }

    private JPanel createZone(int cursor, int dirX, int dirY) {
        JPanel zone = new JPanel();
        zone.setOpaque(false);
        zone.setCursor(Cursor.getPredefinedCursor(cursor));
        MouseAdapter adapter = new MouseAdapter() {
            private java.awt.Point start;
            private java.awt.Rectangle startBounds;

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1 || host.isMaximized()) {
                    start = null;
                    return;
                }
                start = e.getLocationOnScreen();
                Window window = SwingUtilities.getWindowAncestor(WindowChrome.this);
                startBounds = window == null ? null : window.getBounds();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                start = null;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (start == null || startBounds == null || host.isMaximized()) {
                    return;
                }
                Window window = SwingUtilities.getWindowAncestor(WindowChrome.this);
                if (window == null) {
                    return;
                }
                java.awt.Point now = e.getLocationOnScreen();
                int dx = now.x - start.x;
                int dy = now.y - start.y;
                Dimension min = window.getMinimumSize();
                int minWidth = Math.max(min.width, 360);
                int minHeight = Math.max(min.height, 260);
                int x = startBounds.x;
                int y = startBounds.y;
                int width = startBounds.width;
                int height = startBounds.height;
                if (dirX < 0) {
                    width = Math.max(minWidth, startBounds.width - dx);
                    x = startBounds.x + startBounds.width - width;
                } else if (dirX > 0) {
                    width = Math.max(minWidth, startBounds.width + dx);
                }
                if (dirY < 0) {
                    height = Math.max(minHeight, startBounds.height - dy);
                    y = startBounds.y + startBounds.height - height;
                } else if (dirY > 0) {
                    height = Math.max(minHeight, startBounds.height + dy);
                }
                window.setBounds(x, y, width, height);
            }
        };
        zone.addMouseListener(adapter);
        zone.addMouseMotionListener(adapter);
        return zone;
    }

    @Override
    public java.awt.Dimension getPreferredSize() {
        java.awt.Dimension bodyPreferred = body.getPreferredSize();
        java.awt.Dimension titlePreferred = titleBar.getPreferredSize();
        int width = Math.max(bodyPreferred.width, titlePreferred.width) + 2;
        int height = bodyPreferred.height + titleHeight + 2;
        return new java.awt.Dimension(width, height);
    }

    @Override
    public void doLayout() {
        int width = getWidth();
        int height = getHeight();
        titleBar.setBounds(0, 0, width, titleHeight);
        body.setBounds(0, titleHeight, width, Math.max(0, height - titleHeight));
        if (resizable) {
            north.setBounds(EDGE, 0, Math.max(0, width - 2 * EDGE), EDGE);
            south.setBounds(EDGE, height - EDGE, Math.max(0, width - 2 * EDGE), EDGE);
            west.setBounds(0, EDGE, EDGE, Math.max(0, height - 2 * EDGE));
            east.setBounds(width - EDGE, EDGE, EDGE, Math.max(0, height - 2 * EDGE));
            northWest.setBounds(0, 0, EDGE, EDGE);
            northEast.setBounds(width - EDGE, 0, EDGE, EDGE);
            southWest.setBounds(0, height - EDGE, EDGE, EDGE);
            southEast.setBounds(width - EDGE, height - EDGE, EDGE, EDGE);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        ThemeManager.Palette p = ThemeManager.palette();
        g.setColor(p.border);
        g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
        g.setColor(p.border);
        g.drawLine(0, titleHeight - 1, getWidth(), titleHeight - 1);
    }

    /** 标题栏按钮：最小化、最大化/还原、关闭，带悬停反馈。 */
    static final class TitleBarButton extends JComponent {
        static final int MINIMIZE = 0;
        static final int MAXIMIZE = 1;
        static final int RESTORE = 2;
        static final int CLOSE = 3;

        private int iconType;
        private boolean hover;
        private Runnable action;

        TitleBarButton(int iconType) {
            this.iconType = iconType;
            setPreferredSize(new Dimension(46, TITLE_HEIGHT));
            setCursor(Cursor.getDefaultCursor());
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && action != null) {
                        action.run();
                    }
                }
            });
        }

        void setAction(Runnable action) {
            this.action = action;
        }

        void setIconType(int iconType) {
            this.iconType = iconType;
            repaint();
        }

        void refresh() {
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            ThemeManager.Palette p = ThemeManager.palette();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean isClose = iconType == CLOSE;
            if (hover) {
                g2.setColor(isClose ? p.closeHover : p.titleHover);
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            Color glyph = hover && isClose ? p.hoverText : p.text;
            g2.setColor(glyph);
            g2.setStroke(new java.awt.BasicStroke(1.4f));
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            switch (iconType) {
                case MINIMIZE:
                    g2.drawLine(cx - 5, cy + 4, cx + 5, cy + 4);
                    break;
                case MAXIMIZE:
                    g2.drawRect(cx - 5, cy - 5, 10, 10);
                    break;
                case RESTORE:
                    g2.drawRect(cx - 5, cy - 3, 8, 8);
                    g2.drawLine(cx - 2, cy - 3, cx - 2, cy - 6);
                    g2.drawLine(cx - 2, cy - 6, cx + 6, cy - 6);
                    g2.drawLine(cx + 6, cy - 6, cx + 6, cy + 2);
                    g2.drawLine(cx + 6, cy + 2, cx + 3, cy + 2);
                    break;
                case CLOSE:
                    g2.drawLine(cx - 5, cy - 5, cx + 5, cy + 5);
                    g2.drawLine(cx + 5, cy - 5, cx - 5, cy + 5);
                    break;
                default:
                    break;
            }
            g2.dispose();
        }
    }
}
