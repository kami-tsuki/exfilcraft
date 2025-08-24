/*
 * MIT License
 * Copyright (c) 2025 tsuki
 */

package org.kami.exfilCraft.core

import org.bukkit.plugin.java.JavaPlugin
import org.kami.exfilCraft.logging.LoggingService
import java.util.concurrent.ConcurrentHashMap

class ServiceRegistry(private val plugin: JavaPlugin) {
    @PublishedApi
    internal val services = ConcurrentHashMap<Class<*>, Any>()

    fun <T: Any> register(instance: T) { services[instance::class.java] = instance }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T: Any> get(): T = services.values.firstOrNull { it is T } as? T
        ?: error("Service not found: ${T::class.java.name}")

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T: Any> getOptional(): T? = services.values.firstOrNull { it is T } as? T

    @Suppress("UNCHECKED_CAST")
    fun <T: Any> get(clazz: Class<T>): T = services.values.firstOrNull { clazz.isInstance(it) } as? T
        ?: error("Service not found: ${clazz.name}")
}

// Legacy helpers routed through LoggingService when available
fun log(plugin: JavaPlugin, msg: String) {
    val svc = (plugin as? org.kami.exfilCraft.ExfilCraft)?.services?.getOptional<LoggingService>()
    if (svc != null) svc.section("LEGACY").info(msg) else plugin.logger.info("[ExfilCraft] $msg")
}
fun debug(plugin: JavaPlugin, enabled: Boolean, msg: () -> String) {
    val svc = (plugin as? org.kami.exfilCraft.ExfilCraft)?.services?.getOptional<LoggingService>()
    if (svc != null) svc.section("LEGACY").debug(msg()) else if (enabled) plugin.logger.info("[ExfilCraft][DEBUG] ${msg()}")
}
