package com.tsato.data.models

import com.tsato.util.Constants.TYPE_PLAYER_DATA_LIST

data class PlayerDataList(
    val players: List<PlayerData>
) : BaseModel(TYPE_PLAYER_DATA_LIST)
