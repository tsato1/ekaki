package com.tsato.routes

import com.google.gson.JsonParser
import com.tsato.data.Player
import com.tsato.data.Room
import com.tsato.data.models.*
import com.tsato.gson
import com.tsato.server
import com.tsato.session.DrawingSession
import com.tsato.util.Constants.TYPE_ANNOUNCEMENT
import com.tsato.util.Constants.TYPE_CHAT_MESSAGE
import com.tsato.util.Constants.TYPE_CHOSEN_WORD
import com.tsato.util.Constants.TYPE_DRAW_DATA
import com.tsato.util.Constants.TYPE_GAME_STATE
import com.tsato.util.Constants.TYPE_JOIN_ROOM_HANDSHAKE
import com.tsato.util.Constants.TYPE_PHASE_CHANGE
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
                is JoinRoomHandshake -> {
                    val room = server.rooms[payload.roomName]
                    if (room == null) {
                        val gameError = GameError(GameError.ERROR_ROOM_NOT_FOUND)
                        socket.send(Frame.Text(gson.toJson(gameError)))
                        return@standardWebSocket
                    }

                    val player = Player(payload.userName, socket, payload.clientId)

                    server.playerJoined(player)

                    if (!room.containsPlayer(player.userName)) {
                        room.addPlayer(player.clientId, player.userName, socket)
                    }
                }
                is ChosenWord -> {
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket
                    room.setWordAndSwitchToGameRunning(payload.chosenWord)
                }
                is ChatMessage -> { // when any player writes any message in chat, go in here
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket
                    // if the guess was right, handle the case in "checkWordAndNotifyPlayers" function
                    // otherwise: treat the message as a normal message
                    if (!room.checkWordAndNotifyPlayers(payload)) {
                        room.broadcast(message)
                    }
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
                        TYPE_ANNOUNCEMENT -> Announcement::class.java
                        TYPE_JOIN_ROOM_HANDSHAKE -> JoinRoomHandshake::class.java
                        TYPE_PHASE_CHANGE -> PhaseChange::class.java
                        TYPE_CHOSEN_WORD -> ChosenWord::class.java
                        TYPE_GAME_STATE -> GameState::class.java
                        // TYPE_GAME_ERROR is only sent from the server side. no need to add here
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
            val playerWithClientId = server.getRoomWithClientId(session.clientId)?.players?.find {
                it.clientId == session.clientId
            }

            if (playerWithClientId != null) { // the player with clientId exists in any of the server's rooms
                server.playerLeft(session.clientId)
            }
        }
    }
}