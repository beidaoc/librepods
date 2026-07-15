package me.kavishdevar.librepods.xiaomifix.resolve;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.os.Message;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.MatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.MethodsMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.util.HashMap;
import java.util.Map;

public final class DexKitResolver {
    private final ClassLoader classLoader;
    private final LogSink log;

    public interface LogSink {
        void info(String message);

        void warn(String message);
    }

    public DexKitResolver(ClassLoader classLoader, LogSink log) {
        this.classLoader = classLoader;
        this.log = log;
    }

    public Map<String, String> resolveMissing(ResolveResult result) {
        Map<String, String> found = new HashMap<>();
        try (DexKitBridge bridge = DexKitBridge.create(classLoader, false)) {
            for (String id : missingIds(result)) {
                String className = resolveOne(bridge, id);
                if (className != null) {
                    found.put(id, className);
                    log.info("dexkit resolved " + id + " -> " + className);
                } else {
                    log.warn("dexkit miss: " + id);
                }
            }
        } catch (Throwable t) {
            log.warn("dexkit bridge failed: " + t.getMessage());
        }
        return found;
    }

    private String[] missingIds(ResolveResult result) {
        String[] ids = {
                HookIds.FAST_CONNECT_HANDLER,
                HookIds.AIRPODS_SCAN_HELPER,
                HookIds.AIRCORE_MANAGER,
                HookIds.AIRCORE_TRANSPORT_HANDLER,
                HookIds.AIRCORE_TRANSPORT_PAYLOAD,
                HookIds.XIAOMI_FEATURE_UTILS,
        };
        int missing = 0;
        for (String id : ids) {
            ResolvedTarget target = result.get(id);
            if (target == null || target.getClassName() == null || target.getClassName().isEmpty()) {
                missing++;
            }
        }
        String[] out = new String[missing];
        int index = 0;
        for (String id : ids) {
            ResolvedTarget target = result.get(id);
            if (target == null || target.getClassName() == null || target.getClassName().isEmpty()) {
                out[index++] = id;
            }
        }
        return out;
    }

    private String resolveOne(DexKitBridge bridge, String id) {
        ClassMatcher matcher = matcherFor(id);
        if (matcher == null) {
            return null;
        }
        FindClass query = FindClass.create().matcher(matcher);
        ClassDataList list = bridge.findClass(query);
        if (list == null || list.isEmpty()) {
            return null;
        }
        ClassData data = list.get(0);
        return data.getName();
    }

    private ClassMatcher matcherFor(String id) {
        switch (id) {
            case HookIds.FAST_CONNECT_HANDLER:
                return fastConnectHandlerMatcher();
            case HookIds.AIRPODS_SCAN_HELPER:
                return airpodsScanHelperMatcher();
            case HookIds.AIRCORE_MANAGER:
                return airCoreManagerMatcher();
            case HookIds.AIRCORE_TRANSPORT_HANDLER:
                return airCoreTransportHandlerMatcher();
            case HookIds.AIRCORE_TRANSPORT_PAYLOAD:
                return airCoreTransportPayloadMatcher();
            case HookIds.XIAOMI_FEATURE_UTILS:
                return xiaomiFeatureUtilsMatcher();
            default:
                return null;
        }
    }

    private ClassMatcher fastConnectHandlerMatcher() {
        ClassMatcher matcher = new ClassMatcher();
        matcher.superClass("android.os.Handler");
        matcher.addFieldForType("com.android.bluetooth.ble.app.MiuiFastConnectService");
        matcher.addMethod(new MethodMatcher().name("handleMessage").addParamType(Message.class));
        return matcher;
    }

    private ClassMatcher airpodsScanHelperMatcher() {
        MethodsMatcher methods = MethodsMatcher.create();
        methods.matchType(MatchType.Contains);
        methods.add(new MethodMatcher().addParamType(ScanResult.class));
        methods.add(new MethodMatcher().addParamType(int.class).addParamType(BluetoothDevice.class));
        methods.add(new MethodMatcher()
                .addParamType(BluetoothDevice.class)
                .addParamType(int.class)
                .returnType(boolean.class));
        ClassMatcher matcher = new ClassMatcher();
        matcher.methods(methods);
        return matcher;
    }

    private ClassMatcher airCoreManagerMatcher() {
        MethodsMatcher methods = MethodsMatcher.create();
        methods.matchType(MatchType.Contains);
        methods.add(new MethodMatcher().addParamType(Intent.class));
        methods.add(new MethodMatcher().addParamType(BluetoothDevice.class).returnType(void.class));
        methods.add(new MethodMatcher()
                .addParamType(BluetoothDevice.class)
                .addParamType(String.class)
                .addParamType(String.class)
                .returnType(int.class));
        methods.add(new MethodMatcher()
                .addParamType(BluetoothDevice.class)
                .addParamType(byte[].class)
                .addParamType(int.class)
                .returnType(int.class));
        ClassMatcher matcher = new ClassMatcher();
        matcher.methods(methods);
        return matcher;
    }

    private ClassMatcher airCoreTransportHandlerMatcher() {
        ClassMatcher matcher = new ClassMatcher();
        matcher.superClass("android.os.Handler");
        matcher.addMethod(new MethodMatcher()
                .addParamType(BluetoothDevice.class)
                .addParamType(int.class));
        matcher.addMethod(new MethodMatcher().name("handleMessage").addParamType(Message.class));
        return matcher;
    }

    private ClassMatcher airCoreTransportPayloadMatcher() {
        ClassMatcher matcher = new ClassMatcher();
        matcher.addMethod(new MethodMatcher().returnType(BluetoothDevice.class).paramCount(0));
        return matcher;
    }

    private ClassMatcher xiaomiFeatureUtilsMatcher() {
        ClassMatcher matcher = new ClassMatcher();
        matcher.addMethod(new MethodMatcher()
                .addParamType(Context.class)
                .addParamType(BluetoothDevice.class)
                .addParamType(String.class)
                .addUsingString("ConnectL2cap"));
        return matcher;
    }
}
