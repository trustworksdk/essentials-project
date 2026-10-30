package com.example.post.letters.events

sealed interface LetterEvent

data class LetterPosted(val id: String) : LetterEvent
data class LetterReturned(val id: String) : LetterEvent
