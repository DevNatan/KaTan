package me.devnatan.katan.webserver.jwt

import io.ktor.auth.*
import me.devnatan.katan.api.security.account.Account

data class AccountPrincipal(val account: Account) : Principal