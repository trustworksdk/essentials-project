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

package dk.trustworks.essentials.examples.trading.reconciliation.types;

import dk.trustworks.essentials.types.CharSequenceType;

/**
 * Names an operator case by the break it is about: {@code <statementId>:<kind>:<reference>} (design phase 5 §4) - the
 * same break, found again at the next close, is the same case.
 */
public class CaseId extends CharSequenceType<CaseId> {
    public CaseId(String value) {
        super(value);
    }

    public CaseId(CharSequence value) {
        super(value);
    }

    public static CaseId of(CharSequence value) {
        return new CaseId(value);
    }

    public static CaseId of(String statementId, String kind, String reference) {
        return new CaseId(statementId + ":" + kind + ":" + reference);
    }
}
