package com.tsato.data

import com.tsato.data.models.*
import com.tsato.gson
import com.tsato.util.getRandomWords
import com.tsato.util.matchesWord
import com.tsato.util.transformToUnderscores
import com.tsato.util.words
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.*
import kotlin.math.round

class Room(
    val name: String,
    val maxPlayers: Int,
    var players: List<Player> = listOf()
) {
    private var timerJob: Job? = null
    private var drawingPlayer: Player? = null
    private var winningPlayers = listOf<String>() // contains the players who guessed the word right in one round
    private var word: String? = null
    private var currWords: List<String>? = null // contains current 3 words that drawer can choose from
    private var drawingPlayerIndex = 0 // index of the currently drawing player in the players list
    private var startTimeStamp = 0L

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

        sendWordToPlayers(player)
        broadcastPlayerStates()
        broadcast(gson.toJson(announcement))
        return player
    }

    fun removePlayer(clientId: String) {
        GlobalScope.launch {
            broadcastPlayerStates()
        }
    }

    private fun timeAndNotify(ms: Long) { // millisecond until we switch to the next phase
        timerJob?.cancel()
        timerJob = GlobalScope.launch { // it's ok to use global scope because of no lifecycle on server side
            startTimeStamp = System.currentTimeMillis()

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

    private fun isGuessCorrect(guess: ChatMessage): Boolean {
        return guess.matchesWord(word ?: return false) &&
                !winningPlayers.contains(guess.from) &&
                guess.from != drawingPlayer?.userName &&
                phase == Phase.GAME_RUNNING
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
        currWords = getRandomWords(3)
        val newWords = NewWords(currWords!!)
        setNextDrawingPlayer()
        GlobalScope.launch {
            broadcastPlayerStates()
            drawingPlayer?.socket?.send(Frame.Text(gson.toJson(newWords)))
            timeAndNotify(DELAY_NEW_ROUND_TO_GAME_RUNNING)
        }
    }

    private fun gameRunning() {
        winningPlayers = listOf()

        // if word is null, the player didn't choose the word in time -> choose randomly from currWords
        //                                     if still null, -> choose randomly from words (this shouldn't happen)
        val wordToSend = word ?: currWords?.random() ?: words.random()
        val wordWithUnderscores = wordToSend.transformToUnderscores()
        val drawingUserName = (drawingPlayer ?: players.random()).userName // drawingPlayer shouldn't be null

        val gameStateForDrawingPlayer = GameState(drawingUserName, wordToSend)
        val gameStateForGuessingPlayers = GameState(drawingUserName, wordWithUnderscores)

        GlobalScope.launch {
            broadcastToAllExcept(
                gson.toJson(gameStateForGuessingPlayers),
                drawingPlayer?.clientId ?: players.random().clientId
            )
            drawingPlayer?.socket?.send(Frame.Text(gson.toJson(gameStateForDrawingPlayer)))

            timeAndNotify(DELAY_GAME_RUNNING_TO_AFTER_GAME)
            println("Drawing phase in room $name started. It will run for ${DELAY_GAME_RUNNING_TO_AFTER_GAME / 1000}s")
        }
    }

    private fun afterGame() {
        GlobalScope.launch {
            if (winningPlayers.isEmpty()) {
                drawingPlayer?.let {
                    it.score -= PENALTY_NOBODY_GUESSED
                }
            }

            broadcastPlayerStates()

            word?.let {
                val chosenWord = ChosenWord(it, name)
                broadcast(gson.toJson(chosenWord))
            }

            timeAndNotify(DELAY_AFTER_GAME_TO_NEW_ROUND)

            val phaseChange = PhaseChange(Phase.AFTER_GAME, DELAY_AFTER_GAME_TO_NEW_ROUND)
            broadcast(gson.toJson(phaseChange))
        }
    }

    /*
      returns true if everybody guessed the word correctly
     */
    private fun addWinningPlayer(userName: String): Boolean {
        winningPlayers = winningPlayers + userName

        if (winningPlayers.size == players.size - 1) {
            phase = Phase.NEW_ROUND
            return true
        }
        return false
    }

    suspend fun checkWordAndNotifyPlayers(message: ChatMessage): Boolean {
        if (isGuessCorrect(message)) {
            val guessingTime = System.currentTimeMillis() - startTimeStamp // time duration needed to guess the word
            val timePercentageLeft = 1f - guessingTime.toFloat() / DELAY_GAME_RUNNING_TO_AFTER_GAME
            val score = GUESS_SCORE_DEFAULT + GUESS_SCORE_PERCENTAGE_MULTIPLIER * timePercentageLeft
            val player = players.find { it.userName == message.from }

            player?.let {
                it.score += score.toInt()
            }

            drawingPlayer?.let {
                it.score += GUESS_SCORE_DRAWING_PLAYER / players.size
            }

            broadcastPlayerStates()

            val announcement = Announcement(
                "{$message.from} has guessed it.",
                System.currentTimeMillis(),
                Announcement.TYPE_PLAYER_GUESSED_WORD
            )
            broadcast(gson.toJson(announcement))

            val isRoundOver = addWinningPlayer(message.from)
            if (isRoundOver) {
                val roundOverAnnouncement = Announcement(
                    "Everybody guessed it. New round is starting...",
                    System.currentTimeMillis(),
                    Announcement.TYPE_EVERYBODY_GUESSED_IT
                )
                broadcast(gson.toJson(roundOverAnnouncement))
            }

            return true
        }
        return false
    }

    private suspend fun broadcastPlayerStates() {
        val playersData = players
            .sortedByDescending { it.score }
            .map { PlayerData(it.userName, it.isDrawing, it.score, it.rank) }

        players.forEachIndexed { i, playerData ->
            playerData.rank = i + 1
        }
        broadcast(gson.toJson(PlayerDataList(playersData)))
    }

    /*
       called whenever a new player joins a room
     */
    private suspend fun sendWordToPlayers(player: Player) {
        val delay = when (phase) {
            Phase.WAITING_FOR_START -> DELAY_WAITING_FOR_START_TO_NEW_ROUND
            Phase.NEW_ROUND -> DELAY_NEW_ROUND_TO_GAME_RUNNING
            Phase.GAME_RUNNING -> DELAY_GAME_RUNNING_TO_AFTER_GAME
            Phase.AFTER_GAME -> DELAY_AFTER_GAME_TO_NEW_ROUND
            else -> 0L
        }
        val phaseChange = PhaseChange(phase, delay, drawingPlayer?.userName)

        word?.let { currWord ->
            drawingPlayer?.let { drawingPlayer ->
                val gameState = GameState(
                    drawingPlayer.userName,
                    if (player.isDrawing || phase == Phase.AFTER_GAME) {
                        currWord
                    } else {
                        currWord.transformToUnderscores()
                    }
                )
                player.socket.send(Frame.Text(gson.toJson(gameState)))
            }
        }
        player.socket.send(Frame.Text(gson.toJson(phaseChange)))
    }

    private fun setNextDrawingPlayer() {
        drawingPlayer?.isDrawing = false

        if (players.isEmpty())
            return

        drawingPlayer = if (drawingPlayerIndex <= players.size - 1) {
            players[drawingPlayerIndex]
        }
        else {
            players.last()
        }

        if (drawingPlayerIndex < players.size - 1)
            drawingPlayerIndex++
        else
            drawingPlayerIndex = 0
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

        const val PENALTY_NOBODY_GUESSED = 50
        const val GUESS_SCORE_DEFAULT = 50
        const val GUESS_SCORE_PERCENTAGE_MULTIPLIER = 50    // gets multiplied with the percentage of the remaining time
                                                            // , which gets added to the point GUESS_SCORE_DEFAULT
        const val GUESS_SCORE_DRAWING_PLAYER = 50
    }
}