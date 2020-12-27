package me.devnatan.katan.webserver.websocket.handler

import me.devnatan.katan.webserver.websocket.message.WebSocketMessage
import me.devnatan.katan.webserver.websocket.message.WebSocketMessageImpl

typealias WebSocketMessageHandlerBlock = suspend WebSocketMessage.() -> Unit

abstract class WebSocketHandler {

    val mappings = hashMapOf<Int, WebSocketMessageHandlerBlock>()

}

suspend fun WebSocketMessage.respond(content: Map<String, Any>) {
    session.send(WebSocketMessageImpl(op, content, session))
}

fun WebSocketHandler.handle(target: Int, block: WebSocketMessageHandlerBlock) {
    mappings[target] = block
}