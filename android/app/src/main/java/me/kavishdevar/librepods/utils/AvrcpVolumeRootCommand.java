/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 */

package me.kavishdevar.librepods.utils;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.Arrays;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Root app_process entry point that resends the active AirPods AVRCP volume. */
@SuppressLint("MissingPermission")
public final class AvrcpVolumeRootCommand {
    private static final long PLAYBACK_POLL_INTERVAL_MS = 100L;
    private static final long PLAYBACK_TIMEOUT_MS = 6_000L;
    private static final long PREWARM_TIMEOUT_MS = 30L * 60L * 1_000L;

    private static BluetoothAdapter adapter;
    private static BluetoothProfile proxy;
    private static volatile boolean finished;
    private static String commandId = "unknown";
    private static long startedAt;
    private static long waitStartedAt;
    private static long lastProgressAt;
    private static int pollCount;

    private AvrcpVolumeRootCommand() {}

    public static void main(String[] args) {
        startedAt = SystemClock.elapsedRealtime();
        try {
            if (args.length > 0 && args[args.length - 1].startsWith("--session=")) {
                commandId = args[args.length - 1].substring("--session=".length());
                if (!commandId.matches("[0-9-]{1,80}")) throw new IllegalArgumentException("Invalid command session");
                args = Arrays.copyOf(args, args.length - 1);
            }
            boolean waitForPlayback = args.length == 2 && "--wait".equals(args[1]);
            if (!waitForPlayback && args.length != 3) {
                throw new IllegalArgumentException(
                        "Expected MAC plus --wait, or MAC plus pulse and restore volumes");
            }

            String address = args[0];
            int pulseVolume = waitForPlayback ? 0 : parseVolume(args[1]);
            int restoreVolume = waitForPlayback ? 0 : parseVolume(args[2]);
            trace("start", "mode=" + (waitForPlayback ? "wait" : "direct") +
                    " ppid=" + android.system.Os.getppid());

            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }
            Context context = getSystemContext();
            initializeBluetoothFramework();
            BluetoothManager bluetoothManager = context.getSystemService(BluetoothManager.class);
            adapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
            if (adapter == null) {
                throw new IllegalStateException("Bluetooth adapter is unavailable");
            }
            BluetoothDevice target = adapter.getRemoteDevice(address);

            BluetoothProfile.ServiceListener listener =
                    new BluetoothProfile.ServiceListener() {
                        @Override
                        public void onServiceConnected(int profileId, BluetoothProfile service) {
                            if (profileId != BluetoothProfile.A2DP) {
                                finishWithError("Unexpected Bluetooth profile " + profileId);
                                return;
                            }
                            proxy = service;
                            trace("profile_connected", "profile=" + profileId);
                            try {
                                BluetoothA2dp a2dp = (BluetoothA2dp) service;
                                if (waitForPlayback) {
                                    System.out.println("ready=1");
                                    System.out.flush();
                                    trace("ready", "prewarmTimeoutMs=" + PREWARM_TIMEOUT_MS);
                                    watchOwner(a2dp, target, true);
                                } else {
                                    watchOwner(a2dp, target, false);
                                    sendVolume(a2dp, target, pulseVolume, restoreVolume);
                                }
                            } catch (Throwable error) {
                                finishWithError(describe(error));
                            }
                        }

                        @Override
                        public void onServiceDisconnected(int profileId) {
                            if (!finished) {
                                finishWithError("Bluetooth A2DP service disconnected");
                            }
                        }
                    };

            if (!adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)) {
                throw new IllegalStateException("Unable to obtain Bluetooth A2DP proxy");
            }
            Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(
                    () -> {
                        if (proxy == null) {
                            finishWithError("Bluetooth A2DP proxy timed out");
                        }
                    },
                    5_000L);
            if (waitForPlayback) {
                handler.postDelayed(
                        () -> finishWithError("Prewarmed AVRCP command timed out"),
                        PREWARM_TIMEOUT_MS);
            }
            Looper.loop();
        } catch (Throwable error) {
            if (!finished) {
                trace("exit", "status=1 reason=" + describe(error));
                System.out.println("error=" + describe(error));
            }
            closeProxy();
            System.exit(1);
        }
    }

    private static void watchOwner(BluetoothA2dp a2dp, BluetoothDevice target, boolean expectsTrigger) {
        Thread ownerThread = new Thread(() -> {
            try {
                RootCommandInput.readUntilClosed(
                        new BufferedReader(new InputStreamReader(System.in)), expectsTrigger,
                        (pulse, restore) -> {
                            waitStartedAt = SystemClock.elapsedRealtime();
                            trace("trigger", "timeoutMs=" + PLAYBACK_TIMEOUT_MS + " pollIntervalMs=" + PLAYBACK_POLL_INTERVAL_MS);
                            long deadline = SystemClock.elapsedRealtime() + PLAYBACK_TIMEOUT_MS;
                            new Handler(Looper.getMainLooper()).post(
                                    () -> waitForTargetPlayback(a2dp, target, pulse, restore, deadline));
                        });
                finishWithError("AVRCP owner disconnected");
            } catch (Throwable error) {
                finishWithError(describe(error));
            }
        }, "librepods-avrcp-owner");
        ownerThread.setDaemon(true);
        ownerThread.start();
    }

    private static void waitForTargetPlayback(
            BluetoothA2dp a2dp,
            BluetoothDevice target,
            int pulseVolume,
            int restoreVolume,
            long deadline
    ) {
        if (finished) return;
        if (SystemClock.elapsedRealtime() >= deadline) {
            finishWithError("AirPods playback wait timed out");
            return;
        }
        try {
            pollCount++;
            boolean connected = a2dp.getConnectionState(target)
                    == BluetoothProfile.STATE_CONNECTED;
            if (!connected) {
                finishWithError("Target AirPods disconnected");
                return;
            }
            boolean playing = (Boolean) invoke(
                    a2dp,
                    "isA2dpPlaying",
                    new Class<?>[] {BluetoothDevice.class},
                    target);
            long now = SystemClock.elapsedRealtime();
            if (now - lastProgressAt >= 1_000L) {
                lastProgressAt = now;
                trace("playback_wait", "polls=" + pollCount + " waitMs=" + (now - waitStartedAt) +
                        " connected=" + connected + " playing=" + playing);
            }
            if (playing) {
                System.out.println("playback=1");
                System.out.flush();
                sendVolume(a2dp, target, pulseVolume, restoreVolume);
                return;
            }
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> waitForTargetPlayback(
                            a2dp, target, pulseVolume, restoreVolume, deadline),
                    PLAYBACK_POLL_INTERVAL_MS);
        } catch (Throwable error) {
            finishWithError(describe(error));
        }
    }

    private static void sendVolume(
            BluetoothA2dp a2dp,
            BluetoothDevice target,
            int pulseVolume,
            int restoreVolume
    ) throws Exception {
        if (a2dp.getConnectionState(target) != BluetoothProfile.STATE_CONNECTED ||
                !(Boolean) invoke(
                        a2dp,
                        "isA2dpPlaying",
                        new Class<?>[] {BluetoothDevice.class},
                        target)) {
            finishWithError("Target AirPods are not playing on this phone");
            return;
        }

        boolean absoluteVolumeSupported = (Boolean) invoke(
                a2dp,
                "isAvrcpAbsoluteVolumeSupported",
                new Class<?>[0]);
        // HyperOS reports false here while AvrcpVolumeManager still accepts
        // these commands, so retain the value for diagnostics only.
        invoke(
                a2dp,
                "setAvrcpAbsoluteVolume",
                new Class<?>[] {int.class},
                pulseVolume);
        invoke(
                a2dp,
                "setAvrcpAbsoluteVolume",
                new Class<?>[] {int.class},
                restoreVolume);
        System.out.println(
                "avrcp=" + absoluteVolumeSupported +
                        ",sent=" + pulseVolume +
                        ",restored=" + restoreVolume);
        finishSuccessfully();
    }

    private static int parseVolume(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0 || parsed > 255) {
            throw new IllegalArgumentException("Device volumes must be between 0 and 255");
        }
        return parsed;
    }

    private static Context getSystemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }

    private static void initializeBluetoothFramework() throws Exception {
        Class<?> initializer = Class.forName(
                "android.bluetooth.BluetoothFrameworkInitializer");
        Method getManager = initializer.getDeclaredMethod("getBluetoothServiceManager");
        getManager.setAccessible(true);
        if (getManager.invoke(null) != null) return;

        Class<?> managerClass = Class.forName("android.os.BluetoothServiceManager");
        Object manager = managerClass.getDeclaredConstructor().newInstance();
        Method setManager = initializer.getDeclaredMethod(
                "setBluetoothServiceManager", managerClass);
        setManager.setAccessible(true);
        setManager.invoke(null, manager);
    }

    private static Object invoke(
            Object target,
            String name,
            Class<?>[] parameterTypes,
            Object... args
    ) throws Exception {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw error;
        }
    }

    private static synchronized void finishSuccessfully() {
        if (finished) return;
        finished = true;
        trace("exit", "status=0 reason=volume_sent polls=" + pollCount);
        System.out.flush();
        closeProxy();
        System.exit(0);
    }

    private static synchronized void finishWithError(String message) {
        if (finished) return;
        finished = true;
        trace("exit", "status=1 reason=" + message + " polls=" + pollCount);
        System.out.println("error=" + message);
        System.out.flush();
        closeProxy();
        System.exit(1);
    }

    private static void closeProxy() {
        if (adapter != null && proxy != null) {
            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
            proxy = null;
        }
    }

    private static void trace(String event, String details) {
        String line = "entry=" + AvrcpVolumeRootCommand.class.getName() +
                " commandId=" + commandId + " pid=" + android.os.Process.myPid() +
                " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt) +
                " event=" + event + " " + details.replace('\n', ' ').replace('\r', ' ');
        // Logcat survives the owning app; stdout lets the app journal mirror live events.
        Log.i("RootAvrcpTrace", line);
        System.out.println("ROOT_AVRCP " + line);
        System.out.flush();
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null
                ? error.getCause() : error;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() +
                (message == null || message.isEmpty() ? "" : ":" + message);
    }
}
