package top.weixiansen574.hybridfilexfer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Config {
    public static final int MODE_NORMAL = 0;
    public static final int MODE_ROOT = 1;
    public static final int PC_AUTH_UNKNOWN = 0;
    public static final int PC_AUTH_ALLOW = 1;
    public static final int PC_AUTH_DENY = 2;
    private static final String KEY_PC_AUTH = "pc_auth_list";
    private static Config instance;
    SharedPreferences preferences;

    public Config(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public static synchronized Config getInstance(Context context) {
        if (instance == null){
            instance = new Config(context.getApplicationContext().getSharedPreferences("config",Context.MODE_PRIVATE));
        }
        return instance;
    }

    public int getMode(){
        return preferences.getInt("mode",MODE_NORMAL);
    }

    public void setMode(int mode){
        preferences.edit().putInt("mode",mode).apply();
    }

    /** 是否允许电脑主动访问手机文件，安全默认值为关闭。 */
    public boolean isAllowPcAccess() {
        return preferences.getBoolean("allow_pc_access", false);
    }

    public void setAllowPcAccess(boolean allow) {
        preferences.edit().putBoolean("allow_pc_access", allow).apply();
    }

    /** 是否允许远端访问根目录之外的路径（root/Shizuku 用户需要时手动打开）。 */
    public boolean isAllowAllPaths() {
        return preferences.getBoolean("allow_all_paths", false);
    }

    public void setAllowAllPaths(boolean allow) {
        preferences.edit().putBoolean("allow_all_paths", allow).apply();
    }

    /** 已记住的电脑授权状态；未记住返回 PC_AUTH_UNKNOWN。 */
    public synchronized int getPcAuthStatus(String id) {
        PcAuthEntry entry = findPcAuth(id);
        return entry == null ? PC_AUTH_UNKNOWN : entry.status;
    }

    public synchronized PcAuthEntry findPcAuth(String id) {
        if (id == null) {
            return null;
        }
        for (PcAuthEntry entry : listPcAuth()) {
            if (id.equals(entry.id)) {
                return entry;
            }
        }
        return null;
    }

    public synchronized void rememberPc(String id, String name, int status) {
        if (id == null || id.isEmpty()) {
            return;
        }
        List<PcAuthEntry> entries = listPcAuth();
        entries.removeIf(entry -> id.equals(entry.id));
        entries.add(new PcAuthEntry(id, name == null ? "" : name, status, System.currentTimeMillis()));
        savePcAuth(entries);
    }

    /** 已记住的电脑再次连接时更新最后连接时间。 */
    public synchronized void touchPc(String id, String name) {
        List<PcAuthEntry> entries = listPcAuth();
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++) {
            PcAuthEntry entry = entries.get(i);
            if (id != null && id.equals(entry.id)) {
                entries.set(i, new PcAuthEntry(entry.id,
                        name == null || name.isEmpty() ? entry.name : name,
                        entry.status, System.currentTimeMillis()));
                changed = true;
                break;
            }
        }
        if (changed) {
            savePcAuth(entries);
        }
    }

    public synchronized void removePc(String id) {
        List<PcAuthEntry> entries = listPcAuth();
        entries.removeIf(entry -> entry.id.equals(id));
        savePcAuth(entries);
    }

    public synchronized List<PcAuthEntry> listPcAuth() {
        List<PcAuthEntry> entries = new ArrayList<>();
        String raw = preferences.getString(KEY_PC_AUTH, null);
        if (raw == null || raw.isEmpty()) {
            return entries;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                String id = object.optString("id", "");
                if (id.isEmpty()) {
                    continue;
                }
                entries.add(new PcAuthEntry(id,
                        object.optString("name", ""),
                        object.optInt("status", PC_AUTH_ALLOW),
                        object.optLong("last", 0L)));
            }
        } catch (JSONException ignored) {
        }
        return entries;
    }

    private void savePcAuth(List<PcAuthEntry> entries) {
        JSONArray array = new JSONArray();
        for (PcAuthEntry entry : entries) {
            JSONObject object = new JSONObject();
            try {
                object.put("id", entry.id);
                object.put("name", entry.name);
                object.put("status", entry.status);
                object.put("last", entry.lastTime);
                array.put(object);
            } catch (JSONException ignored) {
            }
        }
        preferences.edit().putString(KEY_PC_AUTH, array.toString()).apply();
    }

    public int getServerPort() {
        return preferences.getInt("server_port", 5740);
    }

    public void setServerPort(int port) {
        preferences.edit().putInt("server_port", port).apply();
    }

    public int getLocalBufferCount() {
        return preferences.getInt("local_buffer_count",256);
    }

    public void setLocalBufferCount(int count){
        preferences.edit().putInt("local_buffer_count",count).apply();
    }

    public int getRemoteBufferCount() {
        return preferences.getInt("remote_buffer_count",512);
    }

    public void setRemoteBufferCount(int count){
        preferences.edit().putInt("remote_buffer_count",count).apply();
    }

    public void setClientIOMode(int mode){
        preferences.edit().putInt("client_io_mode",mode).apply();
    }

    public int getClientIOMode(){
        return preferences.getInt("client_io_mode", MODE_NORMAL);
    }

    public void setConnectServerControllerIp(String ip){
        preferences.edit().putString("connect_server_controller_ip",ip).apply();
    }

    public String getConnectServerControllerIp(){
        return preferences.getString("connect_server_controller_ip","");
    }
}
