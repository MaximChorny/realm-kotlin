package io.realm.kotlin.internal.util

import io.realm.kotlin.internal.RealmObjectCompanion
import io.realm.kotlin.internal.platform.realmObjectCompanionCast
import io.realm.kotlin.types.BaseRealmObject
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.measureTime

public class LazyCompanionMap(
    private val schema: Set<KClass<out BaseRealmObject>>
) : Map<KClass<out BaseRealmObject>, RealmObjectCompanion> {

    private val cache = mutableMapOf<KClass<out BaseRealmObject>, RealmObjectCompanion>()
    private val timingMap = mutableMapOf<Long, MutableList<String>>()


    override val entries: Set<Map.Entry<KClass<out BaseRealmObject>, RealmObjectCompanion>>
        get() = schema.map { clazz ->
            object : Map.Entry<KClass<out BaseRealmObject>, RealmObjectCompanion> {
                override val key: KClass<out BaseRealmObject> = clazz
                override val value: RealmObjectCompanion = get(clazz)
            }
        }.toSet()

    override val keys: Set<KClass<out BaseRealmObject>>
        get() = schema

    override val size: Int
        get() = schema.size

    override val values: Collection<RealmObjectCompanion>
        get() = schema.mapNotNull { get(it) }

    override fun containsKey(key: KClass<out BaseRealmObject>): Boolean {
        return schema.contains(key)
    }

    override fun containsValue(value: RealmObjectCompanion): Boolean {
        return cache.containsValue(value)
    }

    override fun get(key: KClass<out BaseRealmObject>): RealmObjectCompanion {
//        if (!schema.contains(key)) return null
        return cache.getOrPut(key) {
            val companion :RealmObjectCompanion
            // Measure the execution time of realmObjectCompanionCast
            val executionTime: Duration = measureTime {
                // Invoke the actual companion cast method
                companion = realmObjectCompanionCast(key)
            }

            // Convert Duration to milliseconds
            val timeInMilliseconds = executionTime.inWholeMilliseconds

            // Update the timingMap
            val className = key.simpleName ?: "Unknown"
            timingMap.getOrPut(timeInMilliseconds) { mutableListOf() }.add(className)

            companion
        }

    }

    public fun printTimingStats() {
        if (timingMap.isEmpty()) {
            println("ForMAx No timing data available.")
            return
        }

        println("ForMAx === RealmObjectCompanion Cast Timing Statistics ===")

        // Sort the map entries by key (execution time) in descending order
        val sortedEntries = timingMap.entries.sortedByDescending { it.key }

        for ((time, classNames) in sortedEntries) {
            println("ForMAx Time: ${time} ms - Classes: ${classNames.joinToString(", ")}")
        }

        println("ForMAx === End of Timing Statistics ===")

    }

    override fun isEmpty(): Boolean {
        return schema.isEmpty()
    }
}