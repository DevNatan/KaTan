package me.devnatan.katan.core

import br.com.devsrsouza.eventkt.EventScope
import br.com.devsrsouza.eventkt.scopes.LocalEventScope
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.core.KeystoreSSLConfig
import com.github.dockerjava.core.LocalDirectorySSLConfig
import com.github.dockerjava.okhttp.OkDockerHttpClient
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigObject
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import me.devnatan.katan.api.*
import me.devnatan.katan.api.annotations.UnstableKatanApi
import me.devnatan.katan.api.cache.Cache
import me.devnatan.katan.api.cache.UnavailableCacheProvider
import me.devnatan.katan.api.game.GameType
import me.devnatan.katan.api.plugin.KatanInit
import me.devnatan.katan.api.plugin.KatanStarted
import me.devnatan.katan.api.security.crypto.Hash
import me.devnatan.katan.common.exceptions.throwSilent
import me.devnatan.katan.common.util.exportResource
import me.devnatan.katan.common.util.get
import me.devnatan.katan.common.util.getMap
import me.devnatan.katan.core.cache.RedisCacheProvider
import me.devnatan.katan.core.crypto.BcryptHash
import me.devnatan.katan.core.database.DatabaseConnector
import me.devnatan.katan.core.database.SUPPORTED_CONNECTORS
import me.devnatan.katan.core.database.jdbc.JDBCConnector
import me.devnatan.katan.core.impl.account.AccountsManagerImpl
import me.devnatan.katan.core.impl.game.GameImpl
import me.devnatan.katan.core.impl.game.GameManagerImpl
import me.devnatan.katan.core.impl.game.GameSettingsImpl
import me.devnatan.katan.core.impl.game.GameVersionImpl
import me.devnatan.katan.core.impl.plugin.DefaultPluginManager
import me.devnatan.katan.core.impl.server.DockerServerManager
import me.devnatan.katan.core.impl.services.ServiceManagerImpl
import me.devnatan.katan.core.repository.JDBCAccountsRepository
import me.devnatan.katan.core.repository.JDBCServersRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.io.File
import java.security.KeyStore
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*
import kotlin.system.measureTimeMillis

@OptIn(UnstableKatanApi::class)
class KatanCore(val config: Config, override val environment: KatanEnvironment, val locale: KatanLocale) :
    CoroutineScope by CoroutineScope(CoroutineName("Katan")), Katan {

    companion object {

        const val DATABASE_DIALECT_FALLBACK = "H2"
        const val DEFAULT_VALUE = "default"
        val logger: Logger = LoggerFactory.getLogger(Katan::class.java)

    }

    val objectMapper = ObjectMapper()
    override val platform: Platform = currentPlatform()
    lateinit var database: DatabaseConnector
    lateinit var docker: DockerClient
    override lateinit var accountManager: AccountsManagerImpl
    override lateinit var serverManager: DockerServerManager
    override val pluginManager = DefaultPluginManager(this)
    override val serviceManager = ServiceManagerImpl()
    override val gameManager = GameManagerImpl()
    override lateinit var cache: Cache<Any>
    override val eventBus: EventScope = LocalEventScope()
    lateinit var hash: Hash

    val dateTimeFormatter: DateTimeFormatter by lazy {
        val value = config.get("timezone", DEFAULT_VALUE)
        val timezone = if (value == DEFAULT_VALUE)
            TimeZone.getDefault()
        else
            TimeZone.getTimeZone(value)

        logger.info(locale["katan.timezone", timezone.displayName])
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withZone(timezone.toZoneId())
    }

    private suspend fun database() {
        val db = config.getConfig("database")
        val dialect = db.get("source", DATABASE_DIALECT_FALLBACK)
        logger.info(locale["katan.database.dialect", dialect, DATABASE_DIALECT_FALLBACK])

        val dialectSettings = runCatching {
            db.getConfig(dialect.toLowerCase())
        }.onFailure {
            throwSilent(IllegalArgumentException("Dialect properties not found: $dialect."), logger)
        }.getOrThrow()

        connectWith(dialect, dialectSettings, db.get("strict", false))
    }

    private suspend fun connectWith(dialect: String, config: Config, strict: Boolean) {
        val dialectName = dialect.toLowerCase()
        if (!SUPPORTED_CONNECTORS.containsKey(dialectName))
            throwSilent(IllegalArgumentException("Database dialect $dialect is not supported"), logger)

        val (connector, settings) = SUPPORTED_CONNECTORS.getValue(dialectName).invoke(config)
        logger.info(locale["katan.database.connector", connector::class.simpleName!!])

        try {
            database = connector
            logger.info(locale["katan.database.connecting", settings.toString()])
            val took = measureTimeMillis {
                connector.connect(settings)
            }
            logger.info(locale["katan.database.connected", String.format("%.2f", took / 1000.0f)])
        } catch (e: Throwable) {
            logger.error(locale["katan.database.fail", dialect])
            if (strict || dialect.equals(DATABASE_DIALECT_FALLBACK, true))
                throwSilent(e, logger)

            logger.info(locale["katan.database.strict", DATABASE_DIALECT_FALLBACK])
            connectWith(DATABASE_DIALECT_FALLBACK, config, strict)
        }
    }

    private fun docker() {
        logger.info(locale["katan.docker.config"])
        val dockerLogger = LoggerFactory.getLogger("Docker")
        val dockerConfig = config.getConfig("docker")
        val tls = dockerConfig.get("tls.verify", false)
        val clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(dockerConfig.getString("host"))
            .withDockerTlsVerify(tls)

        if (tls) {
            dockerLogger.info(locale["katan.docker.tls-enabled"])
            clientConfig.withDockerCertPath(dockerConfig.getString("tls.certPath"))
        } else
            dockerLogger.warn(
                locale["katan.docker.tls-disabled", "https://docs.docker.com/engine/security/https/"]
            )

        if (dockerConfig.get("ssl.enabled", false)) {
            clientConfig.withCustomSslConfig(
                when (dockerConfig.getString("ssl.provider")) {
                    "CERT" -> {
                        val path = dockerConfig.getString("ssl.certPath")
                        dockerLogger.info(locale["katan.docker.cert-loaded", path])
                        LocalDirectorySSLConfig(path)
                    }
                    "KEY_STORE" -> {
                        val type = dockerConfig.get("keyStore.provider") ?: KeyStore.getDefaultType()
                        val keystore = KeyStore.getInstance(type)
                        dockerLogger.info(locale["katan.docker.ks-loaded", type])

                        KeystoreSSLConfig(keystore, dockerConfig.getString("keyStore.password"))
                    }
                    else -> throwSilent(
                        IllegalArgumentException("Unrecognized Docker SSL provider. Must be: CERT or KEY_STORE"),
                        logger
                    )
                }
            )
        }

        val properties = dockerConfig.getConfig("properties")
        val httpConfig = clientConfig.build()
        docker = DockerClientImpl.getInstance(
            httpConfig, OkDockerHttpClient.Builder()
                .dockerHost(httpConfig.dockerHost)
                .sslConfig(httpConfig.sslConfig)
                .connectTimeout(properties.get("connectTimeout", 5000))
                .readTimeout(properties.get("readTimeout", 5000))
                .build()
        )

        // sends a ping to see if the connection will be established.
        docker.pingCmd().exec()
        dockerLogger.info(locale["katan.docker.ready"])
    }

    private fun caching() {
        val redis = config.getConfig("redis")
        if (!redis.get("use", false)) {
            logger.warn(locale["katan.redis.disabled"])
            logger.warn(locale["katan.redis.alert", "https://redis.io/"])
            return
        }

        try {
            // we have to use the pool instead of the direct client due to Katan nature,
            // the default instance of Jedis (without pool) is not thread-safe
            cache = RedisCacheProvider(JedisPool(JedisPoolConfig(), redis.get("host", "localhost")))
            logger.info(locale["katan.redis.ready"])
        } catch (e: Throwable) {
            cache = UnavailableCacheProvider()
            logger.error(locale["katan.redis.connection-failed"])
            logger.error(e.message)
        }
    }

    private fun loadGames() {
        val root = "games"
        val directory = File(root)
        if (!directory.exists())
            directory.mkdirs()
        
        for (supported in GameType.supported) {
            exportResource("$root/${supported.name.toLowerCase(locale.locale)}.conf")
        }

        for (file in directory.listFiles()?.filterNotNull() ?: emptyList()) {
            val config = ConfigFactory.parseFile(file)
            val gameName = config.get("name", file.nameWithoutExtension)
            val settings = config.getConfig("settings").let {
                GameSettingsImpl(it.get("ports.min", 0)..it.get("ports.max", Short.MAX_VALUE * 2 - 1));
            }

            val versions = config.getConfig("versions").root().entries.map { (key, value) ->
                val versionConfig = (value as ConfigObject).toConfig()
                GameVersionImpl(key, versionConfig.get("image", null), versionConfig.getMap("environment"))
            }.toTypedArray()

            // TODO: check for non-native game type
            val game = GameImpl(
                gameName,
                GameType.native(gameName)!!,
                settings,
                config.get("defaults.image", null),
                config.getMap("defaults.environment"),
                versions
            )

            logger.info(if (game.versions.isEmpty())
                locale["katan.game-registered", game.name]
            else
                locale["katan.versioned-game-registered", game.name, game.versions.size]
            )
            gameManager.registerGame(game)
        }
    }

    private fun throwUnavailableRepository(repository: String): Nothing {
        throwSilent(IllegalArgumentException("No repository available: $repository"), logger)
    }

    suspend fun start() {
        logger.info(locale["katan.starting", Katan.VERSION, locale["katan.env.$environment"].toLowerCase(locale.locale)])
        logger.info(locale["katan.platform", "$platform"])
        database()
        docker()
        pluginManager.loadPlugins()
        serverManager = DockerServerManager(
            this, when (database) {
                is JDBCConnector -> JDBCServersRepository(database as JDBCConnector)
                else -> throwUnavailableRepository("servers")
            }
        )

        accountManager = AccountsManagerImpl(
            this, when (database) {
                is JDBCConnector -> JDBCAccountsRepository(this, database as JDBCConnector)
                else -> throwUnavailableRepository("accounts")
            }
        )
        caching()
        pluginManager.callHandlers(KatanInit)
        loadGames()
        serverManager.loadServers()

        hash = when (val algorithm = config.getString("security.crypto.hash")) {
            DEFAULT_VALUE, BcryptHash.NAME -> BcryptHash()
            else -> throwSilent(IllegalArgumentException("Unsupported hash algorithm: $algorithm"), logger)
        }
        logger.info(locale["katan.selected-hash", hash.name])
        accountManager.loadAccounts()

        pluginManager.callHandlers(KatanStarted)
    }

    suspend fun close() {
        pluginManager.disableAll()
    }

}