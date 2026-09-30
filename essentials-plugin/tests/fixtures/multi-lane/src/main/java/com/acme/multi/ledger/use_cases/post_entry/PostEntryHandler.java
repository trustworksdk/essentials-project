package com.acme.multi.ledger.use_cases.post_entry;

import com.acme.multi.ledger.aggregates.Accounts;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.springframework.stereotype.Component;

@Component
public class PostEntryHandler extends AnnotatedCommandHandler {
    private final Accounts accounts;

    public PostEntryHandler(Accounts accounts) { this.accounts = accounts; }

    @CmdHandler
    public void handle(PostEntry cmd) {
        accounts.getAccount(cmd.id()).post(cmd.amountMinor());
    }
}
