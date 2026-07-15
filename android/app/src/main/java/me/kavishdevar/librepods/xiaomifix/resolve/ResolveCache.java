package me.kavishdevar.librepods.xiaomifix.resolve;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

public final class ResolveCache {
    private static final String PREFS = "librepods_xiaomifix_resolve_v1";

    private ResolveCache() {
    }

    public static String buildFingerprint(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            long crc = crc32(new File(info.applicationInfo.sourceDir));
            return info.versionCode + ":" + info.lastUpdateTime + ":" + crc;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    public static Map<String, String> load(Context context, String fingerprint) {
        Map<String, String> map = new HashMap<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString("fingerprint", "");
        if (!fingerprint.equals(stored)) {
            return map;
        }
        for (String id : allIds()) {
            String className = prefs.getString(id, null);
            if (className != null && !className.isEmpty()) {
                map.put(id, className);
            }
        }
        return map;
    }

    public static void save(Context context, String fingerprint, ResolveResult result) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putString("fingerprint", fingerprint);
        for (ResolvedTarget target : result.getTargets().values()) {
            if (target.getClassName() != null && !target.getClassName().isEmpty()) {
                editor.putString(target.getId(), target.getClassName());
            }
        }
        editor.apply();
    }

    private static String[] allIds() {
        return new String[]{
                HookIds.FAST_CONNECT_HANDLER,
                HookIds.AIRPODS_SCAN_HELPER,
                HookIds.AIRCORE_MANAGER,
                HookIds.AIRCORE_TRANSPORT_HANDLER,
                HookIds.AIRCORE_TRANSPORT_PAYLOAD,
                HookIds.XIAOMI_FEATURE_UTILS,
        };
    }

    private static long crc32(File file) {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[8192];
        try (FileInputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
            }
            return crc.getValue();
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
