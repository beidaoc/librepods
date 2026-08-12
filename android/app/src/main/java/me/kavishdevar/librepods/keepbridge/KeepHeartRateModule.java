package me.kavishdevar.librepods.keepbridge;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/** Injects LibrePods samples when Keep's required internal heart-rate capabilities are present. */
public final class KeepHeartRateModule extends XposedModule {
    private static final String TAG = "LibrePodsKeepHR";
    private static final String KEEP_PACKAGE = KeepHeartRateBridge.KEEP_PACKAGE;
    private static final String DEVICE_NAME = "AirPods Pro";
    private static final String DEVICE_ADDRESS = "02:00:00:00:00:01";

    private final KeepHeartRateState state = new KeepHeartRateState();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String processName = "";
    private ClassLoader keepClassLoader;
    private Object syntheticDevice;
    private Object thirdPartyType;
    private Class<?> bleDeviceClass;
    private Method aggregatorSingleton;
    private Method aggregatorNotify;
    private Method bleManagerSingleton;
    private Method bleManagerGetModel;
    private Method bleManagerNotifyModel;
    private Method modelGetCurrentAddress;
    private Method modelGetDeviceMap;
    private Method modelSetCurrentAddress;
    private String previousManagerAddress;
    private boolean managerModelOverridden;
    private boolean initialized;
    private boolean receiverRegistered;
    private boolean deliveredActive;
    private int lastLoggedBpm = -1;

    private final Runnable staleRunnable = () -> {
        if (!state.isActive(SystemClock.elapsedRealtime()) && deliveredActive) {
            deliveredActive = false;
            updateSyntheticDevice(false, -1);
            notifyKeepListeners();
            notifyBleManagerListeners(false);
            info("bridge sample timed out; Keep source disconnected");
        }
    };

    private final BroadcastReceiver bridgeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !KeepHeartRateBridge.ACTION_STATE.equals(intent.getAction())) {
                return;
            }
            boolean enabled = intent.getBooleanExtra(KeepHeartRateBridge.EXTRA_ENABLED, false);
            boolean streaming = intent.getBooleanExtra(KeepHeartRateBridge.EXTRA_STREAMING, false);
            int bpm = intent.getIntExtra(KeepHeartRateBridge.EXTRA_BPM, -1);
            long now = SystemClock.elapsedRealtime();
            state.update(enabled, streaming, bpm, now);
            boolean active = state.isActive(now);

            updateSyntheticDevice(active, active ? bpm : -1);
            notifyKeepListeners();
            notifyBleManagerListeners(active);
            deliveredActive = active;

            mainHandler.removeCallbacks(staleRunnable);
            if (active) {
                mainHandler.postDelayed(
                        staleRunnable,
                        KeepHeartRateState.STALE_AFTER_MILLIS + 250L
                );
            }

            if (active && bpm != lastLoggedBpm) {
                lastLoggedBpm = bpm;
                info("delivered bpm=" + bpm + " to Keep");
            } else if (!active && lastLoggedBpm != -1) {
                lastLoggedBpm = -1;
                info("LibrePods heart-rate stream stopped");
            }
        }
    };

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!KEEP_PACKAGE.equals(param.getPackageName())
                || !KEEP_PACKAGE.equals(processName)
                || !param.isFirstPackage()) {
            return;
        }

        keepClassLoader = param.getDefaultClassLoader();
        try {
            hookApplicationAttach();
        } catch (Throwable throwable) {
            error("failed to hook Keep Application.attach", throwable);
        }
    }

    private void resolveKeepTypes() throws Exception {
        bleDeviceClass = Class.forName(
                "com.gotokeep.keep.data.model.outdoor.heart.HeartRateMonitorConnectModel$BleDevice",
                false,
                keepClassLoader
        );
        Class<?> heartRateTypeClass = Class.forName(
                "com.gotokeep.keep.data.model.outdoor.heart.HeartRateType",
                false,
                keepClassLoader
        );
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object type = Enum.valueOf((Class<? extends Enum>) heartRateTypeClass, "THIRD_PARTY");
        thirdPartyType = type;

        Constructor<?> constructor = bleDeviceClass.getDeclaredConstructor(
                String.class,
                String.class,
                heartRateTypeClass
        );
        constructor.setAccessible(true);
        syntheticDevice = constructor.newInstance(DEVICE_NAME, DEVICE_ADDRESS, thirdPartyType);

        requireField(bleDeviceClass, "heartRate");
        requireField(bleDeviceClass, "isSaved");
        requireField(bleDeviceClass, "connectStatus");
        Class<?> statusClass = Class.forName(
                "com.gotokeep.keep.data.model.outdoor.heart.HeartRateMonitorConnectModel$ConnectStatus",
                false,
                keepClassLoader
        );
        requireEnumConstant(statusClass, "CONNECTED");
        requireEnumConstant(statusClass, "NOT_CONNECTED");

        Class<?> aggregatorClass = Class.forName("el1.f", false, keepClassLoader);
        aggregatorSingleton = aggregatorClass.getDeclaredMethod("n");
        aggregatorSingleton.setAccessible(true);
        aggregatorNotify = aggregatorClass.getDeclaredMethod("q", bleDeviceClass);
        aggregatorNotify.setAccessible(true);

        updateSyntheticDevice(false, -1);
    }

    private void installKeepHooks() throws Exception {
        Class<?> aggregatorClass = Class.forName("el1.f", false, keepClassLoader);
        Class<?> bleManagerClass = Class.forName("hl1.g", false, keepClassLoader);
        Class<?> connectModelClass = Class.forName(
                "com.gotokeep.keep.data.model.outdoor.heart.HeartRateMonitorConnectModel",
                false,
                keepClassLoader
        );

        // Resolve every required member before installing the first hook. If Keep changes an
        // obfuscated class or method, initialization fails closed without leaving partial hooks.
        Method aggregatorHeartRate = requireMethod(aggregatorClass, "l");
        Method aggregatorCalorieHeartRate = requireMethod(aggregatorClass, "m");
        Method aggregatorIsConnected = requireMethod(aggregatorClass, "o");
        Method aggregatorDeviceName = requireMethod(aggregatorClass, "i");
        Method aggregatorHeartRateType = requireMethod(aggregatorClass, "j");
        Method aggregatorCurrentDevice = requireMethod(aggregatorClass, "k");
        Method managerIsConnected = requireMethod(bleManagerClass, "isConnected");
        Method managerDeviceName = requireMethod(bleManagerClass, "getConnectedDeviceName");
        Method managerCurrentDevice = requireMethod(bleManagerClass, "getCurrentBleDevice");
        Method managerDeviceList = requireMethod(bleManagerClass, "b");

        bleManagerSingleton = bleManagerClass.getDeclaredMethod("x");
        bleManagerGetModel = bleManagerClass.getDeclaredMethod("e");
        bleManagerNotifyModel = bleManagerClass.getDeclaredMethod("D");
        modelGetCurrentAddress = connectModelClass.getDeclaredMethod("b");
        modelGetDeviceMap = connectModelClass.getDeclaredMethod("c");
        modelSetCurrentAddress = connectModelClass.getDeclaredMethod("e", String.class);
        bleManagerSingleton.setAccessible(true);
        bleManagerGetModel.setAccessible(true);
        bleManagerNotifyModel.setAccessible(true);
        modelGetCurrentAddress.setAccessible(true);
        modelGetDeviceMap.setAccessible(true);
        modelSetCurrentAddress.setAccessible(true);

        hookActiveReturn(aggregatorHeartRate,
                () -> state.getBpm(SystemClock.elapsedRealtime()));
        hookActiveReturn(aggregatorCalorieHeartRate,
                () -> state.getBpm(SystemClock.elapsedRealtime()));
        hookActiveReturn(aggregatorIsConnected, () -> true);
        hookActiveReturn(aggregatorDeviceName, () -> DEVICE_NAME);
        hookActiveReturn(aggregatorHeartRateType, () -> thirdPartyType);
        hookActiveReturn(aggregatorCurrentDevice, () -> syntheticDevice);
        hookActiveReturn(managerIsConnected, () -> true);
        hookActiveReturn(managerDeviceName, () -> DEVICE_NAME);
        hookActiveReturn(managerCurrentDevice, () -> syntheticDevice);
        hookActiveReturn(managerDeviceList, () -> Collections.singletonList(syntheticDevice));
    }

    private void hookApplicationAttach() throws Exception {
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        attach.setAccessible(true);
        hook(attach).intercept(chain -> {
            Object result = chain.proceed();
            Context context = (Context) chain.getArg(0);
            Context applicationContext = context.getApplicationContext();
            initializeForTarget(applicationContext != null ? applicationContext : context);
            return result;
        });
    }

    private void initializeForTarget(Context context) {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            long versionCode = context.getPackageManager()
                    .getPackageInfo(KEEP_PACKAGE, 0)
                    .getLongVersionCode();
            resolveKeepTypes();
            installKeepHooks();
            registerBridgeReceiver(context);
            info("Keep heart-rate capability probe passed; bridge ready for versionCode="
                    + versionCode);
        } catch (Throwable throwable) {
            error("Keep heart-rate capability probe failed; bridge left disabled", throwable);
        }
    }

    private void registerBridgeReceiver(Context context) {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(KeepHeartRateBridge.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(
                    bridgeReceiver,
                    filter,
                    KeepHeartRateBridge.PERMISSION,
                    mainHandler,
                    Context.RECEIVER_EXPORTED
            );
        } else {
            context.registerReceiver(
                    bridgeReceiver,
                    filter,
                    KeepHeartRateBridge.PERMISSION,
                    mainHandler
            );
        }
        receiverRegistered = true;
        info("registered signature-protected LibrePods receiver");
    }

    private static Method requireMethod(Class<?> targetClass, String methodName) throws Exception {
        Method method = targetClass.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method;
    }

    private static Field requireField(Class<?> targetClass, String fieldName) throws Exception {
        Field field = targetClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object requireEnumConstant(Class<?> enumClass, String constantName) {
        return Enum.valueOf((Class<? extends Enum>) enumClass, constantName);
    }

    private void hookActiveReturn(Method method, ValueProvider provider) {
        hook(method).intercept(chain -> {
            if (!state.isActive(SystemClock.elapsedRealtime())) {
                return chain.proceed();
            }
            return provider.get();
        });
    }

    private void updateSyntheticDevice(boolean active, int bpm) {
        if (syntheticDevice == null) {
            return;
        }
        try {
            setField(bleDeviceClass, syntheticDevice, "heartRate", active ? bpm : -1);
            setField(bleDeviceClass, syntheticDevice, "isSaved", true);
            Class<?> statusClass = Class.forName(
                    "com.gotokeep.keep.data.model.outdoor.heart.HeartRateMonitorConnectModel$ConnectStatus",
                    false,
                    keepClassLoader
            );
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object status = Enum.valueOf(
                    (Class<? extends Enum>) statusClass,
                    active ? "CONNECTED" : "NOT_CONNECTED"
            );
            setField(bleDeviceClass, syntheticDevice, "connectStatus", status);
        } catch (Throwable throwable) {
            error("failed to update Keep BleDevice", throwable);
        }
    }

    private static void setField(Class<?> owner, Object instance, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private void notifyKeepListeners() {
        if (aggregatorSingleton == null || aggregatorNotify == null || syntheticDevice == null) {
            return;
        }
        try {
            Object aggregator = aggregatorSingleton.invoke(null);
            aggregatorNotify.invoke(aggregator, syntheticDevice);
        } catch (Throwable throwable) {
            error("failed to notify Keep heart-rate listeners", throwable);
        }
    }

    /**
     * Keep's workout input source subscribes directly to hl1.g instead of el1.f. Temporarily put
     * the synthetic device into hl1.g's model and trigger its native model callback so a running
     * workout receives the same BPM shown by the equipment screen.
     */
    @SuppressWarnings("unchecked")
    private void notifyBleManagerListeners(boolean active) {
        if (bleManagerSingleton == null
                || bleManagerGetModel == null
                || bleManagerNotifyModel == null
                || syntheticDevice == null) {
            return;
        }
        try {
            Object manager = bleManagerSingleton.invoke(null);
            Object model = bleManagerGetModel.invoke(manager);
            Map<String, Object> deviceMap = (Map<String, Object>) modelGetDeviceMap.invoke(model);

            if (active) {
                if (!managerModelOverridden) {
                    previousManagerAddress = (String) modelGetCurrentAddress.invoke(model);
                    managerModelOverridden = true;
                }
                deviceMap.put(DEVICE_ADDRESS, syntheticDevice);
                modelSetCurrentAddress.invoke(model, DEVICE_ADDRESS);
                bleManagerNotifyModel.invoke(manager);
                return;
            }

            if (managerModelOverridden) {
                deviceMap.put(DEVICE_ADDRESS, syntheticDevice);
                modelSetCurrentAddress.invoke(model, DEVICE_ADDRESS);
                bleManagerNotifyModel.invoke(manager);
                deviceMap.remove(DEVICE_ADDRESS);
                modelSetCurrentAddress.invoke(
                        model,
                        previousManagerAddress == null ? "" : previousManagerAddress
                );
                previousManagerAddress = null;
                managerModelOverridden = false;
            }
        } catch (Throwable throwable) {
            error("failed to notify Keep BLE manager listeners", throwable);
        }
    }

    private void info(String message) {
        log(Log.INFO, TAG, message);
    }

    private void warn(String message) {
        log(Log.WARN, TAG, message);
    }

    private void error(String message, Throwable throwable) {
        log(Log.ERROR, TAG, message + ": " + Log.getStackTraceString(throwable));
    }

    private interface ValueProvider {
        Object get();
    }
}
