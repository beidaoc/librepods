package me.kavishdevar.librepods.xiaomifix.resolve;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

public final class TargetResolver {
    private static final Map<String, String[]> ALIASES = new HashMap<>();

    static {
        ALIASES.put(HookIds.FAST_CONNECT_HANDLER, new String[]{
                "com.android.bluetooth.ble.app.R4",
        });
        ALIASES.put(HookIds.AIRPODS_SCAN_HELPER, new String[]{
                "g0.r", "G0.r",
        });
        ALIASES.put(HookIds.AIRCORE_MANAGER, new String[]{
                "l1.b", "l1.C1554b",
        });
        ALIASES.put(HookIds.AIRCORE_TRANSPORT_HANDLER, new String[]{
                "l1.e", "l1.HandlerC1557e",
        });
        ALIASES.put(HookIds.AIRCORE_TRANSPORT_PAYLOAD, new String[]{
                "l1.j",
        });
        ALIASES.put(HookIds.XIAOMI_FEATURE_UTILS, new String[]{
                "n1.a", "n1.C1582a",
        });
    }

    private TargetResolver() {
    }

    public static ResolveResult resolve(
            ClassLoader classLoader,
            Context context,
            DexKitResolver.LogSink log) {
        String fingerprint = context != null
                ? ResolveCache.buildFingerprint(context)
                : "early-boot";
        ResolveResult result = new ResolveResult(fingerprint);
        seedTargets(result);

        if (context != null) {
            Map<String, String> cached = ResolveCache.load(context, fingerprint);
            applyNames(result, cached, ResolveSource.CACHE, classLoader, log);
        }

        for (Map.Entry<String, String[]> entry : ALIASES.entrySet()) {
            ResolvedTarget target = result.get(entry.getKey());
            if (target != null && target.getClassName() != null && !target.getClassName().isEmpty()) {
                continue;
            }
            String className = firstExisting(classLoader, entry.getValue());
            if (className != null) {
                applyName(result, entry.getKey(), className, ResolveSource.ALIAS, classLoader, log);
            }
        }

        DexKitResolver dexKit = new DexKitResolver(classLoader, log);
        Map<String, String> dexkit = dexKit.resolveMissing(result);
        applyNames(result, dexkit, ResolveSource.DEXKIT, classLoader, log);

        if (context != null) {
            ResolveCache.save(context, fingerprint, result);
        }
        return result;
    }

    private static void seedTargets(ResolveResult result) {
        put(result, HookIds.FAST_CONNECT_HANDLER, true);
        put(result, HookIds.AIRPODS_SCAN_HELPER, true);
        put(result, HookIds.AIRCORE_MANAGER, true);
        put(result, HookIds.AIRCORE_TRANSPORT_HANDLER, true);
        put(result, HookIds.AIRCORE_TRANSPORT_PAYLOAD, false);
        put(result, HookIds.XIAOMI_FEATURE_UTILS, false);
    }

    private static void put(ResolveResult result, String id, boolean critical) {
        result.put(new ResolvedTarget(id, null, ResolveSource.NONE, critical));
    }

    private static void applyNames(
            ResolveResult result,
            Map<String, String> names,
            ResolveSource source,
            ClassLoader classLoader,
            DexKitResolver.LogSink log) {
        for (Map.Entry<String, String> entry : names.entrySet()) {
            applyName(result, entry.getKey(), entry.getValue(), source, classLoader, log);
        }
    }

    private static void applyName(
            ResolveResult result,
            String id,
            String className,
            ResolveSource source,
            ClassLoader classLoader,
            DexKitResolver.LogSink log) {
        ResolvedTarget target = result.get(id);
        if (target == null) {
            return;
        }
        if (target.getClassName() != null && !target.getClassName().isEmpty()) {
            return;
        }
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            target.bind(clazz);
            ResolvedTarget updated = new ResolvedTarget(id, className, source, target.isCritical());
            updated.bind(clazz);
            result.put(updated);
            log.info("resolved " + id + " via " + source + " -> " + className);
        } catch (Throwable t) {
            log.warn("class bind failed for " + id + " (" + className + "): " + t.getMessage());
        }
    }

    private static String firstExisting(ClassLoader classLoader, String[] names) {
        for (String name : names) {
            try {
                Class.forName(name, false, classLoader);
                return name;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
