package com.acme.inbox.events

data class MessageReceived(val messageId: String, val body: String)
