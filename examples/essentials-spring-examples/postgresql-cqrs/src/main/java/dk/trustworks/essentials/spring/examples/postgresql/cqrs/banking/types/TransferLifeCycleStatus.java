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
 * How far an {@code IntraBankMoneyTransfer} has got.
 *
 * <p>The stages are strictly ordered and each transition asserts the one it expects, so a redelivered step cannot
 * move the transfer twice. Moving money between two aggregates cannot be a single transaction under event sourcing;
 * this status is the record of the intermediate states that fact makes unavoidable.
 */
public enum TransferLifeCycleStatus {REQUESTED, FROM_ACCOUNT_WITHDRAWN, TO_ACCOUNT_DEPOSITED, COMPLETED}
