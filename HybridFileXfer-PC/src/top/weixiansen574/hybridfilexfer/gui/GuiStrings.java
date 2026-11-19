package top.weixiansen574.hybridfilexfer.gui;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/** GUI 文案资源，默认英文，缺省语言回退到 messages_gui.properties。 */
public class GuiStrings {
    private static final ResourceBundle BUNDLE =
            ResourceBundle.getBundle("top.weixiansen574.hybridfilexfer.gui.messages_gui", Locale.getDefault());

    public static String get(String key) {
        return BUNDLE.getString(key);
    }

    public static String get(String key, Object... args) {
        return MessageFormat.format(BUNDLE.getString(key), args);
    }
}
