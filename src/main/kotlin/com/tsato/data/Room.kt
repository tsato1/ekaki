package com.tsato.data

import com.tsato.data.models.Announcement
import com.tsato.gson
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.isActive

class Room(
    val name: String,
    val maxPlayers: Int,
    var players: List<Player> = listOf()
) {
    private var phaseChangedListener: ((Phase) -> Unit)? = null
    var phase = Phase.WAITING_FOR_PLAYERS // current phase that is running now
        set(value) {
            synchronized(field) {
                field = value
                phaseChangedListener?.let { listener ->
                    listener(value)
                }
            }
        }

    private fun setPhaseChangedListener(listener: (Phase) -> Unit) {
        phaseChangedListener = listener
    }

    init {
        setPhaseChangedListener { newPhase ->
            when (newPhase) {
                Phase.WAITING_FOR_PLAYERS -> waitingForPlayers()
                Phase.WAITING_FOR_START -> waitingForStart()
                Phase.NEW_ROUND -> newRound()
                Phase.GAME_RUNNING -> gameRunning()
                Phase.AFTER_GAME -> afterGame()
            }
        }
    }

    suspend fun addPlayer(clientId: String, userName: String, webSocketSession: WebSocketSession): Player {
        val player = Player(userName, webSocketSession, clientId)
        players = players + player // player list has to be immutable in multi-threaded situation

        if (players.size == 1) {
            phase = Phase.WAITING_FOR_PLAYERS
        }
        else if (players.size == 2 && phase == Phase.WAITING_FOR_PLAYERS) {
            phase = Phase.WAITING_FOR_START
            players = players.shuffled()
        }
        else if (phase == Phase.WAITING_FOR_START && players.size == maxPlayers) {
            phase = Phase.NEW_ROUND
            players = players.shuffled()
        }

        val announcement = Announcement(
            "$userName joined the party",
            System.currentTimeMillis(),
            Announcement.TYPE_PLAYER_JOINED
        )

        broadcast(gson.toJson(announcement))
        return player
    }

    suspend fun broadcast(message: String) {
        players.forEach { player ->
            if (player.socket.isActive) {
                player.socket.send(Frame.Text(message))
            }
        }
    }

    /*
       clientId:  of the one that we don't want to send this message to
     */
    suspend fun broadcastToAllExcept(message: String, clientId: String) {
        players.forEach { player ->
            if (player.clientId != clientId && player.socket.isActive) {
                player.socket.send(Frame.Text(message))
            }
        }
    }

    fun containsPlayer(userName: String): Boolean {
        return players.find { it.userName == userName } != null
    }

    private fun waitingForPlayers() {

    }

    private fun waitingForStart() {

    }

    private fun newRound() {

    }

    private fun gameRunning() {

    }

    private fun afterGame() {

    }

    enum class Phase {
        WAITING_FOR_PLAYERS,
        WAITING_FOR_START,
        NEW_ROUND,
        GAME_RUNNING,
        AFTER_GAME
    }
}