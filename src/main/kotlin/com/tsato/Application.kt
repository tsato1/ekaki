package com.tsato

import com.tsato.session.DrawingSession
import io.ktor.application.*
import io.ktor.response.*
import io.ktor.request.*
import io.ktor.routing.*
import io.ktor.http.*
import io.ktor.gson.*
import io.ktor.features.*
import org.slf4j.event.*
import io.ktor.websocket.*
import io.ktor.http.cio.websocket.*
import io.ktor.sessions.*
import io.ktor.util.*
import java.time.*

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused") // referenced in Application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {
    install(Sessions) {
        cookie<DrawingSession>("SESSION")
    }
    // executed whenever a client makes request to our server
    intercept(ApplicationCallPipeline.Features) {
        if(call.sessions.get<DrawingSession>() == null) { // no session found
            val clientId = call.parameters["client_id"] ?: ""
            call.sessions.set(DrawingSession(clientId, generateNonce()))
        }
    }

    install(ContentNegotiation) {
        gson {
        }
    }

    install(CallLogging)
    install(WebSockets)
}
