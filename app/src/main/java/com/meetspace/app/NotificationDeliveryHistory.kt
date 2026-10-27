package com.meetspace.app

import java.security.MessageDigest

/** A bounded journal shared by realtime and push delivery. It stores no notification content. */
internal class NotificationDeliveryHistory(restored: String = "", private val limit: Int = 512) {
    init { require(limit > 0) }
    private val delivered = restored.lineSequence().filter { it.matches(Regex("[0-9a-f]{64}")) }
        .toList().takeLast(limit).toCollection(linkedSetOf())

    fun contains(scope: String, id: String) = key(scope, id) in delivered

    fun record(scope: String, id: String) {
        delivered.add(key(scope, id))
        while (delivered.size > limit) delivered.remove(delivered.first())
    }

    fun snapshot() = delivered.joinToString("\n")

    private fun key(scope: String, id: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$scope\u0000$id".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
