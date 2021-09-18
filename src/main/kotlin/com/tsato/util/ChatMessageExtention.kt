package com.tsato.util

import com.tsato.data.models.ChatMessage

/*
 return true if the word passed into this function matches to the chat message
 */
fun ChatMessage.matchesWord(word: String): Boolean {
    return message.lowercase().trim() == word.lowercase().trim()
}