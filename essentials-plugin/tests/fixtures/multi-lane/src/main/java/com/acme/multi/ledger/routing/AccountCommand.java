package com.acme.multi.ledger.routing;

import com.acme.multi.ledger.types.AccountId;

/** Routing marker for the LedgerAccounts aggregate type. */
public interface AccountCommand {
    AccountId id();
}
