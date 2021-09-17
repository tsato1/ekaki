package com.tsato.data

import com.tsato.data.models.Announcement
import com.tsato.data.models.ChosenWord
import com.tsato.data.models.PhaseChange
import com.tsato.gson
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.*

class Room(
    val name: String,
    val maxPlayers: Int,
    var players: List<Player> = listOf()
) {
    private var timerJob: Job? = null
    private var drawingPlayer: Player? = null
    private var winningPlayers = listOf<String>()
    private var word: String? = null

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

    private fun timeAndNotify(ms: Long) { // millisecond until we switch to the next phase
        timerJob?.cancel()
        timerJob = GlobalScope.launch { // it's ok to use global scope because of no lifecycle on server side
            val phaseChange = PhaseChange(
                phase, ms, drawingPlayer?.userName
            )

            repeat((ms / UPDATE_TIME_FREQUENCY).toInt()) {
                if (it != 0) { // except for the first iteration
                    phaseChange.phase = null
                }
                broadcast(gson.toJson(phaseChange))
                phaseChange.time -= UPDATE_TIME_FREQUENCY
                delay(UPDATE_TIME_FREQUENCY)
            }

            phase = when(phase) {
                Phase.WAITING_FOR_START -> Phase.NEW_ROUND
                Phase.GAME_RUNNING -> Phase.AFTER_GAME
                Phase.AFTER_GAME -> Phase.NEW_ROUND
                Phase.NEW_ROUND -> Phase.GAME_RUNNING
                else -> Phase.WAITING_FOR_PLAYERS
            }
        }
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

    fun setWordAndSwitchToGameRunning(word: String) {
        this.word = word
        phase = Phase.GAME_RUNNING
    }

    private fun waitingForPlayers() {
        GlobalScope.launch {
            timeAndNotify(DELAY_WAITING_FOR_START_TO_NEW_ROUND)
            val phaseChange = PhaseChange(
                Phase.WAITING_FOR_START,
                DELAY_WAITING_FOR_START_TO_NEW_ROUND)
            broadcast(gson.toJson(phaseChange))
        }
    }

    private fun waitingForStart() {
        GlobalScope.launch {
            val phaseChange = PhaseChange(
                Phase.WAITING_FOR_PLAYERS,
                DELAY_WAITING_FOR_START_TO_NEW_ROUND
            )
            broadcast(gson.toJson(phaseChange))
        }
    }

    private fun newRound() {

    }

    private fun gameRunning() {

    }

    private fun afterGame() {
        GlobalScope.launch {
            if (winningPlayers.isEmpty()) {
                drawingPlayer?.let {
                    it.score -= PENALTY_NOBODY_GUESSED
                }
            }

            word?.let {
                val chosenWord = ChosenWord(it, name)
                broadcast(gson.toJson(chosenWord))
            }

            timeAndNotify(DELAY_AFTER_GAME_TO_NEW_ROUND)

            val phaseChange = PhaseChange(Phase.AFTER_GAME, DELAY_AFTER_GAME_TO_NEW_ROUND)
            broadcast(gson.toJson(phaseChange))
        }
    }

    enum class Phase {
        WAITING_FOR_PLAYERS,
        WAITING_FOR_START,
        NEW_ROUND,
        GAME_RUNNING,
        AFTER_GAME
    }

    companion object {
        const val UPDATE_TIME_FREQUENCY = 1000L

        const val DELAY_WAITING_FOR_START_TO_NEW_ROUND = 10000L
        const val DELAY_NEW_ROUND_TO_GAME_RUNNING = 20000L
        const val DELAY_GAME_RUNNING_TO_AFTER_GAME = 60000L
        const val DELAY_AFTER_GAME_TO_NEW_ROUND = 10000L

        const val PENALTY_NOBODY_GUESSED = 15
    }
}