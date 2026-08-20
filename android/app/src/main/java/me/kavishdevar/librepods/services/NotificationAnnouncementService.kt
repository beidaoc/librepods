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
            .setOnAudioFocusChangeListener { }
            .build()
    }

    private lateinit var keyguardManager: KeyguardManager
    private lateinit var audioManager: AudioManager
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var ttsGeneration = 0
    private var activeUtteranceId: String? = null
    private var audioFocusHeld = false
    private var pendingFlushScheduled = false
    private val pendingFlush = Runnable {
        pendingFlushScheduled = false
        if (!canAnnounceNow()) {
            pendingAnnouncements.clear()
            return@Runnable
        }
        pendingAnnouncements.poll()?.let(::speak)
    }
    private val audioFocusSafetyRelease = Runnable {
        if (activeUtteranceId != null) {
            Log.w(TAG, "TTS completion timed out; restoring media audio focus")
            textToSpeech?.stop()
            activeUtteranceId = null
            pendingAnnouncements.clear()
            finishAnnouncementPlayback()
        }
    }

    override fun onCreate() {
        super.onCreate()
        keyguardManager = getSystemService(KeyguardManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .getBoolean(PREFERENCE_ENABLED, false)
        ) {
            handler.post { initializeTextToSpeech(preferredEnginePackage()) }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (!getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .getBoolean(PREFERENCE_ENABLED, false)
        ) {
            return
        }
        if (!canAnnounceNow()) return
        if (!isEligible(notification)) return

        val announcement = buildAnnouncement(notification) ?: return
        handler.post {
            if (!canAnnounceNow()) return@post
            if (isDuplicate(notification.key, announcement.spokenText)) return@post
            speakOrQueue(announcement)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        activeUtteranceId = null
        pendingFlushScheduled = false
        pendingAnnouncements.clear()
        finishAnnouncementPlayback()
        super.onDestroy()
    }

    private fun initializeTextToSpeech(enginePackage: String?) {
        if (textToSpeech != null) return
        val generation = ++ttsGeneration
        textToSpeech = TextToSpeech(this, { status ->
            if (generation != ttsGeneration) return@TextToSpeech
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
                flushPendingAnnouncements()
            } else if (enginePackage != null) {
                Log.w(TAG, "XiaoAI TTS initialization failed; falling back to the default engine")
                textToSpeech?.shutdown()
                textToSpeech = null
                initializeTextToSpeech(null)
            } else {
                Log.e(TAG, "Unable to initialize a TTS engine")
                textToSpeech?.shutdown()
                textToSpeech = null
                pendingAnnouncements.clear()
            }
        }, enginePackage)
    }

    private val utteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            handler.post { finishUtterance(utteranceId) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            handler.post { finishUtterance(utteranceId) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            handler.post { finishUtterance(utteranceId) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            handler.post { finishUtterance(utteranceId) }
        }
    }

    private fun finishUtterance(utteranceId: String?) {
        if (utteranceId == null || utteranceId != activeUtteranceId) return
        activeUtteranceId = null
        handler.removeCallbacks(audioFocusSafetyRelease)

        if (!canAnnounceNow()) {
            pendingAnnouncements.clear()
            finishAnnouncementPlayback()
            return
        }

        finishAnnouncementPlayback()
        schedulePendingFlush()
    }

    private fun speakOrQueue(announcement: NotificationAnnouncement) {
        if (textToSpeech == null) initializeTextToSpeech(preferredEnginePackage())
        if (!ttsReady || activeUtteranceId != null || pendingFlushScheduled) {
            pendingAnnouncements.offer(announcement)
            return
        }
        speak(announcement)
    }

    private fun flushPendingAnnouncements() {
        if (!canAnnounceNow()) {
            pendingAnnouncements.clear()
            return
        }
        pendingAnnouncements.poll()?.let(::speak)
    }

    private fun speak(announcement: NotificationAnnouncement) {
        if (!canAnnounceNow()) {
            pendingAnnouncements.clear()
            finishAnnouncementPlayback()
            return
        }
        if (activeUtteranceId != null) {
            pendingAnnouncements.offer(announcement)
            return
        }
        val engine = textToSpeech ?: run {
            finishAnnouncementPlayback()
            return
        }
        if (!audioFocusHeld) {
            NotificationAnnouncementPlayback.begin()
            if (audioManager.requestAudioFocus(audioFocusRequest) !=
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            ) {
                Log.w(TAG, "Skipping notification announcement because audio focus was denied")
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
        if (engine.speak(
                announcement.spokenText,
                TextToSpeech.QUEUE_ADD,
                parameters,
                utteranceId
            ) ==
            TextToSpeech.SUCCESS
        ) {
            activeUtteranceId = utteranceId
            handler.removeCallbacks(audioFocusSafetyRelease)
            handler.postDelayed(audioFocusSafetyRelease, AUDIO_FOCUS_SAFETY_TIMEOUT_MS)
        } else {
            pendingAnnouncements.clear()
            finishAnnouncementPlayback()
        }
    }

    private fun schedulePendingFlush() {
        if (pendingAnnouncements.size() == 0 || pendingFlushScheduled) return
        pendingFlushScheduled = true
        handler.postDelayed(pendingFlush, LATEST_MESSAGE_SETTLE_MS)
    }

    private fun finishAnnouncementPlayback() {
        if (audioFocusHeld) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
            audioFocusHeld = false
        }
        NotificationAnnouncementPlayback.end()
    }

    private fun isEligible(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        if (sbn.packageName == packageName) return false
        if (notification.visibility == Notification.VISIBILITY_SECRET) return false
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (!sbn.isClearable) return false
        if (notification.category in EXCLUDED_CATEGORIES) return false
        return true
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

    private fun hasConnectedAirPodsAudioOutput(): Boolean {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val savedAddress = preferences.getString("mac_address", null)
        val savedName = preferences.getString("name", "AirPods") ?: "AirPods"
        val serviceAddress = ServiceManager.getService()?.device?.address

        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            val isBluetoothAudio = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            if (!isBluetoothAudio) return@any false

            val addressMatches = listOfNotNull(serviceAddress, savedAddress).any {
                it.equals(device.address, ignoreCase = true)
            }
            val nameMatches = device.productName.toString().contains(savedName, ignoreCase = true) ||
                device.productName.toString().contains("AirPods", ignoreCase = true)
            addressMatches || nameMatches
        }
    }

    private fun canAnnounceNow(): Boolean {
        if (!keyguardManager.isKeyguardLocked || !hasConnectedAirPodsAudioOutput()) return false
        val airPodsService = ServiceManager.getService()
        val locallyOwned = airPodsService?.canPlayNotificationAnnouncement() == true
        if (!locallyOwned) {
            Log.d(TAG, "Skipping announcement because AirPods audio is not owned by this phone")
        }
        return locallyOwned
    }

    private fun preferredEnginePackage(): String? =
        XIAOMI_TTS_PACKAGE.takeIf { isXiaomiTtsAvailable(this) }

    companion object {
        private const val TAG = "NotificationAnnounce"
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
}
