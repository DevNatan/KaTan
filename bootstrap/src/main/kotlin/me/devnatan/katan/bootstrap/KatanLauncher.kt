package me.devnatan.katan.bootstrap

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import kotlinx.coroutines.runBlocking
import me.devnatan.katan.cli.KatanCLI
import me.devnatan.katan.common.util.exportResource
import me.devnatan.katan.core.KatanCore
import me.devnatan.katan.core.exceptions.SilentException
import me.devnatan.katan.webserver.KatanWebServer
import kotlin.system.exitProcess

private class KatanLauncher(config: Config) {

    companion object {

        @JvmStatic
        fun main(args: Array<out String>) {
            KatanLauncher(ConfigFactory.parseFile(this::class.exportResource("katan.conf")))
        }

    }

    private val katan = KatanCore(config)
    private lateinit var cli: KatanCLI
    private lateinit var webServer: KatanWebServer

    init {
        runBlocking {
            runCatching {
                katan.start()
            }.onFailure {
                if (it is SilentException)
                    it.logger.error(it.cause.toString())
                else
                    it.printStackTrace()
                exitProcess(0)
            }.onSuccess {
                cli = KatanCLI(katan)
                webServer = KatanWebServer(katan.accountManager, katan.serverManager)

                try {
                    cli.init()
                    webServer.init()
                } finally {
                    cli.close()
                    webServer.close()
                }
            }
        }
    }

}