package com.newax.aegis.engine.registry

import kotlin.reflect.KClass

object ServiceRegistry {

    private val services = mutableMapOf<String, Any>()
    private val factories = mutableMapOf<String, () -> Any>()

    fun <T : Any> register(key: KClass<T>, instance: T) {
        services[key.qualifiedName ?: key.simpleName!!] = instance
    }

    fun <T : Any> register(key: String, instance: T) {
        services[key] = instance
    }

    fun <T : Any> registerFactory(key: KClass<T>, factory: () -> T) {
        factories[key.qualifiedName ?: key.simpleName!!] = factory
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: KClass<T>): T? {
        val k = key.qualifiedName ?: key.simpleName!!
        return (services[k] ?: factories[k]?.invoke()?.also { services[k] = it }) as? T
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T? = services[key] as? T

    fun <T : Any> require(key: KClass<T>): T =
        get(key) ?: error("Service not registered: ${key.simpleName}")

    fun <T : Any> require(key: String): T =
        get(key) ?: error("Service not registered: $key")

    fun unregister(key: String) { services.remove(key); factories.remove(key) }
    fun <T : Any> unregister(key: KClass<T>) = unregister(key.qualifiedName ?: key.simpleName!!)

    fun registeredKeys(): Set<String> = services.keys + factories.keys

    fun isRegistered(key: String): Boolean = key in services || key in factories
    fun <T : Any> isRegistered(key: KClass<T>): Boolean =
        isRegistered(key.qualifiedName ?: key.simpleName!!)

    fun clear() { services.clear(); factories.clear() }
}
