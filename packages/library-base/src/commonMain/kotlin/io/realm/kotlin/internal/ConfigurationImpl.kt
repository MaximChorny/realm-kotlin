package io.realm.kotlin.internal

import io.realm.kotlin.CompactOnLaunchCallback
import io.realm.kotlin.InitialDataCallback
import io.realm.kotlin.InitialRealmFileConfiguration
import io.realm.kotlin.dynamic.DynamicMutableRealm
import io.realm.kotlin.dynamic.DynamicMutableRealmObject
import io.realm.kotlin.dynamic.DynamicRealm
import io.realm.kotlin.dynamic.DynamicRealmObject
import io.realm.kotlin.internal.dynamic.DynamicMutableRealmImpl
import io.realm.kotlin.internal.dynamic.DynamicMutableRealmObjectImpl
import io.realm.kotlin.internal.dynamic.DynamicRealmImpl
import io.realm.kotlin.internal.dynamic.DynamicRealmObjectImpl
import io.realm.kotlin.internal.dynamic.DynamicUnmanagedRealmObject
import io.realm.kotlin.internal.interop.FrozenRealmPointer
import io.realm.kotlin.internal.interop.LiveRealmPointer
import io.realm.kotlin.internal.interop.MigrationCallback
import io.realm.kotlin.internal.interop.RealmConfigurationPointer
import io.realm.kotlin.internal.interop.RealmInterop
import io.realm.kotlin.internal.interop.RealmSchemaPointer
import io.realm.kotlin.internal.interop.SchemaMode
import io.realm.kotlin.internal.interop.use
import io.realm.kotlin.internal.platform.PATH_SEPARATOR
import io.realm.kotlin.internal.platform.appFilesDirectory
import io.realm.kotlin.internal.platform.prepareRealmFilePath
import io.realm.kotlin.internal.platform.realmObjectCompanionCast
import io.realm.kotlin.internal.platform.realmObjectCompanionOrThrow
import io.realm.kotlin.internal.util.CoroutineDispatcherFactory
import io.realm.kotlin.internal.util.LazyCompanionMap
import io.realm.kotlin.migration.AutomaticSchemaMigration
import io.realm.kotlin.migration.RealmMigration
import io.realm.kotlin.types.BaseRealmObject
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

// TODO Public due to being accessed from `library-sync`
@Suppress("LongParameterList")
public open class ConfigurationImpl(
    directory: String,
    name: String,
    schema: Set<KClass<out BaseRealmObject>>,
    maxNumberOfActiveVersions: Long,
    notificationDispatcher: CoroutineDispatcherFactory,
    writeDispatcher: CoroutineDispatcherFactory,
    schemaVersion: Long,
    schemaMode: SchemaMode,
    private val userEncryptionKey: ByteArray?,
    compactOnLaunchCallback: CompactOnLaunchCallback?,
    private val userMigration: RealmMigration?,
    automaticBacklinkHandling: Boolean,
    initialDataCallback: InitialDataCallback?,
    override val isFlexibleSyncConfiguration: Boolean,
    inMemory: Boolean,
    initialRealmFileConfiguration: InitialRealmFileConfiguration?,
    override val logger: ContextLogger
) : InternalConfiguration {

    final override val path: String

    final override val name: String

    final override val schema: Set<KClass<out BaseRealmObject>>

    final override val maxNumberOfActiveVersions: Long

    final override val schemaVersion: Long

    final override val schemaMode: SchemaMode

    override val encryptionKey: ByteArray?
        get(): ByteArray? = userEncryptionKey

    final override val mapOfKClassWithCompanion: Map<KClass<out BaseRealmObject>, RealmObjectCompanion> = LazyCompanionMap(schema)

    final override val mediator: Mediator

    final override val notificationDispatcherFactory: CoroutineDispatcherFactory

    final override val writeDispatcherFactory: CoroutineDispatcherFactory

    final override val compactOnLaunchCallback: CompactOnLaunchCallback?

    final override val initialDataCallback: InitialDataCallback?
    final override val inMemory: Boolean
    final override val initialRealmFileConfiguration: InitialRealmFileConfiguration?

    override fun createNativeConfiguration(): RealmConfigurationPointer {
        val nativeConfig: RealmConfigurationPointer = RealmInterop.realm_config_new()
        return configInitializer(nativeConfig)
    }

    override suspend fun openRealm(realm: RealmImpl): Pair<FrozenRealmReference, Boolean> {
        val configPtr = realm.configuration.createNativeConfiguration()
        return RealmInterop.realm_create_scheduler()
            .use { scheduler ->
                val (dbPointer, fileCreated) = RealmInterop.realm_open(configPtr, scheduler)
                val liveRealmReference = LiveRealmReference(realm, dbPointer)
                val frozenReference = liveRealmReference.snapshot(realm)
                liveRealmReference.close()
                frozenReference to fileCreated
            }
    }

    override suspend fun initializeRealmData(realm: RealmImpl, realmFileCreated: Boolean) {
        val initCallback = initialDataCallback
        if (realmFileCreated && initCallback != null) {
            realm.write { // this: MutableRealm
                with(initCallback) { // this: InitialDataCallback
                    write()
                }
            }
        }
    }

    private val configInitializer: (RealmConfigurationPointer) -> RealmConfigurationPointer

    init {
        this.path = normalizePath(directory, name)
        this.name = name
        this.schema = schema
        this.maxNumberOfActiveVersions = maxNumberOfActiveVersions
        this.notificationDispatcherFactory = notificationDispatcher
        this.writeDispatcherFactory = writeDispatcher
        this.schemaVersion = schemaVersion
        this.schemaMode = schemaMode
        this.compactOnLaunchCallback = compactOnLaunchCallback
        this.initialDataCallback = initialDataCallback
        this.inMemory = inMemory
        this.initialRealmFileConfiguration = initialRealmFileConfiguration

        // Start timing for compactCallback preparation
        val compactCallbackTime: Duration = measureTime {
            // Prepare compactCallback
            val compactCallback = compactOnLaunchCallback?.let { callback ->
                io.realm.kotlin.internal.interop.CompactOnLaunchCallback { totalBytes, usedBytes ->
                    callback.shouldCompact(totalBytes, usedBytes)
                }
            }
        }
        println("forMAx compactCallback preparation time: ${compactCallbackTime.inWholeSeconds} seconds")

        // Start timing for migrationCallback preparation
        val migrationCallbackTime: Duration = measureTime {
            // Prepare migrationCallback
            val migrationCallback: MigrationCallback? = userMigration?.let { userMigration ->
                when (userMigration) {
                    is AutomaticSchemaMigration -> MigrationCallback { oldRealm: FrozenRealmPointer, newRealm: LiveRealmPointer, schema: RealmSchemaPointer ->
                        // If we don't start a read, then we cannot read the version
                        RealmInterop.realm_begin_read(oldRealm)
                        RealmInterop.realm_begin_read(newRealm)
                        val old = DynamicRealmImpl(this@ConfigurationImpl, oldRealm)
                        val new = DynamicMutableRealmImpl(this@ConfigurationImpl, newRealm)
                        userMigration.migrate(object : AutomaticSchemaMigration.MigrationContext {
                            override val oldRealm: DynamicRealm = old
                            override val newRealm: DynamicMutableRealm = new
                        })
                    }
                }
            }
        }
        println("forMAx migrationCallback preparation time: ${migrationCallbackTime.inWholeSeconds} seconds")

        // Start timing for configInitializer setup
        val configInitializerSetupTime: Duration = measureTime {
            // Set up configInitializer
            this.configInitializer = { nativeConfig: RealmConfigurationPointer ->
                val configInitTime: Duration = measureTime {
                    RealmInterop.realm_config_set_path(nativeConfig, this.path)
                    RealmInterop.realm_config_set_schema_mode(nativeConfig, schemaMode)
                    RealmInterop.realm_config_set_schema_version(
                        config = nativeConfig,
                        version = schemaVersion
                    )

                    compactOnLaunchCallback?.let { callback ->
                        RealmInterop.realm_config_set_should_compact_on_launch_function(
                            nativeConfig,
                            io.realm.kotlin.internal.interop.CompactOnLaunchCallback { totalBytes, usedBytes ->
                                callback.shouldCompact(totalBytes, usedBytes)
                            }
                        )
                    }

                    // Start timing for nativeSchema creation
                    val nativeSchemaCreationTime: Duration = measureTime {
                        val seenNames = mutableSetOf<String>()
                        val duplicates = mutableSetOf<String>()
                        val schemaList = mapOfKClassWithCompanion.values.map { companion ->
                            companion.`io_realm_kotlin_schema`().let { schema ->
                                val className = schema.name

                                if (!seenNames.add(className)) {
                                    duplicates.add(className)
                                }

                                schema.cinteropClass to schema.cinteropProperties.sortedBy { it.isComputed }
                            }
                        }

                        if (duplicates.isNotEmpty()) {
                            throw IllegalArgumentException("The schema has declared the following class names multiple times: ${duplicates.joinToString()}")
                        }
                        (mapOfKClassWithCompanion as LazyCompanionMap).printTimingStats()
                        val nativeSchema = RealmInterop.realm_schema_new(schemaList)
                        RealmInterop.realm_config_set_schema(nativeConfig, nativeSchema)
                    }
                    println("forMAx nativeSchema creation time: ${nativeSchemaCreationTime.inWholeSeconds} seconds")

                    RealmInterop.realm_config_set_max_number_of_active_versions(
                        nativeConfig,
                        maxNumberOfActiveVersions
                    )

                    userMigration?.let { userMigration ->
                        val migrationCallback = when (userMigration) {
                            is AutomaticSchemaMigration -> MigrationCallback { oldRealm: FrozenRealmPointer, newRealm: LiveRealmPointer, schema: RealmSchemaPointer ->
                                // If we don't start a read, then we cannot read the version
                                RealmInterop.realm_begin_read(oldRealm)
                                RealmInterop.realm_begin_read(newRealm)
                                val old = DynamicRealmImpl(this@ConfigurationImpl, oldRealm)
                                val new = DynamicMutableRealmImpl(this@ConfigurationImpl, newRealm)
                                userMigration.migrate(object : AutomaticSchemaMigration.MigrationContext {
                                    override val oldRealm: DynamicRealm = old
                                    override val newRealm: DynamicMutableRealm = new
                                })
                            }
                        }
                        RealmInterop.realm_config_set_migration_function(nativeConfig, migrationCallback)
                    }

                    RealmInterop.realm_config_set_automatic_backlink_handling(
                        nativeConfig,
                        automaticBacklinkHandling
                    )

                    userEncryptionKey?.let { key: ByteArray ->
                        RealmInterop.realm_config_set_encryption_key(nativeConfig, key)
                    }

                    RealmInterop.realm_config_set_in_memory(nativeConfig, inMemory)
                }
                println("forMAx configInitializer total time: ${configInitTime.inWholeSeconds} seconds")

                nativeConfig
            }
        }
        println("forMAx configInitializer setup time: ${configInitializerSetupTime.inWholeSeconds} seconds")

        // Start timing for mediator setup
        val mediatorSetupTime: Duration = measureTime {
            mediator = object : Mediator {
                override fun createInstanceOf(clazz: KClass<out BaseRealmObject>): RealmObjectInternal =
                    when (clazz) {
                        DynamicRealmObject::class -> DynamicRealmObjectImpl()
                        DynamicMutableRealmObject::class -> DynamicMutableRealmObjectImpl()
                        DynamicUnmanagedRealmObject::class -> DynamicMutableRealmObjectImpl()
                        else ->
                            companionOf(clazz).`io_realm_kotlin_newInstance`() as RealmObjectInternal
                    }

                override fun companionOf(clazz: KClass<out BaseRealmObject>): RealmObjectCompanion =
                    mapOfKClassWithCompanion[clazz]
                        ?: error("$clazz not part of this configuration schema")
            }
        }
        println("forMAx mediator setup time: ${mediatorSetupTime.inWholeSeconds} seconds")
    }

    private fun normalizePath(directoryPath: String, fileName: String): String {
        var dir = directoryPath.ifEmpty { appFilesDirectory() }
        // If dir is a relative path, replace with full path for easier debugging
        if (dir.startsWith(".$PATH_SEPARATOR")) {
            dir = dir.replaceFirst(".$PATH_SEPARATOR", "${appFilesDirectory()}$PATH_SEPARATOR")
        }
        return prepareRealmFilePath(dir, fileName)
    }
}
