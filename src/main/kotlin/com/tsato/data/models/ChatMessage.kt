package com.tsato.data.models

import com.tsato.util.Constants.TYPE_CHAT_MESSAGE

data class ChatMessage(
    val from: String,
    val roomName: String, // room name that this message should go to
    val message: String,
    val timeStamp: Long
) : BaseModel(TYPE_CHAT_MESSAGE)
