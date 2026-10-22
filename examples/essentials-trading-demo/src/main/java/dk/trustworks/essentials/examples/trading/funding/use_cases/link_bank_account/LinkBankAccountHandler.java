/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.examples.trading.funding.use_cases.link_bank_account;

import dk.trustworks.essentials.examples.trading.funding.aggregates.*;
import dk.trustworks.essentials.reactive.command.*;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

@Service
public class LinkBankAccountHandler extends AnnotatedCommandHandler {
    private final BankAccountLinks links;

    public LinkBankAccountHandler(BankAccountLinks links) {
        this.links = requireNonNull(links, "No links provided");
    }

    @CmdHandler
    public void handle(LinkBankAccount cmd) {
        requireNonNull(cmd, "No cmd provided");
        links.find(cmd.accountId())
             .ifPresentOrElse(link -> link.link(cmd.bankAccount()),
                              () -> links.saveNew(new BankAccountLink(cmd.accountId(), cmd.bankAccount())));
    }
}
