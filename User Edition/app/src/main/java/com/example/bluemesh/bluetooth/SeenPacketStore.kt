package com.example.bluemesh.bluetooth

import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class SeenPacketStore(private val maxSize: Int = 500) {
    private val seen = object : LinkedHashMap<Long, Unit>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Unit>): Boolean =
            size > maxSize
    }
    private val lock = ReentrantReadWriteLock()

    fun isNew(packetId: Long): Boolean {
        lock.write {
            return seen.put(packetId, Unit) == null
        }
    }

    fun clear() {
        lock.write { seen.clear() }
    }
}
