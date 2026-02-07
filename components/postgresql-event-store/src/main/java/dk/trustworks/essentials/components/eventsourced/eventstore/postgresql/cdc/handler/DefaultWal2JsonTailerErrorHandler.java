/*
 *  Copyright 2021-2025 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.handler;

import dk.trustworks.essentials.components.foundation.IOExceptionUtil;

public class DefaultWal2JsonTailerErrorHandler implements Wal2JsonTailerErrorHandler {

    @Override
    public Decision onMessageError(String slotName, String json, Exception error) {
        // mapping/validation: skip
        if (error instanceof IllegalArgumentException) return Decision.CONTINUE;

        // IO-ish: reconnect/backoff
        if (IOExceptionUtil.isIOException(error)) return Decision.RETRY_CONNECTION;

        // unknown: reconnect (safe)
        return Decision.RETRY_CONNECTION;
    }

    @Override
    public Decision onStreamError(String slotName, Exception error) {
        if (IOExceptionUtil.isIOException(error)) return Decision.RETRY_CONNECTION;
        return Decision.RETRY_CONNECTION;
    }
}
