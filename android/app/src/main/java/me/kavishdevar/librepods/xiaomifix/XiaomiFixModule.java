package me.kavishdevar.librepods.xiaomifix;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import me.kavishdevar.librepods.xiaomifix.resolve.DexKitResolver;
import me.kavishdevar.librepods.xiaomifix.resolve.HookIds;
import me.kavishdevar.librepods.xiaomifix.resolve.HookSelfCheck;
import me.kavishdevar.librepods.xiaomifix.resolve.ReflectiveMethodMatcher;
import me.kavishdevar.librepods.xiaomifix.resolve.ResolveResult;
import me.kavishdevar.librepods.xiaomifix.resolve.TargetResolver;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

public class XiaomiFixModule extends XposedModule {
    private static final String TAG = "LibrePodsXiaomiFix";

    static {
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable ignored) {
        }
    }
    private static final String XIAOMI_BT = "com.xiaomi.bluetooth";
    private static final String FAST_CONNECT_AIRPODS = "com.android.bluetooth.FAST_CONNECT_DEVICE_AIRPODS";
    private static final String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";
    private static final long AIRPODS_GUARD_MS = 180_000L;

    private String processName = "";
    private volatile long lastAirPodsActivityMs = 0L;
    private final Map<String, Long> lastLogMs = new HashMap<>();
    private final Set<String> airPodsAddresses = Collections.synchronizedSet(new HashSet<>());
    private Class<?> xiaomiFeatureUtilsClass;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        info("module loaded in " + processName + ", framework=" + getFrameworkName()
                + "(" + getFrameworkVersionCode() + "), api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!XIAOMI_BT.equals(param.getPackageName())) {
            return;
        }

        info("package loaded: " + param.getPackageName() + ", first=" + param.isFirstPackage()
                + ", process=" + processName);

        hookSettingsGate();
        hookContextStartActivity();
        hookBluetoothDiscovery();

        ClassLoader cl = param.getDefaultClassLoader();
        hookMiuiFastConnectService(cl);

        Context context = getAppContext();
        if (context == null) {
            info("app context not ready; resolving dynamic hooks via ClassLoader");
        }

        DexKitResolver.LogSink resolveLog = new DexKitResolver.LogSink() {
            @Override
            public void info(String message) {
                XiaomiFixModule.this.info(message);
            }

            @Override
            public void warn(String message) {
                XiaomiFixModule.this.warn(message);
            }
        };
        ResolveResult resolveResult = TargetResolver.resolve(cl, context, resolveLog);
        xiaomiFeatureUtilsClass = resolveResult.getClass(HookIds.XIAOMI_FEATURE_UTILS);
        installResolvedHooks(resolveResult);
        HookSelfCheck.log(resolveResult, new HookSelfCheck.LogSink() {
            @Override
            public void info(String message) {
                XiaomiFixModule.this.info(message);
            }

            @Override
            public void warn(String message) {
                XiaomiFixModule.this.warn(message);
            }
        });
    }

    private void installResolvedHooks(ResolveResult result) {
        Class<?> handler = result.getClass(HookIds.FAST_CONNECT_HANDLER);
        if (handler != null) {
            hookMiuiHandler(handler);
        }

        Class<?> helper = result.getClass(HookIds.AIRPODS_SCAN_HELPER);
        if (helper != null) {
            hookAirPodsHelper(helper);
        }

        Class<?> airCoreManager = result.getClass(HookIds.AIRCORE_MANAGER);
        Class<?> transportHandler = result.getClass(HookIds.AIRCORE_TRANSPORT_HANDLER);
        Class<?> transportPayload = result.getClass(HookIds.AIRCORE_TRANSPORT_PAYLOAD);
        if (airCoreManager != null || transportHandler != null) {
            hookXiaomiAirCoreTransport(airCoreManager, transportHandler, transportPayload);
        }
    }

    private void hookSettingsGate() {
        hookMethod(Settings.Secure.class, "getInt",
                new Class<?>[]{android.content.ContentResolver.class, String.class, int.class},
                chain -> {
                    String name = asString(chain.getArg(1));
                    if ("AIRPODS_ADAPTER_JAR_ENABLE".equals(name)) {
                        markAirPodsActivity("settings");
                        info("force Settings.Secure AIRPODS_ADAPTER_JAR_ENABLE=0");
                        return 0;
                    }
                    return chain.proceed();
                });

        hookMethod(Settings.Secure.class, "getInt",
                new Class<?>[]{android.content.ContentResolver.class, String.class},
                chain -> {
                    String name = asString(chain.getArg(1));
                    if ("AIRPODS_ADAPTER_JAR_ENABLE".equals(name)) {
                        markAirPodsActivity("settings");
                        info("force Settings.Secure AIRPODS_ADAPTER_JAR_ENABLE=0");
                        return 0;
                    }
                    return chain.proceed();
                });
    }

    private void hookContextStartActivity() {
        hookMethod(ContextWrapper.class, "startActivity", new Class<?>[]{Intent.class}, chain -> {
            Intent intent = (Intent) chain.getArg(0);
            if (isAirPodsFastConnectIntent(intent)) {
                markAirPodsActivity("activity");
                info("blocked AirPods fast connect activity: " + intent);
                return null;
            }
            return chain.proceed();
        });

        hookMethod(ContextWrapper.class, "startActivity", new Class<?>[]{Intent.class, Bundle.class}, chain -> {
            Intent intent = (Intent) chain.getArg(0);
            if (isAirPodsFastConnectIntent(intent)) {
                markAirPodsActivity("activity");
                info("blocked AirPods fast connect activity with bundle: " + intent);
                return null;
            }
            return chain.proceed();
        });
    }

    private void hookBluetoothDiscovery() {
        hookMethod(BluetoothAdapter.class, "startDiscovery", new Class<?>[]{}, chain -> {
            if (shouldSuppressRadioChurn("BluetoothAdapter.startDiscovery")) {
                info("blocked classic discovery during recent AirPods activity");
                return true;
            }
            observeThrottled("classic discovery allowed");
            return chain.proceed();
        });

        hookMethod(BluetoothAdapter.class, "cancelDiscovery", new Class<?>[]{}, chain -> {
            if (isUnderAirPodsGuard()) {
                observeThrottled("classic discovery cancel observed during AirPods guard");
            }
            return chain.proceed();
        });
    }

    private void hookMiuiFastConnectService(ClassLoader cl) {
        Class<?> service = findClass(cl, "com.android.bluetooth.ble.app.MiuiFastConnectService");
        if (service == null) {
            return;
        }

        hookMethod(service, "onCreate", new Class<?>[]{}, chain -> {
            Object result = chain.proceed();
            forceDisableAirPodsFeature(chain.getThisObject(), "MiuiFastConnectService.onCreate");
            return result;
        });

        hookMethod(service, "sendMessageDelayObject",
                new Class<?>[]{int.class, int.class, int.class, Object.class, long.class, boolean.class},
                chain -> {
                    int what = asInt(chain.getArg(0), -1);
                    Object obj = chain.getArg(3);
                    if (what == 17 && isAppleScanResult(obj)) {
                        markAirPodsActivity("scan-result");
                        forceDisableAirPodsFeature(chain.getThisObject(), "sendMessageDelayObject(17)");
                        info("blocked Apple manufacturer scan result before MiuiFastConnect handler");
                        return true;
                    }
                    return chain.proceed();
                });

        hookMethod(service, "startBleScan", new Class<?>[]{long.class}, chain -> {
            long delay = asLong(chain.getArg(0), -1L);
            if (shouldSuppressRadioChurn("MiuiFastConnectService.startBleScan")) {
                info("blocked startBleScan(" + delay + ") during active AirPods/audio window");
                return null;
            }
            observeThrottled("startBleScan(" + delay + ") allowed");
            return chain.proceed();
        });

        hookMethod(service, "stopBleScan", new Class<?>[]{}, chain -> {
            if (shouldSuppressRadioChurn("MiuiFastConnectService.stopBleScan")) {
                info("blocked stopBleScan during AirPods guard window");
                return null;
            }
            if (isUnderAirPodsGuard()) {
                observeThrottled("stopBleScan observed during AirPods guard");
            }
            return chain.proceed();
        });

        hookMethod(service, "changeScanMode", new Class<?>[]{int.class}, chain -> {
            int mode = asInt(chain.getArg(0), -1);
            if (shouldSuppressRadioChurn("MiuiFastConnectService.changeScanMode")) {
                info("blocked changeScanMode(" + mode + ") during active AirPods/audio window");
                return null;
            }
            return chain.proceed();
        });

        hookMethod(service, "changeScanMode", new Class<?>[]{int.class, boolean.class}, chain -> {
            int mode = asInt(chain.getArg(0), -1);
            boolean force = Boolean.TRUE.equals(chain.getArg(1));
            if (shouldSuppressRadioChurn("MiuiFastConnectService.changeScanMode(force)")) {
                info("blocked changeScanMode(" + mode + ", " + force + ") during active AirPods/audio window");
                return null;
            }
            return chain.proceed();
        });

        hookMethod(service, "handleActionConnectionStateChange",
                new Class<?>[]{BluetoothDevice.class, int.class},
                chain -> {
                    BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
                    int state = asInt(chain.getArg(1), -1);
                    if (isAirPodsTargetDevice(device)) {
                        markAirPodsActivity("profile-state");
                        info("blocked MiuiFastConnect profile-state handling for AirPods, state=" + state
                                + ", device=" + safeDevice(device));
                        return null;
                    }
                    return chain.proceed();
                });
    }

    private void hookMiuiHandler(Class<?> handler) {
        hookMethod(handler, "handleMessage", new Class<?>[]{Message.class}, chain -> {
            Message message = (Message) chain.getArg(0);
            if (message == null) {
                return chain.proceed();
            }

            Object service = findR4OuterService(chain.getThisObject());
            int what = message.what;

            if (what == 44) {
                Object result = chain.proceed();
                forceDisableAirPodsFeature(service, "R4 MSG_GET_BASE_VERSION_FOR_AIRPODS");
                return result;
            }

            if (what == 17 && isAppleScanResult(message.obj)) {
                markAirPodsActivity("handler-scan");
                forceDisableAirPodsFeature(service, "R4 MSG_BLE_SCAN_DEVICE");
                info("blocked R4 Apple scan message");
                return null;
            }

            if (what == 15 && isUnderAirPodsGuard()) {
                callSendMessageDelay(service, 15, 60_000L);
                info("blocked R4 60s scan recycle during AirPods guard; rescheduled check");
                return null;
            }

            return chain.proceed();
        });
    }

    private void hookAirPodsHelper(Class<?> helper) {
        hookHelperMethod(helper, new String[]{"Q"}, new Class<?>[]{ScanResult.class}, null, chain -> {
            if (isAppleScanResult(chain.getArg(0))) {
                markAirPodsActivity("helper-Q");
                info("blocked MiuiAirPodsFastConnectHelper.Q(scanResult)");
                return null;
            }
            return chain.proceed();
        }, "(ScanResult)");

        hookHelperMethod(helper, new String[]{"H"}, new Class<?>[]{int.class, BluetoothDevice.class}, null, chain -> {
            int state = asInt(chain.getArg(0), -1);
            BluetoothDevice device = (BluetoothDevice) chain.getArg(1);
            if (!isAirPodsTargetDevice(device)) {
                return chain.proceed();
            }
            markAirPodsActivity("helper-H");
            info("blocked MiuiAirPodsFastConnectHelper.H state=" + state + ", device=" + safeDevice(device));
            return null;
        }, "(int, BluetoothDevice)");

        hookHelperMethod(helper, new String[]{"W"}, new Class<?>[]{BluetoothDevice.class, int.class}, boolean.class, chain -> {
            BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
            int state = asInt(chain.getArg(1), -1);
            if (!isAirPodsTargetDevice(device)) {
                return chain.proceed();
            }
            markAirPodsActivity("helper-W");
            info("blocked AirPods fast connect UI helper, state=" + state + ", device=" + safeDevice(device));
            return false;
        }, "(BluetoothDevice, int)->boolean");

        Method connectMethod = ReflectiveMethodMatcher.findDeclaredMethod(
                helper, "X", new Class<?>[]{Object.class}, null);
        if (connectMethod == null) {
            connectMethod = ReflectiveMethodMatcher.findSingleObjectArgMethod(helper);
        }
        if (connectMethod != null) {
            final Method connectHook = connectMethod;
            hookMethod(connectHook, chain -> {
                markAirPodsActivity("helper-X");
                info("blocked AirPodsInfo connect helper via " + connectHook.getName());
                return null;
            });
        } else {
            warn("method not found: " + helper.getName() + "#X(Object)");
        }
    }

    private void hookHelperMethod(
            Class<?> helper,
            String[] names,
            Class<?>[] paramTypes,
            Class<?> returnType,
            HookBody body,
            String label) {
        Method method = null;
        for (String name : names) {
            method = ReflectiveMethodMatcher.findDeclaredMethod(helper, name, paramTypes, returnType);
            if (method != null) {
                break;
            }
        }
        if (method == null) {
            method = ReflectiveMethodMatcher.findDeclaredMethod(helper, paramTypes, returnType);
        }
        if (method != null) {
            hookMethod(method, body);
        } else {
            warn("method not found: " + helper.getName() + "#" + label);
        }
    }

    private void hookXiaomiAirCoreTransport(
            Class<?> airCoreManager,
            Class<?> transportHandler,
            Class<?> transportPayloadClass) {
        if (airCoreManager != null) {
            hookAirCoreManager(airCoreManager);
        }

        if (transportHandler == null) {
            return;
        }

        Method l2capMethod = ReflectiveMethodMatcher.findDeclaredMethod(
                transportHandler, "b", new Class<?>[]{BluetoothDevice.class, int.class}, void.class);
        if (l2capMethod == null) {
            l2capMethod = ReflectiveMethodMatcher.findDeclaredMethod(
                    transportHandler, new Class<?>[]{BluetoothDevice.class, int.class}, void.class);
        }
        if (l2capMethod != null) {
            hookMethod(l2capMethod, chain -> {
                BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
                int psm = asInt(chain.getArg(1), -1);
                if (psm != 4097 || !isAirPodsTargetDevice(device)) {
                    return chain.proceed();
                }
                markAirPodsActivity("aircore-l2cap-connect");
                info("blocked Xiaomi DevicesTransportHandler.handleConnectL2capMsg psm="
                        + psm + ", device=" + safeDevice(device));
                return null;
            });
        } else {
            warn("method not found: " + transportHandler.getName() + "#(BluetoothDevice, int)");
        }

        hookMethod(transportHandler, "handleMessage", new Class<?>[]{Message.class}, chain -> {
            Message message = (Message) chain.getArg(0);
            if (message != null && message.what == 1 && message.arg1 == 4097
                    && message.obj instanceof BluetoothDevice
                    && isAirPodsTargetDevice((BluetoothDevice) message.obj)) {
                markAirPodsActivity("aircore-l2cap-message");
                info("blocked Xiaomi DevicesTransportHandler message what=1,arg1=4097,obj="
                        + safeDevice((BluetoothDevice) message.obj));
                return null;
            }
            return chain.proceed();
        });

        if (transportPayloadClass != null) {
            Method payloadMethod = ReflectiveMethodMatcher.findDeclaredMethod(
                    transportPayloadClass, new Class<?>[]{}, BluetoothDevice.class);
            if (payloadMethod != null) {
                Method sendDataMethod = ReflectiveMethodMatcher.findDeclaredMethod(
                        transportHandler, "h", new Class<?>[]{transportPayloadClass}, void.class);
                if (sendDataMethod == null) {
                    sendDataMethod = ReflectiveMethodMatcher.findDeclaredMethod(
                            transportHandler, new Class<?>[]{transportPayloadClass}, void.class);
                }
                if (sendDataMethod != null) {
                    hookMethod(sendDataMethod, chain -> {
                            Object payload = chain.getArg(0);
                            BluetoothDevice device = extractDeviceFromTransportPayload(payload, payloadMethod);
                            if (!isAirPodsTargetDevice(device)) {
                                return chain.proceed();
                            }
                            markAirPodsActivity("aircore-send-data-handler");
                            info("blocked Xiaomi DevicesTransportHandler.sendData for " + safeDevice(device));
                            return null;
                        });
                } else {
                    warn("method not found: " + transportHandler.getName() + "#(transportPayload)");
                }
            }
        }
    }

    private void hookAirCoreManager(Class<?> airCoreManager) {
        hookAirCoreMethod(airCoreManager, new String[]{"i"}, new Class<?>[]{BluetoothDevice.class}, void.class, chain -> {
            BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
            if (!isAirPodsTargetDevice(device)) {
                return chain.proceed();
            }
            markAirPodsActivity("aircore-onconnected");
            info("blocked Xiaomi AirCoreManager.onDeviceConnected for " + safeDevice(device));
            return null;
        }, "onDeviceConnected");

        hookAirCoreMethod(airCoreManager, new String[]{"j"}, new Class<?>[]{Intent.class}, void.class, chain -> {
            Intent intent = (Intent) chain.getArg(0);
            BluetoothDevice device = getDeviceFromIntent(intent);
            if (!isAirPodsTargetDevice(device)) {
                return chain.proceed();
            }
            markAirPodsActivity("aircore-hfp");
            info("blocked Xiaomi AirCoreManager.handleHfpConnectionChanged for " + safeDevice(device));
            return null;
        }, "handleHfpConnectionChanged");

        hookAirCoreMethod(airCoreManager, new String[]{"l"}, new Class<?>[]{}, void.class, chain -> {
            if (!hasConnectedAirPodsOnHeadset(chain.getThisObject())) {
                return chain.proceed();
            }
            markAirPodsActivity("aircore-hfp-service");
            info("blocked Xiaomi AirCoreManager.handleHfpServiceConnected for AirPods");
            return null;
        }, "handleHfpServiceConnected");

        hookAirCoreMethod(airCoreManager, new String[]{"q"}, new Class<?>[]{BluetoothDevice.class}, void.class, chain -> {
            BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
            if (!isAirPodsTargetDevice(device)) {
                return chain.proceed();
            }
            markAirPodsActivity("aircore-send-connect");
            info("blocked Xiaomi AirCoreManager.sendConnectMsg for " + safeDevice(device));
            return null;
        }, "sendConnectMsg");

        hookAirCoreMethod(airCoreManager, new String[]{"t"}, new Class<?>[]{BluetoothDevice.class}, void.class, chain -> {
            BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
            if (!isAirPodsTargetDevice(device)) {
                return chain.proceed();
            }
            markAirPodsActivity("aircore-send-disconnect");
            info("blocked Xiaomi AirCoreManager.sendDisconnectMsg for " + safeDevice(device));
            return null;
        }, "sendDisconnectMsg");

        hookAirCoreMethod(airCoreManager, new String[]{"r"},
                new Class<?>[]{BluetoothDevice.class, String.class, String.class}, int.class, chain -> {
            BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
            if (!isAirPodsTargetDevice(device)) {
                return chain.proceed();
            }
            markAirPodsActivity("aircore-set-command");
            info("blocked Xiaomi AirCoreManager.setCommand for " + safeDevice(device));
            return 0;
        }, "setCommand");

        hookAirCoreMethod(airCoreManager, new String[]{"s"},
                new Class<?>[]{BluetoothDevice.class, byte[].class, int.class}, int.class, chain -> {
            BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
            if (!isAirPodsTargetDevice(device)) {
                return chain.proceed();
            }
            markAirPodsActivity("aircore-send-data");
            info("blocked Xiaomi AirCoreManager.sendData for " + safeDevice(device));
            return 0;
        }, "sendData");
    }

    private void hookAirCoreMethod(
            Class<?> clazz,
            String[] names,
            Class<?>[] paramTypes,
            Class<?> returnType,
            HookBody body,
            String label) {
        Method method = null;
        for (String name : names) {
            method = ReflectiveMethodMatcher.findDeclaredMethod(clazz, name, paramTypes, returnType);
            if (method != null) {
                break;
            }
        }
        if (method == null) {
            method = ReflectiveMethodMatcher.findDeclaredMethod(clazz, paramTypes, returnType);
        }
        if (method != null) {
            hookMethod(method, body);
        } else {
            warn("method not found: " + clazz.getName() + "#" + label);
        }
    }

    private boolean isAirPodsFastConnectIntent(Intent intent) {
        return intent != null && FAST_CONNECT_AIRPODS.equals(intent.getAction());
    }

    private boolean isAppleScanResult(Object obj) {
        if (!(obj instanceof ScanResult)) {
            return false;
        }
        try {
            ScanRecord record = ((ScanResult) obj).getScanRecord();
            if (record != null && record.getManufacturerSpecificData(76) != null) {
                BluetoothDevice device = ((ScanResult) obj).getDevice();
                if (device != null) {
                    trackAirPodsDevice(device);
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean isLikelyAirPodsDevice(BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        String text = (safeCallString(device, "getName") + " " + safeCallString(device, "getAlias"))
                .toLowerCase(Locale.US);
        return text.contains("airpods") || text.contains("air pods") || text.contains("beats");
    }

    private boolean isAirPodsTargetDevice(BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        String address = safeAddress(device);
        if (!address.isEmpty() && airPodsAddresses.contains(address)) {
            return true;
        }
        if (isLikelyAirPodsDevice(device)) {
            trackAirPodsDevice(device);
            return true;
        }
        if (hasAirpodsModelEntry(address)) {
            trackAirPodsDevice(device);
            return true;
        }
        Boolean xiaomiTarget = isXiaomiConnectL2capTarget(device);
        if (Boolean.TRUE.equals(xiaomiTarget)) {
            trackAirPodsDevice(device);
            return true;
        }
        return false;
    }

    private void trackAirPodsDevice(BluetoothDevice device) {
        String address = safeAddress(device);
        if (!address.isEmpty()) {
            airPodsAddresses.add(address);
        }
    }

    private boolean hasAirpodsModelEntry(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        try {
            Context context = getAppContext();
            if (context == null) {
                return false;
            }
            SharedPreferences prefs = context.getSharedPreferences("AirpodsModel", Context.MODE_PRIVATE);
            return prefs.contains(address + "-connectState")
                    || prefs.contains(address + "-model")
                    || prefs.contains(address + "-type");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Boolean isXiaomiConnectL2capTarget(BluetoothDevice device) {
        if (xiaomiFeatureUtilsClass == null || device == null) {
            return null;
        }
        try {
            Context context = getAppContext();
            if (context == null) {
                return null;
            }
            Method method = ReflectiveMethodMatcher.findMethod(
                    xiaomiFeatureUtilsClass,
                    new Class<?>[]{Context.class, BluetoothDevice.class, String.class},
                    Boolean.class);
            if (method == null) {
                return null;
            }
            Object result = method.invoke(null, context.getApplicationContext(), device, "ConnectL2cap");
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean hasConnectedAirPodsOnHeadset(Object airCoreManager) {
        try {
            Object headset = getFieldValue(airCoreManager, "f16415g");
            if (!(headset instanceof BluetoothHeadset)) {
                return false;
            }
            List<BluetoothDevice> devices = ((BluetoothHeadset) headset).getConnectedDevices();
            if (devices == null) {
                return false;
            }
            for (BluetoothDevice device : devices) {
                if (isAirPodsTargetDevice(device)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private BluetoothDevice getDeviceFromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                return intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice.class);
            }
            return intent.getParcelableExtra(EXTRA_DEVICE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private BluetoothDevice extractDeviceFromTransportPayload(Object payload, Method deviceMethod) {
        if (payload == null || deviceMethod == null) {
            return null;
        }
        try {
            Object device = deviceMethod.invoke(payload);
            return device instanceof BluetoothDevice ? (BluetoothDevice) device : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Context getAppContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method method = activityThread.getMethod("currentApplication");
            Object app = method.invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object findR4OuterService(Object handler) {
        if (handler == null) {
            return null;
        }
        Object direct = getFieldValue(handler, "f9991a");
        if (direct != null) {
            return direct;
        }
        Class<?> type = handler.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                String fieldType = field.getType().getName();
                if (!fieldType.endsWith("MiuiFastConnectService")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(handler);
                    if (value != null) {
                        info("R4 outer field: " + field.getName());
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private boolean shouldSuppressRadioChurn(String source) {
        if (!isUnderAirPodsGuard()) {
            return false;
        }
        if (!shouldProtectAirPodsRadioNow()) {
            observeThrottled(source + " allowed during AirPods guard without active AirPods reconnect");
            return false;
        }
        return true;
    }

    private boolean shouldProtectAirPodsRadioNow() {
        if (isAirPodsReconnecting()) {
            return true;
        }
        return isAudioProfileActiveForTrackedAirPods();
    }

    private boolean isAirPodsReconnecting() {
        if (!isUnderAirPodsGuard() || airPodsAddresses.isEmpty()) {
            return false;
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                return false;
            }
            for (String address : airPodsAddresses) {
                BluetoothDevice device = adapter.getRemoteDevice(address);
                int a2dp = queryDeviceProfileState(device, BluetoothProfile.A2DP);
                int hfp = queryDeviceProfileState(device, BluetoothProfile.HEADSET);
                if (a2dp == BluetoothProfile.STATE_CONNECTING
                        || hfp == BluetoothProfile.STATE_CONNECTING
                        || a2dp == BluetoothProfile.STATE_DISCONNECTED
                        || hfp == BluetoothProfile.STATE_DISCONNECTED) {
                    return true;
                }
            }
        } catch (Throwable t) {
            warn("AirPods reconnect probe failed: " + t.getMessage());
        }
        return false;
    }

    private boolean isAudioProfileActiveForTrackedAirPods() {
        if (airPodsAddresses.isEmpty()) {
            return isAudioProfileActive();
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                return false;
            }
            for (String address : airPodsAddresses) {
                BluetoothDevice device = adapter.getRemoteDevice(address);
                int a2dp = queryDeviceProfileState(device, BluetoothProfile.A2DP);
                int hfp = queryDeviceProfileState(device, BluetoothProfile.HEADSET);
                if (a2dp == BluetoothProfile.STATE_CONNECTED
                        || a2dp == BluetoothProfile.STATE_CONNECTING
                        || hfp == BluetoothProfile.STATE_CONNECTED
                        || hfp == BluetoothProfile.STATE_CONNECTING) {
                    return true;
                }
            }
        } catch (Throwable t) {
            warn("tracked AirPods audio probe failed: " + t.getMessage());
        }
        return false;
    }

    private int queryDeviceProfileState(BluetoothDevice device, int profile) {
        try {
            Context context = getAppContext();
            if (context != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                BluetoothManager manager =
                        (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                if (manager != null) {
                    return manager.getConnectionState(device, profile);
                }
            }
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }
            return adapter.getProfileConnectionState(profile);
        } catch (Throwable t) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
    }

    private boolean isUnderAirPodsGuard() {
        long last = lastAirPodsActivityMs;
        return last != 0L && SystemClock.uptimeMillis() - last < AIRPODS_GUARD_MS;
    }

    private void markAirPodsActivity(String reason) {
        lastAirPodsActivityMs = SystemClock.uptimeMillis();
        observeThrottled("AirPods activity marker: " + reason);
    }

    private boolean isAudioProfileActive() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                return false;
            }
            int a2dp = adapter.getProfileConnectionState(BluetoothProfile.A2DP);
            int hfp = adapter.getProfileConnectionState(BluetoothProfile.HEADSET);
            return a2dp == BluetoothProfile.STATE_CONNECTED
                    || a2dp == BluetoothProfile.STATE_CONNECTING
                    || hfp == BluetoothProfile.STATE_CONNECTED
                    || hfp == BluetoothProfile.STATE_CONNECTING;
        } catch (Throwable t) {
            warn("audio profile query failed: " + t.getMessage());
            return false;
        }
    }

    private void forceDisableAirPodsFeature(Object service, String source) {
        if (service == null) {
            return;
        }
        boolean changed = false;
        changed |= setFieldValue(service, "mAirPodsAdapterEnable", 0);
        changed |= setFieldValue(service, "mSupportAirPodsFeature", false);
        if (changed) {
            info("forced Xiaomi AirPods feature off at " + source);
        }
    }

    private void callSendMessageDelay(Object service, int what, long delayMs) {
        if (service == null) {
            return;
        }
        try {
            Method method = findMethod(service.getClass(), "sendMessageDelay", int.class, long.class);
            if (method != null) {
                method.setAccessible(true);
                method.invoke(service, what, delayMs);
            }
        } catch (Throwable t) {
            warn("sendMessageDelay(" + what + ") failed: " + t.getMessage());
        }
    }

    private Class<?> findClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            warn("class not found: " + name + " (" + t.getMessage() + ")");
            return null;
        }
    }

    private void hookMethod(Method method, HookBody body) {
        try {
            hook(method).intercept(chain -> body.handle(chain));
            info("hooked " + method.getDeclaringClass().getName() + "#" + method.getName());
        } catch (Throwable t) {
            warn("hook failed: " + method.getDeclaringClass().getName() + "#" + method.getName()
                    + " (" + t.getMessage() + ")");
        }
    }

    private void hookMethod(Class<?> clazz, String name, Class<?>[] parameterTypes, HookBody body) {
        try {
            Method method = findMethod(clazz, name, parameterTypes);
            if (method == null) {
                warn("method not found: " + clazz.getName() + "#" + name);
                return;
            }
            method.setAccessible(true);
            hook(method).intercept(chain -> body.handle(chain));
            info("hooked " + clazz.getName() + "#" + name);
        } catch (Throwable t) {
            warn("hook failed: " + clazz.getName() + "#" + name + " (" + t.getMessage() + ")");
        }
    }

    private Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        Class<?> cur = clazz;
        while (cur != null) {
            try {
                return cur.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private Object getFieldValue(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean setFieldValue(Object target, String name, Object value) {
        try {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                return false;
            }
            field.setAccessible(true);
            field.set(target, value);
            return true;
        } catch (Throwable t) {
            warn("set field failed: " + name + " (" + t.getMessage() + ")");
            return false;
        }
    }

    private Field findField(Class<?> clazz, String name) {
        Class<?> cur = clazz;
        while (cur != null) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private String safeDevice(BluetoothDevice device) {
        if (device == null) {
            return "null";
        }
        return safeAddress(device) + "/" + safeCallString(device, "getName") + "/"
                + safeCallString(device, "getAlias");
    }

    private String safeAddress(BluetoothDevice device) {
        if (device == null) {
            return "";
        }
        try {
            return device.getAddress();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private String safeCallString(Object target, String methodName) {
        if (target == null) {
            return "";
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(target);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int asInt(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private long asLong(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private void observeThrottled(String message) {
        long now = SystemClock.uptimeMillis();
        Long last = lastLogMs.get(message);
        if (last == null || now - last > 5_000L) {
            lastLogMs.put(message, now);
            info(message);
        }
    }

    private void info(String message) {
        log(Log.INFO, TAG, message);
    }

    private void warn(String message) {
        log(Log.WARN, TAG, message);
    }

    private interface HookBody {
        Object handle(XposedInterface.Chain chain) throws Throwable;
    }
}
