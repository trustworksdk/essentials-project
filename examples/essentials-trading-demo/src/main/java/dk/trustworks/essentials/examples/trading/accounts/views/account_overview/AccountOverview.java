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

package dk.trustworks.essentials.examples.trading.accounts.views.account_overview;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.identity.types.UserId;
import dk.trustworks.essentials.types.Money;

/**
 * One account as its owner sees it: the buckets, and the figures derived from them exactly as the aggregate derives
 * them (design §3.3). Eventually consistent; never used to decide anything — the aggregate checks holds itself.
 */
public record AccountOverview(AccountId accountId,
                              UserId ownerId,
                              long generation,
                              PeriodId periodId,
                              Money settledCash,
                              Money pendingCashIn,
                              Money pendingCashOut,
                              Money held,
                              Money uncleared,
                              Money realizedPnlThisPeriod,
                              Money cashBalance,
                              Money buyingPower,
                              Money withdrawable,
                              boolean restricted) {
}
