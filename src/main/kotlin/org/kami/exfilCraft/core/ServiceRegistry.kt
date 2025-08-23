/*
 * MIT License
 * Copyright (c) 2025 tsuki
 */
package org.kami.exfilCraft.core

import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap

class ServiceRegistry(private val plugin: JavaPlugin) {
    private val services = ConcurrentHashMap<Class<*>, Any>()

    fun <T: Any> register(instance: T) {
        services[instance::class.java] = instance
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T: Any> get(): T = services.values.firstOrNull { it is T } as? T
        ?: error("Service not found: ${T::class.java.name}")

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T: Any> getOptional(): T? = services.values.firstOrNull { it is T } as? T
}

fun log(plugin: JavaPlugin, msg: String) {
    plugin.logger.info("[ExfilCraft] $msg")
}

fun debug(plugin: JavaPlugin, enabled: Boolean, msg: () -> String) {
    if (enabled) plugin.logger.info("[ExfilCraft][DEBUG] ${msg()}")
}

