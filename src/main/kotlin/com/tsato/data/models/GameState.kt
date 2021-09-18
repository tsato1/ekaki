package com.tsato.data.models

import com.tsato.util.Constants.TYPE_GAME_STATE

data class GameState(
    val drawingPlayer: String,
    val word: String,
) : BaseModel(TYPE_GAME_STATE)
