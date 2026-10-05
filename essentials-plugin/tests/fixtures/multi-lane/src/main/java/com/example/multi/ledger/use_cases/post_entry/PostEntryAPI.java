package com.example.multi.ledger.use_cases.post_entry;

import com.example.multi.ledger.types.AccountId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledger/accounts")
public class PostEntryAPI {
    private final CommandBus commandBus;

    public PostEntryAPI(CommandBus commandBus) { this.commandBus = commandBus; }

    public record PostEntryRequest(long amountMinor) {}

    @PostMapping("/{accountId}/entries")
    public void postEntry(@PathVariable AccountId accountId, @RequestBody PostEntryRequest body) {
        commandBus.send(new PostEntry(accountId, body.amountMinor()));
    }
}
