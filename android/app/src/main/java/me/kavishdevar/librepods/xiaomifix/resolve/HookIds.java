package me.kavishdevar.librepods.xiaomifix.resolve;

public final class HookIds {
    public static final String FAST_CONNECT_HANDLER = "fast_connect_handler";
    public static final String AIRPODS_SCAN_HELPER = "airpods_scan_helper";
    public static final String AIRCORE_MANAGER = "aircore_manager";
    public static final String AIRCORE_TRANSPORT_HANDLER = "aircore_transport_handler";
    public static final String AIRCORE_TRANSPORT_PAYLOAD = "aircore_transport_payload";
    public static final String XIAOMI_FEATURE_UTILS = "xiaomi_feature_utils";

    private static final String[] CRITICAL = {
            FAST_CONNECT_HANDLER,
            AIRPODS_SCAN_HELPER,
            AIRCORE_MANAGER,
            AIRCORE_TRANSPORT_HANDLER,
    };

    private HookIds() {
    }

    public static String[] criticalIds() {
        return CRITICAL.clone();
    }
}
