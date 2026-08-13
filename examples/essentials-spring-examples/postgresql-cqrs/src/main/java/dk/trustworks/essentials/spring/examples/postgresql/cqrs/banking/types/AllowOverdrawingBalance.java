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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types;

/**
 * Whether a withdrawal may take the account below zero.
 *
 * <p>An explicit parameter on {@code Account.withdraw} rather than a policy the aggregate looks up, so the decision
 * is made -- and visible -- at the call site. The {@code transfer_money} automation passes {@code YES} because the
 * transfer aggregate has already authorised the movement; an ordinary withdrawal passes {@code NO} and is rejected
 * with {@link InsufficientFundsException}.
 *
 * <p>An enum rather than a {@code boolean}: {@code withdraw(amount, date, txId, NO)} says at a glance what
 * {@code withdraw(amount, date, txId, false)} does not.
 */
public enum AllowOverdrawingBalance {
    YES,
    NO;

    public boolean disallowed() {
        return this == AllowOverdrawingBalance.NO;
    }
}
