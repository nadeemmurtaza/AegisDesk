package com.newax.aegis.engine

interface SecureSettings {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String, defaultValue: String? = null): String?
    fun putString(key: String, value: String?)
    fun clear()
}
