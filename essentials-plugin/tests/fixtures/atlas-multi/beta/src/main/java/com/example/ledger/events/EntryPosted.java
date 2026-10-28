package com.example.ledger.events;

public record EntryPosted(String ledgerId, long amount) {
}
