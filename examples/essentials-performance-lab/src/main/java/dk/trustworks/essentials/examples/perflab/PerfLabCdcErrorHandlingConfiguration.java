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

package dk.trustworks.essentials.examples.perflab;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.handler.Wal2JsonTailerErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

@Configuration
public class PerfLabCdcErrorHandlingConfiguration {

    @Bean
    public Wal2JsonTailerErrorHandler perfLabWal2JsonTailerErrorHandler() {
        return new Wal2JsonTailerErrorHandler() {
            @Override
            public Decision onMessageError(String slotName, String json, Exception error) {
                return Decision.RETRY_CONNECTION;
            }

            @Override
            public Decision onStreamError(String slotName, Exception error) {
                if (isTerminalReplicationProtocolError(error)) {
                    return Decision.STOP;
                }
                return Decision.RETRY_CONNECTION;
            }

            private boolean isTerminalReplicationProtocolError(Throwable error) {
                Throwable current = error;
                while (current != null) {
                    String message = current.getMessage();
                    if (message != null) {
                        String normalized = message.toLowerCase(Locale.ROOT);
                        if (normalized.contains("start_replication") && normalized.contains("syntax error")) {
                            return true;
                        }
                        if (normalized.contains("not in replication mode")) {
                            return true;
                        }
                    }
                    current = current.getCause();
                }
                return false;
            }
        };
    }
}
