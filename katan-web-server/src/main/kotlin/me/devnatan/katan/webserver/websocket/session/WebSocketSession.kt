package me.devnatan.katan.webserver.websocket.session

import me.devnatan.katan.webserver.websocket.message.WebSocketMessage

interface WebSocketSession {

    suspend fun send(message: WebSocketMessage)

    suspend fun close()


}