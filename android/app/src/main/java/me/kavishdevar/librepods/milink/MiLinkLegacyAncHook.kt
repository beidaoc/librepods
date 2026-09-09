package me.kavishdevar.librepods.milink

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import me.kavishdevar.librepods.R

/** Legacy three-button layout, resolved by semantic method and structural constraints. */
internal class MiLinkLegacyAncHook private constructor(
    private val constructor: Constructor<*>,
    private val updateMode: Method,
    private val querySupport: Method,
    private val click: Method,
    private val showCard: Method,
    private val showMode: Method,
    private val detailField: Field,
    private val itemsField: Field,
) {
    private class Entry {
        var pending: MiLinkLegacyAncSelection.Pending? = null
        var styled = false
        var managed = false
    }

    private val entries = WeakHashMap<Any, Entry>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var isTarget: (Any?) -> Boolean
    private lateinit var reportedMode: () -> Int
    private lateinit var log: (String) -> Unit

    fun install(
        module: XposedModule,
        initialize: (Context) -> Unit,
        isTarget: (Any?) -> Boolean,
        reportedMode: () -> Int,
        sendCommand: (Int) -> Boolean,
        setIcon: (ImageView?, Context?, Int) -> Unit,
        log: (String) -> Unit,
    ) {
        this.isTarget = isTarget
        this.reportedMode = reportedMode
        this.log = log

        fun prepare(presenter: Any): Entry {
            val entry = entries.getOrPut(presenter) { Entry() }
            if (!entry.styled) {
                val detail = detailField.get(presenter) as View
                val moduleContext = runCatching {
                    detail.context.createPackageContext(
                        MiLinkAirPodsBridgeContract.LIBREPODS_PACKAGE,
                        Context.CONTEXT_IGNORE_SECURITY,
                    )
                }.getOrNull()
                val labels = listOf(R.string.transparency, R.string.adaptive, R.string.noise_cancellation)
                val icons = listOf(R.drawable.transparency, R.drawable.adaptive, R.drawable.noise_cancellation)
                items(presenter).forEachIndexed { index, item ->
                    val title = child(item, "anc_title") as? TextView
                    val label = moduleContext?.getString(labels[index])
                        ?: listOf("通透", "自适应", "降噪")[index]
                    title?.text = label
                    item.contentDescription = label
                    setIcon(child(item, "anc_icon") as? ImageView, moduleContext, icons[index])
                }
                entry.styled = true
                log("Legacy ANC card bound: presenter=${updateMode.declaringClass.name}.${updateMode.name}, slots=transparency,adaptive,noise_cancellation")
            }
            entry.managed = true
            items(presenter).forEach { it.visibility = View.VISIBLE }
            return entry
        }

        module.hook(constructor).intercept { chain ->
            chain.proceed().also { presenter ->
                if (presenter != null) {
                    entries[presenter] = Entry()
                    (detailField.get(presenter) as? View)?.let { initialize(it.context) }
                }
            }
        }
        module.hook(updateMode).intercept { chain ->
            val presenter = chain.thisObject ?: return@intercept chain.proceed()
            if (!target(presenter)) {
                restorePresentation(presenter)
                return@intercept chain.proceed()
            }
            val entry = prepare(presenter)
            val mode = MiLinkLegacyAncSelection.displayMode(
                reportedMode(), entry.pending, SystemClock.uptimeMillis(),
            )
            log("Legacy ANC display: host=${chain.args[0]}, resolved=$mode, reported=${reportedMode()}, pending=${entry.pending?.mode}")
            chain.proceed(arrayOf(mode)).also {
                if (mode == -1) {
                    // Off is not a fourth button, but must not make the three controls disappear.
                    showCard.invoke(presenter, true)
                    showMode.invoke(detailField.get(presenter), true)
                    items(presenter).forEach { it.isSelected = false }
                }
                items(presenter).forEach { item ->
                    child(item, "anc_icon")?.isSelected = item.isSelected
                }
            }
        }
        module.hook(querySupport).intercept { chain ->
            val presenter = chain.thisObject ?: return@intercept chain.proceed()
            if (!target(presenter)) {
                restorePresentation(presenter)
                return@intercept chain.proceed()
            }
            // The native support query uses Xiaomi's device database; this card is served by AACP.
            prepare(presenter)
            render(presenter)
            null
        }
        module.hook(click).intercept { chain ->
            val presenter = chain.thisObject ?: return@intercept chain.proceed()
            if (!target(presenter)) {
                restorePresentation(presenter)
                return@intercept chain.proceed()
            }
            val mode = MiLinkLegacyAncSelection.commandMode(chain.args[1] as? Int ?: -1)
            if (mode == null) {
                log("Legacy ANC click rejected: unknown presenter mode=${chain.args[1]}")
                return@intercept null
            }
            val entry = prepare(presenter)
            val previous = entry.pending
            if (mode == (previous?.mode ?: reportedMode())) return@intercept null
            if (!sendCommand(mode)) {
                log("Legacy ANC click not sent: mode=$mode")
                return@intercept null
            }
            val pending = MiLinkLegacyAncSelection.Pending(
                mode, SystemClock.uptimeMillis() + MiLinkLegacyAncSelection.TIMEOUT_MS,
            )
            entry.pending = pending
            log("Legacy ANC click sent: presenter=${chain.args[1]}, mode=$mode")
            render(presenter)
            val weakPresenter = WeakReference(presenter)
            handler.postDelayed({
                val current = weakPresenter.get()
                if (current != null && entries[current]?.pending === pending) {
                    entries[current]?.pending = null
                    log("Legacy ANC confirmation timeout: requested=$mode, reported=${reportedMode()}")
                    if (target(current)) render(current)
                }
            }, MiLinkLegacyAncSelection.TIMEOUT_MS)
            // Do not also run the native setter: it would use a second control path and mapping.
            null
        }
        log("Legacy ANC hooks installed: update=$updateMode, support=$querySupport, click=$click")
    }

    /** Called for LibrePods state broadcasts, never for an optimistic host update. */
    fun onStateChanged(ancMode: Int, isAncReport: Boolean) {
        handler.post {
            entries.keys.toList().forEach { presenter ->
                val entry = entries[presenter] ?: return@forEach
                if (!target(presenter)) {
                    entry.pending = null
                    if (entry.managed) {
                        restorePresentation(presenter)
                        // Restore the host's support query when LibrePods no longer owns this card.
                        runCatching { querySupport.invoke(presenter) }
                    }
                    return@forEach
                }
                val previous = entry.pending
                entry.pending = MiLinkLegacyAncSelection.pendingAfterReport(
                    previous, ancMode, SystemClock.uptimeMillis(), isAncReport,
                )
                if (previous != null && entry.pending == null) {
                    log("Legacy ANC report: requested=${previous.mode}, reported=$ancMode, confirmed=${isAncReport && previous.mode == ancMode}")
                }
                render(presenter)
            }
        }
    }

    private fun render(presenter: Any) {
        runCatching { updateMode.invoke(presenter, -2) }
            .onFailure { log("Legacy ANC render failed: ${it.javaClass.simpleName}") }
    }

    private fun restorePresentation(presenter: Any) {
        val entry = entries[presenter] ?: return
        entry.pending = null
        if (!entry.styled) return
        runCatching {
            val labels = listOf(
                "circulate_headset_control_anc_clear",
                "circulate_headset_control_anc_noise_cancel",
                "circulate_headset_control_anc_off",
            )
            val icons = listOf("transparency_selector", "anc_selector", "off_selector")
            items(presenter).forEachIndexed { index, item ->
                val resources = item.resources
                val packageName = item.context.packageName
                val label = resources.getIdentifier(labels[index], "string", packageName)
                if (label != 0) {
                    (child(item, "anc_title") as? TextView)?.setText(label)
                    item.contentDescription = resources.getString(label)
                }
                val icon = resources.getIdentifier(icons[index], "drawable", packageName)
                if (icon != 0) (child(item, "anc_icon") as? ImageView)?.apply {
                    isSelected = false
                    setImageResource(icon)
                }
            }
        }.onFailure { log("Legacy ANC presentation restore failed: ${it.javaClass.simpleName}") }
        entry.styled = false
        entry.managed = false
    }

    private fun target(presenter: Any): Boolean = runCatching {
        isTarget(detailField.get(presenter)) && items(presenter).map {
            it.resources.getResourceEntryName(it.id)
        } == listOf("anc_clear", "anc_noise_cancel", "anc_off")
    }.getOrDefault(false)

    private fun items(presenter: Any): List<View> =
        (itemsField.get(presenter) as Array<*>).filterIsInstance<View>()

    private fun child(view: View, name: String): View? =
        view.resources.getIdentifier(name, "id", view.context.packageName)
            .takeIf { it != 0 }?.let { view.findViewById(it) }

    companion object {
        /** Resolve the complete known layout before installing any legacy UI hook. */
        fun resolve(loader: ClassLoader, update: Method?): MiLinkLegacyAncHook? = runCatching {
            val owner = requireNotNull(update).declaringClass
            val detail = Class.forName("com.miui.circulateplus.world.headset.HeadSetsDetail", false, loader)
            Class.forName("com.miui.circulate.world.headset.ui.HeadsetControlAncItemView", false, loader)
            fun method(vararg parameters: Class<*>): Method = owner.declaredMethods.single {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.returnType == Void.TYPE && it.parameterTypes.contentEquals(parameters)
            }.apply { isAccessible = true }
            val detailField = owner.declaredFields.single { it.type == detail }.apply { isAccessible = true }
            val itemsField = owner.declaredFields.single { it.type == Array<View>::class.java }.apply { isAccessible = true }
            MiLinkLegacyAncHook(
                owner.getDeclaredConstructor(detail).apply { isAccessible = true },
                update,
                method(),
                method(View::class.java, Int::class.javaPrimitiveType!!, String::class.java),
                method(Boolean::class.javaPrimitiveType!!),
                detail.getDeclaredMethod("setModeVisible", Boolean::class.javaPrimitiveType!!).apply {
                    check(returnType == Void.TYPE)
                    isAccessible = true
                },
                detailField,
                itemsField,
            )
        }.getOrNull()
    }
}
