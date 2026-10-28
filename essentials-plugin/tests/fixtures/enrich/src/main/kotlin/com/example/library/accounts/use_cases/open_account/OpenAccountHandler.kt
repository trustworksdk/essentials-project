package com.example.library.accounts.use_cases.open_account

import com.example.library.accounts.events.AccountOpened

/**
 * Registers a new member so they can borrow.
 */
class OpenAccountHandler(private val known: Set<String>) {
    fun handle(cmd: OpenAccount): AccountOpened {
        val memberId = cmd.memberId ?: throw IllegalArgumentException("memberId is required")
        requireNotNull(cmd.tier) { "tier is required" }
        require(cmd.age >= 16) { "A member is at least 16 years old" }
        check(memberId !in known) { "A member opens one account only" }
        if (cmd.tier == "staff") {
            throw IllegalStateException("Staff accounts are opened by the administration")
        }
        return AccountOpened(memberId, cmd.tier)
    }
}
