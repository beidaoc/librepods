package me.kavishdevar.librepods.milink

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher

/** Resolve semantic anchors rather than release numbers or R8-generated names. */
internal class MiLinkHookResolver(loader: ClassLoader, private val log: (String) -> Unit) : AutoCloseable {
    private val loader = loader
    private val bridge = runCatching {
        System.loadLibrary("dexkit")
        DexKitBridge.create(loader, false)
    }.onFailure { log("MiLink resolver unavailable: ${it.javaClass.simpleName}") }.getOrNull()

    fun anchored(scope: String, text: String, result: String, vararg parameters: String): Method? {
        val candidates = runCatching {
            bridge?.findMethod(FindMethod.create().searchPackages(scope).matcher(
                MethodMatcher.create().usingStrings(text).returnType(result).paramTypes(*parameters),
            ))?.map { it.getMethodInstance(loader) }?.filter { !Modifier.isStatic(it.modifiers) }.orEmpty()
        }.onFailure { log("MiLink resolver failed: anchor=$text error=${it.javaClass.simpleName}") }.getOrDefault(emptyList())
        val method = candidates.singleOrNull()?.apply { isAccessible = true }
        log("MiLink resolver: anchor=$text candidates=${candidates.size} resolved=${method?.toGenericString()}")
        return method
    }

    override fun close() { bridge?.close() }
}
