package com.tsato.routes

import com.google.gson.JsonParser
import com.tsato.data.Room
import com.tsato.data.models.BaseModel
import com.tsato.data.models.ChatMessage
import com.tsato.data.models.DrawData
import com.tsato.gson
import com.tsato.server
import com.tsato.session.DrawingSession
import com.tsato.util.Constants.TYPE_CHAT_MESSAGE
import com.tsato.util.Constants.TYPE_DRAW_DATA
import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.sessions.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach

fun Route.gameWebSocketRoute() {
    route("/ws/draw") {
        standardWebSocket { socket, clientId, message, payload ->
            when(payload) {
                is DrawData -> {
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket
                    if (room.phase == Room.Phase.GAME_RUNNING) {
                        room.broadcastToAllExcept(message, clientId)
                    }
                }
                is ChatMessage -> {

                }
            }
        }
    }
}


// this is a wrapper function
fun Route.standardWebSocket(
    // frame is a single piece of data that is either received or sent using websockets

    // this function determines what kind of frame this is
    handleFrame: suspend (
        socket: DefaultWebSocketServerSession, // WebSocketServerSession is a connection from a client to the server
        clientId: String, // who sent the data to the server
        message: String, // message that the player sent by the socket
        payload: BaseModel // parsed json data. BaseModel is a wrapper around every json object we send by a WebSocket
    ) -> Unit
) {
    // url through which we can receive the request from clients -> we don't do
//    route("/")

    // webSocket requests that we receive
    webSocket {
        val session = call.sessions.get<DrawingSession>()
        if (session == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session"))
            return@webSocket
        }

        try {
            // ReceiveChannels in Kotlin receive events which are Frames sent from our clients
            // receiving all the frames
            incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val message = frame.readText()
                    val jsonObject = JsonParser.parseString(message).asJsonObject
                    val type = when (jsonObject.get("type").asString) {
                        TYPE_DRAW_DATA -> DrawData::class.java
                        TYPE_CHAT_MESSAGE -> ChatMessage::class.java
                        else -> BaseModel::class.java
                    }
                    val payload = gson.fromJson(message, type)
                    handleFrame(this, session.clientId, message, payload)
                }
            }
        }
        catch (e: Exception) {
            e.printStackTrace()
        }
        finally { // handle disconnects

        }
    }
}