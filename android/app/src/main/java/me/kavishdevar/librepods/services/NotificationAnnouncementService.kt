/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.services

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import me.kavishdevar.librepods.notifications.LatestNotificationAnnouncementQueue
import me.kavishdevar.librepods.notifications.NotificationAnnouncement
import me.kavishdevar.librepods.notifications.NotificationAnnouncementFormatter
import me.kavishdevar.librepods.notifications.NotificationAnnouncementPlayback
import java.util.Locale

class NotificationAnnouncementService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val pendingAnnouncements = LatestNotificationAnnouncementQueue(
        MAX_PENDING_CONVERSATIONS
    )
    private val recentAnnouncements = LinkedHashMap<String, Long>()
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change ->
                trace("audio_focus_changed", "change=$change")
            }
            .build()
    }

    private lateinit var keyguardManager: KeyguardManager
    private lateinit var audioManager: AudioManager
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var ttsGeneration = 0
    private var activeUtteranceId: String? = null
    private var activeConversationToken: String? = null
    private var activeUtteranceStartedAt = 0L
    private var audioFocusHeld = false
    private var pendingFlushScheduled = false
    private val pendingFlush = Runnable {
        pendingFlushScheduled = false
        trace("queue_flush", "pending=${pendingAnnouncements.size()}")
        if (!canAnnounceNow("pending_flush")) {
            trace("queue_cleared", "reason=route_blocked pending=${pendingAnnouncements.size()}")
            pendingAnnouncements.clear()
            return@Runnable
        }
        pendingAnnouncements.poll()?.let { announcement ->
            trace(
                "queue_poll",
                "conversation=${token(announcement.conversationKey)} pending=${pendingAnnouncements.size()}"
            )
            speak(announcement, "pending_flush")
        }
    }
    private val audioFocusSafetyRelease = Runnable {
        if (activeUtteranceId != null) {
            Log.w(TAG, "TTS completion timed out; restoring media audio focus")
            trace(
                "tts_timeout",
                "conversation=${activeConversationToken ?: "none"} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - activeUtteranceStartedAt}"
            )
            textToSpeech?.stop()
            activeUtteranceId = null
            activeConversationToken = null
            activeUtteranceStartedAt = 0L
            pendingAnnouncements.clear()
            finishAnnouncementPlayback()
        }
    }

    override fun onCreate() {
        super.onCreate()
        keyguardManager = getSystemService(KeyguardManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        trace("service_created", "featureEnabled=${announcementsEnabled()}")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val enabled = announcementsEnabled()
        trace(
            "listener_connected",
            "featureEnabled=$enabled xiaomiTtsAvailable=${isXiaomiTtsAvailable(this)}"
        )
        if (enabled) {
            handler.post { initializeTextToSpeech(preferredEnginePackage()) }
        }
    }

    override fun onListenerDisconnected() {
        trace("listener_disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: run {
            trace("notification_ignored", "reason=null_status_bar_notification")
            return
        }
        val notificationToken = token(notification.key)
        val enabled = announcementsEnabled()
        trace(
            "notification_received",
            "notification=$notificationToken app=${notification.packageName} " +
                "category=${notification.notification.category ?: "none"} " +
                "clearable=${notification.isClearable} featureEnabled=$enabled"
        )
        if (!enabled) {
            trace(
                "notification_ignored",
                "notification=$notificationToken app=${notification.packageName} " +
                    "reason=feature_disabled"
            )
            return
        }
        ineligibilityReason(notification)?.let { reason ->
            trace(
                "notification_ignored",
                "notification=$notificationToken app=${notification.packageName} reason=$reason"
            )
            return
        }
        if (!canAnnounceNow("notification_received", notificationToken, notification.packageName)) {
            return
        }

        val announcement = buildAnnouncement(notification) ?: run {
            trace(
                "notification_ignored",
                "notification=$notificationToken app=${notification.packageName} " +
                    "reason=no_speakable_content"
            )
            return
        }
        val conversationToken = token(announcement.conversationKey)
        trace(
            "announcement_built",
            "notification=$notificationToken app=${notification.packageName} " +
                "conversation=$conversationToken"
        )
        handler.post {
            if (!canAnnounceNow("handler_dispatch", notificationToken, notification.packageName)) {
                return@post
            }
            if (isDuplicate(notification.key, announcement.spokenText)) {
                trace(
                    "notification_ignored",
                    "notification=$notificationToken app=${notification.packageName} " +
                        "conversation=$conversationToken reason=duplicate"
                )
                return@post
            }
            speakOrQueue(announcement, notificationToken, notification.packageName)
        }
    }

    override fun onDestroy() {
        trace(
            "service_destroyed",
            "active=${activeUtteranceId != null} pending=${pendingAnnouncements.size()} " +
                "audioFocusHeld=$audioFocusHeld"
        )
        handler.removeCallbacksAndMessages(null)
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        activeUtteranceId = null
        activeConversationToken = null
        activeUtteranceStartedAt = 0L
        pendingFlushScheduled = false
        pendingAnnouncements.clear()
        finishAnnouncementPlayback()
        super.onDestroy()
    }

    private fun initializeTextToSpeech(enginePackage: String?) {
        if (textToSpeech != null) {
            trace("tts_initialization_skipped", "reason=already_initialized")
            return
        }
        val generation = ++ttsGeneration
        trace(
            "tts_initialization_requested",
            "engine=${enginePackage ?: "system_default"} generation=$generation"
        )
        textToSpeech = TextToSpeech(this, { status ->
            if (generation != ttsGeneration) {
                trace(
                    "tts_initialization_ignored",
                    "reason=stale_generation generation=$generation current=$ttsGeneration"
                )
                return@TextToSpeech
            }
            if (status == TextToSpeech.SUCCESS) {
                val engine = textToSpeech ?: return@TextToSpeech
                engine.setAudioAttributes(audioAttributes)
                val languageResult = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "Simplified Chinese is unavailable in the selected TTS engine")
                }
                engine.setOnUtteranceProgressListener(utteranceProgressListener)
                ttsReady = true
                Log.i(TAG, "TTS ready with engine=${engine.defaultEngine}")
                trace(
                    "tts_ready",
                    "engine=${engine.defaultEngine} languageResult=$languageResult generation=$generation"
                )
                flushPendingAnnouncements()
            } else if (enginePackage != null) {
                Log.w(TAG, "XiaoAI TTS initialization failed; falling back to the default engine")
                trace(
                    "tts_initialization_failed",
                    "engine=$enginePackage status=$status fallback=system_default"
                )
                textToSpeech?.shutdown()
                textToSpeech = null
                initializeTextToSpeech(null)
            } else {
                Log.e(TAG, "Unable to initialize a TTS engine")
                trace(
                    "tts_initialization_failed",
                    "engine=system_default status=$status fallback=none"
                )
                textToSpeech?.shutdown()
                textToSpeech = null
                trace("queue_cleared", "reason=tts_unavailable pending=${pendingAnnouncements.size()}")
                pendingAnnouncements.clear()
            }
        }, enginePackage)
    }

    private val utteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            trace(
                "tts_started",
                "conversation=${activeConversationToken ?: "none"} " +
                    "callbackMatches=${utteranceId != null && utteranceId == activeUtteranceId}"
            )
        }

        override fun onDone(utteranceId: String?) {
            handler.post { finishUtterance(utteranceId, "done") }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            handler.post { finishUtterance(utteranceId, "error_legacy") }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            handler.post { finishUtterance(utteranceId, "error", "errorCode=$errorCode") }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            handler.post {
                finishUtterance(utteranceId, "stopped", "interrupted=$interrupted")
            }
        }
    }

    private fun finishUtterance(
        utteranceId: String?,
        outcome: String,
        details: String = ""
    ) {
        if (utteranceId == null || utteranceId != activeUtteranceId) {
            trace(
                "tts_callback_ignored",
                "outcome=$outcome reason=utterance_mismatch hasActive=${activeUtteranceId != null}"
            )
            return
        }
        trace(
            "tts_finished",
            "conversation=${activeConversationToken ?: "none"} outcome=$outcome " +
                "durationMs=${SystemClock.elapsedRealtime() - activeUtteranceStartedAt}" +
                details.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
        )
        activeUtteranceId = null
        activeConversationToken = null
        activeUtteranceStartedAt = 0L
        handler.removeCallbacks(audioFocusSafetyRelease)

        if (!canAnnounceNow("utterance_finished")) {
            trace("queue_cleared", "reason=route_blocked pending=${pendingAnnouncements.size()}")
            pendingAnnouncements.clear()
            finishAnnouncementPlayback()
            return
        }

        finishAnnouncementPlayback()
        schedulePendingFlush()
    }

    private fun speakOrQueue(
        announcement: NotificationAnnouncement,
        notificationToken: String,
        sourcePackage: String
    ) {
        if (textToSpeech == null) initializeTextToSpeech(preferredEnginePackage())
        if (!ttsReady || activeUtteranceId != null || pendingFlushScheduled) {
            queueAnnouncement(
                announcement,
                when {
                    !ttsReady -> "tts_not_ready"
                    activeUtteranceId != null -> "utterance_active"
                    else -> "settle_window"
                },
                notificationToken,
                sourcePackage
            )
            return
        }
        speak(announcement, "immediate")
    }

    private fun flushPendingAnnouncements() {
        trace("queue_flush", "trigger=tts_ready pending=${pendingAnnouncements.size()}")
        if (!canAnnounceNow("tts_ready_flush")) {
            trace("queue_cleared", "reason=route_blocked pending=${pendingAnnouncements.size()}")
            pendingAnnouncements.clear()
            return
        }
        pendingAnnouncements.poll()?.let { announcement ->
            trace(
                "queue_poll",
                "conversation=${token(announcement.conversationKey)} pending=${pendingAnnouncements.size()}"
            )
            speak(announcement, "tts_ready_flush")
        }
    }

    private fun speak(announcement: NotificationAnnouncement, trigger: String) {
        val conversationToken = token(announcement.conversationKey)
        if (!canAnnounceNow("speak_$trigger")) {
            trace("queue_cleared", "reason=route_blocked pending=${pendingAnnouncements.size()}")
            pendingAnnouncements.clear()
            finishAnnouncementPlayback()
            return
        }
        if (activeUtteranceId != null) {
            queueAnnouncement(announcement, "utterance_active", "none", "none")
            return
        }
        val engine = textToSpeech ?: run {
            trace("tts_speak_rejected", "conversation=$conversationToken reason=engine_unavailable")
            finishAnnouncementPlayback()
            return
        }
        if (!audioFocusHeld) {
            NotificationAnnouncementPlayback.begin()
            val focusResult = audioManager.requestAudioFocus(audioFocusRequest)
            trace(
                "audio_focus_requested",
                "conversation=$conversationToken result=$focusResult"
            )
            if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "Skipping notification announcement because audio focus was denied")
                trace("tts_speak_rejected", "conversation=$conversationToken reason=audio_focus_denied")
                trace("queue_cleared", "reason=audio_focus_denied pending=${pendingAnnouncements.size()}")
                pendingAnnouncements.clear()
                NotificationAnnouncementPlayback.end()
                return
            }
            audioFocusHeld = true
        }

        val utteranceId = "notification-${SystemClock.elapsedRealtime()}"
        val parameters = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        activeUtteranceId = utteranceId
        activeConversationToken = conversationToken
        activeUtteranceStartedAt = SystemClock.elapsedRealtime()
        val speakResult = engine.speak(
            announcement.spokenText,
            TextToSpeech.QUEUE_ADD,
            parameters,
            utteranceId
        )
        trace(
            "tts_speak_requested",
            "conversation=$conversationToken trigger=$trigger result=$speakResult"
        )
        if (speakResult == TextToSpeech.SUCCESS) {
            handler.removeCallbacks(audioFocusSafetyRelease)
            handler.postDelayed(audioFocusSafetyRelease, AUDIO_FOCUS_SAFETY_TIMEOUT_MS)
        } else {
            activeUtteranceId = null
            activeConversationToken = null
            activeUtteranceStartedAt = 0L
            trace("tts_speak_rejected", "conversation=$conversationToken reason=tts_result_$speakResult")
            trace("queue_cleared", "reason=tts_rejected pending=${pendingAnnouncements.size()}")
            pendingAnnouncements.clear()
            finishAnnouncementPlayback()
        }
    }

    private fun schedulePendingFlush() {
        if (pendingAnnouncements.size() == 0 || pendingFlushScheduled) return
        pendingFlushScheduled = true
        trace(
            "queue_flush_scheduled",
            "pending=${pendingAnnouncements.size()} delayMs=$LATEST_MESSAGE_SETTLE_MS"
        )
        handler.postDelayed(pendingFlush, LATEST_MESSAGE_SETTLE_MS)
    }

    private fun finishAnnouncementPlayback() {
        if (audioFocusHeld) {
            val result = audioManager.abandonAudioFocusRequest(audioFocusRequest)
            audioFocusHeld = false
            trace("audio_focus_abandoned", "result=$result")
        }
        NotificationAnnouncementPlayback.end()
    }

    private fun ineligibilityReason(sbn: StatusBarNotification): String? {
        val notification = sbn.notification
        return when {
            sbn.packageName == packageName -> "self_notification"
            notification.visibility == Notification.VISIBILITY_SECRET -> "secret_visibility"
            notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 -> "group_summary"
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0 -> "ongoing_event"
            !sbn.isClearable -> "not_clearable"
            notification.category in EXCLUDED_CATEGORIES ->
                "excluded_category_${notification.category}"
            else -> null
        }
    }

    private fun buildAnnouncement(sbn: StatusBarNotification): NotificationAnnouncement? {
        val notification = sbn.notification
        val extras = notification.extras
        val appName = runCatching {
            val applicationInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(applicationInfo)
        }.getOrElse { sbn.packageName }.toString()

        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        )
        val latestMessage = messages.lastOrNull()
        if (latestMessage != null) {
            val sender = latestMessage.senderPerson?.name
                ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            val spokenText = NotificationAnnouncementFormatter.format(
                appName,
                sender,
                latestMessage.text
            ) ?: return null
            return NotificationAnnouncement(
                conversationKey = conversationKey(sbn, sender),
                spokenText = spokenText
            )
        }

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        val spokenText = NotificationAnnouncementFormatter.format(appName, title, text)
            ?: return null
        return NotificationAnnouncement(
            conversationKey = conversationKey(sbn, title),
            spokenText = spokenText
        )
    }

    private fun conversationKey(
        sbn: StatusBarNotification,
        fallbackTitle: CharSequence?
    ): String {
        val notification = sbn.notification
        val identity = notification.shortcutId
            ?: notification.extras
                .getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?.toString()
            ?: fallbackTitle?.toString()
            ?: sbn.key
        return "${sbn.packageName}\u0000$identity"
    }

    private fun isDuplicate(notificationKey: String, announcement: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        recentAnnouncements.entries.removeAll { now - it.value > DEDUPE_WINDOW_MS }
        val signature = "$notificationKey\u0000$announcement"
        if (recentAnnouncements.containsKey(signature)) return true
        recentAnnouncements[signature] = now
        while (recentAnnouncements.size > MAX_RECENT_ANNOUNCEMENTS) {
            recentAnnouncements.remove(recentAnnouncements.keys.first())
        }
        return false
    }

    private fun airPodsAudioOutputSnapshot(): AirPodsAudioOutputSnapshot {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val savedAddress = preferences.getString("mac_address", null)
        val savedName = preferences.getString("name", "AirPods") ?: "AirPods"
        val serviceAddress = ServiceManager.getService()?.device?.address
        var bluetoothOutputCount = 0
        var matchedType = AudioDeviceInfo.TYPE_UNKNOWN
        var matchedByAddress = false
        var matchedByName = false

        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { device ->
            val isBluetoothAudio = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            if (!isBluetoothAudio) return@forEach
            bluetoothOutputCount++

            val addressMatches = listOfNotNull(serviceAddress, savedAddress).any {
                it.equals(device.address, ignoreCase = true)
            }
            val nameMatches = device.productName.toString().contains(savedName, ignoreCase = true) ||
                device.productName.toString().contains("AirPods", ignoreCase = true)
            if (addressMatches || nameMatches) {
                matchedType = device.type
                matchedByAddress = matchedByAddress || addressMatches
                matchedByName = matchedByName || nameMatches
            }
        }
        return AirPodsAudioOutputSnapshot(
            matched = matchedType != AudioDeviceInfo.TYPE_UNKNOWN,
            bluetoothOutputCount = bluetoothOutputCount,
            matchedType = matchedType,
            matchedByAddress = matchedByAddress,
            matchedByName = matchedByName
        )
    }

    private fun canAnnounceNow(
        trigger: String,
        notificationToken: String = "none",
        sourcePackage: String = "none"
    ): Boolean {
        val locked = keyguardManager.isKeyguardLocked
        val output = airPodsAudioOutputSnapshot()
        val snapshot =
            "trigger=$trigger notification=$notificationToken app=$sourcePackage " +
                "locked=$locked bluetoothOutputs=${output.bluetoothOutputCount} " +
                "airPodsOutput=${output.matched} outputType=${output.matchedType} " +
                "matchedByAddress=${output.matchedByAddress} matchedByName=${output.matchedByName}"
        if (!locked) {
            trace("gate_blocked", "$snapshot reason=keyguard_unlocked")
            return false
        }
        if (!output.matched) {
            trace("gate_blocked", "$snapshot reason=no_airpods_audio_output")
            return false
        }
        val airPodsService = ServiceManager.getService()
        if (airPodsService == null) {
            trace("gate_blocked", "$snapshot reason=airpods_service_unavailable")
            return false
        }
        val locallyOwned = airPodsService.canPlayNotificationAnnouncement()
        if (!locallyOwned) {
            Log.d(TAG, "Skipping announcement because AirPods audio is not owned by this phone")
            trace("gate_blocked", "$snapshot reason=route_not_locally_owned")
            return false
        }
        trace("gate_allowed", snapshot)
        return true
    }

    private fun queueAnnouncement(
        announcement: NotificationAnnouncement,
        reason: String,
        notificationToken: String,
        sourcePackage: String
    ) {
        val result = pendingAnnouncements.offer(announcement)
        trace(
            "queue_offer",
            "notification=$notificationToken app=$sourcePackage " +
                "conversation=${token(announcement.conversationKey)} reason=$reason " +
                "result=${result.name.lowercase(Locale.US)} pending=${pendingAnnouncements.size()}"
        )
    }

    private fun announcementsEnabled(): Boolean =
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getBoolean(PREFERENCE_ENABLED, false)

    private fun trace(event: String, details: String = "") {
        Log.d(
            TRACE_TAG,
            if (details.isBlank()) "event=$event" else "event=$event $details"
        )
    }

    private fun token(value: String?): String =
        value?.let { Integer.toHexString(it.hashCode()) } ?: "none"

    private fun preferredEnginePackage(): String? =
        XIAOMI_TTS_PACKAGE.takeIf { isXiaomiTtsAvailable(this) }

    companion object {
        private const val TAG = "NotificationAnnounce"
        private const val TRACE_TAG = "NotificationAnnounceTrace"
        private const val PREFERENCES_NAME = "settings"
        const val PREFERENCE_ENABLED = "announce_notifications_on_lock_screen"
        const val XIAOMI_TTS_PACKAGE = "com.xiaomi.mibrain.speech"
        private const val DEDUPE_WINDOW_MS = 30_000L
        private const val AUDIO_FOCUS_SAFETY_TIMEOUT_MS = 60_000L
        private const val LATEST_MESSAGE_SETTLE_MS = 1_200L
        private const val MAX_RECENT_ANNOUNCEMENTS = 64
        private const val MAX_PENDING_CONVERSATIONS = 3

        private val EXCLUDED_CATEGORIES = setOf(
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_LOCATION_SHARING,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_STOPWATCH,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_WORKOUT
        )

        fun isXiaomiTtsAvailable(context: Context): Boolean {
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
                .setPackage(XIAOMI_TTS_PACKAGE)
            return context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
        }
    }

    private data class AirPodsAudioOutputSnapshot(
        val matched: Boolean,
        val bluetoothOutputCount: Int,
        val matchedType: Int,
        val matchedByAddress: Boolean,
        val matchedByName: Boolean
    )
}
