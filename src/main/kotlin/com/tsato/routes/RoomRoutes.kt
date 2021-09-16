package com.tsato.routes

import com.tsato.data.Room
import com.tsato.data.models.BasicApiResponse
import com.tsato.data.models.CreateRoomRequest
import com.tsato.data.models.RoomResponse
import com.tsato.server
import com.tsato.util.Constants.MAX_ROOM_SIZE
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*

fun Route.createRoomRoutes() {
    route("/api/createRoom") {
        post {
            val roomRequest = call.receiveOrNull<CreateRoomRequest>()

            if (roomRequest == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            if (server.rooms[roomRequest.name] != null) { // found the room
                call.respond(HttpStatusCode.OK,
                    BasicApiResponse(false, "Room already exists."))
                return@post
            }

            if (roomRequest.maxPlayers < 2) {
                call.respond(HttpStatusCode.OK,
                    BasicApiResponse(false, "Minimum room size is 2."))
                return@post
            }

            if (roomRequest.maxPlayers > MAX_ROOM_SIZE) {
                call.respond(HttpStatusCode.OK,
                    BasicApiResponse(false, "Maximum room size is $MAX_ROOM_SIZE."))
                return@post
            }

            val room = Room (
                roomRequest.name,
                roomRequest.maxPlayers
            )

            server.rooms[roomRequest.name] = room
            println("Room created: ${roomRequest.name}")

            call.respond(HttpStatusCode.OK, BasicApiResponse(true))
        }
    }
}

fun Route.getRoomsRoute() {
    route("/api/getRooms") {
        get {
            val searchQuery = call.parameters["searchQuery"]

            if (searchQuery == null) { // never happens unless a request to our server comes from outside of our app
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }

            val roomsResult = server.rooms.filterKeys {
                it.contains(searchQuery, ignoreCase = true)
            }

            val roomResponses = roomsResult.values.map {
                RoomResponse(it.name, it.maxPlayers, it.players.size)
            }.sortedBy { it.name }

            call.respond(HttpStatusCode.OK, roomResponses)
        }
    }
}