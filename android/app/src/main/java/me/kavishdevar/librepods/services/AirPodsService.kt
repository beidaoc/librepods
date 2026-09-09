/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.services

//import me.kavishdevar.librepods.utils.CrossDevice
//import me.kavishdevar.librepods.utils.CrossDevicePackets
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.MainActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType
import me.kavishdevar.librepods.bluetooth.AirPodsHeartRateSample
import me.kavishdevar.librepods.bluetooth.AttTransport
import me.kavishdevar.librepods.bluetooth.ATTManagerv2
import me.kavishdevar.librepods.bluetooth.BLEManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.bluetooth.HeartRateServiceResolution
import me.kavishdevar.librepods.bluetooth.createBluetoothSocket
import me.kavishdevar.librepods.data.AirPodsInstance
import me.kavishdevar.librepods.data.AirPodsModels
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.CustomEq
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.data.XposedRemotePrefProvider
import me.kavishdevar.librepods.data.isHeadTrackingData
import me.kavishdevar.librepods.keepbridge.KeepHeartRateBridge
import me.kavishdevar.librepods.milink.MiLinkAirPodsBridgeContract
import me.kavishdevar.librepods.milink.MiLinkAncModeMapper
import me.kavishdevar.librepods.milink.MiLinkSpatialAudioAvailability
import me.kavishdevar.librepods.milink.MiLinkSpatialAudioModeMapper
import me.kavishdevar.librepods.notifications.NotificationAnnouncementRoutePolicy
import me.kavishdevar.librepods.presentation.overlays.IslandType
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.presentation.overlays.PopupWindow
import me.kavishdevar.librepods.presentation.widgets.BatteryWidget
import me.kavishdevar.librepods.presentation.widgets.NoiseControlWidget
import me.kavishdevar.librepods.utils.GestureDetector
import me.kavishdevar.librepods.utils.HeadTracking
import me.kavishdevar.librepods.utils.MediaController
import me.kavishdevar.librepods.utils.RootHeadTrackerBridge
import me.kavishdevar.librepods.utils.RootAvrcpVolumeController
import me.kavishdevar.librepods.utils.RootSpatialAudioController
import me.kavishdevar.librepods.utils.SpatialAudioMode
import me.kavishdevar.librepods.utils.SystemApisUtils
import me.kavishdevar.librepods.utils.SystemApisUtils.DEVICE_TYPE_UNTETHERED_HEADSET
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_COMPANION_APP
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_DEVICE_TYPE
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MAIN_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MANUFACTURER_NAME
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MODEL_NAME
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "AirPodsService"
private const val A2DP_DIRECT_VOLUME_RESYNC_DELAY_MS = 350L
private const val A2DP_VOLUME_RESYNC_DEBOUNCE_MS = 1_000L
private const val A2DP_VOLUME_PULSE_MS = 180L

object ServiceManager {
    private var service: AirPodsService? = null

    @Synchronized
    fun getService(): AirPodsService? {
        return service
    }

    @Synchronized
    fun setService(service: AirPodsService?) {
        this.service = service
    }
}

// @Suppress("unused")
class AirPodsService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    var macAddress = ""
    var localMac = ""
    lateinit var aacpManager: AACPManager
    lateinit var attManager: ATTManagerv2
    var airpodsInstance: AirPodsInstance? = null
    var cameraActive = false
    private var disconnectedBecauseReversed = false
    private var otherDeviceTookOver = false
    private var lastAudioSourceMac: String? = null
    private var lastAudioSourceType: AACPManager.Companion.AudioSourceType? = null
    private val remoteStreamingDevices = ConcurrentHashMap.newKeySet<String>()
    private var lastAacpControlRefreshAt = 0L
    private var lastA2dpVolumeResyncAt = 0L
    private var a2dpVolumeResyncGeneration = 0
    private var earlyA2dpVolumeResyncInFlight = false
    private val spatialHeadTrackerBridge = RootHeadTrackerBridge()
    private val rootAvrcpVolumeController by lazy { RootAvrcpVolumeController(this) }
    private val spatialAudioController by lazy { RootSpatialAudioController(this) }
    private val audioFeatureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val heartRateProbeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val heartRateProbeLock = Any()
    private var heartRateProbeJob: Job? = null
    private var heartRateOwnershipTimeoutJob: Job? = null
    private var heartRateOwnershipSettleJob: Job? = null
    private var heartRateRemoteReleaseJob: Job? = null
    private val heartRateRemoteTakeoverGate = HeartRateRemoteTakeoverGate()
    private var pendingHeartRateStopAcknowledgement: CompletableDeferred<Unit>? = null
    private var heartRateProbeGeneration = 0L
    private var lastHeartRateOwnershipRequestAt = 0L
    @Volatile
    private var heartRateProbeRequested = false
    private val _heartRateProbeEnabled = MutableStateFlow(false)
    val heartRateProbeEnabled: StateFlow<Boolean> get() = _heartRateProbeEnabled
    private val _heartRateProbeStreaming = MutableStateFlow(false)
    val heartRateProbeStreaming: StateFlow<Boolean> get() = _heartRateProbeStreaming
    @Volatile
    private var lastHeartRateSampleElapsedRealtime = 0L
    private var heartRateWarmupRemaining = 0
    private val _heartRateSample = MutableStateFlow<AirPodsHeartRateSample?>(null)
    val heartRateSample: StateFlow<AirPodsHeartRateSample?> get() = _heartRateSample
    private var isAirPodsA2dpPlaying = false
    private var spatialAudioTransitionId = 0
    private val spatialAudioTransitionMutex = Mutex()
    @Volatile
    private var miLinkSpatialAudioCapabilityChecked = false
    @Volatile
    private var miLinkSpatialAudioAvailable = false
    private var miLinkSpatialAudioCapabilityJob: Job? = null

    data class ServiceConfig(
        var deviceName: String = "AirPods",
        var earDetectionEnabled: Boolean = true,
        var conversationalAwarenessPauseMusic: Boolean = false,
        var showPhoneBatteryInWidget: Boolean = true,
        var relativeConversationalAwarenessVolume: Boolean = true,
        var headGestures: Boolean = true,
        var disconnectWhenNotWearing: Boolean = false,
        var conversationalAwarenessVolume: Int = 43,
        var qsClickBehavior: String = "cycle",
        var bleOnlyMode: Boolean = false,

        // AirPods state-based takeover
        var takeoverWhenDisconnected: Boolean = true,
        var takeoverWhenIdle: Boolean = true,
        var takeoverWhenMusic: Boolean = false,
        var takeoverWhenCall: Boolean = true,

        // Phone state-based takeover
        var takeoverWhenRingingCall: Boolean = true,
        var takeoverWhenMediaStart: Boolean = true,

        var leftSinglePressAction: StemAction = StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!,
        var rightSinglePressAction: StemAction = StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!,

        var leftDoublePressAction: StemAction = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!,
        var rightDoublePressAction: StemAction = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!,

        var leftTriplePressAction: StemAction = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!,
        var rightTriplePressAction: StemAction = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!,

        var leftLongPressAction: StemAction = StemAction.defaultActions[StemPressType.LONG_PRESS]!!,
        var rightLongPressAction: StemAction = StemAction.defaultActions[StemPressType.LONG_PRESS]!!,

        var cameraAction: StemPressType? = null,

        // AirPods device information
        var airpodsName: String = "",
        var airpodsModelNumber: String = "",
        var airpodsManufacturer: String = "",
        var airpodsSerialNumber: String = "",
        var airpodsLeftSerialNumber: String = "",
        var airpodsRightSerialNumber: String = "",
        var airpodsVersion1: String = "",
        var airpodsVersion2: String = "",
        var airpodsVersion3: String = "",
        var airpodsHardwareRevision: String = "",
        var airpodsUpdaterIdentifier: String = "",

        // phone's mac, needed for tipi
        var selfMacAddress: String = ""
    )

    private lateinit var config: ServiceConfig

    inner class LocalBinder : Binder() {
        fun getService(): AirPodsService = this@AirPodsService
    }

    private lateinit var sharedPreferencesLogs: SharedPreferences
    private lateinit var sharedPreferences: SharedPreferences
    private val packetLogKey = "packet_log"
    private val _packetLogsFlow = MutableStateFlow<Set<String>>(emptySet())
    val packetLogsFlow: StateFlow<Set<String>> get() = _packetLogsFlow

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var phoneStateListener: TelephonyCallback
    private val maxLogEntries = 1000
    private val inMemoryLogs = mutableSetOf<String>()

    private var handleIncomingCallOnceConnected = false

    lateinit var bleManager: BLEManager

    companion object {
        const val ACTION_HEART_RATE_PROBE_START =
            "me.kavishdevar.librepods.action.HEART_RATE_PROBE_START"
        const val ACTION_HEART_RATE_PROBE_STOP =
            "me.kavishdevar.librepods.action.HEART_RATE_PROBE_STOP"
        const val ACTION_HEART_RATE_PROBE_STATUS =
            "me.kavishdevar.librepods.action.HEART_RATE_PROBE_STATUS"
        private const val LEGACY_HEART_RATE_PROBE_PREFERENCE = "heart_rate_probe_enabled"
        private const val HEART_RATE_FIRST_SAMPLE_TIMEOUT_MILLIS = 10_000L
        private const val HEART_RATE_STALL_TIMEOUT_MILLIS = 6_000L
        private const val HEART_RATE_OWNERSHIP_TIMEOUT_MILLIS = 10_000L
        private const val HEART_RATE_OWNERSHIP_REQUEST_DEBOUNCE_MILLIS = 1_000L
        private const val HEART_RATE_OWNERSHIP_SETTLE_MILLIS = 750L
        private const val HEART_RATE_STOP_ACK_TIMEOUT_MILLIS = 1_200L
        private const val HEART_RATE_WARMUP_SAMPLES = 3
        private val HEART_RATE_RETRY_BACKOFF_MILLIS = longArrayOf(500L, 1_000L, 2_000L)

        init {
            System.loadLibrary("bluetooth_socket")
        }
    }

    private val bleStatusListener = object : BLEManager.AirPodsStatusListener {
        @SuppressLint("NewApi")
        override fun onDeviceStatusChanged(
            device: BLEManager.AirPodsStatus, previousStatus: BLEManager.AirPodsStatus?
        ) {
            if (device.connectionState == "Disconnected" && BluetoothConnectionManager.aacpSocket?.isConnected != true) { // should never happen unless android messes up and sends us a stale broadcast
                Log.d(TAG, "Seems no device has taken over, we will.")
                val bluetoothManager = getSystemService(BluetoothManager::class.java)
                val bluetoothAdapter = bluetoothManager.adapter
                val bluetoothDevice = bluetoothAdapter.getRemoteDevice(
                    sharedPreferences.getString(
                        "mac_address", ""
                    ) ?: ""
                )
                connectToSocket(bluetoothAdapter, bluetoothDevice)
            }
            Log.d(TAG, "Device status changed")
            if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
            val leftLevel = bleManager.getMostRecentStatus()?.leftBattery ?: 0
            val rightLevel = bleManager.getMostRecentStatus()?.rightBattery ?: 0
            val caseLevel = bleManager.getMostRecentStatus()?.caseBattery ?: 0
            val leftCharging = bleManager.getMostRecentStatus()?.isLeftCharging
            val rightCharging = bleManager.getMostRecentStatus()?.isRightCharging
            val caseCharging = bleManager.getMostRecentStatus()?.isCaseCharging

            batteryNotification.setBatteryDirect(
                leftLevel = leftLevel,
                leftCharging = leftCharging == true,
                rightLevel = rightLevel,
                rightCharging = rightCharging == true,
                caseLevel = caseLevel,
                caseCharging = caseCharging == true
            )
            updateBattery()
        }

        override fun onBroadcastFromNewAddress(device: BLEManager.AirPodsStatus) {
            Log.d(TAG, "New address detected")
        }

        override fun onLidStateChanged(
            lidOpen: Boolean,
        ) {
            if (lidOpen) {
                Log.d(TAG, "Lid opened")
                showPopup(
                    this@AirPodsService,
                    getSharedPreferences("settings", MODE_PRIVATE).getString("name", "AirPods Pro")
                        ?: "AirPods"
                )
                if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
                val leftLevel = bleManager.getMostRecentStatus()?.leftBattery ?: 0
                val rightLevel = bleManager.getMostRecentStatus()?.rightBattery ?: 0
                val caseLevel = bleManager.getMostRecentStatus()?.caseBattery ?: 0
                val leftCharging = bleManager.getMostRecentStatus()?.isLeftCharging
                val rightCharging = bleManager.getMostRecentStatus()?.isRightCharging
                val caseCharging = bleManager.getMostRecentStatus()?.isCaseCharging

                batteryNotification.setBatteryDirect(
                    leftLevel = leftLevel,
                    leftCharging = leftCharging == true,
                    rightLevel = rightLevel,
                    rightCharging = rightCharging == true,
                    caseLevel = caseLevel,
                    caseCharging = caseCharging == true
                )
                sendBatteryBroadcast()
            } else {
                Log.d(TAG, "Lid closed")
            }
        }

        override fun onEarStateChanged(
            device: BLEManager.AirPodsStatus, leftInEar: Boolean, rightInEar: Boolean
        ) {
            Log.d(TAG, "Ear state changed - Left: $leftInEar, Right: $rightInEar")

            // In BLE-only mode, ear detection is purely based on BLE data
            if (config.bleOnlyMode) {
                Log.d(TAG, "BLE-only mode: ear detection from BLE data")
            }
        }

        override fun onBatteryChanged(device: BLEManager.AirPodsStatus) {
            if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
            val leftLevel = bleManager.getMostRecentStatus()?.leftBattery ?: 0
            val rightLevel = bleManager.getMostRecentStatus()?.rightBattery ?: 0
            val caseLevel = bleManager.getMostRecentStatus()?.caseBattery ?: 0
            val leftCharging = bleManager.getMostRecentStatus()?.isLeftCharging
            val rightCharging = bleManager.getMostRecentStatus()?.isRightCharging
            val caseCharging = bleManager.getMostRecentStatus()?.isCaseCharging

            batteryNotification.setBatteryDirect(
                leftLevel = leftLevel,
                leftCharging = leftCharging == true,
                rightLevel = rightLevel,
                rightCharging = rightCharging == true,
                caseLevel = caseLevel,
                caseCharging = caseCharging == true
            )
            updateBattery()
            Log.d(TAG, "Battery changed")
        }

        override fun onDeviceDisappeared() {
            Log.d(TAG, "All disappeared")
            updateNotificationContent(
                false
            )
        }
    }

    fun isBluetoothSocketExempted(): Boolean {
        return try {
            BluetoothSocket::class.java.declaredConstructors // will throw if still blocked
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }


    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag", "HardwareIds")
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "lib exempt worked: ${isBluetoothSocketExempted()}")

        sharedPreferencesLogs = getSharedPreferences("packet_logs", MODE_PRIVATE)

        inMemoryLogs.addAll(
            sharedPreferencesLogs.getStringSet(packetLogKey, emptySet()) ?: emptySet()
        )
        _packetLogsFlow.value = inMemoryLogs.toSet()

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        // Heart-rate monitoring belongs to the current AirPods ownership session. Restoring it
        // after a process restart or a later Bluetooth connection leaves the UI enabled without
        // a valid sensor session, so discard the value written by older builds.
        sharedPreferences.edit { remove(LEGACY_HEART_RATE_PROBE_PREFERENCE) }
        heartRateProbeRequested = false
        _heartRateProbeEnabled.value = false
        initializeConfig()

        aacpManager = AACPManager()
        initializeAACPManagerCallback()

        attManager = ATTManagerv2(log = { Log.d("ATTManager", it) })

        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        refreshMiLinkSpatialAudioCapability("service created")

        localMac = config.selfMacAddress
        if (localMac.isEmpty()) {
            if (checkSelfPermission("android.permission.LOCAL_MAC_ADDRESS") == PackageManager.PERMISSION_GRANTED) {
                val bluetoothManager = getSystemService(BluetoothManager::class.java)
                val bluetoothAdapter = bluetoothManager.adapter
                localMac = bluetoothAdapter.address
            } else {
                localMac = try {
                    val process = Runtime.getRuntime().exec(
                        arrayOf("su", "-c", "settings get secure bluetooth_address")
                    )

                    val exitCode = process.waitFor()

                    if (exitCode == 0) {
                        process.inputStream.bufferedReader().use { it.readLine()?.trim().orEmpty() }
                    } else {
                        ""
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Error retrieving local MAC address: ${e.message}. We probably aren't rooted."
                    )
                    ""
                }
            }
            config.selfMacAddress = localMac
            sharedPreferences.edit {
                putString("self_mac_address", localMac)
            }
        }

        ServiceManager.setService(this)
        startForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            initGestureDetector()
        } else {
            gestureDetector = null
            config.headGestures = false
            sharedPreferences.edit { putBoolean("head_gestures", false) }
            Log.d(TAG, "Head gestures disabled as device is running Android 9 or below")
        }

        bleManager = BLEManager(this)
        bleManager.setAirPodsStatusListener(bleStatusListener)

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)

        with(sharedPreferences) {
            edit {
                if (!contains("conversational_awareness_pause_music")) putBoolean(
                    "conversational_awareness_pause_music", false
                )
                if (!contains("personalized_volume")) putBoolean("personalized_volume", false)
                if (!contains("automatic_ear_detection")) putBoolean(
                    "automatic_ear_detection", true
                )
                if (!contains("long_press_nc")) putBoolean("long_press_nc", true)
                if (!contains("show_phone_battery_in_widget")) putBoolean(
                    "show_phone_battery_in_widget", true
                )
                if (!contains("single_anc")) putBoolean("single_anc", true)
                if (!contains("long_press_transparency")) putBoolean(
                    "long_press_transparency", true
                )
                if (!contains("conversational_awareness")) putBoolean(
                    "conversational_awareness", true
                )
                if (!contains("relative_conversational_awareness_volume")) putBoolean(
                    "relative_conversational_awareness_volume", true
                )
                if (!contains("long_press_adaptive")) putBoolean("long_press_adaptive", true)
                if (!contains("loud_sound_reduction")) putBoolean("loud_sound_reduction", true)
                if (!contains("long_press_off")) putBoolean("long_press_off", false)
                if (!contains("volume_control")) putBoolean("volume_control", true)
                if (!contains("head_gestures")) putBoolean("head_gestures", true)
                if (!contains("disconnect_when_not_wearing")) putBoolean(
                    "disconnect_when_not_wearing", false
                )

                // AirPods state-based takeover
                if (!contains("takeover_when_disconnected")) putBoolean(
                    "takeover_when_disconnected", false
                )
                if (!contains("takeover_when_idle")) putBoolean("takeover_when_idle", false)
                if (!contains("takeover_when_music")) putBoolean("takeover_when_music", false)
                if (!contains("takeover_when_call")) putBoolean("takeover_when_call", false)

                // Phone state-based takeover
                if (!contains("takeover_when_ringing_call")) putBoolean(
                    "takeover_when_ringing_call", false
                )
                if (!contains("takeover_when_media_start")) putBoolean(
                    "takeover_when_media_start", false
                )

                if (!contains("adaptive_strength")) putInt("adaptive_strength", 51)
                if (!contains("tone_volume")) putInt("tone_volume", 75)
                if (!contains("conversational_awareness_volume")) putInt(
                    "conversational_awareness_volume", 43
                )

                if (!contains("qs_click_behavior")) putString("qs_click_behavior", "cycle")
                if (!contains("name")) putString("name", "AirPods")

                if (!contains("left_single_press_action")) putString(
                    "left_single_press_action",
                    StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!.name
                )
                if (!contains("right_single_press_action")) putString(
                    "right_single_press_action",
                    StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!.name
                )
                if (!contains("left_double_press_action")) putString(
                    "left_double_press_action",
                    StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!.name
                )
                if (!contains("right_double_press_action")) putString(
                    "right_double_press_action",
                    StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!.name
                )
                if (!contains("left_triple_press_action")) putString(
                    "left_triple_press_action",
                    StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!.name
                )
                if (!contains("right_triple_press_action")) putString(
                    "right_triple_press_action",
                    StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!.name
                )
                if (!contains("left_long_press_action")) putString(
                    "left_long_press_action",
                    StemAction.defaultActions[StemPressType.LONG_PRESS]!!.name
                )
                if (!contains("right_long_press_action")) putString(
                    "right_long_press_action",
                    StemAction.defaultActions[StemPressType.LONG_PRESS]!!.name
                )
                if (!contains("camera_action")) putString("camera_action", "SINGLE_PRESS")

            }
        }

        initializeConfig()

        externalBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == MiLinkAirPodsBridgeContract.ACTION_REQUEST_STATE) {
                    if (!miLinkSpatialAudioCapabilityChecked) {
                        refreshMiLinkSpatialAudioCapability("MiLink requested state")
                    }
                    sendMiLinkBridgeState("requested")
                } else if (intent?.action == MiLinkAirPodsBridgeContract.ACTION_SET_ANC) {
                    handleMiLinkAncCommand(intent)
                } else if (intent?.action ==
                    MiLinkAirPodsBridgeContract.ACTION_SET_SPATIAL_AUDIO
                ) {
                    handleMiLinkSpatialAudioCommand(intent)
                } else if (intent?.action == "me.kavishdevar.librepods.SET_ANC_MODE") {
                    if (intent.hasExtra("mode")) {
                        val mode = intent.getIntExtra("mode", -1)
                        if (mode in 1..4) {
                            aacpManager.sendControlCommand(
                                AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
                                mode
                            )
                        }
                    } else {
                        val currentMode = ancNotification.status
                        val configByte = sharedPreferences.getInt("long_press_byte", 0b0111)
                        val allowOffModeValue =
                            aacpManager.controlCommandStatusList.find { it.identifier == AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION }
                        val allowOffMode =
                            allowOffModeValue?.value?.takeIf { it.isNotEmpty() }?.get(0) == 0x01.toByte() || sharedPreferences.getBoolean("off_listening_mode", true)
                        val nextMode = getNextMode(currentMode = currentMode, configByte = configByte, allowOffMode)

                        aacpManager.sendControlCommand(
                            AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
                            nextMode
                        )
                        Log.d(
                            TAG,
                            "Cycling ANC mode from $currentMode to $nextMode"
                        )
                    }
                } else  if (intent?.action == "me.kavishdevar.librepods.CONVO_DETECT") {
                    if (intent.hasExtra("enabled")) {
                        val enabled = intent.getBooleanExtra("enabled", false)
                        aacpManager.sendControlCommand(
                            AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG.value,
                            enabled
                        )
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(externalBroadcastReceiver, externalBroadcastFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                externalBroadcastReceiver, externalBroadcastFilter
            )
        }
        val audioManager = this@AirPodsService.getSystemService(AUDIO_SERVICE) as AudioManager
        MediaController.initialize(
            audioManager, this@AirPodsService.getSharedPreferences(
                "settings", MODE_PRIVATE
            )
        )
//        Log.d(TAG, "Initializing CrossDevice")
//        CoroutineScope(Dispatchers.IO).launch {
//            CrossDevice.init(this@AirPodsService)
//            Log.d(TAG, "CrossDevice initialized")
//        }

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        macAddress = sharedPreferences.getString("mac_address", "") ?: ""

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object: TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                traceConnectionEvent("call_state_changed", "state=$state previousInCall=$isInCall")
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        val leAvailableForAudio =
                            bleManager.getMostRecentStatus()?.isLeftInEar == true || bleManager.getMostRecentStatus()?.isRightInEar == true
//                        if ((CrossDevice.isAvailable && !isConnectedLocally && earDetectionNotification.status.contains(0x00)) || leAvailableForAudio) CoroutineScope(Dispatchers.IO).launch {
                        if (leAvailableForAudio) runBlocking {
                            takeOver("call")
                        }
                        if (config.headGestures) {
                            handleIncomingCall()
                        }
                    }

                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        val leAvailableForAudio =
                            bleManager.getMostRecentStatus()?.isLeftInEar == true || bleManager.getMostRecentStatus()?.isRightInEar == true
//                        if ((CrossDevice.isAvailable && !isConnectedLocally && earDetectionNotification.status.contains(0x00)) || leAvailableForAudio) CoroutineScope(
                        if (leAvailableForAudio) CoroutineScope(
                            Dispatchers.IO
                        ).launch {
                            takeOver("call")
                        }
                        isInCall = true
                    }

                    TelephonyManager.CALL_STATE_IDLE -> {
                        isInCall = false
                        gestureDetector?.stopDetection()
                    }
                }
            }
        }
        if (checkSelfPermission("android.permission.READ_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.registerTelephonyCallback(mainExecutor, phoneStateListener)
        }

        if (config.showPhoneBatteryInWidget) {
            widgetMobileBatteryEnabled = true
            val batteryChangedIntentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            batteryChangedIntentFilter.addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    BatteryChangedIntentReceiver, batteryChangedIntentFilter, RECEIVER_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                    BatteryChangedIntentReceiver, batteryChangedIntentFilter
                )
            }
        }
        val serviceIntentFilter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.bluetooth.device.action.BOND_STATE_CHANGED")
            addAction("android.bluetooth.device.action.NAME_CHANGED")
            addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.action.VENDOR_SPECIFIC_HEADSET_EVENT")
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED")
            addAction("android.bluetooth.device.action.UUID")
        }

        connectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AirPodsNotifications.AIRPODS_CONNECTION_DETECTED) {
                    device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("device", BluetoothDevice::class.java)!!
                    } else {
                        intent.getParcelableExtra("device") as BluetoothDevice?
                    }

                    if (config.deviceName == "AirPods" && device?.name != null) {
                        config.deviceName = device?.name ?: "AirPods"
                        sharedPreferences.edit { putString("name", config.deviceName) }
                    }

//                    Log.d("AirPodsCrossDevice", CrossDevice.isAvailable.toString())
//                    if (!CrossDevice.isAvailable) {
                    Log.d(TAG, "${config.deviceName} connected")
                    CoroutineScope(Dispatchers.IO).launch {
                        val bluetoothManager = getSystemService(BluetoothManager::class.java)
                        connectToSocket(bluetoothManager.adapter, device!!)
                    }
                    Log.d(TAG, "Setting metadata")
                    setMetadatas(device!!)
//                    isConnectedLocally = true
                    macAddress = device!!.address
                    sharedPreferences.edit {
                        putString("mac_address", macAddress)
                    }
                    sendMiLinkBridgeState("AirPods detected")
//                    }

                } else if (intent?.action == AirPodsNotifications.AIRPODS_DISCONNECTED) {
                    resetHeartRateMonitoringForSession(
                        reason = "AACP disconnected",
                        sendStop = false
                    )
                    isAirPodsA2dpPlaying = false
                    cancelPendingAirPodsAbsoluteVolumeResync("AACP disconnected")
                    rootAvrcpVolumeController.cancel()
                    updateSpatialAudioTracking("AACP disconnected")
                    device = null
//                    isConnectedLocally = false
                    popupShown = false
                    updateNotificationContent(false)
                    remoteStreamingDevices.clear()
                    aacpManager.disconnected()
                    BluetoothConnectionManager.aacpSocket = null
                    attManager.disconnected()
                    sendMiLinkBridgeState("AirPods disconnected")
                }
            }
        }
        val showIslandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "me.kavishdevar.librepods.cross_device_island") {
                    showIsland(
                        this@AirPodsService,
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                                batteryNotification.getBattery()
                                    .find { it.component == BatteryComponent.RIGHT }?.level!!
                            )
                    )
                } else if (intent?.action == AirPodsNotifications.DISCONNECT_RECEIVERS) {
                    try {
                        context?.unregisterReceiver(this)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        val showIslandIntentFilter = IntentFilter().apply {
            addAction("me.kavishdevar.librepods.cross_device_island")
            addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(showIslandReceiver, showIslandIntentFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                showIslandReceiver, showIslandIntentFilter
            )
        }

        val deviceIntentFilter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionReceiver, deviceIntentFilter, RECEIVER_EXPORTED)
            registerReceiver(bluetoothReceiver, serviceIntentFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                connectionReceiver, deviceIntentFilter
            )
            registerReceiver(bluetoothReceiver, serviceIntentFilter)
        }

        refreshCurrentA2dpPlaybackState("service startup")

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter.bondedDevices.forEach { device ->
            device.fetchUuidsWithSdp()
            if (device.uuids != null) {
                if (device.uuids.contains(ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a"))) {
                    bluetoothAdapter.getProfileProxy(
                        this, object : BluetoothProfile.ServiceListener {
                            @SuppressLint("NewApi")
                            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                                if (profile == BluetoothProfile.A2DP) {
                                    val connectedDevices = proxy.connectedDevices
                                    if (connectedDevices.isNotEmpty()) {
//                                        if (!CrossDevice.isAvailable) {
                                        CoroutineScope(Dispatchers.IO).launch {
                                            connectToSocket(bluetoothAdapter, device)
                                        }
                                        setMetadatas(device)
                                        macAddress = device.address
                                        sharedPreferences.edit {
                                            putString("mac_address", macAddress)
                                        }
//                                        }
                                        sendBroadcast(
                                            Intent(AirPodsNotifications.AIRPODS_CONNECTED).apply {
                                                setPackage(packageName)
                                            })
                                    }
                                }
                                bluetoothAdapter.closeProfileProxy(profile, proxy)
                            }

                            override fun onServiceDisconnected(profile: Int) {}
                        }, BluetoothProfile.A2DP
                    )
                }
            }
        }

//        if (!isConnectedLocally && !CrossDevice.isAvailable) {
//            clearPacketLogs()
//        }

        CoroutineScope(Dispatchers.IO).launch {
            bleManager.startScanning()
        }
    }

    @Suppress("unused")
    fun cameraOpened() {
        Log.d(TAG, "Camera opened, gonna handle stem presses and take action if visible")
        cameraActive = true
        setupStemActions()
    }

    @Suppress("unused")
    fun cameraClosed() {
        cameraActive = false
        setupStemActions()
    }

    fun isCustomAction(
        action: StemAction?, default: StemAction?
    ): Boolean {
        return action != default
    }

    fun setupStemActions() {
        val singlePressDefault = StemAction.defaultActions[StemPressType.SINGLE_PRESS]
        val doublePressDefault = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]
        val triplePressDefault = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]
        val longPressDefault = StemAction.defaultActions[StemPressType.LONG_PRESS]

        val singlePressCustomized =
            isCustomAction(config.leftSinglePressAction, singlePressDefault) || isCustomAction(
                config.rightSinglePressAction, singlePressDefault
            ) || (cameraActive && config.cameraAction == StemPressType.SINGLE_PRESS)
        val doublePressCustomized =
            isCustomAction(config.leftDoublePressAction, doublePressDefault) || isCustomAction(
                config.rightDoublePressAction, doublePressDefault
            )
        val triplePressCustomized =
            isCustomAction(config.leftTriplePressAction, triplePressDefault) || isCustomAction(
                config.rightTriplePressAction, triplePressDefault
            )
        val longPressCustomized = isCustomAction(
            config.leftLongPressAction, longPressDefault
        ) || isCustomAction(
            config.rightLongPressAction, longPressDefault
        ) || (cameraActive && config.cameraAction == StemPressType.LONG_PRESS)
        Log.d(
            TAG,
            "Setting up stem actions: Single Press Customized: $singlePressCustomized, Double Press Customized: $doublePressCustomized, Triple Press Customized: $triplePressCustomized, Long Press Customized: $longPressCustomized"
        )
        aacpManager.sendStemConfigPacket(
            singlePressCustomized,
            doublePressCustomized,
            triplePressCustomized,
            longPressCustomized,
        )
    }

    @ExperimentalEncodingApi
    private fun initializeAACPManagerCallback() {
        aacpManager.setPacketCallback(object : AACPManager.PacketCallback {
            @SuppressLint("MissingPermission")
            override fun onBatteryInfoReceived(batteryInfo: ByteArray) {
                batteryNotification.setBattery(batteryInfo)
                sendBroadcast(Intent(AirPodsNotifications.BATTERY_DATA).apply {
                    putParcelableArrayListExtra("data", ArrayList(batteryNotification.getBattery()))
                    setPackage(packageName)
                })
                updateBattery()
                updateNotificationContent(
                    true,
                    this@AirPodsService.getSharedPreferences("settings", MODE_PRIVATE)
                        .getString("name", device?.name),
                    batteryNotification.getBattery()
                )
//                CrossDevice.sendRemotePacket(batteryInfo)
//                CrossDevice.batteryBytes = batteryInfo

                for (battery in batteryNotification.getBattery()) {
                    Log.d(
                        "AirPodsParser",
                        "${battery.getComponentName()}: ${battery.getStatusName()} at ${battery.level}% "
                    )
                }

                if (batteryNotification.getBattery()[0].status == BatteryStatus.CHARGING && batteryNotification.getBattery()[1].status == BatteryStatus.CHARGING) {
                    disconnectAudio(this@AirPodsService, device, "both_buds_charging")
                } else {
                    connectAudio(this@AirPodsService, device)
                }
            }

            override fun onEarDetectionReceived(earDetection: ByteArray) {
                sendBroadcast(Intent(AirPodsNotifications.EAR_DETECTION_DATA).apply {
                    val list = earDetectionNotification.status
                    val bytes = ByteArray(2)
                    bytes[0] = list[0]
                    bytes[1] = list[1]
                    putExtra("data", bytes)
                }.apply {
                    setPackage(packageName)
                })
                Log.d(
                    "AirPodsParser",
                    "Ear Detection: ${earDetectionNotification.status[0]} ${earDetectionNotification.status[1]}"
                )
                processEarDetectionChange(earDetection)
            }

            override fun onConversationAwarenessReceived(conversationAwareness: ByteArray) {
                if (!conversationAwarenessNotification.setData(conversationAwareness)) {
                    Log.w(
                        "AirPodsParser",
                        "Ignoring incomplete conversation-awareness packet: " +
                            conversationAwareness.joinToString(" ") { "%02X".format(it) }
                    )
                    return
                }
                sendBroadcast(Intent(AirPodsNotifications.CA_DATA).apply {
                    putExtra("data", conversationAwarenessNotification.status)
                }.apply {
                    setPackage(packageName)
                })

                if (conversationAwarenessNotification.status == 1.toByte() || conversationAwarenessNotification.status == 2.toByte()) {
                    MediaController.startSpeaking()
                } else if (conversationAwarenessNotification.status == 6.toByte() ||conversationAwarenessNotification.status == 8.toByte() || conversationAwarenessNotification.status == 9.toByte()) {
                    MediaController.stopSpeaking()
                }

                Log.d(
                    "AirPodsParser",
                    "Conversation Awareness: ${conversationAwarenessNotification.status}"
                )
            }

            override fun onControlCommandReceived(controlCommand: ByteArray) {
                val command = AACPManager.ControlCommand.fromByteArray(controlCommand)
                if (command.identifier == AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value) {
                    ancNotification.setStatus(byteArrayOf(command.value.takeIf { it.isNotEmpty() }
                        ?.get(0) ?: 0x00.toByte()))
                    sendANCBroadcast()
                    updateNoiseControlWidget()
                }
            }

            override fun onOwnershipChangeReceived(owns: Boolean) {
                if (owns) {
                    heartRateOwnershipTimeoutJob?.cancel()
                    heartRateOwnershipTimeoutJob = null
                    stopOrphanedHeartRateSampling("AACP ownership acquired while disabled")
                    scheduleHeartRateProbeAfterOwnershipSettled(
                        "AirPods confirmed local ownership"
                    )
                    scheduleAirPodsAbsoluteVolumeResync(
                        "AACP ownership confirmed for this phone",
                        delayMs = 0L
                    )
                } else {
                    heartRateOwnershipSettleJob?.cancel()
                    heartRateOwnershipSettleJob = null
                    if (heartRateRemoteReleaseJob?.isActive != true) {
                        resetHeartRateMonitoringForSession(
                            reason = "AACP ownership lost",
                            sendStop = true
                        )
                    }
                    prewarmAirPodsAbsoluteVolumeResync("AACP ownership lost")
                    cancelPendingAirPodsAbsoluteVolumeResync("AACP ownership lost")
                    MediaController.recentlyLostOwnership = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        MediaController.recentlyLostOwnership = false
                    }, 3000)
                    Log.d(TAG, "ownership lost")
                    MediaController.sendPause()
                    MediaController.pausedForOtherDevice = true
                    otherDeviceTookOver = true
                    disconnectAudio(
                        this@AirPodsService, device, "aacp_ownership_lost"
                    )
                }
            }

            override fun onOwnershipToFalseRequest(sender: String, reasonReverseTapped: Boolean) {
                // TODO: Show a reverse button, but that's a lot of effort -- i'd have to change the UI too, which i hate doing, and handle other device's reverses too, and disconnect audio etc... so for now, just pause the audio and show the island without asking to reverse.
                // handling reverse is a problem because we'd have to disconnect the audio, but there's no option connect audio again natively, so notification would have to be changed. I wish there was a way to just "change the audio output device".
                // (20 minutes later) i've done it nonetheless :]
                val senderName =
                    aacpManager.connectedDevices.find { it.mac == sender }?.type ?: "Other device"
                Log.d(
                    TAG,
                    "other device has hijacked the connection, reasonReverseTapped: $reasonReverseTapped"
                )
                releaseOwnershipAfterHeartRateStops("ownership requested by $senderName") {
                    aacpManager.sendControlCommand(
                        AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                        byteArrayOf(0x00)
                    )
                    otherDeviceTookOver = true
                    disconnectAudio(this@AirPodsService, device, "ownership_request sender=$sender reverse=$reasonReverseTapped")
                    if (reasonReverseTapped) {
                        Log.d(TAG, "reverse tapped, disconnecting audio")
                        disconnectedBecauseReversed = true
                        disconnectAudio(this@AirPodsService, device, "ownership_reverse sender=$sender")
                    }
                    showIsland(
                        this@AirPodsService,
                        (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.level
                            ?: 0).coerceAtMost(
                            batteryNotification.getBattery()
                                .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                        ),
                        IslandType.MOVED_TO_OTHER_DEVICE,
                        reversed = reasonReverseTapped,
                        otherDeviceName = senderName
                    )
                    MediaController.sendPause()
                }
            }

            override fun onRemoteStreamingStateChanged(sender: String, isStreaming: Boolean) {
                handleRemoteStreamingStateChanged(
                    sender = sender,
                    isStreaming = isStreaming,
                    reason = "remote Smart Routing host started streaming"
                )
            }

            override fun onShowNearbyUI(sender: String) {
                val senderName =
                    aacpManager.connectedDevices.find { it.mac == sender }?.type ?: "Other device"
                showIsland(
                    this@AirPodsService,
                    (batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.level ?: 0).coerceAtMost(
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                    ),
                    IslandType.MOVED_TO_OTHER_DEVICE,
                    reversed = false,
                    otherDeviceName = senderName
                )
            }

            override fun onDeviceInformationReceived(deviceInformation: AACPManager.Companion.AirPodsInformation) {
                Log.d(
                    "AirPodsParser",
                    "Device Information: name: ${deviceInformation.name}, modelNumber: ${deviceInformation.modelNumber}, manufacturer: ${deviceInformation.manufacturer}, serialNumber: ${deviceInformation.serialNumber}, version1: ${deviceInformation.version1}, version2: ${deviceInformation.version2}, hardwareRevision: ${deviceInformation.hardwareRevision}, updaterIdentifier: ${deviceInformation.updaterIdentifier}, leftSerialNumber: ${deviceInformation.leftSerialNumber}, rightSerialNumber: ${deviceInformation.rightSerialNumber}, version3: ${deviceInformation.version3}"
                )
                // Store in SharedPreferences
                sharedPreferences.edit {
                    putString("name", deviceInformation.name)
                    putString("airpods_model_number", deviceInformation.modelNumber)
                    putString("airpods_manufacturer", deviceInformation.manufacturer)
                    putString("airpods_serial_number", deviceInformation.serialNumber)
                    putString("airpods_left_serial_number", deviceInformation.leftSerialNumber)
                    putString("airpods_right_serial_number", deviceInformation.rightSerialNumber)
                    putString("airpods_version1", deviceInformation.version1)
                    putString("airpods_version2", deviceInformation.version2)
                    putString("airpods_version3", deviceInformation.version3)
                    putString("airpods_hardware_revision", deviceInformation.hardwareRevision)
                    putString("airpods_updater_identifier", deviceInformation.updaterIdentifier)
                }
                // Update config
                config.airpodsName = deviceInformation.name
                config.airpodsModelNumber = deviceInformation.modelNumber
                config.airpodsManufacturer = deviceInformation.manufacturer
                config.airpodsSerialNumber = deviceInformation.serialNumber
                config.airpodsLeftSerialNumber = deviceInformation.leftSerialNumber
                config.airpodsRightSerialNumber = deviceInformation.rightSerialNumber
                config.airpodsVersion1 = deviceInformation.version1
                config.airpodsVersion2 = deviceInformation.version2
                config.airpodsVersion3 = deviceInformation.version3
                config.airpodsHardwareRevision = deviceInformation.hardwareRevision
                config.airpodsUpdaterIdentifier = deviceInformation.updaterIdentifier

                val model = AirPodsModels.getModelByModelNumber(config.airpodsModelNumber)
                if (model != null) {
                    airpodsInstance = AirPodsInstance(
                        name = config.airpodsName,
                        model = model,
                        actualModelNumber = config.airpodsModelNumber,
                        serialNumber = config.airpodsSerialNumber,
                        leftSerialNumber = config.airpodsLeftSerialNumber,
                        rightSerialNumber = config.airpodsRightSerialNumber,
                        version1 = config.airpodsVersion1,
                        version2 = config.airpodsVersion2,
                        version3 = config.airpodsVersion3,
                    )
                    if (device != null) setMetadatas(device!!)
                }
                sendBroadcast(
                    Intent(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED).setPackage(
                        packageName
                    )
                )
            }

            @SuppressLint("NewApi")
            override fun onHeadTrackingReceived(headTracking: ByteArray) {
                if (isHeadTrackingActive) {
                    HeadTracking.processPacket(headTracking)?.let(spatialHeadTrackerBridge::submit)
                    processHeadTrackingData(headTracking)
                }
            }

            override fun onHeartRateReceived(sample: AirPodsHeartRateSample) {
                val (warmup, streamingStarted) = synchronized(heartRateProbeLock) {
                    if (!heartRateProbeRequested || !aacpManager.owns) return
                    lastHeartRateSampleElapsedRealtime = SystemClock.elapsedRealtime()
                    val wasStreaming = _heartRateProbeStreaming.value
                    val isWarmup = if (heartRateWarmupRemaining > 0) {
                        heartRateWarmupRemaining--
                        true
                    } else {
                        false
                    }
                    _heartRateProbeStreaming.value = !isWarmup
                    if (!isWarmup) _heartRateSample.value = sample
                    isWarmup to (!isWarmup && !wasStreaming)
                }
                if (streamingStarted) {
                    heartRateRemoteTakeoverGate.onHeartRateStreamingStarted()
                    Log.d(TAG, "Heart-rate stream active; armed for new remote playback")
                }
                KeepHeartRateBridge.publish(
                    context = this@AirPodsService,
                    enabled = _heartRateProbeEnabled.value,
                    streaming = _heartRateProbeStreaming.value,
                    sample = _heartRateSample.value
                )
                Log.i(
                    "HeartRateProbe",
                    "sample bpm=${sample.bpm} sequence=${sample.sequence} service=${sample.service} " +
                        "status=0x${sample.statusTail.toString(16).padStart(6, '0')} warmup=$warmup"
                )
            }

            override fun onHeartRateServiceSettingAcknowledged() {
                val acknowledgement = synchronized(heartRateProbeLock) {
                    pendingHeartRateStopAcknowledgement
                }
                if (acknowledgement?.complete(Unit) == true) {
                    Log.i("HeartRateProbe", "sampling stop acknowledged before ownership release")
                }
            }

            override fun onProximityKeysReceived(proximityKeys: ByteArray) {
                val keys = aacpManager.parseProximityKeysResponse(proximityKeys)
                Log.d("AirPodsParser", "Proximity keys: $keys")
                sharedPreferences.edit {
                    for (key in keys) {
                        Log.d("AirPodsParser", "Proximity key: ${key.key.name} = ${key.value}")
                        putString(key.key.name, Base64.encode(key.value))
                    }
                }
            }

            override fun onStemPressReceived(stemPress: ByteArray) {

                val (stemPressType, bud) = aacpManager.parseStemPressResponse(stemPress)

                Log.d(
                    "AirPodsParser",
                    "Stem press received: $stemPressType on $bud, cameraActive: $cameraActive, cameraAction: ${config.cameraAction}"
                )
                if (cameraActive && config.cameraAction != null && stemPressType == config.cameraAction) {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 27"))
                } else {
                    val action = getActionFor(bud, stemPressType)
                    Log.d("AirPodsParser", "$bud $stemPressType action: $action")
                    action?.let { executeStemAction(it) }
                }
            }

            override fun onAudioSourceReceived(audioSource: ByteArray) {
                val previousMac = lastAudioSourceMac
                val previousType = lastAudioSourceType
                val currentSource = aacpManager.audioSource
                lastAudioSourceMac = currentSource?.mac
                lastAudioSourceType = currentSource?.type
                if (
                    currentSource != null &&
                    currentSource.type != AACPManager.Companion.AudioSourceType.NONE &&
                    currentSource.mac.equals(localMac, ignoreCase = true)
                ) {
                    remoteStreamingDevices.clear()
                }
                Log.d(
                    "AirPodsParser",
                    "Audio source changed mac: ${currentSource?.mac}, type: ${currentSource?.type?.name}"
                )
                if (
                    localMac != "" &&
                    currentSource != null &&
                    currentSource.type != AACPManager.Companion.AudioSourceType.NONE &&
                    !currentSource.mac.equals(localMac, ignoreCase = true)
                ) {
                    remoteStreamingDevices.add(currentSource.mac.uppercase())
                    Log.d(
                        "AirPodsParser",
                        "Audio source is another device; evaluating remote takeover"
                    )
                    handleRemoteStreamingStateChanged(
                        sender = currentSource.mac,
                        isStreaming = true,
                        reason = "AirPods audio source moved to remote ${currentSource.type.name} device"
                    )
                    // this also means that the other device has start playing the audio, and if that's true, we can again start listening for audio config changes
//                    Log.d(TAG, "Another device started playing audio, listening for audio config changes again")
//                    MediaController.pausedForOtherDevice = false
// future me: what the heck is this? this just means it will not be taking over again if audio source doesn't change???
                } else if (
                    previousMac != null &&
                    currentSource != null &&
                    currentSource.mac.equals(localMac, ignoreCase = true) &&
                    currentSource.type != AACPManager.Companion.AudioSourceType.NONE &&
                    (!previousMac.equals(localMac, ignoreCase = true) ||
                        previousType == AACPManager.Companion.AudioSourceType.NONE)
                ) {
                    remoteStreamingDevices.clear()
                    refreshAacpControlSession("audio source returned to this phone")
                    scheduleAirPodsAbsoluteVolumeResync(
                        "audio source returned to this phone",
                        delayMs = 0L
                    )
                }
            }

            override fun onConnectedDevicesReceived(connectedDevices: List<AACPManager.Companion.ConnectedDevice>) {
                for (device in connectedDevices) {
                    Log.d(
                        "AirPodsParser",
                        "Connected device: ${device.mac}, info1: ${device.info1}, info2: ${device.info2})"
                    )
                }
                val newDevices = connectedDevices.filter { newDevice ->
                    val notInOld =
                        aacpManager.oldConnectedDevices.none { oldDevice -> oldDevice.mac == newDevice.mac }
                    val notLocal = newDevice.mac != localMac
                    notInOld && notLocal
                }

                for (device in newDevices) {
                    Log.d(
                        "AirPodsParser",
                        "New connected device: ${device.mac}, info1: ${device.info1}, info2: ${device.info2})"
                    )
                    Log.d(
                        TAG,
                        "Sending new Tipi packet for device ${device.mac}, and sending media info to the device"
                    )
                    aacpManager.sendMediaInformationNewDevice(
                        selfMacAddress = localMac, targetMacAddress = device.mac
                    )
                    aacpManager.sendAddTiPiDevice(
                        selfMacAddress = localMac, targetMacAddress = device.mac
                    )
                }
            }

            override fun onHeadphoneAccommodationReceived(eqData: FloatArray) {
                sendBroadcast(
                    Intent(AirPodsNotifications.EQ_DATA).putExtra("eqData", eqData).apply {
                        setPackage(packageName)
                    })
            }

            override fun onCustomEqReceived(customEq: CustomEq) {
                // TODO
            }

            override fun onCapabilitiesReceived(capabilities: List<Capability>) {
                // TODO
            }

            override fun onUnknownPacketReceived(packet: ByteArray) {
                Log.d(
                    "AACPManager",
                    "Unknown packet received: ${packet.joinToString(" ") { "%02X".format(it) }}"
                )
            }
        })
    }

    private fun getActionFor(
        bud: AACPManager.Companion.StemPressBudType, type: StemPressType
    ): StemAction? {
        return when (type) {
            StemPressType.SINGLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftSinglePressAction else config.rightSinglePressAction
            StemPressType.DOUBLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftDoublePressAction else config.rightDoublePressAction
            StemPressType.TRIPLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftTriplePressAction else config.rightTriplePressAction
            StemPressType.LONG_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftLongPressAction else config.rightLongPressAction
        }
    }

    private fun executeStemAction(action: StemAction) {
        when (action) {
            StemAction.defaultActions[StemPressType.SINGLE_PRESS] -> {
                Log.d(
                    "AirPodsParser", "Default single press action: Play/Pause, not taking action."
                )
            }

            StemAction.PLAY_PAUSE -> MediaController.sendPlayPause()
            StemAction.PREVIOUS_TRACK -> MediaController.sendPreviousTrack()
            StemAction.NEXT_TRACK -> MediaController.sendNextTrack()
            StemAction.DIGITAL_ASSISTANT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } else {
                    Log.w(
                        "AirPodsParser",
                        "Digital Assistant action is not supported on this Android version."
                    )
                }
            }

            StemAction.CYCLE_NOISE_CONTROL_MODES -> {
                Log.d("AirPodsParser", "Cycling noise control modes")
                sendBroadcast(Intent("me.kavishdevar.librepods.SET_ANC_MODE").apply {
                    setPackage(packageName)
                })
            }
        }
    }

    private fun processEarDetectionChange(earDetection: ByteArray) {
        var inEar: Boolean
        val inEarData = listOf(
            earDetectionNotification.status[0] == 0x00.toByte(),
            earDetectionNotification.status[1] == 0x00.toByte()
        )
        var justEnabledA2dp = false
        earDetectionNotification.setStatus(earDetection)
        if (config.earDetectionEnabled) {
            val data = earDetection.copyOfRange(earDetection.size - 2, earDetection.size)
            inEar = data[0] == 0x00.toByte() && data[1] == 0x00.toByte()

            val newInEarData = listOf(
                data[0] == 0x00.toByte(), data[1] == 0x00.toByte()
            )

            if (inEarData.sorted() == listOf(false, false) && newInEarData.sorted() != listOf(
                    false, false
                ) && islandWindow?.isVisible != true
            ) {
                showIsland(
                    this@AirPodsService,
                    (batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.level ?: 0).coerceAtMost(
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                    )
                )
            }

            if (newInEarData == listOf(false, false) && islandWindow?.isVisible == true) {
                islandWindow?.close()
            }

            if (newInEarData.contains(true) && inEarData == listOf(false, false)) {
                connectAudio(this@AirPodsService, device)
                justEnabledA2dp = true
                registerA2dpConnectionReceiver()
                if (MediaController.getMusicActive()) {
                    MediaController.userPlayedTheMedia = true
                }
            } else if (newInEarData == listOf(false, false)) {
                MediaController.sendPause(force = true)
                if (config.disconnectWhenNotWearing) {
                    disconnectAudio(this@AirPodsService, device, "not_wearing_preference")
                }
            }
            val wasNone = inEarData == listOf(false, false)
            val nowSingle = newInEarData.count { it } == 1

            if (wasNone && nowSingle) {
                MediaController.sendPlay()
                MediaController.iPausedTheMedia = false
                return
            }

            if (inEarData.contains(false) && newInEarData == listOf(true, true)) {
                Log.d("AirPodsParser", "User put in both AirPods from just one.")
                MediaController.userPlayedTheMedia = false
            }

            if (newInEarData.contains(false) && inEarData == listOf(true, true)) {
                Log.d("AirPodsParser", "User took one of two out.")
                MediaController.userPlayedTheMedia = false
            }

            Log.d(
                "AirPodsParser",
                "inEarData: ${inEarData.sorted()}, newInEarData: ${newInEarData.sorted()}"
            )

            if (newInEarData.sorted() != inEarData.sorted()) {
                if (inEar) {
                    if (!justEnabledA2dp) {
                        MediaController.sendPlay()
                        MediaController.iPausedTheMedia = false
                    }
                } else {
                    MediaController.sendPause()
                }
            }
        }
    }

    private fun registerA2dpConnectionReceiver() {
        val a2dpConnectionStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED") {
                    val state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED
                    )
                    val previousState = intent.getIntExtra(
                        BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED
                    )
                    val device =
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

                    Log.d(
                        "MediaController",
                        "A2DP state changed: $previousState -> $state for device: ${device?.address}"
                    )

                    if (state == BluetoothProfile.STATE_CONNECTED && previousState != BluetoothProfile.STATE_CONNECTED && device?.address == this@AirPodsService.device?.address) {

                        Log.d("MediaController", "A2DP connected, sending play command")
                        MediaController.sendPlay()
                        MediaController.iPausedTheMedia = false

                        context.unregisterReceiver(this)
                    }
                }
            }
        }

        val a2dpIntentFilter =
            IntentFilter("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(a2dpConnectionStateReceiver, a2dpIntentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(a2dpConnectionStateReceiver, a2dpIntentFilter)
        }
    }

    private fun initializeConfig() {
        config = ServiceConfig(
            deviceName = sharedPreferences.getString("name", "AirPods") ?: "AirPods",
            earDetectionEnabled = sharedPreferences.getBoolean("automatic_ear_detection", true),
            conversationalAwarenessPauseMusic = sharedPreferences.getBoolean(
                "conversational_awareness_pause_music", false
            ),
            showPhoneBatteryInWidget = sharedPreferences.getBoolean(
                "show_phone_battery_in_widget", true
            ),
            relativeConversationalAwarenessVolume = sharedPreferences.getBoolean(
                "relative_conversational_awareness_volume", true
            ),
            headGestures = sharedPreferences.getBoolean("head_gestures", true),
            disconnectWhenNotWearing = sharedPreferences.getBoolean(
                "disconnect_when_not_wearing", false
            ),
            conversationalAwarenessVolume = sharedPreferences.getInt(
                "conversational_awareness_volume", 43
            ),
            qsClickBehavior = sharedPreferences.getString("qs_click_behavior", "cycle") ?: "cycle",

            // AirPods state-based takeover
            takeoverWhenDisconnected = sharedPreferences.getBoolean(
                "takeover_when_disconnected", false
            ),
            takeoverWhenIdle = sharedPreferences.getBoolean("takeover_when_idle", false),
            takeoverWhenMusic = sharedPreferences.getBoolean("takeover_when_music", false),
            takeoverWhenCall = sharedPreferences.getBoolean("takeover_when_call", false),

            // Phone state-based takeover
            takeoverWhenRingingCall = sharedPreferences.getBoolean(
                "takeover_when_ringing_call", false
            ),
            takeoverWhenMediaStart = sharedPreferences.getBoolean(
                "takeover_when_media_start", false
            ),

            // Stem actions
            leftSinglePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_single_press_action", "PLAY_PAUSE"
                ) ?: "PLAY_PAUSE"
            )!!,
            rightSinglePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_single_press_action", "PLAY_PAUSE"
                ) ?: "PLAY_PAUSE"
            )!!,

            leftDoublePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_double_press_action", "PREVIOUS_TRACK"
                ) ?: "NEXT_TRACK"
            )!!,
            rightDoublePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_double_press_action", "NEXT_TRACK"
                ) ?: "NEXT_TRACK"
            )!!,

            leftTriplePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_triple_press_action", "PREVIOUS_TRACK"
                ) ?: "PREVIOUS_TRACK"
            )!!,
            rightTriplePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_triple_press_action", "PREVIOUS_TRACK"
                ) ?: "PREVIOUS_TRACK"
            )!!,

            leftLongPressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_long_press_action", "CYCLE_NOISE_CONTROL_MODES"
                ) ?: "CYCLE_NOISE_CONTROL_MODES"
            )!!,
            rightLongPressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_long_press_action", "DIGITAL_ASSISTANT"
                ) ?: "DIGITAL_ASSISTANT"
            )!!,

            cameraAction = sharedPreferences.getString("camera_action", null)
                ?.let { StemPressType.valueOf(it) },

            // AirPods device information
            airpodsName = sharedPreferences.getString("airpods_name", "") ?: "",
            airpodsModelNumber = sharedPreferences.getString("airpods_model_number", "") ?: "",
            airpodsManufacturer = sharedPreferences.getString("airpods_manufacturer", "") ?: "",
            airpodsSerialNumber = sharedPreferences.getString("airpods_serial_number", "") ?: "",
            airpodsLeftSerialNumber = sharedPreferences.getString("airpods_left_serial_number", "")
                ?: "",
            airpodsRightSerialNumber = sharedPreferences.getString(
                "airpods_right_serial_number", ""
            ) ?: "",
            airpodsVersion1 = sharedPreferences.getString("airpods_version1", "") ?: "",
            airpodsVersion2 = sharedPreferences.getString("airpods_version2", "") ?: "",
            airpodsVersion3 = sharedPreferences.getString("airpods_version3", "") ?: "",
            airpodsHardwareRevision = sharedPreferences.getString("airpods_hardware_revision", "")
                ?: "",
            airpodsUpdaterIdentifier = sharedPreferences.getString("airpods_updater_identifier", "")
                ?: "",

            selfMacAddress = sharedPreferences.getString("self_mac_address", "") ?: ""
        )
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (preferences == null || key == null) return

        when (key) {
            "vendor_att_socket" -> if (!preferences.getBoolean(key, false)) attManager.disconnected()
            "name" -> config.deviceName = preferences.getString(key, "AirPods") ?: "AirPods"
            "mac_address" -> macAddress = preferences.getString(key, "") ?: ""
            "automatic_ear_detection" -> config.earDetectionEnabled =
                preferences.getBoolean(key, true)

            "conversational_awareness_pause_music" -> config.conversationalAwarenessPauseMusic =
                preferences.getBoolean(key, false)

            "show_phone_battery_in_widget" -> {
                config.showPhoneBatteryInWidget = preferences.getBoolean(key, true)
                widgetMobileBatteryEnabled = config.showPhoneBatteryInWidget
                updateBattery()
            }

            "relative_conversational_awareness_volume" -> config.relativeConversationalAwarenessVolume =
                preferences.getBoolean(key, true)

            "head_gestures" -> config.headGestures = preferences.getBoolean(key, true)
            "spatial_audio_enabled", SpatialAudioMode.PREFERENCE_KEY -> {
                updateSpatialAudioTracking("setting changed")
                sendMiLinkBridgeState("spatial audio setting changed")
            }
            "disconnect_when_not_wearing" -> config.disconnectWhenNotWearing =
                preferences.getBoolean(key, false)

            "conversational_awareness_volume" -> config.conversationalAwarenessVolume =
                preferences.getInt(key, 43)

            "qs_click_behavior" -> config.qsClickBehavior =
                preferences.getString(key, "cycle") ?: "cycle"

            // AirPods state-based takeover
            "takeover_when_disconnected" -> config.takeoverWhenDisconnected =
                preferences.getBoolean(key, true)

            "takeover_when_idle" -> config.takeoverWhenIdle = preferences.getBoolean(key, true)
            "takeover_when_music" -> config.takeoverWhenMusic = preferences.getBoolean(key, false)
            "takeover_when_call" -> config.takeoverWhenCall = preferences.getBoolean(key, true)

            // Phone state-based takeover
            "takeover_when_ringing_call" -> config.takeoverWhenRingingCall =
                preferences.getBoolean(key, true)

            "takeover_when_media_start" -> config.takeoverWhenMediaStart =
                preferences.getBoolean(key, true)

            "left_single_press_action" -> {
                config.leftSinglePressAction = StemAction.fromString(
                    preferences.getString(key, "PLAY_PAUSE") ?: "PLAY_PAUSE"
                )!!
                setupStemActions()
            }

            "right_single_press_action" -> {
                config.rightSinglePressAction = StemAction.fromString(
                    preferences.getString(key, "PLAY_PAUSE") ?: "PLAY_PAUSE"
                )!!
                setupStemActions()
            }

            "left_double_press_action" -> {
                config.leftDoublePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                )!!
                setupStemActions()
            }

            "right_double_press_action" -> {
                config.rightDoublePressAction = StemAction.fromString(
                    preferences.getString(key, "NEXT_TRACK") ?: "NEXT_TRACK"
                )!!
                setupStemActions()
            }

            "left_triple_press_action" -> {
                config.leftTriplePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                )!!
                setupStemActions()
            }

            "right_triple_press_action" -> {
                config.rightTriplePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                )!!
                setupStemActions()
            }

            "left_long_press_action" -> {
                config.leftLongPressAction = StemAction.fromString(
                    preferences.getString(key, "CYCLE_NOISE_CONTROL_MODES")
                        ?: "CYCLE_NOISE_CONTROL_MODES"
                )!!
                setupStemActions()
            }

            "right_long_press_action" -> {
                config.rightLongPressAction = StemAction.fromString(
                    preferences.getString(key, "DIGITAL_ASSISTANT") ?: "DIGITAL_ASSISTANT"
                )!!
                setupStemActions()
            }

            "camera_action" -> config.cameraAction =
                preferences.getString(key, null)?.let { StemPressType.valueOf(it) }

            // AirPods device information
            "airpods_name" -> config.airpodsName = preferences.getString(key, "") ?: ""
            "airpods_model_number" -> config.airpodsModelNumber =
                preferences.getString(key, "") ?: ""

            "airpods_manufacturer" -> config.airpodsManufacturer =
                preferences.getString(key, "") ?: ""

            "airpods_serial_number" -> config.airpodsSerialNumber =
                preferences.getString(key, "") ?: ""

            "airpods_left_serial_number" -> config.airpodsLeftSerialNumber =
                preferences.getString(key, "") ?: ""

            "airpods_right_serial_number" -> config.airpodsRightSerialNumber =
                preferences.getString(key, "") ?: ""

            "airpods_version1" -> config.airpodsVersion1 = preferences.getString(key, "") ?: ""
            "airpods_version2" -> config.airpodsVersion2 = preferences.getString(key, "") ?: ""
            "airpods_version3" -> config.airpodsVersion3 = preferences.getString(key, "") ?: ""
            "airpods_hardware_revision" -> config.airpodsHardwareRevision =
                preferences.getString(key, "") ?: ""

            "airpods_updater_identifier" -> config.airpodsUpdaterIdentifier =
                preferences.getString(key, "") ?: ""

            "self_mac_address" -> config.selfMacAddress = preferences.getString(key, "") ?: ""
        }
    }

    private fun logPacket(packet: ByteArray, @Suppress("SameParameterValue") source: String) {
        val packetHex = packet.joinToString(" ") { "%02X".format(it) }
        val logEntry = "$source: $packetHex"

        synchronized(inMemoryLogs) {
            inMemoryLogs.add(logEntry)
            if (inMemoryLogs.size > maxLogEntries) {
                inMemoryLogs.iterator().next().let {
                    inMemoryLogs.remove(it)
                }
            }

            _packetLogsFlow.value = inMemoryLogs.toSet()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val logs =
                sharedPreferencesLogs.getStringSet(packetLogKey, mutableSetOf())?.toMutableSet()
                    ?: mutableSetOf()
            logs.add(logEntry)

            if (logs.size > maxLogEntries) {
                val toKeep = logs.toList().takeLast(maxLogEntries).toSet()
                sharedPreferencesLogs.edit { putStringSet(packetLogKey, toKeep) }
            } else {
                sharedPreferencesLogs.edit { putStringSet(packetLogKey, logs) }
            }
        }
    }

    private fun clearPacketLogs() {
        synchronized(inMemoryLogs) {
            inMemoryLogs.clear()
            _packetLogsFlow.value = emptySet()
        }
        sharedPreferencesLogs.edit { remove(packetLogKey) }
    }

    fun clearLogs() {
        clearPacketLogs()
        _packetLogsFlow.value = emptySet()
    }

    override fun onBind(intent: Intent?): IBinder {
        return LocalBinder()
    }

    private var gestureDetector: GestureDetector? = null
    private var isInCall = false
    private var callNumber: String? = null

    private fun initGestureDetector() {
        if (gestureDetector == null) {
            gestureDetector = GestureDetector(this)
        }
    }


    var popupShown = false
    fun showPopup(service: Service, name: String) {
        if (!sharedPreferences.getBoolean("show_bottom_sheet_popup", true)) {
            return
        }
        if (!Settings.canDrawOverlays(service)) {
            Log.d(TAG, "No permission for SYSTEM_ALERT_WINDOW")
            return
        }
        if (popupShown) {
            return
        }
        val popupWindow = PopupWindow(service.applicationContext)
        popupWindow.open(name, batteryNotification)
        popupShown = true
    }

    var islandOpen = false
    var islandWindow: IslandWindow? = null

    @SuppressLint("MissingPermission")
    fun showIsland(
        service: Service,
        batteryPercentage: Int,
        type: IslandType = IslandType.CONNECTED,
        reversed: Boolean = false,
        otherDeviceName: String? = null
    ) {
        Log.d(TAG, "Showing island window")
        if (!sharedPreferences.getBoolean("show_island_popup", true)) {
            return
        }
        if (!Settings.canDrawOverlays(service)) {
            Log.d(TAG, "No permission for SYSTEM_ALERT_WINDOW")
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            islandWindow = IslandWindow(service.applicationContext)
            islandWindow!!.show(
                sharedPreferences.getString("name", "AirPods Pro").toString(),
                batteryPercentage,
                this@AirPodsService,
                type,
                reversed,
                otherDeviceName
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    //    var isConnectedLocally = false
    var device: BluetoothDevice? = null

    private lateinit var earReceiver: BroadcastReceiver
    var widgetMobileBatteryEnabled = false

    object BatteryChangedIntentReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                ServiceManager.getService()?.updateBattery()
            } else if (intent.action == AirPodsNotifications.DISCONNECT_RECEIVERS) {
                try {
                    context?.unregisterReceiver(this)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun startForegroundNotification() {
        val disconnectedNotificationChannel = NotificationChannel(
            "background_service_status",
            "Background Service Status",
            NotificationManager.IMPORTANCE_NONE
        )

        val connectedNotificationChannel = NotificationChannel(
            "airpods_connection_status",
            "AirPods Connection Status",
            NotificationManager.IMPORTANCE_LOW,
        )

        val socketFailureChannel = NotificationChannel(
            "socket_connection_failure",
            "AirPods BluetoothConnectionManager.aacpSocket? Connection Issues",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications about problems connecting to AirPods protocol"
            enableLights(true)
            lightColor = Color.RED
            enableVibration(true)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(disconnectedNotificationChannel)
        notificationManager.createNotificationChannel(connectedNotificationChannel)
        notificationManager.createNotificationChannel(socketFailureChannel)

        val notificationSettingsIntent =
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, "background_service_status")
            }
        val pendingIntentNotifDisable = PendingIntent.getActivity(
            this,
            0,
            notificationSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "background_service_status")
            .setSmallIcon(R.drawable.airpods).setContentTitle("Background Service Running")
            .setContentText("Useless notification, disable it by clicking on it.")
            .setContentIntent(pendingIntentNotifDisable).setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()

        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Suppress("KotlinUnreachableCode")
    @OptIn(ExperimentalMaterial3Api::class)
    private fun showSocketConnectionFailureNotification(errorMessage: String) {
        return // something causes too many notifications. turning off for now
        if (BuildConfig.FLAVOR != "xposed") {
            Log.w(
                TAG,
                "Not showing BluetoothConnectionManager.aacpSocket? error notification to user, the service shouldn't be running if it isn't supported."
            )
            return
        }
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "socket_connection_failure")
            .setSmallIcon(R.drawable.airpods).setContentTitle("AirPods Connection Issue")
            .setContentText("Unable to connect to AirPods over L2CAP").setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Your AirPods are connected via Bluetooth, but LibrePods couldn't connect to AirPods using L2CAP. Error: $errorMessage"
                )
            ).setContentIntent(pendingIntent).setCategory(Notification.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()

        notificationManager.notify(3, notification)
    }

    fun sendANCBroadcast() {
        sendBroadcast(Intent(AirPodsNotifications.ANC_DATA).apply {
            putExtra("data", ancNotification.status)
            setPackage(packageName)
        })
        sendMiLinkBridgeState("ANC status changed")
    }

    fun sendBatteryBroadcast() {
        broadcastBatteryInformation()
        sendBroadcast(Intent(AirPodsNotifications.BATTERY_DATA).apply {
            putParcelableArrayListExtra("data", ArrayList(batteryNotification.getBattery()))
            setPackage(packageName)
        })
        sendMiLinkBridgeState("battery status changed")
    }

    private fun handleMiLinkAncCommand(intent: Intent) {
        val protocolVersion = intent.getIntExtra(
            MiLinkAirPodsBridgeContract.EXTRA_PROTOCOL_VERSION,
            -1,
        )
        val expectedToken = getMiLinkBridgeToken()
        val receivedToken = intent.getStringExtra(MiLinkAirPodsBridgeContract.EXTRA_TOKEN)
        val requestedAddress = intent.getStringExtra(MiLinkAirPodsBridgeContract.EXTRA_ADDRESS)
        val currentAddress = device?.address ?: macAddress
        val requestedMode = intent.getIntExtra(
            MiLinkAirPodsBridgeContract.EXTRA_ANC_MODE,
            -1,
        )
        val validRequest = protocolVersion == MiLinkAirPodsBridgeContract.PROTOCOL_VERSION &&
            receivedToken == expectedToken &&
            requestedAddress.equals(currentAddress, ignoreCase = true) &&
            MiLinkAncModeMapper.isSelectableLibrePodsMode(requestedMode)
        val controlSessionAvailable =
            BluetoothConnectionManager.aacpSocket?.isConnected == true && device != null

        if (!validRequest || !controlSessionAvailable) {
            Log.w(
                TAG,
                "MiLink ANC command rejected: valid=$validRequest, " +
                    "controlSessionAvailable=$controlSessionAvailable",
            )
            sendMiLinkBridgeState("ANC command rejected")
            return
        }

        Log.i(TAG, "MiLink ANC command accepted: mode=$requestedMode")
        aacpManager.sendControlCommand(
            AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
            requestedMode,
        )
    }

    private fun handleMiLinkSpatialAudioCommand(intent: Intent) {
        val protocolVersion = intent.getIntExtra(
            MiLinkAirPodsBridgeContract.EXTRA_PROTOCOL_VERSION,
            -1,
        )
        val expectedToken = getMiLinkBridgeToken()
        val receivedToken = intent.getStringExtra(MiLinkAirPodsBridgeContract.EXTRA_TOKEN)
        val requestedAddress = intent.getStringExtra(MiLinkAirPodsBridgeContract.EXTRA_ADDRESS)
        val currentAddress = device?.address ?: macAddress
        val requestedMode = intent.getIntExtra(
            MiLinkAirPodsBridgeContract.EXTRA_SPATIAL_AUDIO_MODE,
            -1,
        )
        val validRequest = protocolVersion == MiLinkAirPodsBridgeContract.PROTOCOL_VERSION &&
            receivedToken == expectedToken &&
            requestedAddress.equals(currentAddress, ignoreCase = true) &&
            MiLinkSpatialAudioModeMapper.isSelectableLibrePodsMode(requestedMode) &&
            miLinkSpatialAudioCapabilityChecked && miLinkSpatialAudioAvailable
        val connected = device != null && BluetoothConnectionManager.aacpSocket?.isConnected == true

        if (!validRequest || !connected) {
            Log.w(
                TAG,
                "MiLink spatial audio command rejected: valid=$validRequest, connected=$connected",
            )
            sendMiLinkBridgeState("spatial audio command rejected")
            return
        }

        val mode = when (requestedMode) {
            MiLinkSpatialAudioModeMapper.LIBREPODS_FIXED -> SpatialAudioMode.FIXED
            MiLinkSpatialAudioModeMapper.LIBREPODS_HEAD_TRACKED ->
                SpatialAudioMode.HEAD_TRACKED
            else -> SpatialAudioMode.OFF
        }
        Log.i(TAG, "MiLink spatial audio command accepted: mode=$mode")
        val previousMode = SpatialAudioMode.fromPreferences(sharedPreferences)
        sharedPreferences.edit {
            putString(SpatialAudioMode.PREFERENCE_KEY, mode.preferenceValue)
        }
        if (previousMode == mode) {
            updateSpatialAudioTracking("MiLink selected current spatial mode")
            sendMiLinkBridgeState("spatial audio command unchanged")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendMiLinkBridgeState(reason: String) {
        val batteries = batteryNotification.getBattery()
        fun battery(component: Int): Battery? = batteries.firstOrNull { it.component == component }
        fun level(value: Battery?): Int =
            if (value == null || value.status == BatteryStatus.DISCONNECTED) -1
            else value.level.coerceIn(0, 100)
        fun charging(value: Battery?): Boolean =
            value?.status == BatteryStatus.CHARGING ||
                value?.status == BatteryStatus.OPTIMIZED_CHARGING

        val left = battery(BatteryComponent.LEFT)
        val right = battery(BatteryComponent.RIGHT)
        val case = battery(BatteryComponent.CASE)
        val currentAddress = device?.address ?: macAddress
        if (currentAddress.isBlank()) return

        sendBroadcast(
            Intent(MiLinkAirPodsBridgeContract.ACTION_STATE_CHANGED).apply {
                setPackage(MiLinkAirPodsBridgeContract.MI_LINK_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(
                    MiLinkAirPodsBridgeContract.EXTRA_PROTOCOL_VERSION,
                    MiLinkAirPodsBridgeContract.PROTOCOL_VERSION,
                )
                putExtra(MiLinkAirPodsBridgeContract.EXTRA_TOKEN, getMiLinkBridgeToken())
                putExtra(MiLinkAirPodsBridgeContract.EXTRA_ADDRESS, currentAddress)
                putExtra(
                    MiLinkAirPodsBridgeContract.EXTRA_NAME,
                    sharedPreferences.getString("name", device?.name ?: "AirPods") ?: "AirPods",
                )
                putExtra(
                    MiLinkAirPodsBridgeContract.EXTRA_CONNECTED,
                    device != null && BluetoothConnectionManager.aacpSocket?.isConnected == true,
                )
                putExtra(MiLinkAirPodsBridgeContract.EXTRA_ANC_MODE, ancNotification.status)
                putExtra(MiLinkAirPodsBridgeContract.EXTRA_STATE_REASON, reason)
                putExtra(
                    MiLinkAirPodsBridgeContract.EXTRA_SPATIAL_AUDIO_MODE,
                    when (SpatialAudioMode.fromPreferences(sharedPreferences)) {
                        SpatialAudioMode.OFF ->
                            MiLinkSpatialAudioModeMapper.LIBREPODS_OFF
                        SpatialAudioMode.FIXED ->
                            MiLinkSpatialAudioModeMapper.LIBREPODS_FIXED
                        SpatialAudioMode.HEAD_TRACKED ->
                            MiLinkSpatialAudioModeMapper.LIBREPODS_HEAD_TRACKED
                    },
                )
                putExtra(
                    MiLinkAirPodsBridgeContract.EXTRA_SPATIAL_AUDIO_AVAILABLE,
                    miLinkSpatialAudioCapabilityChecked && miLinkSpatialAudioAvailable,
                )
                putExtra(MiLinkAirPodsBridgeContract.EXTRA_LEFT_BATTERY, level(left))
                putExtra(MiLinkAirPodsBridgeContract.EXTRA_RIGHT_BATTERY, level(right))
                putExtra(MiLinkAirPodsBridgeContract.EXTRA_CASE_BATTERY, level(case))
                putExtra(MiLinkAirPodsBridgeContract.EXTRA_LEFT_CHARGING, charging(left))
                putExtra(MiLinkAirPodsBridgeContract.EXTRA_RIGHT_CHARGING, charging(right))
                putExtra(MiLinkAirPodsBridgeContract.EXTRA_CASE_CHARGING, charging(case))
            },
        )
        Log.d(TAG, "MiLink bridge state sent: $reason")
    }

    private fun refreshMiLinkSpatialAudioCapability(reason: String) {
        if (miLinkSpatialAudioCapabilityJob?.isActive == true) return
        miLinkSpatialAudioCapabilityJob = audioFeatureScope.launch(Dispatchers.IO) {
            val result = spatialAudioController.query()
            miLinkSpatialAudioAvailable = MiLinkSpatialAudioAvailability.isPanelAvailable(
                capabilityChecked = true,
                spatializerAvailable = result.spatializerAvailable,
                helperAvailable = result.helperAvailable,
                error = result.error,
            )
            miLinkSpatialAudioCapabilityChecked = true
            Log.i(
                TAG,
                "MiLink spatial capability for '$reason': " +
                    "available=${result.spatializerAvailable}, " +
                    "helper=${result.helperAvailable}, error=${result.error}",
            )
            withContext(Dispatchers.Main) {
                sendMiLinkBridgeState("spatial audio capability checked")
            }
        }
    }

    private fun getMiLinkBridgeToken(): String {
        val prefs = getSharedPreferences("milink_airpods_bridge", MODE_PRIVATE)
        return prefs.getString("token", null) ?: UUID.randomUUID().toString().also { token ->
            prefs.edit { putString("token", token) }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendBatteryNotification() {
        updateNotificationContent(
            true,
            getSharedPreferences("settings", MODE_PRIVATE).getString("name", device?.name),
            batteryNotification.getBattery()
        )
    }

    fun setBatteryMetadata() {
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
            device?.let { it ->
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_CASE_BATTERY,
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.CASE }?.level.toString()
                        .toByteArray()
                )
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_CASE_CHARGING,
                    (if (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.CASE }?.status == BatteryStatus.CHARGING
                    ) "1".toByteArray() else "0".toByteArray())
                )
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_LEFT_BATTERY,
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.level.toString()
                        .toByteArray()
                )
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_LEFT_CHARGING,
                    (if (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.CHARGING
                    ) "1".toByteArray() else "0".toByteArray())
                )
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_RIGHT_BATTERY,
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.RIGHT }?.level.toString()
                        .toByteArray()
                )
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_RIGHT_CHARGING,
                    (if (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.CHARGING
                    ) "1".toByteArray() else "0".toByteArray())
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun updateBatteryWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, BatteryWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

        val remoteViews = RemoteViews(packageName, R.layout.battery_widget).also { it ->
            val openActivityIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            it.setOnClickPendingIntent(R.id.battery_widget, openActivityIntent)

            val leftBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT }
            val rightBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT }
            val caseBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.CASE }

            it.setTextViewText(R.id.left_battery_widget, leftBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.left_battery_progress, 100, leftBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.left_charging_icon,
                if (leftBattery?.status == BatteryStatus.CHARGING || leftBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.right_battery_widget, rightBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.right_battery_progress, 100, rightBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.right_charging_icon,
                if (rightBattery?.status == BatteryStatus.CHARGING || rightBattery?.status == BatteryStatus.OPTIMIZED_CHARGING ) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.case_battery_widget, caseBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.case_battery_progress, 100, caseBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.case_charging_icon,
                if (caseBattery?.status == BatteryStatus.CHARGING || caseBattery?.status == BatteryStatus.OPTIMIZED_CHARGING ) View.VISIBLE else View.GONE
            )

            it.setViewVisibility(
                R.id.phone_battery_widget_container,
                if (widgetMobileBatteryEnabled) View.VISIBLE else View.GONE
            )
            if (widgetMobileBatteryEnabled) {
                val batteryManager = getSystemService(BatteryManager::class.java)
                val batteryLevel =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
                it.setTextViewText(
                    R.id.phone_battery_widget, "$batteryLevel%"
                )
                it.setViewVisibility(
                    R.id.phone_charging_icon, if (charging) View.VISIBLE else View.GONE
                )
                it.setProgressBar(
                    R.id.phone_battery_progress, 100, batteryLevel, false
                )
            }
        }
        appWidgetManager.updateAppWidget(widgetIds, remoteViews)
    }

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalMaterial3Api::class)
    fun updateBattery() {
        setBatteryMetadata()
        updateBatteryWidget()
        sendBatteryBroadcast()
        sendBatteryNotification()
    }

    fun updateNoiseControlWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, NoiseControlWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
        val remoteViews = RemoteViews(packageName, R.layout.noise_control_widget).also { it ->
            val ancStatus = ancNotification.status
            val allowOffModeValue =
                aacpManager.controlCommandStatusList.find { it.identifier == AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION }
            val allowOffMode =
                allowOffModeValue?.value?.takeIf { it.isNotEmpty() }?.get(0) == 0x01.toByte() || sharedPreferences.getBoolean("off_listening_mode", true)
            it.setInt(
                R.id.widget_off_button,
                "setBackgroundResource",
                if (ancStatus == 1) R.drawable.widget_button_checked_shape_start else R.drawable.widget_button_shape_start
            )
            it.setInt(
                R.id.widget_transparency_button,
                "setBackgroundResource",
                if (ancStatus == 3) (if (allowOffMode) R.drawable.widget_button_checked_shape_middle else R.drawable.widget_button_checked_shape_start) else (if (allowOffMode) R.drawable.widget_button_shape_middle else R.drawable.widget_button_shape_start)
            )
            it.setInt(
                R.id.widget_adaptive_button,
                "setBackgroundResource",
                if (ancStatus == 4) R.drawable.widget_button_checked_shape_middle else R.drawable.widget_button_shape_middle
            )
            it.setInt(
                R.id.widget_anc_button,
                "setBackgroundResource",
                if (ancStatus == 2) R.drawable.widget_button_checked_shape_end else R.drawable.widget_button_shape_end
            )
            it.setViewVisibility(
                R.id.widget_off_button, if (allowOffMode) View.VISIBLE else View.GONE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                it.setViewLayoutMargin(
                    R.id.widget_transparency_button,
                    RemoteViews.MARGIN_START,
                    if (allowOffMode) 2f else 12f,
                    TypedValue.COMPLEX_UNIT_DIP
                )
            } else {
                it.setViewPadding(
                    R.id.widget_transparency_button,
                    if (allowOffMode) 2.dpToPx() else 12.dpToPx(),
                    12.dpToPx(),
                    2.dpToPx(),
                    12.dpToPx()
                )
            }
        }

        appWidgetManager.updateAppWidget(widgetIds, remoteViews)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun updateNotificationContent(
        connected: Boolean, airpodsName: String? = null, batteryList: List<Battery>? = null
    ) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (BluetoothConnectionManager.aacpSocket == null) {
            return
        }
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
            val updatedNotificationBuilder =
                NotificationCompat.Builder(this, "airpods_connection_status")
                    .setSmallIcon(R.drawable.airpods)
                    .setContentTitle(airpodsName ?: config.deviceName).setContentText(
                        """${
                        batteryList?.find { it.component == BatteryComponent.LEFT }?.let {
                            if (it.status != BatteryStatus.DISCONNECTED) {
                                "L: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                            } else {
                                ""
                            }
                        } ?: ""
                    } ${
                        batteryList?.find { it.component == BatteryComponent.RIGHT }?.let {
                            if (it.status != BatteryStatus.DISCONNECTED) {
                                "R: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                            } else {
                                ""
                            }
                        } ?: ""
                    } ${
                        batteryList?.find { it.component == BatteryComponent.CASE }?.let {
                            if (it.status != BatteryStatus.DISCONNECTED) {
                                "Case: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                            } else {
                                ""
                            }
                        } ?: ""
                    }""").setContentIntent(pendingIntent).setCategory(Notification.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true)

            if (disconnectedBecauseReversed) {
                updatedNotificationBuilder.addAction(
                    R.drawable.ic_bluetooth, "Reconnect", PendingIntent.getService(
                        this, 0, Intent(this, AirPodsService::class.java).apply {
                            action = "me.kavishdevar.librepods.RECONNECT_AFTER_REVERSE"
                        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            val updatedNotification = updatedNotificationBuilder.build()

            notificationManager.notify(2, updatedNotification)
            notificationManager.cancel(1)
        } else if (!connected) {
            notificationManager.cancel(2)
        } else if (!config.bleOnlyMode && BluetoothConnectionManager.aacpSocket?.isConnected != true) {
            showSocketConnectionFailureNotification("BluetoothConnectionManager.aacpSocket? created, but not connected. Check logs")
        }
    }

    fun handleIncomingCall() {
        if (isInCall) return
        if (config.headGestures) {
            initGestureDetector()
            startHeadTracking()
            gestureDetector?.startDetection { accepted ->
                if (accepted) {
                    answerCall()
                    handleIncomingCallOnceConnected = false
                } else {
                    rejectCall()
                    handleIncomingCallOnceConnected = false
                }
            }

        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun testHeadGestures(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            gestureDetector?.startDetection(doNotStop = true) { accepted ->
                if (continuation.isActive) {
                    continuation.resume(accepted) { _, _, _ ->
                        gestureDetector?.stopDetection()
                    }
                }
            }
        }
    }

    private fun answerCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.acceptRingingCall() // TODO: Switch to InCallService (needs CDM association)
                }
            } else {
                val telephonyService = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val telephonyClass = Class.forName(telephonyService.javaClass.name)
                val method = telephonyClass.getDeclaredMethod("getITelephony")
                method.isAccessible = true
                val telephonyInterface = method.invoke(telephonyService)
                val answerCallMethod =
                    telephonyInterface.javaClass.getDeclaredMethod("answerRingingCall")
                answerCallMethod.invoke(telephonyInterface)
            }

            sendToast("Call answered via head gesture")
        } catch (e: Exception) {
            e.printStackTrace()
            sendToast("Failed to answer call: ${e.message}")
        } finally {
            islandWindow?.close()
        }
    }

    private fun rejectCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.endCall() // TODO: Switch to InCallService (needs CDM association)
                }
            } else {
                val telephonyService = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val telephonyClass = Class.forName(telephonyService.javaClass.name)
                val method = telephonyClass.getDeclaredMethod("getITelephony")
                method.isAccessible = true
                val telephonyInterface = method.invoke(telephonyService)
                val endCallMethod = telephonyInterface.javaClass.getDeclaredMethod("endCall")
                endCallMethod.invoke(telephonyInterface)
            }

            sendToast("Call rejected via head gesture")
        } catch (e: Exception) {
            e.printStackTrace()
            sendToast("Failed to reject call: ${e.message}")
        } finally {
            islandWindow?.close()
        }
    }

    fun sendToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun processHeadTrackingData(data: ByteArray) {
        val horizontal = ByteBuffer.wrap(data, 51, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val vertical = ByteBuffer.wrap(data, 53, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        try {
            gestureDetector?.processHeadOrientation(horizontal, vertical)
        } catch (e: Exception) {
            Log.w(TAG, "gesture detector on ${data.toHexString()}: ${e.message}")
        }
    }

    private lateinit var connectionReceiver: BroadcastReceiver

    private fun resToUri(resId: Int): Uri? {
        return try {
            Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority("me.kavishdevar.librepods")
                .appendPath(applicationContext.resources.getResourceTypeName(resId))
                .appendPath(applicationContext.resources.getResourceEntryName(resId)).build()
        } catch (_: Resources.NotFoundException) {
            null
        }
    }

    @Suppress("PrivatePropertyName")
    private val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV = "+IPHONEACCEV"

    @Suppress("PrivatePropertyName")
    private val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL = 1

    @Suppress("PrivatePropertyName")
    private val APPLE = 0x004C

    @Suppress("PrivatePropertyName")
    private val ACTION_BATTERY_LEVEL_CHANGED =
        "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"

    @Suppress("PrivatePropertyName")
    private val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    @Suppress("PrivatePropertyName")
    private val PACKAGE_ASI = "com.google.android.settings.intelligence"

    @Suppress("PrivatePropertyName")
    private val ACTION_ASI_UPDATE_BLUETOOTH_DATA = "batterywidget.impl.action.update_bluetooth_data"

    @SuppressLint("MissingPermission")
    fun broadcastBatteryInformation() {
        if (device == null || checkSelfPermission("android.permission.INTERACT_ACROSS_USERS") != PackageManager.PERMISSION_GRANTED) return

        val batteryList = batteryNotification.getBattery()
        val leftBattery = batteryList.find { it.component == BatteryComponent.LEFT }
        val rightBattery = batteryList.find { it.component == BatteryComponent.RIGHT }

        // Calculate unified battery level (minimum of left and right)
        val batteryUnified = minOf(
            leftBattery?.level ?: 100, rightBattery?.level ?: 100
        )

        // Check charging status
        val isLeftCharging = leftBattery?.status == BatteryStatus.CHARGING
        val isRightCharging = rightBattery?.status == BatteryStatus.CHARGING
        isLeftCharging && isRightCharging

        // Create arguments for vendor-specific event
        val arguments = arrayOf<Any>(
            1, // Number of key/value pairs
            VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL, // IndicatorType: Battery Level
            batteryUnified // Battery Level
        )

        // Broadcast vendor-specific event
        val intent = Intent(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT).apply {
            putExtra(
                BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD,
                VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV
            )
            putExtra(
                BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE,
                BluetoothHeadset.AT_CMD_TYPE_SET
            )
            putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS, arguments)
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(BluetoothDevice.EXTRA_NAME, device?.name)
            addCategory("${BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY}.$APPLE")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sendBroadcastAsUser(
                    intent,
                    UserHandle.getUserHandleForUid(-1),
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(-1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send vendor-specific event: ${e.message}")
        }

        // Broadcast battery level changes
        val batteryIntent = Intent(ACTION_BATTERY_LEVEL_CHANGED).apply {
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(EXTRA_BATTERY_LEVEL, batteryUnified)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sendBroadcast(batteryIntent, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                sendBroadcastAsUser(batteryIntent, UserHandle.getUserHandleForUid(-1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send battery level broadcast: ${e.message}")
        }

        // Update Android Settings Intelligence's battery widget
        val statusIntent = Intent(ACTION_ASI_UPDATE_BLUETOOTH_DATA).apply {
            setPackage(PACKAGE_ASI)
            putExtra(ACTION_BATTERY_LEVEL_CHANGED, intent)
        }

        try {
            sendBroadcastAsUser(statusIntent, UserHandle.getUserHandleForUid(-1))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ASI battery level broadcast: ${e.message}")
        }

        Log.d(TAG, "Broadcast battery level $batteryUnified% to system")
    }

    private fun setMetadatas(d: BluetoothDevice) {
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "no permission BLUETOOTH_PRIVILEGED, returning")
            return
        }
        Log.d(TAG, "has permission BLUETOOTH_PRIVILEGED, proceeding")
        d.let { device ->
            val instance = airpodsInstance
            if (instance != null) {
                val metadataSet = SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_MAIN_ICON,
                    resToUri(instance.model.budCaseRes).toString().toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device, device.METADATA_MODEL_NAME, instance.model.name.toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_DEVICE_TYPE,
                    device.DEVICE_TYPE_UNTETHERED_HEADSET.toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_CASE_ICON,
                    resToUri(instance.model.caseRes).toString().toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_RIGHT_ICON,
                    resToUri(instance.model.rightBudsRes).toString().toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_LEFT_ICON,
                    resToUri(instance.model.leftBudsRes).toString().toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_MANUFACTURER_NAME,
                    instance.model.manufacturer.toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device, device.METADATA_COMPANION_APP, "me.kavishdevar.librepods".toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                )
                Log.d(TAG, "Metadata set: $metadataSet")
            } else {
                Log.w(
                    TAG,
                    "AirPods demoInstance is not of type AirPodsInstance, skipping metadata setting"
                )
            }
        }
    }

    @Suppress("ClassName")
    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent) {
            val bluetoothDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    "android.bluetooth.device.extra.DEVICE", BluetoothDevice::class.java
                )
            } else {
                intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE") as BluetoothDevice?
            }
            val action = intent.action
            val context = context?.applicationContext
            val name = context?.getSharedPreferences("settings", MODE_PRIVATE)
                ?.getString("name", bluetoothDevice?.name)
            if (bluetoothDevice != null && !action.isNullOrEmpty()) {
                Log.d(TAG, "Received bluetooth connection broadcast: action=$action")
                val uuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")

                if (BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED == action) {
                    val airPodsMac = this@AirPodsService.device?.address ?: macAddress
                    if (bluetoothDevice.address.equals(airPodsMac, ignoreCase = true)) {
                        val previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                        traceConnectionEvent("hfp_audio_state_changed", "previous=$previous state=$state inCall=$isInCall")
                    }
                } else if (BluetoothDevice.ACTION_ACL_CONNECTED == action) {
                    if (bluetoothDevice.uuids?.contains(uuid) == true) {
                        val intent = Intent(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
                        intent.putExtra("name", name)
                        intent.putExtra("device", bluetoothDevice)
                        context?.sendBroadcast(intent)
                    } else {
                        bluetoothDevice.fetchUuidsWithSdp()
                    }
                } else if ("android.bluetooth.device.action.UUID" == action) {
                    val savedMac = context?.getSharedPreferences("settings", MODE_PRIVATE)
                        ?.getString("mac_address", "") ?: ""
                    val matchedByMac = savedMac.isNotEmpty() && bluetoothDevice.address == savedMac
                    val matchedByUuid = bluetoothDevice.uuids?.contains(uuid) == true
                    if (matchedByUuid || matchedByMac) {
                        val intent = Intent(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
                        intent.putExtra("name", name)
                        intent.putExtra("device", bluetoothDevice)
                        context?.sendBroadcast(intent)
                    }
                } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED == action) {
                    val state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED
                    )
                    val airPodsMac = this@AirPodsService.device?.address ?: macAddress
                    val isCurrentAirPods = airPodsMac.isNotEmpty() &&
                        bluetoothDevice.address.equals(airPodsMac, ignoreCase = true)
                    if (isCurrentAirPods && state == BluetoothProfile.STATE_CONNECTED) {
                        refreshCurrentA2dpPlaybackState("AirPods A2DP connected")
                    } else if (isCurrentAirPods && state == BluetoothProfile.STATE_DISCONNECTED) {
                        isAirPodsA2dpPlaying = false
                        cancelPendingAirPodsAbsoluteVolumeResync("AirPods A2DP disconnected")
                        updateSpatialAudioTracking("AirPods A2DP disconnected")
                    }
                } else if (BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED == action) {
                    val state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE, BluetoothA2dp.STATE_NOT_PLAYING
                    )
                    val airPodsMac = this@AirPodsService.device?.address ?: macAddress
                    val isCurrentAirPods = airPodsMac.isNotEmpty() &&
                        bluetoothDevice.address.equals(airPodsMac, ignoreCase = true)
                    if (isCurrentAirPods && state == BluetoothA2dp.STATE_PLAYING) {
                        isAirPodsA2dpPlaying = true
                        remoteStreamingDevices.clear()
                        Log.d(TAG, "Local AirPods A2DP started playing; reclaiming AACP control")
                        otherDeviceTookOver = false
                        aacpManager.sendControlCommand(
                            AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                            byteArrayOf(0x01)
                        )
                        if (localMac.isNotEmpty()) {
                            aacpManager.sendMediaInformataion(localMac)
                        }
                        refreshAacpControlSession("local AirPods A2DP playback started")
                        // Smart routing can emit a transient PLAYING state before
                        // the AirPods have accepted this phone's AVRCP ownership.
                        // Give the AACP ownership/source response the first chance
                        // to trigger a direct resend, then use this as a fallback.
                        scheduleAirPodsAbsoluteVolumeResync(
                            "A2DP playback started",
                            A2DP_DIRECT_VOLUME_RESYNC_DELAY_MS
                        )
                        if (SpatialAudioMode.fromPreferences(sharedPreferences) ==
                            SpatialAudioMode.HEAD_TRACKED
                        ) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                updateSpatialAudioTracking("A2DP playback started")
                            }, 300)
                        }
                    } else if (isCurrentAirPods && state == BluetoothA2dp.STATE_NOT_PLAYING) {
                        isAirPodsA2dpPlaying = false
                        prewarmAirPodsAbsoluteVolumeResync("AirPods A2DP stopped")
                        cancelPendingAirPodsAbsoluteVolumeResync("AirPods A2DP stopped")
                        updateSpatialAudioTracking("A2DP playback stopped")
                    }
                }
            }
        }
    }

    val externalBroadcastFilter = IntentFilter().apply {
        addAction("me.kavishdevar.librepods.SET_ANC_MODE")
        addAction("me.kavishdevar.librepods.CONVO_DETECT")
        addAction(MiLinkAirPodsBridgeContract.ACTION_REQUEST_STATE)
        addAction(MiLinkAirPodsBridgeContract.ACTION_SET_ANC)
        addAction(MiLinkAirPodsBridgeContract.ACTION_SET_SPATIAL_AUDIO)
    }
    var externalBroadcastReceiver: BroadcastReceiver? = null

    @SuppressLint("InlinedApi", "MissingPermission", "UnspecifiedRegisterReceiverFlag")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with intent action: ${intent?.action}")

        when (intent?.action) {
            "me.kavishdevar.librepods.RECONNECT_AFTER_REVERSE" -> {
                Log.d(TAG, "reconnect after reversed received, taking over")
                disconnectedBecauseReversed = false
                otherDeviceTookOver = false
                takeOver("music", manualTakeOverAfterReversed = true)
            }

            ACTION_HEART_RATE_PROBE_START -> setHeartRateMonitoringEnabled(true)
            ACTION_HEART_RATE_PROBE_STOP -> setHeartRateMonitoringEnabled(false)
            ACTION_HEART_RATE_PROBE_STATUS -> logHeartRateProbeStatus("requested")
        }

        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission", "HardwareIds")
    fun takeOver(
        takingOverFor: String,
        manualTakeOverAfterReversed: Boolean = false
    ) {
        val manualTakeover = takingOverFor == "reverse" || manualTakeOverAfterReversed
        val automaticTakeoverEnabled = sharedPreferences.getBoolean(
            "smart_routing_auto_takeover", false
        )

        if (!manualTakeover && !automaticTakeoverEnabled) {
            Log.d(TAG, "Not taking over: automatic Smart Routing takeover is disabled")
            return
        }

        val shouldTakeOverPhoneState = when (takingOverFor) {
            "music" -> config.takeoverWhenMediaStart
            "call" -> config.takeoverWhenRingingCall
            else -> false
        }

        if (!manualTakeover && !shouldTakeOverPhoneState) {
            Log.d(TAG, "Not taking over audio: phone state takeover is disabled")
            return
        }

        if (takingOverFor == "reverse") {
            aacpManager.sendControlCommand(
                AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value, 1
            )
            aacpManager.sendMediaInformataion(
                localMac
            )
            aacpManager.sendHijackReversed(
                localMac
            )
            connectAudio(
                this@AirPodsService, device
            )
            otherDeviceTookOver = false
            refreshAacpControlSession("manual Smart Routing takeover")
            Log.d(TAG, "Manual Smart Routing takeover request sent")
            return
        }
        val ownsConnection = aacpManager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION)?.value?.get(0)?.toInt()
        Log.d(
            TAG, "owns connection: $ownsConnection"
        )
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
            if (!XposedRemotePrefProvider.create().getBoolean("vendor_id_hook", false) || ownsConnection == 0) {
                Log.d(TAG, "not taking over, vendorid is probably not set to apple")
                return
            }
            if (aacpManager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION)?.value[0]?.toInt() != 1 || (aacpManager.audioSource?.mac != localMac && aacpManager.audioSource?.type != AACPManager.Companion.AudioSourceType.NONE)) {
                if (disconnectedBecauseReversed) {
                    if (manualTakeOverAfterReversed) {
                        Log.d(TAG, "forcefully taking over despite reverse as user requested")
                        disconnectedBecauseReversed = false
                    } else {
                        Log.d(
                            TAG,
                            "connected locally, but can not hijack as other device had reversed"
                        )
                        return
                    }
                }

                Log.d(TAG, "already connected locally, hijacking connection by asking AirPods")
                aacpManager.sendControlCommand(
                    AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value, 1
                )
                aacpManager.sendMediaInformataion(
                    localMac
                )
                aacpManager.sendSmartRoutingShowUI(
                    localMac
                )
                aacpManager.sendHijackRequest(
                    localMac
                )
                otherDeviceTookOver = false
                connectAudio(this, device)
                showIsland(
                    this,
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                            batteryNotification.getBattery()
                                .find { it.component == BatteryComponent.RIGHT }?.level!!
                        ),
                    IslandType.CONNECTED
                )

                CoroutineScope(Dispatchers.IO).launch {
                    delay(500) // a2dp takes time, and so does taking control + AirPods pause it for no reason after connecting
                    if (takingOverFor == "music") {
                        Log.d(TAG, "Resuming music after taking control")
                        MediaController.sendPlay(replayWhenPaused = true)
                    }
                    delay(1000) // should ideally have a callback when it's taken over because for some reason android doesn't dispatch when it's paused
                    if (takingOverFor == "music") {
                        Log.d(TAG, "resuming again just in case")
                        MediaController.sendPlay(force = true)
                    }
                }
            } else {
                Log.d(
                    TAG, "Already connected locally and already own connection, skipping takeover"
                )
            }
            return
        }

//        if (CrossDevice.isAvailable) {
//            Log.d(TAG, "CrossDevice is available, continuing")
//        }
//        else if (bleManager.getMostRecentStatus()?.isLeftInEar == true || bleManager.getMostRecentStatus()?.isRightInEar == true) {
//            Log.d(TAG, "At least one AirPod is in ear, continuing")
//        }
//        else {
//            Log.d(TAG, "CrossDevice not available and AirPods not in ear, skipping")
//            return
//        }

        if (bleManager.getMostRecentStatus()?.isLeftInEar == false && bleManager.getMostRecentStatus()?.isRightInEar == false) {
            Log.d(TAG, "Both AirPods are out of ear, not taking over audio")
            return
        }

        val shouldTakeOver = when (bleManager.getMostRecentStatus()?.connectionState) {
            "Disconnected" -> config.takeoverWhenDisconnected
            "Idle" -> config.takeoverWhenIdle
            "Music" -> config.takeoverWhenMusic
            "Call" -> config.takeoverWhenCall
            "Ringing" -> config.takeoverWhenCall
            "Hanging Up" -> config.takeoverWhenCall
            else -> false
        }

        if (!shouldTakeOver) {
            Log.d(TAG, "Not taking over audio, airpods state takeover disabled")
            return
        }

        if (takingOverFor == "music") {
            Log.d(TAG, "Pausing music so that it doesn't play through speakers")
            MediaController.pausedWhileTakingOver = true
            MediaController.sendPause(true)
        } else {
            handleIncomingCallOnceConnected = true
        }

        Log.d(TAG, "Taking over audio")
//        CrossDevice.sendRemotePacket(CrossDevicePackets.REQUEST_DISCONNECT.packet)
        Log.d(TAG, macAddress)

//        sharedPreferences.edit { putBoolean("CrossDeviceIsAvailable", false) }
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter
        device = bluetoothAdapter.bondedDevices.find {
            it.address == macAddress
        }

        if (device != null) {
            if (config.bleOnlyMode) {
                // In BLE-only mode, just show connecting status without actual L2CAP connection
                Log.d(TAG, "BLE-only mode: showing connecting status without L2CAP connection")
                updateNotificationContent(
                    true, config.deviceName, batteryNotification.getBattery()
                )
                // Set a temporary connecting state
//                isConnectedLocally = false // Keep as false since we're not actually connecting to L2CAP
            } else {
                connectToSocket(bluetoothAdapter, device!!)
                connectAudio(this, device)
//                isConnectedLocally = true
            }
        }
        showIsland(
            this,
            batteryNotification.getBattery()
                .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.RIGHT }?.level!!
                ),
            IslandType.TAKING_OVER
        )

//        CrossDevice.isAvailable = false
    }

    private fun refreshAacpControlSession(reason: String) {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastAacpControlRefreshAt < 2_000L) {
                Log.d(TAG, "Skipping duplicate AACP control refresh: $reason")
                return
            }
            lastAacpControlRefreshAt = now
        }

        CoroutineScope(Dispatchers.IO).launch {
            delay(350)
            if (BluetoothConnectionManager.aacpSocket?.isConnected != true) {
                Log.w(TAG, "Cannot refresh AACP control session: socket is not connected")
                return@launch
            }

            Log.d(TAG, "Refreshing AACP control session after $reason")
            val conversationAwarenessValue = aacpManager.getControlCommandStatus(
                AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG
            )?.value?.firstOrNull()

            aacpManager.sendPacket(aacpManager.createHandshakePacket())
            aacpManager.sendSetFeatureFlagsPacket()
            aacpManager.sendNotificationRequest()

            if (conversationAwarenessValue == 0x01.toByte() || conversationAwarenessValue == 0x02.toByte()) {
                aacpManager.sendControlCommand(
                    AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG.value,
                    conversationAwarenessValue
                )
            }

            delay(350)
            aacpManager.sendNotificationRequest()
            Log.d(TAG, "AACP control session refresh complete")
        }
    }

    @Synchronized
    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    fun connectToSocket(
        adapter: BluetoothAdapter, device: BluetoothDevice, manual: Boolean = false
    ) {
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
            Log.d(TAG, "AACP socket is already connected, skipping duplicate request")
            return
        }
        Log.d(TAG, "<LogCollector:Start> Connecting to socket")
        val uuid: ParcelUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
//        if (!isConnectedLocally) {
        val socket = try {
            createBluetoothSocket(adapter, device, uuid, 4097)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create BluetoothSocket: ${e.message}")
            showSocketConnectionFailureNotification("Failed to create Bluetooth socket: ${e.localizedMessage}")
            return
        }

        try {
            runBlocking {
                withTimeout(5000.milliseconds) {
                    try {
                        socket.connect()
                        this@AirPodsService.device = device
                        BluetoothConnectionManager.aacpSocket = socket
                        val useVendorAttSocket = XposedRemotePrefProvider.create().getBoolean(
                            "vendor_id_hook", false
                        ) && sharedPreferences.getBoolean("vendor_att_socket", false)
                        if (useVendorAttSocket) {
                            attManager.connect(
                                key = socket,
                                factory = {
                                    val att = createBluetoothSocket(
                                        adapter, device,
                                        ParcelUuid.fromString("00000000-0000-0000-0000-000000000000"),
                                        31
                                    )
                                    object : AttTransport {
                                        override val input get() = att.inputStream
                                        override val output get() = att.outputStream
                                        override fun connect() = att.connect()
                                        override fun close() = att.close()
                                    }
                                },
                                isValid = {
                                    BluetoothConnectionManager.aacpSocket === socket && socket.isConnected &&
                                        sharedPreferences.getBoolean("vendor_att_socket", false) &&
                                        XposedRemotePrefProvider.create().getBoolean("vendor_id_hook", false)
                                }
                            )
                        } else {
                            attManager.disconnected()
                        }

                        // Create AirPodsInstance from stored config if available
                        if (airpodsInstance == null && config.airpodsModelNumber.isNotEmpty()) {
                            val model =
                                AirPodsModels.getModelByModelNumber(config.airpodsModelNumber)
                            if (model != null) {
                                airpodsInstance = AirPodsInstance(
                                    name = config.airpodsName,
                                    model = model,
                                    actualModelNumber = config.airpodsModelNumber,
                                    serialNumber = config.airpodsSerialNumber,
                                    leftSerialNumber = config.airpodsLeftSerialNumber,
                                    rightSerialNumber = config.airpodsRightSerialNumber,
                                    version1 = config.airpodsVersion1,
                                    version2 = config.airpodsVersion2,
                                    version3 = config.airpodsVersion3,
                                )
                                setMetadatas(device)
                            }
                        }

                        updateNotificationContent(
                            true, config.deviceName, batteryNotification.getBattery()
                        )
                        Log.d(TAG, "<LogCollector:Complete:Success> Socket connected")
                        sharedPreferences.edit { putBoolean("connection_successful", true) }
                        if (!sharedPreferences.contains("first_connection_successful_time")) {
                            sharedPreferences.edit {
                                putLong(
                                    "first_connection_successful_time",
                                    System.currentTimeMillis()
                                )
                            }
                        }
                        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_L2CAP_CONNECTED).apply {
                            setPackage(packageName)
                        })
                    } catch (e: Exception) {
//                        sharedPreferences.edit { putBoolean("connection_successful", false) }
                        Log.d(
                            TAG, "<LogCollector:Complete:Failed> Socket not connected, ${e.message}"
                        )
                        if (manual) {
                            sendToast(
                                "Couldn't connect to socket: ${e.localizedMessage}"
                            )
                        } else {
                            showSocketConnectionFailureNotification("Couldn't connect to socket: ${e.localizedMessage}")
                        }
                        return@withTimeout
//                            throw e // lol how did i not catch this before... gonna comment this line instead of removing to preserve history
                    }
                }
            }
            if (!socket.isConnected) {
                Log.d(TAG, "<LogCollector:Complete:Failed> socket not connected")
                if (manual) {
                    sendToast(
                        "Couldn't connect to socket: timeout."
                    )
                } else {
                    showSocketConnectionFailureNotification("Couldn't connect to socket: Timeout")
                }
                return
            }
            this@AirPodsService.device = device
            socket.let {
                aacpManager.sendPacket(aacpManager.createHandshakePacket())
                aacpManager.sendSetFeatureFlagsPacket()
                aacpManager.sendNotificationRequest()
                Log.d(TAG, "Requesting proximity keys")
                aacpManager.sendRequestProximityKeys((AACPManager.Companion.ProximityKeyType.IRK.value + AACPManager.Companion.ProximityKeyType.ENC_KEY.value).toByte())
                CoroutineScope(Dispatchers.IO).launch {
                    delay(200)
                    aacpManager.sendPacket(aacpManager.createHandshakePacket())
                    delay(200)
                    aacpManager.sendSetFeatureFlagsPacket()
                    delay(200)
                    aacpManager.sendNotificationRequest()
                    delay(200)
                    aacpManager.sendSomePacketIDontKnowWhatItIs()
                    delay(200)
                    aacpManager.sendRequestProximityKeys((AACPManager.Companion.ProximityKeyType.IRK.value + AACPManager.Companion.ProximityKeyType.ENC_KEY.value).toByte())
                    if (!handleIncomingCallOnceConnected) {
                        if (SpatialAudioMode.fromPreferences(sharedPreferences) ==
                            SpatialAudioMode.HEAD_TRACKED
                        ) {
                            // The PLAYING broadcast may have happened while the
                            // service was stopped, so query again once AACP is usable.
                            refreshCurrentA2dpPlaybackState("AACP connected")
                        } else {
                            startHeadTracking()
                        }
                    } else {
                        handleIncomingCall()
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        aacpManager.sendPacket(aacpManager.createHandshakePacket())
                        aacpManager.sendSetFeatureFlagsPacket()
                        aacpManager.sendNotificationRequest()
                        aacpManager.sendRequestProximityKeys(AACPManager.Companion.ProximityKeyType.IRK.value)
                        if (!handleIncomingCallOnceConnected) stopHeadTracking()
                    }, 5000)

                    sendBroadcast(
                        Intent(AirPodsNotifications.AIRPODS_CONNECTED).putExtra("device", device)
                            .apply {
                                setPackage(packageName)
                            })

                    setupStemActions()
                    stopOrphanedHeartRateSampling("new AACP connection while disabled")
                    startHeartRateProbeIfRequested()

                    while (socket.isConnected) {
                        try {
                            val buffer = ByteArray(1024)
                            val bytesRead = it.inputStream.read(buffer)
                            var data: ByteArray
                            if (bytesRead > 0) {
                                data = buffer.copyOfRange(0, bytesRead)
                                sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DATA).apply {
                                    putExtra("data", buffer.copyOfRange(0, bytesRead))
                                    setPackage(packageName)
                                })
                                val bytes = buffer.copyOfRange(0, bytesRead)
                                val formattedHex = bytes.joinToString(" ") { "%02X".format(it) }
//                                    CrossDevice.sendReceivedPacket(bytes)
                                updateNotificationContent(
                                    true,
                                    sharedPreferences.getString("name", device.name),
                                    batteryNotification.getBattery()
                                )

                                val suppressRawPacketLogging = try {
                                    aacpManager.receivePacket(data)
                                } catch (e: Exception) {
                                    // A malformed or fragmented protocol packet is not a socket
                                    // disconnect. Keep the AACP session alive and wait for the next
                                    // read instead of tearing down the AirPods control connection.
                                    Log.w(
                                        TAG,
                                        "Ignoring malformed AACP packet: $formattedHex",
                                        e
                                    )
                                    false
                                }

                                if (!suppressRawPacketLogging && !isHeadTrackingData(data)) {
                                    Log.d("AirPodsData", "Data received: $formattedHex")
                                    logPacket(data, "AirPods")
                                }

                            } else if (bytesRead == -1) {
                                attManager.disconnected(socket)
                                Log.d("AirPodsService", "socket closed (bytesRead = -1)")
                                traceConnectionEvent("aacp_eof", "transport_closed; HCI reason requires Bluetooth log")
                                resetHeartRateMonitoringForSession(
                                    reason = "AACP socket closed",
                                    sendStop = false
                                )
                                sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
                                    setPackage(packageName)
                                })
                                aacpManager.disconnected()
                                return@launch
                            }
                        } catch (e: Exception) {
                            attManager.disconnected(socket)
                            Log.w(TAG, "Error reading data, we have probably disconnected.")
                            traceConnectionEvent("aacp_read_failed", e.javaClass.simpleName)
                            e.printStackTrace()
                            resetHeartRateMonitoringForSession(
                                reason = "AACP socket read failed",
                                sendStop = false
                            )
                            sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
                                setPackage(packageName)
                            })
                            aacpManager.disconnected()
                            return@launch
                        }

                    }
                    attManager.disconnected(socket)
                    Log.d("AirPods Service", "socket closed")
                    resetHeartRateMonitoringForSession(
                        reason = "AACP socket loop ended",
                        sendStop = false
                    )
//                        isConnectedLocally = false
                    aacpManager.disconnected()
                    updateNotificationContent(false)
                    sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
                        setPackage(packageName)
                    })
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.d(TAG, "Failed to connect to BluetoothConnectionManager.aacpSocket?: ${e.message}")
            resetHeartRateMonitoringForSession(
                reason = "AACP connection failed",
                sendStop = false
            )
            showSocketConnectionFailureNotification("Failed to establish connection: ${e.localizedMessage}")
//                isConnectedLocally = false
            this@AirPodsService.device = device
            updateNotificationContent(false)
        }
//        } else {
//            Log.d(TAG, "Already connected locally, skipping BluetoothConnectionManager.aacpSocket? connection (isConnectedLocally = $isConnectedLocally, BluetoothConnectionManager.aacpSocket?.isConnected = ${this::BluetoothConnectionManager.aacpSocket?.isInitialized && BluetoothConnectionManager.aacpSocket?.isConnected})")
//        }
    }

    fun disconnectForCD() {
        attManager.disconnected()
        resetHeartRateMonitoringForSession(
            reason = "cross-device disconnect",
            sendStop = true
        )
        BluetoothConnectionManager.aacpSocket?.close()
        MediaController.pausedWhileTakingOver = false
        Log.d(TAG, "Disconnected from AirPods, showing island.")
        showIsland(
            this,
            batteryNotification.getBattery()
                .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.RIGHT }?.level!!
                ),
            IslandType.MOVED_TO_REMOTE
        )
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    val connectedDevices = proxy.connectedDevices
                    if (connectedDevices.isNotEmpty()) {
                        MediaController.sendPause()
                    }
                }
                bluetoothAdapter.closeProfileProxy(profile, proxy)
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
//        isConnectedLocally = false
//        CrossDevice.isAvailable = true
    }

    fun disconnectAirPods() {
        attManager.disconnected()
        if (BluetoothConnectionManager.aacpSocket == null) return
        resetHeartRateMonitoringForSession(
            reason = "manual AirPods disconnect",
            sendStop = true
        )
        try {
            BluetoothConnectionManager.aacpSocket?.close()
        } catch(e: Exception) {
            Log.e(TAG, "error closing aacp socket ${e.message}")
        }
//        isConnectedLocally = false
        aacpManager.disconnected()

        BluetoothConnectionManager.aacpSocket = null

        updateNotificationContent(false)
        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
            setPackage(packageName)
        })

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED){
            bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        val connectedDevices = proxy.connectedDevices
                        if (connectedDevices.isNotEmpty()) {
                            MediaController.sendPause()
                        }
                    }
                    bluetoothAdapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)
            if (Build.VERSION.SDK_INT >= 37) {
                try {
                    device?.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "device.disconnect() failed, $e")
                }
            }
        }
        if (checkSelfPermission("android.permission.MODIFY_PHONE_STATE") == PackageManager.PERMISSION_GRANTED){
            bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        val connectedDevices = proxy.connectedDevices
                        if (connectedDevices.isNotEmpty()) {
                            MediaController.sendPause()
                        }
                    }
                    bluetoothAdapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)
        }
        Log.d(TAG, "Disconnected AirPods upon user request")
    }

    val earDetectionNotification = AirPodsNotifications.EarDetection()
    val ancNotification = AirPodsNotifications.ANC()
    val batteryNotification = AirPodsNotifications.BatteryNotification()
    val conversationAwarenessNotification =
        AirPodsNotifications.ConversationalAwarenessNotification()

    @Suppress("unused")
    fun setEarDetection(enabled: Boolean) {
        if (config.earDetectionEnabled != enabled) {
            config.earDetectionEnabled = enabled
            sharedPreferences.edit { putBoolean("automatic_ear_detection", enabled) }
        }
    }

    fun getBattery(): List<Battery> {
//        if (!isConnectedLocally && CrossDevice.isAvailable) {
//            batteryNotification.setBattery(CrossDevice.batteryBytes)
//        }
        return batteryNotification.getBattery()
    }

    fun getANC(): Int {
//        if (!isConnectedLocally && CrossDevice.isAvailable) {
//            ancNotification.setStatus(CrossDevice.ancBytes)
//        }
        return ancNotification.status
    }

    @Volatile private var lastAudioDisconnectRequest: String = "none"
    @Volatile private var lastAudioDisconnectRequestAt: Long = 0

    private fun traceConnectionEvent(event: String, reason: String) {
        // Diagnostics must not prevent the existing connection action, including during initialization.
        val state = runCatching { diagnosticStateSnapshot().replace('\n', ' ').take(3_000) }
            .getOrElse { "snapshotError=${it.javaClass.simpleName}" }
        Log.i("AirPodsConnectionTrace", "event=$event elapsedRealtimeMs=${SystemClock.elapsedRealtime()} reason=$reason $state")
    }

    fun disconnectAudio(context: Context, device: BluetoothDevice?, reason: String) {
        lastAudioDisconnectRequest = reason
        lastAudioDisconnectRequestAt = SystemClock.elapsedRealtime()
        traceConnectionEvent("disconnect_audio_requested", reason)
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        try {
                            if (proxy.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED) {
                                Log.d(TAG, "Already disconnected from A2DP")
                                return
                            }
                            val method = proxy.javaClass.getMethod(
                                "setConnectionPolicy", BluetoothDevice::class.java, Int::class.java
                            )
                            Log.i("AirPodsConnectionTrace", "event=policy_request profile=A2DP policy=0 device=${device?.address} reason=$reason")
                            val result = method.invoke(proxy, device, 0)
                            Log.i("AirPodsConnectionTrace", "event=policy_result profile=A2DP result=$result reason=$reason (result is not proof of link disconnection)")
                        } catch (e: Exception) {
                            Log.w("AirPodsConnectionTrace", "event=policy_failed profile=A2DP reason=$reason", e)
                            e.printStackTrace()
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                        }
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)
        } else {
            Log.d(TAG, "not disconnecting A2DP, no BLUETOOTH_PRIVILEGED permission")
        }
        if (checkSelfPermission("android.permission.MODIFY_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        try {
                            val method =
                                proxy.javaClass.getMethod(
                                    "setConnectionPolicy",
                                    BluetoothDevice::class.java,
                                    Int::class.java
                                )
                            Log.i("AirPodsConnectionTrace", "event=policy_request profile=HEADSET policy=0 device=${device?.address} reason=$reason")
                            val result = method.invoke(proxy, device, 0)
                            Log.i("AirPodsConnectionTrace", "event=policy_result profile=HEADSET result=$result reason=$reason (result is not proof of link disconnection)")
                        } catch (e: Exception) {
                            Log.w("AirPodsConnectionTrace", "event=policy_failed profile=HEADSET reason=$reason", e)
                            e.printStackTrace()
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                        }
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)
        } else {
            Log.d(TAG, "not disconnecting HEADSET, no MODIFIY_PHONE_STATE permission")
        }
    }

    fun connectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    if (context.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
                        try {
                            val policyMethod = proxy.javaClass.getMethod(
                                "setConnectionPolicy",
                                BluetoothDevice::class.java,
                                Int::class.java
                            )
                            Log.d(TAG, "calling A2DP.setConnectionPolicy for ${device?.address} to 100")
                            policyMethod.invoke(proxy, device, 100)

                            val connectMethod =
                                proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                            connectMethod.invoke(
                                proxy, device
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                            if (MediaController.pausedWhileTakingOver) {
                                MediaController.sendPlay()
                            }
                        }
                    }
                    else {
                        val connectMethod =
                            proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        connectMethod.invoke(
                            proxy, device
                        )
                        Log.d(TAG, "not setting connection policy for A2DP, no BLUETOOTH_PRIVILEGED permission. just called connect")
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    if (checkSelfPermission("android.permission.MODIFY_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
                        try {
                            val policyMethod = proxy.javaClass.getMethod(
                                "setConnectionPolicy",
                                BluetoothDevice::class.java,
                                Int::class.java
                            )
                            Log.d(
                                TAG,
                                "calling HEADSET.setConnectionPolicy for ${device?.address} to 100"
                            )
                            policyMethod.invoke(proxy, device, 100)
                            val connectMethod =
                                proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                            connectMethod.invoke(proxy, device)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                        }
                    } else {
                        Log.d(TAG, "not setting connection policy for HEADSET, no MODIFIY_PHONE_STATE permission")
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.HEADSET)
    }

    fun setName(name: String) {
        aacpManager.sendRename(name)

        if (config.deviceName != name) {
            config.deviceName = name
            device?.alias = name
            sharedPreferences.edit { putString("name", name) }
        }

        updateNotificationContent(true, name, batteryNotification.getBattery())
        Log.d(TAG, "setName: $name")
    }

    fun setHeartRateMonitoringEnabled(enabled: Boolean) {
        if (enabled) {
            heartRateRemoteTakeoverGate.onHeartRateRequested()
            synchronized(heartRateProbeLock) {
                heartRateProbeRequested = true
                _heartRateProbeEnabled.value = true
                _heartRateSample.value = null
            }
            if (aacpManager.owns) {
                startHeartRateProbeIfRequested()
            } else {
                requestHeartRateOwnership("heart-rate switch enabled")
            }
        } else {
            stopHeartRateProbe(clearRequest = true, sendStop = true)
        }
        logHeartRateProbeStatus(if (enabled) "enabled" else "disabled")
    }

    private fun resetHeartRateMonitoringForSession(reason: String, sendStop: Boolean) {
        val wasActive = stopHeartRateProbe(clearRequest = true, sendStop = sendStop)
        if (wasActive) logHeartRateProbeStatus("session-reset:$reason")
    }

    private fun scheduleHeartRateProbeAfterOwnershipSettled(reason: String) {
        heartRateOwnershipSettleJob?.cancel()
        heartRateOwnershipSettleJob = heartRateProbeScope.launch {
            delay(HEART_RATE_OWNERSHIP_SETTLE_MILLIS)
            if (!heartRateProbeRequested || !aacpManager.owns) return@launch
            Log.i("HeartRateProbe", "ownership settled; starting session reason=$reason")
            startHeartRateProbeIfRequested()
        }
    }

    private fun handleRemoteStreamingStateChanged(
        sender: String,
        isStreaming: Boolean,
        reason: String
    ) {
        if (sender.equals(localMac, ignoreCase = true)) return
        val senderKey = sender.uppercase()
        if (isStreaming) {
            remoteStreamingDevices.add(senderKey)
        } else {
            remoteStreamingDevices.remove(senderKey)
        }
        if (!heartRateRemoteTakeoverGate.onRemoteStreamingStateChanged(isStreaming)) {
            if (isStreaming && heartRateProbeRequested) {
                Log.d(
                    TAG,
                    "Ignoring pre-existing remote playback while heart-rate takeover settles " +
                        "sender=$sender reason=$reason"
                )
            }
            return
        }

        Log.d(TAG, "Remote device started streaming; releasing AACP ownership sender=$sender reason=$reason")
        releaseOwnershipAfterHeartRateStops(reason) {
            if (aacpManager.owns) {
                aacpManager.sendControlCommand(
                    AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                    byteArrayOf(0x00)
                )
            }
            otherDeviceTookOver = true
            disconnectAudio(this@AirPodsService, device, "remote_streaming sender=$sender reason=$reason")
            MediaController.sendPause()
        }
    }

    private fun releaseOwnershipAfterHeartRateStops(reason: String, release: () -> Unit) {
        if (heartRateRemoteReleaseJob?.isActive == true) {
            Log.d("HeartRateProbe", "remote ownership release already pending reason=$reason")
            return
        }
        heartRateRemoteReleaseJob = heartRateProbeScope.launch {
            try {
                stopHeartRateForRemoteTakeover(reason)
                release()
            } finally {
                heartRateRemoteReleaseJob = null
            }
        }
    }

    private suspend fun stopHeartRateForRemoteTakeover(reason: String) {
        val wasActive = stopHeartRateProbe(clearRequest = true, sendStop = false)
        if (!wasActive) return
        logHeartRateProbeStatus("session-reset:$reason")

        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return
        val acknowledgement = CompletableDeferred<Unit>()
        synchronized(heartRateProbeLock) {
            pendingHeartRateStopAcknowledgement?.cancel()
            pendingHeartRateStopAcknowledgement = acknowledgement
        }
        val stopSent = aacpManager.sendHeartRateSampling(0)
        if (!stopSent) {
            synchronized(heartRateProbeLock) {
                if (pendingHeartRateStopAcknowledgement === acknowledgement) {
                    pendingHeartRateStopAcknowledgement = null
                }
            }
            Log.w("HeartRateProbe", "could not send sampling stop before $reason")
            return
        }

        val acknowledged = withTimeoutOrNull(HEART_RATE_STOP_ACK_TIMEOUT_MILLIS) {
            acknowledgement.await()
            true
        } ?: false
        synchronized(heartRateProbeLock) {
            if (pendingHeartRateStopAcknowledgement === acknowledgement) {
                pendingHeartRateStopAcknowledgement = null
            }
        }
        if (!acknowledged) {
            Log.w(
                "HeartRateProbe",
                "sampling stop acknowledgement timed out; releasing ownership reason=$reason"
            )
        }
    }

    private fun requestHeartRateOwnership(reason: String) {
        if (!heartRateProbeRequested ||
            BluetoothConnectionManager.aacpSocket?.isConnected != true
        ) return
        if (aacpManager.owns) {
            startHeartRateProbeIfRequested()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeartRateOwnershipRequestAt <
            HEART_RATE_OWNERSHIP_REQUEST_DEBOUNCE_MILLIS
        ) return
        lastHeartRateOwnershipRequestAt = now

        Log.i("HeartRateProbe", "requesting local AACP ownership reason=$reason")
        aacpManager.sendControlCommand(
            AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
            1
        )
        aacpManager.sendMediaInformataion(localMac)
        aacpManager.sendSmartRoutingShowUI(localMac)
        aacpManager.sendHijackRequest(localMac)
        otherDeviceTookOver = false
        connectAudio(this, device)

        heartRateOwnershipTimeoutJob?.cancel()
        heartRateOwnershipTimeoutJob = heartRateProbeScope.launch {
            delay(HEART_RATE_OWNERSHIP_TIMEOUT_MILLIS)
            if (heartRateProbeRequested && !aacpManager.owns) {
                Log.w("HeartRateProbe", "local ownership request timed out")
                resetHeartRateMonitoringForSession(
                    reason = "ownership request timed out",
                    sendStop = false
                )
            }
        }
    }

    private fun stopOrphanedHeartRateSampling(reason: String) {
        if (heartRateProbeRequested ||
            BluetoothConnectionManager.aacpSocket?.isConnected != true
        ) return
        aacpManager.sendHeartRateSampling(0)
        Log.i("HeartRateProbe", "orphaned sampling stop sent reason=$reason")
    }

    private fun startHeartRateProbeIfRequested() {
        if (!heartRateProbeRequested) return
        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) {
            Log.i("HeartRateProbe", "waiting for AACP connection")
            return
        }
        if (!aacpManager.owns) {
            Log.i("HeartRateProbe", "waiting for local AACP ownership")
            return
        }
        synchronized(heartRateProbeLock) {
            if (heartRateProbeJob?.isActive == true) return
            _heartRateProbeStreaming.value = false
            lastHeartRateSampleElapsedRealtime = 0L
            val generation = ++heartRateProbeGeneration
            val job = heartRateProbeScope.launch(start = CoroutineStart.LAZY) {
                runHeartRateProbe(generation)
            }
            heartRateProbeJob = job
            job.start()
        }
    }

    private suspend fun runHeartRateProbe(generation: Long) {
        var refreshAttempt = 0
        try {
            while (heartRateProbeRequested &&
                BluetoothConnectionManager.aacpSocket?.isConnected == true &&
                aacpManager.owns
            ) {
                var firstSampleTimedOut = false
                synchronized(heartRateProbeLock) {
                    heartRateWarmupRemaining = HEART_RATE_WARMUP_SAMPLES
                    _heartRateProbeStreaming.value = false
                    lastHeartRateSampleElapsedRealtime = 0L
                }
                val attemptStartedAt = SystemClock.elapsedRealtime()
                val started = initializeHeartRateSession()
                if (!started) {
                    Log.w("HeartRateProbe", "session initialization failed")
                } else {
                    Log.i("HeartRateProbe", "sampling request sent interval=1s")
                    while (heartRateProbeRequested &&
                        BluetoothConnectionManager.aacpSocket?.isConnected == true &&
                        aacpManager.owns
                    ) {
                        delay(1_000L)
                        val now = SystemClock.elapsedRealtime()
                        val lastSample = lastHeartRateSampleElapsedRealtime
                        val timedOut = when {
                            lastSample == 0L ->
                                now - attemptStartedAt >= HEART_RATE_FIRST_SAMPLE_TIMEOUT_MILLIS
                            else -> now - lastSample >= HEART_RATE_STALL_TIMEOUT_MILLIS
                        }
                        if (timedOut) {
                            firstSampleTimedOut = lastSample == 0L
                            val reason = if (lastSample == 0L) {
                                "first-sample-timeout"
                            } else {
                                "stream-stalled"
                            }
                            Log.w("HeartRateProbe", "refresh requested reason=$reason")
                            break
                        }
                    }
                }

                if (!heartRateProbeRequested ||
                    BluetoothConnectionManager.aacpSocket?.isConnected != true ||
                    !aacpManager.owns
                ) break
                aacpManager.sendHeartRateSampling(0)
                if (firstSampleTimedOut) {
                    val fallback = aacpManager.advanceHeartRateServiceFallback()
                    Log.i(
                        "HeartRateProbe",
                        "next sampling attempt service=${fallback.serviceId} source=${fallback.source}"
                    )
                }
                if (refreshAttempt >= HEART_RATE_RETRY_BACKOFF_MILLIS.size) {
                    Log.e("HeartRateProbe", "refresh attempts exhausted")
                    break
                }
                val backoff = HEART_RATE_RETRY_BACKOFF_MILLIS[refreshAttempt++]
                delay(backoff)
            }
        } finally {
            synchronized(heartRateProbeLock) {
                if (generation == heartRateProbeGeneration) {
                    _heartRateProbeStreaming.value = false
                    heartRateProbeJob = null
                }
            }
            logHeartRateProbeStatus("session-ended")
        }
    }

    private suspend fun initializeHeartRateSession(): Boolean {
        // The successful iOS 27 path writes four HID feature reports. HRM and DEVMOTION6 remain
        // active together, so head tracking and the current HRM session stay untouched.
        var resolution = aacpManager.prepareHeartRateSamplingSession()
        if (resolution.serviceId == null) {
            Log.w("HeartRateProbe", "no compatible RTBuddy heart-rate service available")
            return false
        }
        if (resolution.serviceId == 19) {
            if (!initializeLegacyHeartRateAacpSession()) return false
            for (metadataPoll in 0 until 30) {
                resolution = aacpManager.refreshPreparedHeartRateServiceResolution()
                if (resolution.source == HeartRateServiceResolution.Source.METADATA) {
                    break
                }
                delay(50L)
            }
            if (!sendHeartRateFrame {
                    aacpManager.sendControlCommand(
                        AACPManager.Companion.ControlCommandIdentifiers.HRM_STATE.value,
                        true
                    )
                }
            ) return false
            delay(120L)
        }
        Log.i(
            "HeartRateProbe",
            "initializing sampling service=${resolution.serviceId} source=${resolution.source}"
        )
        val packets = aacpManager.createHeartRateStartPackets()
        if (packets.isEmpty()) return false
        val delays = longArrayOf(165L, 45L, 105L)
        packets.forEachIndexed { index, packet ->
            if (!sendHeartRateFrame { aacpManager.sendPacket(packet) }) return false
            if (index < delays.size) delay(delays[index])
        }
        return true
    }

    private suspend fun initializeLegacyHeartRateAacpSession(): Boolean {
        val frames = listOf(
            aacpManager::sendHeartRateConnectService0 to 180L,
            aacpManager::sendHeartRateCapabilitiesService0 to 220L,
            aacpManager::sendHeartRateConnectService4 to 180L,
            aacpManager::sendHeartRateCapabilitiesService4 to 220L
        )
        for ((send, delayAfter) in frames) {
            if (!sendHeartRateFrame(send)) return false
            delay(delayAfter)
        }
        Log.i("HeartRateProbe", "legacy AACP 1.3 heart-rate discovery initialized")
        return true
    }

    private fun sendHeartRateFrame(send: () -> Boolean): Boolean =
        heartRateProbeRequested &&
            BluetoothConnectionManager.aacpSocket?.isConnected == true &&
            aacpManager.owns &&
            send()

    private fun stopHeartRateProbe(clearRequest: Boolean, sendStop: Boolean): Boolean {
        val (hadActiveSession, jobToCancel) = synchronized(heartRateProbeLock) {
            val wasActive = heartRateProbeRequested ||
                _heartRateProbeStreaming.value ||
                heartRateProbeJob?.isActive == true ||
                _heartRateSample.value != null
            if (clearRequest) {
                heartRateProbeRequested = false
                _heartRateProbeEnabled.value = false
            }
            _heartRateSample.value = null
            heartRateProbeGeneration++
            val currentJob = heartRateProbeJob
            heartRateProbeJob = null
            _heartRateProbeStreaming.value = false
            lastHeartRateSampleElapsedRealtime = 0L
            heartRateWarmupRemaining = 0
            wasActive to currentJob
        }
        jobToCancel?.cancel()
        if (clearRequest) {
            heartRateRemoteTakeoverGate.onHeartRateStopped()
            heartRateOwnershipSettleJob?.cancel()
            heartRateOwnershipSettleJob = null
        }
        heartRateOwnershipTimeoutJob?.cancel()
        heartRateOwnershipTimeoutJob = null
        if (hadActiveSession && sendStop &&
            BluetoothConnectionManager.aacpSocket?.isConnected == true
        ) {
            aacpManager.sendHeartRateSampling(0)
        }
        if (hadActiveSession) updateSpatialAudioTracking("heart-rate probe stopped")
        return hadActiveSession
    }

    private fun logHeartRateProbeStatus(reason: String) {
        val sample = _heartRateSample.value
        KeepHeartRateBridge.publish(
            context = this,
            enabled = _heartRateProbeEnabled.value,
            streaming = _heartRateProbeStreaming.value,
            sample = sample
        )
        Log.i(
            "HeartRateProbe",
            "status reason=$reason requested=$heartRateProbeRequested " +
                "streaming=${_heartRateProbeStreaming.value} connected=" +
                "${BluetoothConnectionManager.aacpSocket?.isConnected == true} " +
                "bpm=${sample?.bpm ?: "none"} sequence=${sample?.sequence ?: "none"}"
        )
    }

    /** Read cached state only; collecting diagnostics must never take over the audio route. */
    fun diagnosticStateSnapshot(): String = buildString {
        appendLine("airPodsService=running")
        appendLine("localBluetoothAddress=$localMac, airPodsAddress=${device?.address}")
        appendLine("lastAudioDisconnectRequest=$lastAudioDisconnectRequest")
        appendLine("lastAudioDisconnectRequestAgeMs=" + if (lastAudioDisconnectRequestAt > 0) {
            (SystemClock.elapsedRealtime() - lastAudioDisconnectRequestAt).toString()
        } else "none")
        appendLine("aacpConnected=${BluetoothConnectionManager.aacpSocket?.isConnected == true}")
        appendLine("attConnected=${::attManager.isInitialized && attManager.isConnected}")
        appendLine("inCallCached=$isInCall")
        appendLine("a2dpPlayingReported=$isAirPodsA2dpPlaying")
        appendLine("headTrackingActive=$isHeadTrackingActive")
        appendLine("ancModeReported=${ancNotification.status}")
        appendLine("remoteStreamingDeviceCount=${remoteStreamingDevices.size}")
        appendLine("remoteStreamingDevices=${remoteStreamingDevices.take(16).joinToString(",")}")
        if (::aacpManager.isInitialized) {
            val source = aacpManager.audioSource
            appendLine("audioSourceAddress=${source?.mac ?: "unknown"}")
            appendLine("connectedDevicesReported=" + aacpManager.connectedDevices.take(16).joinToString(";") {
                "${it.mac}:${it.type}"
            })
            appendLine("ownsConnectionCached=${aacpManager.owns}")
            appendLine("ownsConnectionReported=" +
                (aacpManager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION)
                    ?.value?.firstOrNull()?.toInt()?.toString() ?: "unknown"))
            appendLine("audioSourceType=${source?.type?.name ?: "unknown"}")
            appendLine("audioSourceRelation=" + when {
                source == null -> "unknown"
                source.type == AACPManager.Companion.AudioSourceType.NONE -> "none"
                localMac.isBlank() -> "unknown"
                source.mac.equals(localMac, ignoreCase = true) -> "local"
                else -> "remote"
            })
        } else appendLine("aacpState=uninitialized")
        if (::sharedPreferences.isInitialized) {
            appendLine("spatialAudioModeRequested=${SpatialAudioMode.fromPreferences(sharedPreferences)}")
        }
        appendLine("miLinkSpatialCapabilityChecked=$miLinkSpatialAudioCapabilityChecked")
        appendLine("miLinkSpatialAvailable=$miLinkSpatialAudioAvailable")
        appendLine("heartRateEnabled=${_heartRateProbeEnabled.value}")
        appendLine("heartRateStreaming=${_heartRateProbeStreaming.value}")
        val lastSampleAt = lastHeartRateSampleElapsedRealtime
        appendLine("heartRateLastSampleAgeMs=" + if (lastSampleAt > 0) {
            (SystemClock.elapsedRealtime() - lastSampleAt).toString()
        } else "none")
    }

    fun canPlayNotificationAnnouncement(): Boolean {
        if (!::aacpManager.isInitialized || localMac.isBlank()) return false
        val source = aacpManager.audioSource
        val sourceIsLocal = source != null &&
            source.type != AACPManager.Companion.AudioSourceType.NONE &&
            source.mac.equals(localMac, ignoreCase = true)
        val sourceIsRemote = source != null &&
            source.type != AACPManager.Companion.AudioSourceType.NONE &&
            !source.mac.equals(localMac, ignoreCase = true)
        val allowed = NotificationAnnouncementRoutePolicy.canAnnounce(
            localOwnsConnection = aacpManager.owns,
            remoteDeviceStreaming = remoteStreamingDevices.isNotEmpty(),
            activeAudioSourceIsLocal = sourceIsLocal,
            activeAudioSourceIsRemote = sourceIsRemote
        )
        if (allowed && !aacpManager.owns && sourceIsLocal) {
            Log.i(
                TAG,
                "Notification announcement allowed by confirmed local audio source fallback"
            )
        }
        if (!allowed) {
            Log.d(
                TAG,
                "Notification announcement blocked: owns=${aacpManager.owns}, " +
                    "remoteStreaming=${remoteStreamingDevices.isNotEmpty()}, " +
                    "sourceIsLocal=$sourceIsLocal, sourceIsRemote=$sourceIsRemote, " +
                    "audioSourceMac=${source?.mac}, audioSourceType=${source?.type?.name}"
            )
        }
        return allowed
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        attManager.close()
        remoteStreamingDevices.clear()
        clearPacketLogs()
        Log.d(TAG, "Service stopped is being destroyed for some reason!")

        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(externalBroadcastReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(connectionReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(earReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            bleManager.stopScanning()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (checkSelfPermission("android.permission.READ_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.unregisterTelephonyCallback(phoneStateListener)
        }
        resetHeartRateMonitoringForSession(
            reason = "service destroyed",
            sendStop = true
        )
        heartRateProbeScope.cancel()
        audioFeatureScope.cancel()
        spatialHeadTrackerBridge.stop()
        rootAvrcpVolumeController.cancel()
//        isConnectedLocally = false
//        CrossDevice.isAvailable = true
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        resetHeartRateMonitoringForSession(
            reason = "app task removed",
            sendStop = true
        )
        super.onTaskRemoved(rootIntent)
    }

    var isHeadTrackingActive = false

    @SuppressLint("MissingPermission")
    private fun refreshCurrentA2dpPlaybackState(reason: String) {
        Log.d(TAG, "Querying current AirPods A2DP playback state: $reason")
        val savedMac = macAddress.takeIf { it.isNotBlank() }
            ?: sharedPreferences.getString("mac_address", null)
        if (savedMac.isNullOrBlank()) {
            isAirPodsA2dpPlaying = false
            updateSpatialAudioTracking("$reason; no saved AirPods")
            return
        }

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        val target = try {
            bluetoothAdapter.getRemoteDevice(savedMac)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Cannot query A2DP playback for invalid address $savedMac", error)
            isAirPodsA2dpPlaying = false
            updateSpatialAudioTracking("$reason; invalid AirPods address")
            return
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.A2DP) return
                val a2dp = proxy as BluetoothA2dp
                val reflectedPlaying = try {
                    val method = BluetoothA2dp::class.java.getDeclaredMethod(
                        "isA2dpPlaying",
                        BluetoothDevice::class.java
                    )
                    method.isAccessible = true
                    method.invoke(a2dp, target) as? Boolean
                } catch (error: Exception) {
                    Log.w(TAG, "BluetoothA2dp.isA2dpPlaying unavailable", error)
                    null
                }
                val targetConnected = a2dp.getConnectionState(target) ==
                    BluetoothProfile.STATE_CONNECTED
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                val playing = reflectedPlaying
                    ?: (targetConnected && audioManager.isMusicActive)
                bluetoothAdapter.closeProfileProxy(profile, proxy)

                Handler(Looper.getMainLooper()).post {
                    isAirPodsA2dpPlaying = playing
                    Log.i(
                        TAG,
                        "Current AirPods A2DP playing=$playing " +
                            "(connected=$targetConnected): $reason"
                    )
                    updateSpatialAudioTracking(reason)
                }
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }

        if (!bluetoothAdapter.getProfileProxy(this, listener, BluetoothProfile.A2DP)) {
            Log.w(TAG, "Could not obtain A2DP profile proxy: $reason")
            isAirPodsA2dpPlaying = false
            updateSpatialAudioTracking("$reason; A2DP proxy unavailable")
        }
    }

    private fun shouldRunSpatialAudio(): Boolean {
        return SpatialAudioMode.fromPreferences(sharedPreferences) ==
            SpatialAudioMode.HEAD_TRACKED &&
            isAirPodsA2dpPlaying &&
            BluetoothConnectionManager.aacpSocket?.isConnected == true
    }

    private fun scheduleAirPodsAbsoluteVolumeResync(reason: String, delayMs: Long) {
        if (earlyA2dpVolumeResyncInFlight) {
            Log.d(TAG, "Early A2DP volume resync already active: $reason")
            return
        }
        if (SystemClock.elapsedRealtime() - lastA2dpVolumeResyncAt <
            A2DP_VOLUME_RESYNC_DEBOUNCE_MS
        ) {
            Log.d(TAG, "Skipping recently completed A2DP volume resync: $reason")
            return
        }
        val generation = ++a2dpVolumeResyncGeneration
        Handler(Looper.getMainLooper()).postDelayed({
            if (generation != a2dpVolumeResyncGeneration) {
                Log.d(TAG, "Skipping superseded A2DP volume resync: $reason")
                return@postDelayed
            }
            resendAirPodsAbsoluteVolume(reason, generation)
        }, delayMs)
    }

    private fun cancelPendingAirPodsAbsoluteVolumeResync(reason: String) {
        a2dpVolumeResyncGeneration++
        Log.d(TAG, "Cancelled pending A2DP volume resync: $reason")
    }

    @SuppressLint("MissingPermission", "SoonBlockedPrivateApi")
    private fun resendAirPodsAbsoluteVolume(reason: String, generation: Int) {
        if (!isAirPodsA2dpPlaying) {
            Log.d(TAG, "Skipping A2DP volume resync while not playing: $reason")
            return
        }

        val target = device
        if (target == null) {
            Log.w(TAG, "Cannot resend A2DP volume without an AirPods device: $reason")
            return
        }
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Using root for AirPods volume resync; app is not privileged: $reason")
            resendAirPodsAbsoluteVolumeAsRoot(target, reason, generation)
            return
        }
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.A2DP) return
                val a2dp = proxy as BluetoothA2dp
                try {
                    if (generation != a2dpVolumeResyncGeneration ||
                        !isAirPodsA2dpPlaying ||
                        a2dp.getConnectionState(target) != BluetoothProfile.STATE_CONNECTED
                    ) {
                        Log.d(TAG, "Skipping stale A2DP volume resend: $reason")
                        return
                    }

                    val (pulseVolume, volume) = currentA2dpVolumePulse(direct = true)
                    val method = BluetoothA2dp::class.java.getDeclaredMethod(
                        "setAvrcpAbsoluteVolume",
                        Int::class.javaPrimitiveType
                    )
                    method.isAccessible = true
                    // Despite the public method name/docs, Android's audio
                    // service supplies the active device-volume index here.
                    // HyperOS then maps its 0..150 index to AVRCP's 0..127.
                    method.invoke(a2dp, pulseVolume)
                    method.invoke(a2dp, volume)
                    lastA2dpVolumeResyncAt = SystemClock.elapsedRealtime()
                    Log.i(
                        TAG,
                        "Directly pulsed AirPods device volume=$pulseVolume->$volume: $reason"
                    )
                } catch (error: Exception) {
                    val cause = error.cause ?: error
                    Log.w(
                        TAG,
                        "Direct AirPods AVRCP volume resend failed; trying root: $reason",
                        cause
                    )
                    resendAirPodsAbsoluteVolumeAsRoot(target, reason, generation)
                } finally {
                    bluetoothAdapter.closeProfileProxy(profile, proxy)
                }
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }

        if (!bluetoothAdapter.getProfileProxy(this, listener, BluetoothProfile.A2DP)) {
            Log.w(TAG, "Could not obtain A2DP proxy for volume resend: $reason")
            resendAirPodsAbsoluteVolumeAsRoot(target, reason, generation)
        }
    }

    private fun resendAirPodsAbsoluteVolumeAsRoot(
        target: BluetoothDevice,
        reason: String,
        generation: Int
    ) {
        if (generation != a2dpVolumeResyncGeneration || !isAirPodsA2dpPlaying) return
        val (pulseVolume, volume) = currentA2dpVolumePulse(direct = true)
        audioFeatureScope.launch(Dispatchers.IO) {
            val result = rootAvrcpVolumeController.resend(
                target.address,
                pulseVolume,
                volume
            )
            withContext(Dispatchers.Main) {
                if (result.success) {
                    Log.i(
                        TAG,
                        "Root pulsed AirPods device volume=$pulseVolume->$volume: $reason"
                    )
                    lastA2dpVolumeResyncAt = SystemClock.elapsedRealtime()
                    prewarmAirPodsAbsoluteVolumeResync("root volume resync complete")
                } else {
                    Log.w(TAG, "Root AirPods AVRCP resend failed: ${result.output}")
                    if (generation == a2dpVolumeResyncGeneration) {
                        pulseAirPodsAbsoluteVolume(reason)
                    }
                }
            }
        }
    }

    private fun prewarmAirPodsAbsoluteVolumeResync(reason: String) {
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val target = device ?: return
        audioFeatureScope.launch(Dispatchers.IO) {
            val result = rootAvrcpVolumeController.prewarm(target.address)
            if (result.success) {
                Log.i(TAG, "Prewarmed root AirPods volume bridge: $reason")
            } else {
                Log.w(TAG, "Could not prewarm root AirPods volume bridge: ${result.output}")
            }
        }
    }

    fun onLocalMediaPlaybackStarting() {
        if (isAirPodsA2dpPlaying || earlyA2dpVolumeResyncInFlight) return
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val target = device ?: return
        val (pulseVolume, volume) = currentA2dpVolumePulse(direct = true)
        earlyA2dpVolumeResyncInFlight = true
        Log.i(TAG, "Triggering prewarmed AirPods volume bridge from local media start")
        audioFeatureScope.launch(Dispatchers.IO) {
            val result = rootAvrcpVolumeController.resend(
                target.address,
                pulseVolume,
                volume,
                awaitPlayback = true
            )
            withContext(Dispatchers.Main) {
                earlyA2dpVolumeResyncInFlight = false
                if (result.success) {
                    Log.i(
                        TAG,
                        "Early root pulse restored AirPods device volume=" +
                            "$pulseVolume->$volume"
                    )
                    lastA2dpVolumeResyncAt = SystemClock.elapsedRealtime()
                    prewarmAirPodsAbsoluteVolumeResync("early volume resync complete")
                } else {
                    Log.w(TAG, "Early root AirPods AVRCP resend failed: ${result.output}")
                    if (isAirPodsA2dpPlaying) {
                        pulseAirPodsAbsoluteVolume("early root AVRCP fallback")
                    }
                }
            }
        }
    }

    private fun pulseAirPodsAbsoluteVolume(reason: String) {
        if (!isAirPodsA2dpPlaying) {
            Log.d(TAG, "Skipping A2DP volume pulse while not playing: $reason")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastA2dpVolumeResyncAt < A2DP_VOLUME_RESYNC_DEBOUNCE_MS) {
            Log.d(TAG, "Skipping duplicate A2DP volume pulse: $reason")
            return
        }

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        // AirPods/HyperOS quantizes AVRCP volume to roughly 15 steps. A same-
        // index write, or a one-unit change on fine-volume devices, is filtered
        // before it reaches the earbuds. Pulse exactly one effective step and
        // restore the cached phone value after the first command is delivered.
        val (pulseVolume, volume) = currentA2dpVolumePulse(direct = false)
        lastA2dpVolumeResyncAt = now
        if (pulseVolume == volume) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
            Log.i(TAG, "Resent AirPods A2DP volume=$volume: $reason")
            return
        }

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, pulseVolume, 0)
        Handler(Looper.getMainLooper()).postDelayed({
            // Do not overwrite a real user adjustment made during the pulse.
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == pulseVolume) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                Log.i(
                    TAG,
                    "Restored AirPods A2DP volume=$volume after pulse=$pulseVolume: $reason"
                )
            } else {
                Log.i(TAG, "User changed volume during A2DP resync; not restoring $volume")
            }
        }, A2DP_VOLUME_PULSE_MS)
        Log.i(TAG, "Pulsed AirPods A2DP volume=$pulseVolume from $volume: $reason")
    }

    private fun calculateA2dpVolumePulse(
        volume: Int,
        minimum: Int,
        maximum: Int
    ): Int {
        // AirPods/HyperOS quantizes absolute volume to roughly 15 steps.
        // Move one effective step so the Bluetooth cache cannot discard the
        // following restore as a same-value write.
        val step = ((maximum - minimum) / 15).coerceAtLeast(1)
        return if (volume + step <= maximum) {
            volume + step
        } else {
            (volume - step).coerceAtLeast(minimum)
        }
    }

    private fun currentA2dpVolumePulse(direct: Boolean): Pair<Int, Int> {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val minimum = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val pulse = if (direct) {
            calculateDirectA2dpVolumePulse(volume, minimum, maximum)
        } else {
            calculateA2dpVolumePulse(volume, minimum, maximum)
        }
        return pulse to volume
    }

    private fun calculateDirectA2dpVolumePulse(
        volume: Int,
        minimum: Int,
        maximum: Int
    ): Int {
        val range = maximum - minimum
        if (range <= 0) return volume
        fun toAvrcp(deviceVolume: Int): Int {
            val normalized = (deviceVolume - minimum).coerceIn(0, range)
            return ((normalized.toLong() * 127L + range / 2L) / range).toInt()
        }

        val currentAvrcp = toAvrcp(volume)
        for (candidate in (volume + 1)..maximum) {
            if (toAvrcp(candidate) != currentAvrcp) return candidate
        }
        for (candidate in (volume - 1) downTo minimum) {
            if (toAvrcp(candidate) != currentAvrcp) return candidate
        }
        return volume
    }

    private fun updateSpatialAudioTracking(reason: String) {
        val shouldRun = shouldRunSpatialAudio()
        val transitionId = ++spatialAudioTransitionId
        if (shouldRun) {
            Log.i(TAG, "Starting spatial tracking: $reason")
            // Android can select a head-tracking mode only after the matching
            // dynamic sensor exists, so create UHID before changing the mode.
            startHeadTracking()
            audioFeatureScope.launch(Dispatchers.IO) {
                spatialAudioTransitionMutex.withLock {
                    if (transitionId != spatialAudioTransitionId) return@withLock
                    var state = spatialAudioController.setMode(SpatialAudioMode.HEAD_TRACKED)
                    if (state.error == null && state.actualMode != 1 &&
                        transitionId == spatialAudioTransitionId && shouldRunSpatialAudio()
                    ) {
                        // HyperOS can keep a previously registered dynamic
                        // head tracker stuck in DISABLED even though it reports
                        // available. Recreating the UHID device makes the native
                        // Spatializer bind the new sensor handle and apply the
                        // already-requested RELATIVE_WORLD mode.
                        Log.w(
                            TAG,
                            "Spatializer actual mode stayed ${state.actualMode}; " +
                                "recreating the head tracker"
                        )
                        withContext(Dispatchers.Main) {
                            spatialHeadTrackerBridge.stop()
                            HeadTracking.reset()
                        }
                        // DynamicSensorManager removes UHID sensors
                        // asynchronously. Starting the replacement too soon
                        // can make it overlap the old device with the same UUID.
                        delay(1500)
                        if (transitionId != spatialAudioTransitionId ||
                            !shouldRunSpatialAudio()
                        ) {
                            return@withLock
                        }
                        withContext(Dispatchers.Main) {
                            startHeadTracking()
                        }
                        // Give the HID raw sensor service time to publish the
                        // replacement handle before asking AudioService again.
                        delay(2500)
                        if (transitionId != spatialAudioTransitionId ||
                            !shouldRunSpatialAudio()
                        ) {
                            return@withLock
                        }
                        state = spatialAudioController.setMode(SpatialAudioMode.HEAD_TRACKED)
                    }
                    Log.i(
                        TAG,
                        "Spatializer mode for '$reason': desired=${state.desiredMode}, " +
                            "actual=${state.actualMode}, error=${state.error}"
                    )
                }
            }
        } else {
            Log.i(TAG, "Stopping spatial tracking: $reason")
            // Keep UHID alive until AudioService confirms the disabled mode;
            // otherwise some vendor implementations retain a stale actual=1.
            audioFeatureScope.launch(Dispatchers.IO) {
                spatialAudioTransitionMutex.withLock {
                    if (transitionId != spatialAudioTransitionId) return@withLock
                    val selectedMode = SpatialAudioMode.fromPreferences(sharedPreferences)
                    val inactiveMode = if (selectedMode == SpatialAudioMode.OFF) {
                        SpatialAudioMode.OFF
                    } else {
                        SpatialAudioMode.FIXED
                    }
                    val state = spatialAudioController.setMode(inactiveMode)
                    Log.i(
                        TAG,
                        "Spatializer mode=$inactiveMode for '$reason': " +
                            "enabled=${state.spatializerEnabled}, desired=${state.desiredMode}, " +
                            "actual=${state.actualMode}, error=${state.error}"
                    )
                    withContext(Dispatchers.Main) {
                        if (transitionId == spatialAudioTransitionId && !shouldRunSpatialAudio()) {
                            stopHeadTracking(force = true)
                        }
                    }
                }
            }
        }
    }

    fun startHeadTracking() {
        isHeadTrackingActive = true
        val useAlternatePackets =
            sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && aacpManager.getControlCommandStatus(
                AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION
            )?.value?.get(0)?.toInt() != 1
        ) {
            Log.w(TAG, "Not taking over ownership for head tracking; tracking might not work")
        } else {
            Log.d(TAG, "Already own the connection; starting head tracking")
        }
        if (useAlternatePackets) {
            aacpManager.sendDataPacket(aacpManager.createAlternateStartHeadTrackingPacket())
        } else {
            aacpManager.sendStartHeadTracking()
        }
        HeadTracking.reset()
        if (shouldRunSpatialAudio()) {
            val trackerMac = device?.address
                ?: macAddress.takeIf { it.isNotBlank() }
                ?: sharedPreferences.getString("mac_address", null)
            if (trackerMac == null) {
                Log.e(TAG, "Cannot start spatial head tracker: no AirPods Bluetooth address")
            } else {
                Log.d(TAG, "Starting spatial head tracker for $trackerMac")
                spatialHeadTrackerBridge.start(trackerMac)
            }
        }
    }

    fun stopHeadTracking(force: Boolean = false) {
        if (!force && shouldRunSpatialAudio()) {
            Log.d(TAG, "Keeping head tracking active for spatial audio")
            return
        }
        val useAlternatePackets =
            sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true)
        if (useAlternatePackets) {
            aacpManager.sendDataPacket(aacpManager.createAlternateStopHeadTrackingPacket())
        } else {
            aacpManager.sendStopHeadTracking()
        }
        isHeadTrackingActive = false
        spatialHeadTrackerBridge.stop()
        gestureDetector?.stopDetection(doNotStop = true)
    }

    @SuppressLint("MissingPermission")
    fun reconnectFromSavedMac() {
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        device = bluetoothAdapter.bondedDevices.find {
            it.address == macAddress
        }
        if (device != null) {
            CoroutineScope(Dispatchers.IO).launch {
                Log.d(TAG, "connecting to $macAddress")
                connectToSocket(bluetoothAdapter, device!!, manual = true)
                connectAudio(this@AirPodsService, device!!)
            }
        }
    }
}

private fun Int.dpToPx(): Int {
    val density = Resources.getSystem().displayMetrics.density
    return (this * density).toInt()
}

fun getNextMode(currentMode: Int, configByte: Int, offmodeEnabled: Boolean): Int {
    val enabledModes = buildList {
        if ((configByte and 0x01) != 0 && offmodeEnabled) add(1)
        if ((configByte and 0x04) != 0) add(3)
        if ((configByte and 0x08) != 0) add(4)
        if ((configByte and 0x02) != 0) add(2)
    }
    Log.d(TAG, "currentMode: $currentMode, config: ${configByte.toString(2)}")

    if (enabledModes.isEmpty()) return currentMode

    val currentIndex = enabledModes.indexOf(currentMode)
    val nextIndex = if (currentIndex == -1) 0 else (currentIndex + 1) % enabledModes.size

    return enabledModes[nextIndex]
}
