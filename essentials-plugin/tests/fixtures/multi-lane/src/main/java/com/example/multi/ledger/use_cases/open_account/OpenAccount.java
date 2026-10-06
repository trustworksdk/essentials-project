package com.example.multi.ledger.use_cases.open_account;

import com.example.multi.ledger.routing.AccountCommand;
import com.example.multi.ledger.types.AccountId;

public record OpenAccount(AccountId id, String currency) implements AccountCommand {
}
