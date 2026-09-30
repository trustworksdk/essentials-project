package com.acme.multi.ledger.use_cases.open_account;

import com.acme.multi.ledger.routing.AccountCommand;
import com.acme.multi.ledger.types.AccountId;

public record OpenAccount(AccountId id, String currency) implements AccountCommand {
}
