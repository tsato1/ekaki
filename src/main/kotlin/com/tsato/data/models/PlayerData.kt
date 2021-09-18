package com.tsato.data.models

/*
    player data sent by webSocket
    this class doesn't inherit from BaseModel because;
    we don't send visually via webSocket, but send as a list: PlayerData of each player together in a list
 */
data class PlayerData(
    val userName: String,
    var isDrawing: Boolean = false,
    var score: Int = 0,
    var rank: Int = 0
)
