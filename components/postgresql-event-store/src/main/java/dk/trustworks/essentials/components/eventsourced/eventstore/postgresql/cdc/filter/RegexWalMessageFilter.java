/*
 *  Copyright 2021-2026 the original author or authors.
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

/**
 * Filters Write-Ahead Log (WAL) messages based on specified criteria using pattern matching
 * and byte array processing. This class implements the {@code WalMessageFilter} interface
 * and determines whether a WAL message should be persisted based on its content.
 * <p>
 * The criteria checks involve:
 * - The presence of a specific "kind" field with a value of "insert".
 * - The presence of a "table" field and its adherence to a naming convention (ending with "_events").
 * <p>
 * Regex-based filtering is applied for text-based JSON messages, and byte array processing is
 * utilized for optimization.
 * <p>
 * Criteria Breakdown:
 * - For text-based JSON: Regex patterns are used to locate and validate fields.
 * - For byte arrays: Direct parsing is employed to find and validate fields and their values
 *   without relying on conversion to intermediate String objects.
 * <p>
 * This class allows detecting and filtering WAL messages that match the following:
 * - The "kind" field must exist and be a case-insensitive match for "insert".
 * - The "table" field must exist and be a case-insensitive match for names ending with "_events".
 */
public class RegexWalMessageFilter implements WalMessageFilter {
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

    @Override
    public boolean shouldPersist(byte[] walJsonBytes) {
        if (walJsonBytes == null || walJsonBytes.length == 0) {
            return false;
        }

        if (!fieldValueEqualsIgnoreCase(walJsonBytes, "kind", "insert")) {
            return false;
        }

        return fieldValueEndsWithIgnoreCase(walJsonBytes, "table", "_events");
    }

    private static boolean fieldValueEqualsIgnoreCase(byte[] bytes, String field, String expectedValue) {
        int[] range = findQuotedFieldValue(bytes, field);
        return range != null && equalsIgnoreCase(bytes, range[0], range[1] - range[0], expectedValue);
    }

    private static boolean fieldValueEndsWithIgnoreCase(byte[] bytes, String field, String suffix) {
        int[] range = findQuotedFieldValue(bytes, field);
        return range != null && endsWithIgnoreCase(bytes, range[0], range[1] - range[0], suffix);
    }

    private static int[] findQuotedFieldValue(byte[] bytes, String fieldName) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != '"') continue;

            int keyStart = i + 1;
            int keyEnd = findUnescapedQuote(bytes, keyStart);
            if (keyEnd < 0) return null;

            if (!equalsIgnoreCase(bytes, keyStart, keyEnd - keyStart, fieldName)) {
                i = keyEnd;
                continue;
            }

            int cursor = skipWhitespace(bytes, keyEnd + 1);
            if (cursor >= bytes.length || bytes[cursor] != ':') {
                i = keyEnd;
                continue;
            }

            cursor = skipWhitespace(bytes, cursor + 1);
            if (cursor >= bytes.length || bytes[cursor] != '"') {
                i = keyEnd;
                continue;
            }

            int valueStart = cursor + 1;
            int valueEnd = findUnescapedQuote(bytes, valueStart);
            if (valueEnd < 0) return null;
            return new int[]{valueStart, valueEnd};
        }
        return null;
    }

    private static int skipWhitespace(byte[] bytes, int idx) {
        while (idx < bytes.length) {
            byte b = bytes[idx];
            if (b != ' ' && b != '\n' && b != '\r' && b != '\t') {
                return idx;
            }
            idx++;
        }
        return idx;
    }

    private static int findUnescapedQuote(byte[] bytes, int start) {
        for (int i = start; i < bytes.length; i++) {
            if (bytes[i] != '"') continue;
            if (!isEscaped(bytes, i)) return i;
        }
        return -1;
    }

    private static boolean isEscaped(byte[] bytes, int idx) {
        int slashCount = 0;
        for (int i = idx - 1; i >= 0 && bytes[i] == '\\'; i--) {
            slashCount++;
        }
        return (slashCount & 1) == 1;
    }

    private static boolean equalsIgnoreCase(byte[] bytes, int offset, int len, String value) {
        if (len != value.length()) return false;
        for (int i = 0; i < len; i++) {
            int left = asciiToLower(bytes[offset + i]);
            int right = asciiToLower((byte) value.charAt(i));
            if (left != right) return false;
        }
        return true;
    }

    private static boolean endsWithIgnoreCase(byte[] bytes, int offset, int len, String suffix) {
        if (len < suffix.length()) return false;
        int start = offset + (len - suffix.length());
        for (int i = 0; i < suffix.length(); i++) {
            int left = asciiToLower(bytes[start + i]);
            int right = asciiToLower((byte) suffix.charAt(i));
            if (left != right) return false;
        }
        return true;
    }

    private static int asciiToLower(byte b) {
        return b >= 'A' && b <= 'Z' ? b + 32 : b;
    }
}
