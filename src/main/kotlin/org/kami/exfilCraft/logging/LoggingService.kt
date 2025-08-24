package org.kami.exfilCraft.logging

import org.bukkit.ChatColor
import org.bukkit.plugin.java.JavaPlugin
import org.kami.exfilCraft.core.ConfigService
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * Central structured logging service with hierarchical sections, spam suppression and
 * debug filtering. Intended single source for all plugin logging output.
 */
class LoggingService(private val plugin: JavaPlugin, private val config: ConfigService) {
    // We reevaluate config flags at emission to allow hot reload without explicit callbacks.
    private val pluginName = plugin.name
    private fun colorizeIfEnabled(text: String): String = if (config.logColorize) {
        ChatColor.translateAlternateColorCodes('&', text)
    } else {
        ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', text)) ?: text
    }
    private fun debugEnabled(): Boolean = config.debugEnabled && config.logDebugOverride
    private fun normalizeSection(section: String) = section.uppercase()
    private fun normalizeSub(sub: String?) = sub?.uppercase()

    private val spamWindowSec get() = config.logSpamSuppressionSeconds
    private val lastKeyTimes = ConcurrentHashMap<String, Long>()
    private val df: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withLocale(Locale.US)

    private fun nowEpoch() = Instant.now().epochSecond

    fun section(name: String, sub: String? = null): LogHandle = LogHandle(this, name, sub, plugin)

    internal fun emit(
        level: Level,
        section: String,
        sub: String?,
        msg: String,
        data: Map<String, Any?> = emptyMap(),
        key: String? = null,
        debugOnly: Boolean = false,
        force: Boolean = false
    ) {
        if (debugOnly && !debugEnabled()) return
        if (!force && key != null) {
            val now = nowEpoch()
            val last = lastKeyTimes.put(key, now)
            if (last != null && (now - last) < spamWindowSec) return
        }
        val sec = normalizeSection(section)
        val subNorm = normalizeSub(sub)
        val prefix = buildString {
            append("[").append(pluginName).append("]")
            append('[').append(sec)
            if (subNorm != null) append('/').append(subNorm)
            append(']')
        }
        val dataStr = if (data.isEmpty()) "" else data.entries.joinToString(prefix = " | ", separator = " ") { (k,v) -> "$k=${v}" }
        val base = "$prefix $msg$dataStr"
        val final = colorizeIfEnabled(base)
        // Downgrade non-error debug to FINE for console filtering if needed
        val effLevel = if (debugOnly && level == Level.INFO) Level.FINE else level
        plugin.logger.log(effLevel, final)
    }

    data class LogHandle internal constructor(
        private val svc: LoggingService,
        private val section: String,
        private val sub: String?,
        private val plugin: JavaPlugin
    ) {
        fun sub(child: String) = LogHandle(svc, section, child, plugin)
        fun info(msg: String, vararg data: Pair<String, Any?>) = svc.emit(Level.INFO, section, sub, msg, data.toMap())
        fun warn(msg: String, vararg data: Pair<String, Any?>) = svc.emit(Level.WARNING, section, sub, msg, data.toMap())
        fun error(msg: String, t: Throwable? = null, vararg data: Pair<String, Any?>) {
            svc.emit(Level.SEVERE, section, sub, msg, data.toMap())
            if (t != null) plugin.logger.log(Level.SEVERE, "[ExfilCraft][${section}${if (sub!=null) "/$sub" else ""}]", t)
        }
        fun debug(msg: String, vararg data: Pair<String, Any?>) = svc.emit(Level.INFO, section, sub, msg, data.toMap(), debugOnly = true)
        fun trace(msg: String, vararg data: Pair<String, Any?>) = svc.emit(Level.FINE, section, sub, msg, data.toMap(), debugOnly = true)
        fun spamSuppressed(key: String, msg: String, vararg data: Pair<String, Any?>) = svc.emit(Level.INFO, section, sub, msg, data.toMap(), key = key)
        inline fun <T> time(label: String, vararg data: Pair<String, Any?>, block: () -> T): T {
            val start = System.nanoTime()
            val result = block()
            val ms = (System.nanoTime() - start) / 1_000_000
            debug("$label completed", *(data.toList() + ("ms" to ms)).toTypedArray())
            return result
        }
    }
}
