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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter;

import java.util.regex.Pattern;

public class Wal2JsonEventTableInsertFilter implements WalMessageFilter {
    private static final Pattern INSERT_KIND = Pattern.compile("\"kind\"\\s*:\\s*\"insert\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE       = Pattern.compile("\"table\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENTS_TBL  = Pattern.compile("(?i)^.+_events$");

    @Override
    public boolean shouldPersist(String walJson) {
        if (walJson == null || walJson.isBlank()) {
            return false;
        }
        if (!INSERT_KIND.matcher(walJson).find()) {
            return false;
        }

        var m = TABLE.matcher(walJson);
        if (!m.find()) {
            return false;
        }

        var table = m.group(1);
        return EVENTS_TBL.matcher(table).matches();
    }
}
