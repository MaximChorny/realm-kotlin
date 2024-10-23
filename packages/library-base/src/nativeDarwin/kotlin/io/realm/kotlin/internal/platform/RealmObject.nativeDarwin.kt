package io.realm.kotlin.internal.platform

import io.realm.kotlin.internal.RealmObjectCompanion
import io.realm.kotlin.types.BaseRealmObject
import kotlin.reflect.KClass

/**
 * Returns the [RealmObjectCompanion] associated with a given [BaseRealmObject]'s [KClass].
 */
@PublishedApi
internal actual fun <T : BaseRealmObject> realmObjectCompanionCast(clazz: KClass<T>): RealmObjectCompanion {
    TODO("Not yet implemented")
}