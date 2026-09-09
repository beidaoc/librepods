/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 */

package me.kavishdevar.librepods.milink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.widget.ImageView
import android.widget.TextView
import android.view.View
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CompletableFuture
import java.util.WeakHashMap

/**
 * MiLink native and legacy headset bridge. MiLink reads state from LibrePods and ANC clicks
 * are routed back to the existing LibrePods AACP session.
 */
object MiLinkAirPodsHook {
    private const val TAG = "LibrePodsMiLink"
    // Mirror hook diagnostics into the host UID's logcat stream for in-app exports.
    private fun XposedModule.logDiagnostic(
        priority: Int,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        Log.println(priority, tag, message + (throwable?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
        if (throwable == null) log(priority, tag, message)
        else log(priority, tag, message, throwable)
    }

    private const val STRATEGY_CLASS =
        "com.miui.headset.runtime.model.AirPodsHeadsetStrategy"
    private const val LOCAL_PREFS = "librepods_airpods_bridge"
    private const val FALLBACK_DEVICE_ID = "01010607"

    @Volatile
    private var state = BridgeState()
    @Volatile private var liveStateReceived = false
    private var detailUpdateMethod: Method? = null
    private var context: Context? = null
    private var receiverRegistered = false
    private var lastStrategy: Any? = null
    private var lastModel: Any? = null
    private var lastAncBatteryController: Any? = null
    private var lastProfileContext: Any? = null
    private var lastDevice: BluetoothDevice? = null
    private var capabilityOverrideLogged = false
    private val presentationLoggedSlots = mutableSetOf<AncItemSlot>()
    private val pendingAncSelections = WeakHashMap<View, PendingAncSelection>()
    private val optimisticSelectionCall = ThreadLocal<Boolean>()
    private val moreSettingsRedirectCall = ThreadLocal<Boolean>()
    private var legacyAncHook: MiLinkLegacyAncHook? = null

    fun install(module: XposedModule, param: PackageLoadedParam) {
        if (!param.isFirstPackage ||
            param.packageName != MiLinkAirPodsBridgeContract.MI_LINK_PACKAGE
        ) {
            return
        }

        val strategyClass = findClass(param.defaultClassLoader, STRATEGY_CLASS)
        MiLinkHookResolver(param.defaultClassLoader) {
            module.logDiagnostic(Log.INFO, TAG, it)
        }.use { resolver ->
            detailUpdateMethod = resolver.anchored(
                "com.miui.circulateplus.world.headset", "updateMode: ", "void", "int",
            )
            if (strategyClass == null) {
                legacyAncHook = MiLinkLegacyAncHook.resolve(param.defaultClassLoader, detailUpdateMethod)
            }
            hookBatteryRefresh(module, resolver)
        }
        if (strategyClass == null && legacyAncHook == null) {
            module.logDiagnostic(Log.WARN, TAG, "MiLink ANC UI unavailable: no native strategy or verified legacy layout; independent runtime/battery hooks remain enabled")
        }
        module.logDiagnostic(
            Log.INFO, TAG,
            "MiLink bridge profile=${if (strategyClass != null) "native_strategy" else "legacy_feature_resolved"}",
        )

        hookContextEntry(module, param.defaultClassLoader)
        hookMxBluetoothCapabilities(module, param.defaultClassLoader)
        hookRuntimeDisplay(module, param.defaultClassLoader)
        hookMoreSettingsRedirect(module, param.defaultClassLoader)

        if (strategyClass == null) {
            hookLegacyContextEntry(module, param.defaultClassLoader)
            legacyAncHook?.install(
                module,
                initialize = { registerReceiver(module, it) },
                isTarget = { state.connected && isTargetDetail(it) },
                reportedMode = { state.ancMode },
                sendCommand = { sendAncCommand(null, it) },
                setIcon = ::setLibrePodsModeIcon,
                log = { module.logDiagnostic(Log.INFO, TAG, it) },
            )
            module.logDiagnostic(Log.INFO, TAG, "Legacy AirPods MiLink bridge hooks installed")
            return
        }

        hookAncItemPresentation(module, param.defaultClassLoader)
        hookAncOptimisticSelection(module, param.defaultClassLoader)
        hookDetailAncMode(module, param.defaultClassLoader)

        strategyClass.declaredConstructors
            .firstOrNull { constructor ->
                constructor.parameterTypes.firstOrNull() == Context::class.java
            }
            ?.also { constructor ->
                constructor.isAccessible = true
                module.hook(constructor).intercept { chain ->
                    chain.proceed().also { strategy ->
                        lastStrategy = strategy
                        registerReceiver(module, chain.args.firstOrNull() as? Context)
                    }
                }
            }

        hookAirPodsDetection(module, strategyClass)
        hookIntGetter(module, strategyClass, "getAncState") {
            MiLinkAncModeMapper.toMiLink(state.ancMode)
        }
        hookListGetter(module, strategyClass, "getBatteryCache") { state.batteryList() }
        hookListGetter(module, strategyClass, "getBatteryLevelCache") { state.batteryList() }
        hookIntGetter(module, strategyClass, "getHeadsetPropertyBlock") {
            state.minimumConnectedBudBattery()
        }
        hookBooleanGetter(module, strategyClass, "isConnected") { state.connected }
        hookCreateModel(module, strategyClass)
        hookSetAnc(module, strategyClass)

        module.logDiagnostic(Log.INFO, TAG, "Native AirPods MiLink bridge hooks installed")
    }

    private fun hookBatteryRefresh(module: XposedModule, resolver: MiLinkHookResolver) {
        val battery = resolver.anchored(
            "com.miui.circulate.api.protocol.headset", "get bluetooth device battery:",
            "java.util.List", "com.miui.circulate.api.service.CirculateServiceInfo",
        ) ?: return
        val serviceId = runCatching { battery.parameterTypes[0].getField("deviceId") }
            .getOrNull()?.takeIf { it.type == String::class.java } ?: return
        module.hook(battery).intercept { chain ->
            val snapshot = state
            val address = runCatching { serviceId.get(chain.args[0]) as? String }.getOrNull()
            if (MiLinkBatteryPolicy.owns(liveStateReceived, snapshot.connected, snapshot.address, address)) {
                val values = snapshot.batteryList()
                module.logDiagnostic(Log.DEBUG, TAG, "Battery read bridge: power=$values")
                values
            } else chain.proceed()
        }
        // This query normally returns a status code; failures make its caller erase the cached
        // power and mode. For the live LibrePods device, fill those properties from AACP instead.
        val query = battery.declaringClass.declaredMethods.singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.returnType == Int::class.javaPrimitiveType &&
                it.parameterTypes.map { type -> type.name } == listOf(
                    "com.miui.circulate.api.service.CirculateDeviceInfo",
                    "com.miui.circulate.api.protocol.headset.HeadsetDeviceInfo",
                )
        }?.apply { isAccessible = true } ?: return logSkipped(module, "battery property refresh signature")
        val model = query.parameterTypes[1]
        val fields = runCatching {
            listOf(model.getField("mac"), model.getField("power"), model.getField("mode")).also {
                check(it.map { field -> field.type } == listOf(String::class.java, List::class.java, Int::class.javaPrimitiveType))
            }
        }.getOrNull() ?: return logSkipped(module, "battery property fields")
        module.hook(query).intercept { chain ->
            val info = chain.args[1]
            val snapshot = state
            val address = runCatching { fields[0].get(info) as? String }.getOrNull()
            if (!MiLinkBatteryPolicy.owns(liveStateReceived, snapshot.connected, snapshot.address, address)) {
                return@intercept chain.proceed()
            }
            val filled = runCatching {
                fields[1].set(info, snapshot.batteryList())
                fields[2].setInt(info, MiLinkAncModeMapper.toDetailPresenterMode(snapshot.ancMode))
            }.isSuccess
            if (filled) {
                module.logDiagnostic(Log.DEBUG, TAG, "Battery property refresh bridge: power=${snapshot.batteryList()}, anc=${snapshot.ancMode}")
                100
            } else chain.proceed()
        }
        module.logDiagnostic(Log.INFO, TAG, "Battery refresh hooks installed: read=$battery query=$query")
    }

    private fun hookLegacyContextEntry(module: XposedModule, loader: ClassLoader) {
        val owner = findClass(loader, "com.miui.headset.runtime.AncBatteryController") ?: return
        owner.declaredConstructors.filter {
            it.parameterTypes.firstOrNull() == Context::class.java
        }.forEach { constructor ->
            module.hook(constructor).intercept { chain ->
                registerReceiver(module, chain.args.firstOrNull() as? Context)
                chain.proceed().also { rememberRuntime(it, null) }
            }
        }
    }

    private fun hookContextEntry(module: XposedModule, classLoader: ClassLoader) {
        listOf(
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager",
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService",
        ).forEach { className ->
            val owner = findClass(classLoader, className) ?: return@forEach
            owner.declaredMethods.firstOrNull {
                it.name == "getInstanceForIsMiTWS" &&
                    it.parameterTypes.contentEquals(arrayOf(Context::class.java))
            }?.apply { isAccessible = true }?.let { method ->
                module.hook(method).intercept { chain ->
                    registerReceiver(module, chain.args.firstOrNull() as? Context)
                    chain.proceed()
                }
            }
        }
    }

    private fun hookMxBluetoothCapabilities(module: XposedModule, classLoader: ClassLoader) {
        listOf(
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager",
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService",
        ).forEach { className ->
            val owner = findClass(classLoader, className) ?: return@forEach
            hookDeviceResult(module, owner, "checkIsMiTWS") { 1 }
            hookDeviceResult(module, owner, "getDeviceId") { FALLBACK_DEVICE_ID }
            hookDeviceResult(module, owner, "getBatteryLevel") { 1 }
            hookDeviceResult(module, owner, "getAncState") {
                MiLinkAncModeMapper.toMiLink(state.ancMode)
            }
            hookDeviceResult(module, owner, "getSpatialMode") {
                miLinkSpatialMode()
            }
            hookDeviceResult(module, owner, "getDeviceRunInfo") { 0 }
            hookDeviceResult(module, owner, "getWearStatus") { "0,0" }
            hookDeviceResult(module, owner, "isLeAudio") { false }

            hookStringResult(module, owner, "isMiTWS") { true }
            hookStringResult(module, owner, "isSupportAudioSwitch") {
                miLinkSpatialSwitchState()
            }
            hookStringResult(module, owner, "getRingFindState") { false }

            hookAncCommand(module, owner, "openAnc", MiLinkAncModeMapper.LIBREPODS_ADAPTIVE, 1)
            hookAncCommand(module, owner, "closeAnc", MiLinkAncModeMapper.LIBREPODS_NOISE_CANCELLATION, 0)
            hookAncCommand(module, owner, "openTransparent", MiLinkAncModeMapper.LIBREPODS_TRANSPARENCY, 2)
            hookMxSpatialCommand(module, owner)
        }
    }

    /**
     * HyperOS exposes only three fixed ANC item views. Reuse their visual slots without changing
     * the host layout: Transparency (left), Adaptive (middle), Noise Cancellation (right).
     */
    private fun hookAncItemPresentation(module: XposedModule, classLoader: ClassLoader) {
        val owner = findClass(
            classLoader,
            "com.miui.circulate.world.headset.ui.HeadsetSelectCardView",
        ) ?: return

        owner.declaredMethods.firstOrNull { method ->
            method.returnType.name ==
                "com.miui.circulate.world.headset.ui.HeadsetSelectItemView" &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ),
                )
        }?.apply { isAccessible = true }?.let { method ->
            module.hook(method).intercept { chain ->
                val iconRes = chain.args[0] as? Int ?: 0
                val labelRes = chain.args[1] as? Int ?: 0
                chain.proceed().also { item ->
                    updateAncItemPresentation(module, item, iconRes, labelRes)
                }
            }
        }
    }

    private fun updateAncItemPresentation(
        module: XposedModule,
        item: Any?,
        originalIconRes: Int,
        originalLabelRes: Int,
    ) {
        val itemView = item as? View ?: return
        val resources = itemView.resources
        val packageName = itemView.context.packageName
        val titleId = resources.getIdentifier("tools_text", "id", packageName)
        val iconId = resources.getIdentifier("tools_icon", "id", packageName)
        val title = titleId.takeIf { it != 0 }
            ?.let { itemView.findViewById<TextView>(it) } ?: return
        val icon = iconId.takeIf { it != 0 }
            ?.let { itemView.findViewById<ImageView>(it) }
        val offStringId = resources.getIdentifier(
            "circulate_headset_control_anc_off",
            "string",
            packageName,
        )
        val noiseStringId = resources.getIdentifier(
            "circulate_headset_control_anc_noise_cancel",
            "string",
            packageName,
        )
        val transparencyStringId = resources.getIdentifier(
            "circulate_headset_control_anc_clear",
            "string",
            packageName,
        )
        val noiseIconId = resources.getIdentifier(
            "headset_noise_cancel_selector",
            "drawable",
            packageName,
        )
        val offIconId = resources.getIdentifier(
            "headset_anc_off_selector",
            "drawable",
            packageName,
        )
        val transparencyIconId = resources.getIdentifier(
            "headset_transparency_selector",
            "drawable",
            packageName,
        )
        val slot = when {
            originalLabelRes == transparencyStringId ||
                originalIconRes == transparencyIconId -> AncItemSlot.TRANSPARENCY
            originalLabelRes == noiseStringId || originalIconRes == noiseIconId ->
                AncItemSlot.ADAPTIVE
            originalLabelRes == offStringId || originalIconRes == offIconId ->
                AncItemSlot.NOISE_CANCELLATION
            else -> AncItemSlot.UNCHANGED
        }
        val moduleContext = runCatching {
            title.context.createPackageContext(
                MiLinkAirPodsBridgeContract.LIBREPODS_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY,
            )
        }.getOrNull()

        when (slot) {
            AncItemSlot.TRANSPARENCY -> {
                setLibrePodsModeIcon(
                    icon,
                    moduleContext,
                    me.kavishdevar.librepods.R.drawable.transparency,
                )
            }

            AncItemSlot.ADAPTIVE -> {
                title.text = moduleContext?.getString(
                    me.kavishdevar.librepods.R.string.adaptive,
                ) ?: "自适应"
                title.contentDescription = title.text
                setLibrePodsModeIcon(
                    icon,
                    moduleContext,
                    me.kavishdevar.librepods.R.drawable.adaptive,
                )
            }

            AncItemSlot.NOISE_CANCELLATION -> {
                if (noiseStringId != 0) title.setText(noiseStringId) else title.text = "降噪"
                title.contentDescription = title.text
                setLibrePodsModeIcon(
                    icon,
                    moduleContext,
                    me.kavishdevar.librepods.R.drawable.noise_cancellation,
                )
            }

            AncItemSlot.UNCHANGED -> Unit
        }

        if (slot != AncItemSlot.UNCHANGED &&
            synchronized(presentationLoggedSlots) { presentationLoggedSlots.add(slot) }
        ) {
            module.logDiagnostic(
                Log.INFO,
                TAG,
                "MiLink ANC item presentation changed: slot=$slot",
            )
        }
    }

    private fun setLibrePodsModeIcon(
        icon: ImageView?,
        moduleContext: Context?,
        drawableRes: Int,
    ) {
        if (icon == null || moduleContext == null) return
        runCatching {
            val base = moduleContext.getDrawable(drawableRes)
            val selected = base?.constantState?.newDrawable()?.mutate()?.apply {
                setTint(Color.rgb(52, 130, 255))
            }
            val unselected = base?.constantState?.newDrawable()?.mutate()?.apply {
                setTint(Color.WHITE)
            }
            if (selected != null && unselected != null) {
                icon.setImageDrawable(
                    StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_selected), selected)
                        addState(intArrayOf(), unselected)
                    },
                )
            }
        }
    }

    /**
     * MiLink normally waits for the headset status callback before moving the selected indicator.
     * The AirPods command has already been accepted at click time, so update this one ANC card
     * immediately and let the authoritative status callback correct it if the command fails.
     */
    private fun hookAncOptimisticSelection(
        module: XposedModule,
        classLoader: ClassLoader,
    ) {
        val itemClass = findClass(
            classLoader,
            "com.miui.circulate.world.headset.ui.HeadsetSelectItemView",
        ) ?: return
        val cardClass = findClass(
            classLoader,
            "com.miui.circulate.world.headset.ui.HeadsetSelectCardView",
        ) ?: return
        val selectMethod = cardClass.declaredMethods.firstOrNull { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType),
                )
        }?.apply { isAccessible = true } ?: return
        val performClick = View::class.java.getDeclaredMethod("performClick").apply {
            isAccessible = true
        }

        module.hook(selectMethod).intercept { chain ->
            val card = chain.thisObject as? View
            val requestedIndex = chain.args.firstOrNull() as? Int
            if (card == null || requestedIndex == null || !isAncSelectionCard(card) ||
                optimisticSelectionCall.get() == true
            ) {
                chain.proceed()
            } else {
                val now = SystemClock.uptimeMillis()
                val pending = synchronized(pendingAncSelections) {
                    pendingAncSelections[card]
                }
                if (pending == null || now >= pending.deadlineMillis) {
                    synchronized(pendingAncSelections) { pendingAncSelections.remove(card) }
                    chain.proceed()
                } else if (requestedIndex == pending.targetIndex) {
                    if (now - pending.createdAtMillis >= ANC_CONFIRMATION_MIN_DELAY_MS) {
                        synchronized(pendingAncSelections) { pendingAncSelections.remove(card) }
                        module.logDiagnostic(
                            Log.INFO,
                            TAG,
                            "MiLink ANC selection confirmed: index=$requestedIndex",
                        )
                    }
                    chain.proceed()
                } else {
                    pending.lastReportedIndex = requestedIndex
                    module.logDiagnostic(
                        Log.INFO,
                        TAG,
                        "MiLink ANC stale selection suppressed: requested=$requestedIndex, " +
                            "pending=${pending.targetIndex}",
                    )
                    null
                }
            }
        }

        module.hook(performClick).intercept { chain ->
            val item = chain.thisObject as? View
            val result = chain.proceed()
            if (result == true && item != null && itemClass.isInstance(item)) {
                val parent = item.parent as? View
                if (parent != null && cardClass.isInstance(parent) &&
                    isAncSelectionCard(parent)
                ) {
                    val index = (parent as? android.view.ViewGroup)?.indexOfChild(item) ?: -1
                    if (index >= 0) {
                        val now = SystemClock.uptimeMillis()
                        val pending = PendingAncSelection(
                            targetIndex = index,
                            createdAtMillis = now,
                            deadlineMillis = now + ANC_PENDING_TIMEOUT_MS,
                        )
                        synchronized(pendingAncSelections) {
                            pendingAncSelections[parent] = pending
                        }
                        runCatching {
                            optimisticSelectionCall.set(true)
                            selectMethod.invoke(parent, index)
                        }.also {
                            optimisticSelectionCall.remove()
                        }
                            .onSuccess {
                                module.logDiagnostic(
                                    Log.INFO,
                                    TAG,
                                    "MiLink ANC optimistic selection: index=$index",
                                )
                            }
                        parent.postDelayed(
                            {
                                val expired = synchronized(pendingAncSelections) {
                                    if (pendingAncSelections[parent] === pending) {
                                        pendingAncSelections.remove(parent)
                                    } else {
                                        null
                                    }
                                }
                                val fallbackIndex = expired?.lastReportedIndex ?: -1
                                if (fallbackIndex >= 0 && fallbackIndex != index) {
                                    runCatching {
                                        optimisticSelectionCall.set(true)
                                        selectMethod.invoke(parent, fallbackIndex)
                                    }.also {
                                        optimisticSelectionCall.remove()
                                    }
                                    module.logDiagnostic(
                                        Log.WARN,
                                        TAG,
                                        "MiLink ANC selection timed out; " +
                                            "restored index=$fallbackIndex",
                                    )
                                }
                            },
                            ANC_PENDING_TIMEOUT_MS,
                        )
                    }
                }
            }
            result
        }
    }

    /** Redirect only MiLink's settings launch while preserving its normal detail-card lifecycle. */
    private fun hookMoreSettingsRedirect(module: XposedModule, classLoader: ClassLoader) {
        val owner = findClass(
            classLoader,
            "com.miui.circulateplus.world.headset.HeadSetsDetail",
        ) ?: return
        val controllerClass = findClass(
            classLoader,
            "com.miui.circulate.api.protocol.headset.HeadsetServiceController",
        ) ?: return
        val switchToHeadsetActivity = controllerClass.declaredMethods.firstOrNull { method ->
            method.name == "switchToHeadsetActivity" &&
                method.parameterTypes.size == 1 &&
                CompletableFuture::class.java.isAssignableFrom(method.returnType)
        }?.apply { isAccessible = true } ?: run {
            module.logDiagnostic(Log.WARN, TAG, "MiLink headset settings launch method unavailable")
            return
        }
        val callback = owner.declaredMethods.firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.isSynthetic &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(owner, View::class.java))
        }?.apply { isAccessible = true } ?: run {
            module.logDiagnostic(Log.WARN, TAG, "MiLink more settings callback unavailable")
            return
        }

        module.hook(switchToHeadsetActivity).intercept { chain ->
            if (moreSettingsRedirectCall.get() == true) {
                CompletableFuture.completedFuture<Any?>(null)
            } else {
                chain.proceed()
            }
        }

        module.hook(callback).intercept { chain ->
            val detail = chain.args.firstOrNull()
            val source = chain.args.getOrNull(1) as? View
            if (detail == null || source == null ||
                !isTargetMoreSettingsCallback(detail, source)
            ) {
                return@intercept chain.proceed()
            }

            val result = try {
                moreSettingsRedirectCall.set(true)
                chain.proceed()
            } finally {
                moreSettingsRedirectCall.remove()
            }
            launchLibrePodsHome(module, source)
            result
        }
        module.logDiagnostic(
            Log.INFO,
            TAG,
            "MiLink more settings callback hook installed: ${callback.name}",
        )
    }

    /**
     * MiLink's detail presenter receives both transient -2 values and stale valid values while the
     * card is rebuilt. LibrePods owns this headset's AACP state, so use it as the authoritative
     * idle display mode. While a click is awaiting confirmation, preserve MiLink's incoming mode:
     * the optimistic-selection hook needs it to distinguish the confirming callback from stale
     * callbacks without fighting this correction hook.
     */
    private fun hookDetailAncMode(module: XposedModule, classLoader: ClassLoader) {
        // Preserve the original native path if a host strips the semantic logging anchor.
        val updateMode = detailUpdateMethod ?: findClass(classLoader,
            "com.miui.circulateplus.world.headset.r")?.declaredMethods?.singleOrNull {
            it.name == "M" && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }?.apply { isAccessible = true } ?: return
        val detailField = updateMode.declaringClass.declaredFields.singleOrNull {
            it.type.name == "com.miui.circulateplus.world.headset.HeadSetsDetail"
        }?.apply { isAccessible = true } ?: return

        module.hook(updateMode).intercept { chain ->
            val reportedMode = chain.args.firstOrNull() as? Int
            val detail = detailField.get(chain.thisObject)
            val selectionPending = hasActiveAncSelection(chain.thisObject)
            val resolvedMode = runCatching {
                if (reportedMode != null && state.connected && isTargetDetail(detail) &&
                    !selectionPending
                ) {
                    MiLinkAncModeMapper.resolveDisplayMode(
                        reportedMode,
                        state.ancMode,
                    )
                } else {
                    reportedMode
                }
            }.onFailure { error ->
                module.logDiagnostic(Log.ERROR, TAG, "Unable to resolve initial MiLink ANC mode", error)
            }.getOrNull()

            if (reportedMode != null && resolvedMode != null && resolvedMode != reportedMode) {
                module.logDiagnostic(
                    Log.INFO,
                    TAG,
                    "Corrected MiLink ANC display mode: $reportedMode -> $resolvedMode",
                )
                chain.proceed(arrayOf(resolvedMode))
            } else {
                chain.proceed()
            }
        }
        module.logDiagnostic(Log.INFO, TAG, "MiLink detail ANC display mode hook installed")
    }

    private fun hasActiveAncSelection(modePresenter: Any?): Boolean {
        val cards = modePresenter?.javaClass?.declaredFields.orEmpty().mapNotNull { field ->
            if (!View::class.java.isAssignableFrom(field.type)) return@mapNotNull null
            runCatching { field.isAccessible = true; field.get(modePresenter) as? View }.getOrNull()
        }
        val card = cards.singleOrNull { isAncSelectionCard(it) } ?: return false
        val now = SystemClock.uptimeMillis()
        return synchronized(pendingAncSelections) {
            val pending = pendingAncSelections[card]
            if (pending != null && now >= pending.deadlineMillis) {
                pendingAncSelections.remove(card)
                false
            } else {
                pending != null
            }
        }
    }

    private fun isTargetDetail(detail: Any?): Boolean {
        if (detail == null) return false
        val serviceInfo = runCatching {
            findMethod(detail.javaClass, "getHeadsetInfo", 0)?.invoke(detail)
        }.getOrNull() ?: return false
        return isTargetAddress(readField(serviceInfo, "deviceId") as? String)
    }

    private fun isTargetMoreSettingsCallback(detail: Any, source: View): Boolean {
        if (source.id == View.NO_ID) return false
        val resourceName = runCatching {
            source.resources.getResourceEntryName(source.id)
        }.getOrNull()
        if (resourceName != "headset_more_settings") return false

        return isTargetDetail(detail)
    }

    private fun launchLibrePodsHome(module: XposedModule, source: View): Boolean {
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(
                MiLinkAirPodsBridgeContract.LIBREPODS_PACKAGE,
                "${MiLinkAirPodsBridgeContract.LIBREPODS_PACKAGE}.MainActivity",
            )
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        return runCatching {
            source.context.startActivity(launchIntent)
        }.onSuccess {
            module.logDiagnostic(Log.INFO, TAG, "MiLink more settings redirected to LibrePods home")
        }.onFailure { error ->
            module.logDiagnostic(Log.ERROR, TAG, "Unable to open LibrePods home", error)
        }.isSuccess
    }

    private fun isAncSelectionCard(view: View): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        return runCatching { view.resources.getResourceEntryName(id) == "anc_select_card" }
            .getOrDefault(false)
    }

    private data class PendingAncSelection(
        val targetIndex: Int,
        val createdAtMillis: Long,
        val deadlineMillis: Long,
        @Volatile var lastReportedIndex: Int = -1,
    )

    private const val ANC_CONFIRMATION_MIN_DELAY_MS = 150L
    private const val ANC_PENDING_TIMEOUT_MS = 2_500L

    private enum class AncItemSlot {
        TRANSPARENCY,
        ADAPTIVE,
        NOISE_CANCELLATION,
        UNCHANGED,
    }

    private fun hookRuntimeDisplay(module: XposedModule, classLoader: ClassLoader) {
        findClass(classLoader, "com.miui.headset.runtime.ProfileContext")?.let { owner ->
            hookDeviceResult(module, owner, "getDeviceId") { FALLBACK_DEVICE_ID }
            hookDeviceResult(module, owner, "getBatteryLevel") { state.batteryList() }
            hookDeviceResult(module, owner, "getAudioSpatialEffectState") {
                miLinkSpatialMode()
            }
            // Older ProfileContext passes the MAC address instead of BluetoothDevice.
            hookStringResult(module, owner, "getAudioSpatialEffectState") { miLinkSpatialMode() }
            hookProfileSetSpatialAudio(module, owner)
        }

        findClass(classLoader, "com.miui.headset.runtime.AncBatteryController")?.let { owner ->
            hookDeviceResult(module, owner, "getDeviceId") { FALLBACK_DEVICE_ID }
            hookDeviceResult(module, owner, "getAncState") {
                MiLinkAncModeMapper.toMiLink(state.ancMode)
            }
            hookDeviceResult(module, owner, "getBatteryLevelCache") { state.batteryList() }
            hookDeviceResult(module, owner, "getHeadsetPropertyBlock") {
                state.minimumConnectedBudBattery()
            }
            hookDeviceResult(module, owner, "getMiAudioEffect") {
                miLinkSpatialMode()
            }
            hookStringResult(module, owner, "getSwitchState") {
                miLinkSpatialSwitchState()
            }
            hookControllerSetAnc(module, owner)
            hookControllerSetSpatialAudio(module, owner)
            hookControllerSetHeadTracking(module, owner)
        }

        hookSpatialAudioModel(module, classLoader)
        hookSpatialAudioCallbacks(module, classLoader)

        findClass(classLoader, "com.miui.headset.api.HeadsetInfo")?.let { owner ->
            hookHeadsetInfoResult(module, owner, listOf("getDeviceId", "component3")) {
                FALLBACK_DEVICE_ID
            }
            hookHeadsetInfoResult(module, owner, listOf("getPowers", "component4")) {
                state.batteryList()
            }
            hookHeadsetInfoResult(module, owner, listOf("getMode", "component5")) {
                MiLinkAncModeMapper.toMiLink(state.ancMode)
            }
            hookHeadsetInfoResult(module, owner, listOf("getSwitchState", "component8")) {
                miLinkSpatialSwitchState()
            }
            hookHeadsetInfoResult(
                module,
                owner,
                listOf("getAudioEffectState", "component10"),
            ) { if (state.spatialAudioAvailable) state.spatialAudioMode else -1 }
        }
    }

    private fun hookDeviceResult(
        module: XposedModule,
        owner: Class<*>,
        methodName: String,
        result: () -> Any,
    ) {
        val method = findDeviceMethod(owner, methodName) ?: return
        module.hook(method).intercept { chain ->
            val device = chain.args.firstOrNull() as? BluetoothDevice
            if (!isTarget(device)) {
                chain.proceed()
            } else {
                rememberRuntime(chain.thisObject, device)
                logCapabilityOverride(module, device)
                result()
            }
        }
    }

    private fun hookStringResult(
        module: XposedModule,
        owner: Class<*>,
        methodName: String,
        result: () -> Any,
    ) {
        val method = owner.declaredMethods.firstOrNull {
            it.name == methodName && it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }?.apply { isAccessible = true } ?: return
        module.hook(method).intercept { chain ->
            val address = chain.args.firstOrNull() as? String
            if (!isTargetAddress(address)) chain.proceed() else result()
        }
    }

    private fun hookAncCommand(
        module: XposedModule,
        owner: Class<*>,
        methodName: String,
        librePodsMode: Int,
        result: Int,
    ) {
        val method = findDeviceMethod(owner, methodName) ?: return
        module.hook(method).intercept { chain ->
            val device = chain.args.firstOrNull() as? BluetoothDevice
            if (!isTarget(device)) {
                chain.proceed()
            } else {
                rememberRuntime(chain.thisObject, device)
                state = state.copy(ancMode = librePodsMode)
                syncRuntimeModels()
                sendAncCommand(device, librePodsMode)
                notifyRuntimeStateChanged(module, device, "Mx ANC command")
                result
            }
        }
    }

    private fun hookControllerSetAnc(module: XposedModule, owner: Class<*>) {
        val method = owner.declaredMethods.firstOrNull {
            it.name == "setAncStateBlock" &&
                it.parameterTypes.contentEquals(
                    arrayOf(BluetoothDevice::class.java, Int::class.javaPrimitiveType),
                )
        }?.apply { isAccessible = true } ?: return
        module.hook(method).intercept { chain ->
            val device = chain.args[0] as? BluetoothDevice
            if (!isTarget(device)) {
                chain.proceed()
            } else {
                val librePodsMode = MiLinkAncModeMapper.toLibrePods(
                    chain.args[1] as? Int ?: MiLinkAncModeMapper.MI_LINK_UNSELECTED,
                )
                rememberRuntime(chain.thisObject, device)
                state = state.copy(ancMode = librePodsMode)
                syncRuntimeModels()
                sendAncCommand(device, librePodsMode)
                notifyRuntimeStateChanged(module, device, "controller ANC command")
                MiLinkAncModeMapper.toMiLink(librePodsMode)
            }
        }
    }

    private fun hookMxSpatialCommand(module: XposedModule, owner: Class<*>) {
        val method = owner.declaredMethods.firstOrNull {
            it.name == "setSpatialMode" &&
                it.parameterTypes.contentEquals(
                    arrayOf(BluetoothDevice::class.java, Int::class.javaPrimitiveType),
                )
        }?.apply { isAccessible = true } ?: return
        module.hook(method).intercept { chain ->
            val device = chain.args[0] as? BluetoothDevice
            if (!isTarget(device)) {
                chain.proceed()
            } else {
                applySpatialAudioCommand(
                    module,
                    chain.thisObject,
                    device,
                    MiLinkSpatialAudioModeMapper.toLibrePods(chain.args[1] as? Int ?: 0),
                    "Mx spatial command",
                )
                1
            }
        }
    }

    private fun hookControllerSetSpatialAudio(module: XposedModule, owner: Class<*>) {
        val method = owner.declaredMethods.firstOrNull {
            it.name == "setMiAudioEffect" &&
                it.parameterTypes.contentEquals(
                    arrayOf(BluetoothDevice::class.java, Int::class.javaPrimitiveType),
                )
        }?.apply { isAccessible = true } ?: return
        module.hook(method).intercept { chain ->
            val device = chain.args[0] as? BluetoothDevice
            if (!isTarget(device)) {
                chain.proceed()
            } else {
                applySpatialAudioCommand(
                    module,
                    chain.thisObject,
                    device,
                    MiLinkSpatialAudioModeMapper.toLibrePods(chain.args[1] as? Int ?: 0),
                    "controller spatial command",
                )
                null
            }
        }
    }

    private fun hookControllerSetHeadTracking(module: XposedModule, owner: Class<*>) {
        val method = findDeviceMethod(owner, "setHeadTracking") ?: return
        module.hook(method).intercept { chain ->
            val device = chain.args.firstOrNull() as? BluetoothDevice
            if (!isTarget(device)) {
                chain.proceed()
            } else {
                applySpatialAudioCommand(
                    module,
                    chain.thisObject,
                    device,
                    MiLinkSpatialAudioModeMapper.LIBREPODS_HEAD_TRACKED,
                    "controller head tracking command",
                )
                100
            }
        }
    }

    private fun hookProfileSetSpatialAudio(module: XposedModule, owner: Class<*>) {
        owner.declaredMethods.singleOrNull {
            it.name == "setAudioEffectState" && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(String::class.java, Int::class.javaPrimitiveType))
        }?.apply { isAccessible = true }?.let { method ->
            module.hook(method).intercept { chain ->
                val address = chain.args[0] as? String
                if (!isTargetAddress(address)) return@intercept chain.proceed()
                val mode = chain.args[1] as? Int ?: return@intercept chain.proceed()
                if (mode !in MiLinkSpatialAudioModeMapper.LIBREPODS_OFF..MiLinkSpatialAudioModeMapper.LIBREPODS_HEAD_TRACKED) {
                    return@intercept chain.proceed()
                }
                applySpatialAudioCommand(module, chain.thisObject, null, mode, "profile address spatial command")
                null
            }
        }
        val method = owner.declaredMethods.firstOrNull {
            it.name == "setAudioEffectState" &&
                it.parameterTypes.contentEquals(
                    arrayOf(
                        BluetoothDevice::class.java,
                        String::class.java,
                        Int::class.javaPrimitiveType,
                    ),
                )
        }?.apply { isAccessible = true } ?: return
        module.hook(method).intercept { chain ->
            val device = chain.args[0] as? BluetoothDevice
            if (!isTarget(device)) {
                chain.proceed()
            } else {
                val mode = (chain.args[2] as? Int ?: 0).coerceIn(
                    MiLinkSpatialAudioModeMapper.LIBREPODS_OFF,
                    MiLinkSpatialAudioModeMapper.LIBREPODS_HEAD_TRACKED,
                )
                applySpatialAudioCommand(
                    module,
                    chain.thisObject,
                    device,
                    mode,
                    "profile spatial command",
                )
                null
            }
        }
    }

    private fun hookSpatialAudioModel(module: XposedModule, classLoader: ClassLoader) {
        val owner = findClass(
            classLoader,
            "com.miui.headset.runtime.AncBatteryModel",
        ) ?: return
        owner.declaredMethods.firstOrNull {
            it.name == "getDeviceSpatialType" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.let { method ->
            module.hook(method).intercept { chain ->
                if (!isTargetAncBatteryModel(chain.thisObject)) {
                    chain.proceed()
                } else {
                    miLinkDeviceSpatialType()
                }
            }
        }
        owner.declaredMethods.firstOrNull {
            it.name == "setDeviceSpatialType" &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }?.apply { isAccessible = true }?.let { method ->
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                if (isTargetAncBatteryModel(chain.thisObject)) {
                    writeField(
                        chain.thisObject,
                        "deviceSpatialType",
                        miLinkDeviceSpatialType(),
                    )
                }
                result
            }
        }
    }

    private fun hookSpatialAudioCallbacks(module: XposedModule, classLoader: ClassLoader) {
        val owner = findClass(
            classLoader,
            "com.miui.headset.runtime.AncBatteryController\$mmaCallback\$1",
        ) ?: return
        listOf("onDeviceSpatialType", "onReportSpatialState").forEach { methodName ->
            owner.declaredMethods.firstOrNull {
                it.name == methodName &&
                    it.parameterTypes.contentEquals(
                        arrayOf(BluetoothDevice::class.java, Int::class.javaPrimitiveType),
                    )
            }?.apply { isAccessible = true }?.let { method ->
                module.hook(method).intercept { chain ->
                    val device = chain.args[0] as? BluetoothDevice
                    if (!isTarget(device)) {
                        chain.proceed()
                    } else {
                        syncRuntimeModels()
                        notifyRuntimeStateChanged(module, device, "MiLink $methodName callback")
                        null
                    }
                }
            }
        }
    }

    private fun applySpatialAudioCommand(
        module: XposedModule,
        owner: Any?,
        device: BluetoothDevice?,
        librePodsMode: Int,
        reason: String,
    ) {
        rememberRuntime(owner, device)
        if (!state.spatialAudioAvailable) {
            module.logDiagnostic(Log.WARN, TAG, "Ignoring unavailable spatial command: reason=$reason")
            notifyRuntimeStateChanged(module, device, "$reason unavailable")
            return
        }
        state = state.copy(spatialAudioMode = librePodsMode)
        syncRuntimeModels()
        sendSpatialAudioCommand(device, librePodsMode)
        notifyRuntimeStateChanged(module, device, reason)
    }

    private fun hookHeadsetInfoResult(
        module: XposedModule,
        owner: Class<*>,
        methodNames: List<String>,
        result: () -> Any,
    ) {
        methodNames.forEach { methodName ->
            owner.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }?.let { method ->
                module.hook(method).intercept { chain ->
                    if (!isTargetHeadsetInfo(chain.thisObject)) chain.proceed() else result()
                }
            }
        }
    }

    private fun hookAirPodsDetection(module: XposedModule, strategyClass: Class<*>) {
        findDeviceMethod(strategyClass, "isAirpodsDevice")?.let { method ->
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                if (result == true) {
                    rememberRuntime(chain.thisObject, chain.args.firstOrNull() as? BluetoothDevice)
                }
                result
            }
        }
    }

    private fun hookIntGetter(
        module: XposedModule,
        strategyClass: Class<*>,
        name: String,
        value: () -> Int,
    ) {
        val method = findDeviceMethod(strategyClass, name) ?: return logSkipped(module, name)
        module.hook(method).intercept { chain ->
            val device = chain.args.firstOrNull() as? BluetoothDevice
            if (!isTarget(device)) {
                chain.proceed()
            } else {
                rememberRuntime(chain.thisObject, device)
                value()
            }
        }
    }

    private fun hookBooleanGetter(
        module: XposedModule,
        strategyClass: Class<*>,
        name: String,
        value: () -> Boolean,
    ) {
        val method = findDeviceMethod(strategyClass, name) ?: return logSkipped(module, name)
        module.hook(method).intercept { chain ->
            val device = chain.args.firstOrNull() as? BluetoothDevice
            if (!isTarget(device)) {
                chain.proceed()
            } else {
                rememberRuntime(chain.thisObject, device)
                value()
            }
        }
    }

    private fun hookListGetter(
        module: XposedModule,
        strategyClass: Class<*>,
        name: String,
        value: () -> List<Int>,
    ) {
        val method = findDeviceMethod(strategyClass, name) ?: return logSkipped(module, name)
        module.hook(method).intercept { chain ->
            val device = chain.args.firstOrNull() as? BluetoothDevice
            if (!isTarget(device)) {
                chain.proceed()
            } else {
                rememberRuntime(chain.thisObject, device)
                value()
            }
        }
    }

    private fun hookCreateModel(module: XposedModule, strategyClass: Class<*>) {
        val method = findDeviceMethod(strategyClass, "createModel")
            ?: return logSkipped(module, "createModel")
        module.hook(method).intercept { chain ->
            val model = chain.proceed()
            val device = chain.args.firstOrNull() as? BluetoothDevice
            if (isTarget(device)) {
                rememberRuntime(chain.thisObject, device)
                lastModel = model
                syncModel(model)
            }
            model
        }
    }

    private fun hookSetAnc(module: XposedModule, strategyClass: Class<*>) {
        val method = strategyClass.declaredMethods.firstOrNull {
            it.name == "setAncStateBlock" &&
                it.parameterTypes.contentEquals(
                    arrayOf(BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                )
        }?.apply { isAccessible = true } ?: return logSkipped(module, "setAncStateBlock")

        module.hook(method).intercept { chain ->
            val device = chain.args[0] as? BluetoothDevice
            if (!isTarget(device)) {
                chain.proceed()
            } else {
                val miLinkMode = chain.args[1] as? Int ?: MiLinkAncModeMapper.MI_LINK_UNSELECTED
                val librePodsMode = MiLinkAncModeMapper.toLibrePods(miLinkMode)
                rememberRuntime(chain.thisObject, device)
                state = state.copy(ancMode = librePodsMode)
                syncModel(lastModel)
                sendAncCommand(device, librePodsMode)
                notifyPropertyChanged(chain.thisObject, device, 8)
                notifyPropertyChanged(chain.thisObject, device, 4)
                MiLinkAncModeMapper.toMiLink(librePodsMode)
            }
        }
    }

    private fun registerReceiver(module: XposedModule, sourceContext: Context?) {
        if (sourceContext == null || receiverRegistered) return
        val appContext = sourceContext.applicationContext ?: sourceContext
        context = appContext
        loadState(appContext)

        appContext.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != MiLinkAirPodsBridgeContract.ACTION_STATE_CHANGED ||
                        intent.getIntExtra(
                            MiLinkAirPodsBridgeContract.EXTRA_PROTOCOL_VERSION,
                            -1,
                        ) != MiLinkAirPodsBridgeContract.PROTOCOL_VERSION
                    ) {
                        return
                    }
                    val newState = BridgeState.fromIntent(intent) ?: return
                    state = newState
                    liveStateReceived = true
                    context?.let { saveState(it, newState) }
                    syncRuntimeModels()
                    notifyRuntimeStateChanged(module, lastDevice, "AirPods state update")
                    legacyAncHook?.onStateChanged(
                        newState.ancMode,
                        intent.getStringExtra(MiLinkAirPodsBridgeContract.EXTRA_STATE_REASON) ==
                            "ANC status changed",
                    )
                    module.logDiagnostic(
                        Log.INFO,
                        TAG,
                        "State updated: connected=${newState.connected}, anc=${newState.ancMode}, " +
                            "spatial=${newState.spatialAudioMode}, power=${newState.batteryList()}, " +
                            "reason=${intent.getStringExtra(MiLinkAirPodsBridgeContract.EXTRA_STATE_REASON)}",
                    )
                }
            },
            IntentFilter(MiLinkAirPodsBridgeContract.ACTION_STATE_CHANGED),
            Context.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
        if (legacyAncHook != null) {
            val version = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
            }.getOrNull()
            module.logDiagnostic(Log.INFO, TAG, "Legacy bridge receiver registered: hostVersion=$version")
        }
        requestState(appContext)
    }

    private fun requestState(context: Context) {
        context.sendBroadcast(
            Intent(MiLinkAirPodsBridgeContract.ACTION_REQUEST_STATE).apply {
                setPackage(MiLinkAirPodsBridgeContract.LIBREPODS_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            },
        )
    }

    private fun sendAncCommand(device: BluetoothDevice?, mode: Int): Boolean {
        val currentContext = context
        val token = state.token
        if (currentContext == null || token.isBlank() || !state.connected ||
            !MiLinkAncModeMapper.isSelectableLibrePodsMode(mode)
        ) {
            Log.w(TAG, "ANC broadcast not sent: context=${currentContext != null}, tokenAvailable=${token.isNotBlank()}, connected=${state.connected}, mode=$mode")
            return false
        }
        return runCatching { currentContext.sendBroadcast(
            Intent(MiLinkAirPodsBridgeContract.ACTION_SET_ANC).apply {
                setPackage(MiLinkAirPodsBridgeContract.LIBREPODS_PACKAGE)
                putExtra(MiLinkAirPodsBridgeContract.EXTRA_PROTOCOL_VERSION, 1)
                putExtra(MiLinkAirPodsBridgeContract.EXTRA_TOKEN, token)
                putExtra(
                    MiLinkAirPodsBridgeContract.EXTRA_ADDRESS,
                    runCatching { device?.address }.getOrNull() ?: state.address,
                )
                putExtra(MiLinkAirPodsBridgeContract.EXTRA_ANC_MODE, mode)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            },
        ) }.onSuccess {
            Log.i(TAG, "ANC broadcast sent: mode=$mode, profile=${if (legacyAncHook != null) "legacy_feature_resolved" else "native_strategy"}")
        }.onFailure {
            Log.e(TAG, "ANC broadcast failed: mode=$mode", it)
        }.isSuccess
    }

    private fun sendSpatialAudioCommand(device: BluetoothDevice?, mode: Int) {
        val currentContext = context ?: return
        val token = state.token.takeIf { it.isNotBlank() } ?: return
        currentContext.sendBroadcast(
            Intent(MiLinkAirPodsBridgeContract.ACTION_SET_SPATIAL_AUDIO).apply {
                setPackage(MiLinkAirPodsBridgeContract.LIBREPODS_PACKAGE)
                putExtra(
                    MiLinkAirPodsBridgeContract.EXTRA_PROTOCOL_VERSION,
                    MiLinkAirPodsBridgeContract.PROTOCOL_VERSION,
                )
                putExtra(MiLinkAirPodsBridgeContract.EXTRA_TOKEN, token)
                putExtra(
                    MiLinkAirPodsBridgeContract.EXTRA_ADDRESS,
                    runCatching { device?.address }.getOrNull() ?: state.address,
                )
                putExtra(MiLinkAirPodsBridgeContract.EXTRA_SPATIAL_AUDIO_MODE, mode)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            },
        )
    }

    private fun rememberRuntime(strategy: Any?, device: BluetoothDevice?) {
        when (strategy?.javaClass?.name) {
            STRATEGY_CLASS -> lastStrategy = strategy
            "com.miui.headset.runtime.AncBatteryController" ->
                lastAncBatteryController = strategy
            "com.miui.headset.runtime.ProfileContext" -> lastProfileContext = strategy
        }
        if (device != null) lastDevice = device
        if (lastModel == null && strategy?.javaClass?.name == STRATEGY_CLASS) {
            lastModel = readField(strategy, "model")
        }
    }

    private fun syncRuntimeModels() {
        syncModel(lastModel)
        listOf(lastAncBatteryController, lastProfileContext).forEach { owner ->
            val model = readField(owner, "ancBatteryModel") ?: return@forEach
            if (legacyAncHook != null && !isTargetAncBatteryModel(model)) return@forEach
            callMethod(model, "setBattery", state.batteryList())
            callMethod(model, "setAncState", MiLinkAncModeMapper.toMiLink(state.ancMode))
            syncSpatialModel(model)
        }
    }

    private fun syncModel(model: Any?) {
        if (model == null) return
        callMethod(model, "setBattery", state.batteryList())
        callMethod(model, "setAncState", MiLinkAncModeMapper.toMiLink(state.ancMode))
        syncSpatialModel(model)
    }

    private fun syncSpatialModel(model: Any?) {
        if (model == null) return
        val miLinkMode = miLinkSpatialMode()
        callMethod(model, "setSpatialState", miLinkMode)
        callMethod(
            model,
            "setDeviceSpatialType",
            miLinkDeviceSpatialType(),
        )
        writeField(model, "spatialState", miLinkMode)
        writeField(
            model,
            "deviceSpatialType",
            miLinkDeviceSpatialType(),
        )
    }

    private fun miLinkSpatialMode(): Int =
        if (state.spatialAudioAvailable) {
            MiLinkSpatialAudioModeMapper.toMiLink(state.spatialAudioMode)
        } else {
            -1
        }

    private fun miLinkSpatialSwitchState(): Int =
        if (state.spatialAudioAvailable) 1 else 0

    private fun miLinkDeviceSpatialType(): Int =
        if (state.spatialAudioAvailable) {
            MiLinkSpatialAudioModeMapper.DEVICE_SPATIAL_TYPE_XIAOMI
        } else {
            0
        }

    private fun notifyPropertyChanged(strategy: Any?, device: BluetoothDevice?, type: Int) {
        if (strategy == null || device == null) return
        findMethod(strategy.javaClass, "notifyPropertyChanged", 3)?.let { method ->
            runCatching { method.invoke(strategy, device, type, 0L) }
        }
    }

    private fun notifyRuntimeStateChanged(
        module: XposedModule,
        device: BluetoothDevice?,
        reason: String,
    ) {
        if (device == null) return
        notifyPropertyChanged(lastStrategy, device, 4)
        notifyPropertyChanged(lastStrategy, device, 8)
        notifyPropertyChanged(lastStrategy, device, 9)

        var listenerCount = 0
        listOf(lastAncBatteryController, lastProfileContext).distinct().forEach { owner ->
            val listener = readField(owner, "headsetPropertyChangeListener") ?: return@forEach
            val invoke = findMethod(listener.javaClass, "invoke", 2) ?: return@forEach
            runCatching {
                invoke.invoke(listener, device, 8)
                invoke.invoke(listener, device, 4)
                invoke.invoke(listener, device, 9)
                listenerCount += 1
            }
        }
        listOf(lastAncBatteryController, lastProfileContext).distinct().forEach { owner ->
            val listener = readField(owner, "audioEffectListener") ?: return@forEach
            val invoke = findMethod(listener.javaClass, "invoke", 1) ?: return@forEach
            runCatching { invoke.invoke(listener, state.spatialAudioMode) }
        }
        module.logDiagnostic(
            Log.INFO,
            TAG,
            "MiLink UI refresh: reason=$reason, listeners=$listenerCount, " +
                "anc=${state.ancMode}, spatial=${state.spatialAudioMode}",
        )
    }

    @SuppressLint("MissingPermission")
    private fun isTarget(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        if (legacyAncHook != null && !state.connected) return false
        val address = runCatching { device.address }.getOrNull()
        if (state.address.isNotBlank() && address.equals(state.address, ignoreCase = true)) {
            return true
        }
        if (state.address.isNotBlank()) return false
        val name = runCatching { device.name ?: device.alias }.getOrNull().orEmpty()
        return name.contains("AirPods", ignoreCase = true) || name.contains("苹果", ignoreCase = true)
    }

    private fun isTargetAddress(address: String?): Boolean =
        (legacyAncHook == null || state.connected) &&
        !address.isNullOrBlank() &&
            state.address.isNotBlank() &&
            address.equals(state.address, ignoreCase = true)

    private fun isTargetHeadsetInfo(info: Any?): Boolean {
        if (info == null) return false
        listOf("getAddress", "component1").forEach { methodName ->
            val address = runCatching {
                findMethod(info.javaClass, methodName, 0)?.invoke(info) as? String
            }.getOrNull()
            if (isTargetAddress(address)) return true
        }
        return false
    }

    private fun isTargetAncBatteryModel(model: Any?): Boolean {
        if (model == null) return false
        val device = runCatching {
            findMethod(model.javaClass, "getBluetoothDevice", 0)?.invoke(model) as? BluetoothDevice
        }.getOrNull()
        return isTarget(device)
    }

    private fun findClass(classLoader: ClassLoader, name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun logCapabilityOverride(module: XposedModule, device: BluetoothDevice?) {
        if (capabilityOverrideLogged) return
        capabilityOverrideLogged = true
        val suffix = runCatching { device?.address?.takeLast(5) }.getOrNull().orEmpty()
        module.logDiagnostic(Log.INFO, TAG, "MiLink capability override active for device=**:$suffix")
    }

    private fun findDeviceMethod(owner: Class<*>, name: String): Method? =
        owner.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(arrayOf(BluetoothDevice::class.java))
        }?.apply { isAccessible = true }

    private fun findMethod(owner: Class<*>, name: String, parameterCount: Int): Method? {
        var current: Class<*>? = owner
        while (current != null) {
            current.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == parameterCount
            }?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }

    private fun callMethod(instance: Any?, name: String, vararg args: Any?) {
        if (instance == null) return
        findMethod(instance.javaClass, name, args.size)?.let { method ->
            runCatching { method.invoke(instance, *args) }
        }
    }

    private fun readField(instance: Any?, name: String): Any? {
        if (instance == null) return null
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name).apply { isAccessible = true }.get(instance)
            }
            current = current.superclass
        }
        return null
    }

    private fun writeField(instance: Any?, name: String, value: Any?) {
        if (instance == null) return
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            val updated = runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
            }.isSuccess
            if (updated) return
            current = current.superclass
        }
    }

    private fun logSkipped(module: XposedModule, method: String) {
        module.logDiagnostic(Log.WARN, TAG, "Hook skipped: $STRATEGY_CLASS.$method")
    }

    private fun loadState(context: Context) {
        val prefs = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
        state = BridgeState(
            token = prefs.getString("token", "").orEmpty(),
            address = prefs.getString("address", "").orEmpty(),
            name = prefs.getString("name", "AirPods").orEmpty(),
            connected = prefs.getBoolean("connected", false),
            ancMode = prefs.getInt("anc_mode", MiLinkAncModeMapper.LIBREPODS_OFF),
            spatialAudioMode = prefs.getInt(
                "spatial_audio_mode",
                MiLinkSpatialAudioModeMapper.LIBREPODS_OFF,
            ),
            spatialAudioAvailable = prefs.getBoolean("spatial_audio_available", false),
            leftBattery = prefs.getInt("left_battery", -1),
            rightBattery = prefs.getInt("right_battery", -1),
            caseBattery = prefs.getInt("case_battery", -1),
            leftCharging = prefs.getBoolean("left_charging", false),
            rightCharging = prefs.getBoolean("right_charging", false),
            caseCharging = prefs.getBoolean("case_charging", false),
        )
    }

    private fun saveState(context: Context, value: BridgeState) {
        context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", value.token)
            .putString("address", value.address)
            .putString("name", value.name)
            .putBoolean("connected", value.connected)
            .putInt("anc_mode", value.ancMode)
            .putInt("spatial_audio_mode", value.spatialAudioMode)
            .putBoolean("spatial_audio_available", value.spatialAudioAvailable)
            .putInt("left_battery", value.leftBattery)
            .putInt("right_battery", value.rightBattery)
            .putInt("case_battery", value.caseBattery)
            .putBoolean("left_charging", value.leftCharging)
            .putBoolean("right_charging", value.rightCharging)
            .putBoolean("case_charging", value.caseCharging)
            .apply()
    }

    private data class BridgeState(
        val token: String = "",
        val address: String = "",
        val name: String = "AirPods",
        val connected: Boolean = false,
        val ancMode: Int = MiLinkAncModeMapper.LIBREPODS_OFF,
        val spatialAudioMode: Int = MiLinkSpatialAudioModeMapper.LIBREPODS_OFF,
        val spatialAudioAvailable: Boolean = false,
        val leftBattery: Int = -1,
        val rightBattery: Int = -1,
        val caseBattery: Int = -1,
        val leftCharging: Boolean = false,
        val rightCharging: Boolean = false,
        val caseCharging: Boolean = false,
    ) {
        fun batteryList(): List<Int> = listOf(
            caseBattery.normalizedBattery(),
            leftBattery.normalizedBattery(),
            rightBattery.normalizedBattery(),
            caseCharging.asInt(),
            leftCharging.asInt(),
            rightCharging.asInt(),
        )

        fun minimumConnectedBudBattery(): Int =
            listOf(leftBattery, rightBattery).filter { it >= 0 }.minOrNull() ?: 0

        companion object {
            fun fromIntent(intent: Intent): BridgeState? {
                val token = intent.getStringExtra(MiLinkAirPodsBridgeContract.EXTRA_TOKEN)
                    ?.takeIf { it.isNotBlank() } ?: return null
                val address = intent.getStringExtra(MiLinkAirPodsBridgeContract.EXTRA_ADDRESS)
                    ?.takeIf { it.isNotBlank() } ?: return null
                return BridgeState(
                    token = token,
                    address = address,
                    name = intent.getStringExtra(MiLinkAirPodsBridgeContract.EXTRA_NAME)
                        .orEmpty().ifBlank { "AirPods" },
                    connected = intent.getBooleanExtra(
                        MiLinkAirPodsBridgeContract.EXTRA_CONNECTED,
                        false,
                    ),
                    ancMode = intent.getIntExtra(
                        MiLinkAirPodsBridgeContract.EXTRA_ANC_MODE,
                        MiLinkAncModeMapper.LIBREPODS_OFF,
                    ),
                    spatialAudioMode = intent.getIntExtra(
                        MiLinkAirPodsBridgeContract.EXTRA_SPATIAL_AUDIO_MODE,
                        MiLinkSpatialAudioModeMapper.LIBREPODS_OFF,
                    ).coerceIn(
                        MiLinkSpatialAudioModeMapper.LIBREPODS_OFF,
                        MiLinkSpatialAudioModeMapper.LIBREPODS_HEAD_TRACKED,
                    ),
                    spatialAudioAvailable = intent.getBooleanExtra(
                        MiLinkAirPodsBridgeContract.EXTRA_SPATIAL_AUDIO_AVAILABLE,
                        false,
                    ),
                    leftBattery = intent.getIntExtra(
                        MiLinkAirPodsBridgeContract.EXTRA_LEFT_BATTERY,
                        -1,
                    ),
                    rightBattery = intent.getIntExtra(
                        MiLinkAirPodsBridgeContract.EXTRA_RIGHT_BATTERY,
                        -1,
                    ),
                    caseBattery = intent.getIntExtra(
                        MiLinkAirPodsBridgeContract.EXTRA_CASE_BATTERY,
                        -1,
                    ),
                    leftCharging = intent.getBooleanExtra(
                        MiLinkAirPodsBridgeContract.EXTRA_LEFT_CHARGING,
                        false,
                    ),
                    rightCharging = intent.getBooleanExtra(
                        MiLinkAirPodsBridgeContract.EXTRA_RIGHT_CHARGING,
                        false,
                    ),
                    caseCharging = intent.getBooleanExtra(
                        MiLinkAirPodsBridgeContract.EXTRA_CASE_CHARGING,
                        false,
                    ),
                )
            }
        }
    }

    private fun Int.normalizedBattery(): Int = if (this in 0..100) this else -1
    private fun Boolean.asInt(): Int = if (this) 1 else 0
}
