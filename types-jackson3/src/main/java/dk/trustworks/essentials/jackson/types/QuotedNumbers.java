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

package dk.trustworks.essentials.jackson.types;

import tools.jackson.core.JsonParser;
import tools.jackson.core.exc.StreamConstraintsException;

/**
 * Holds a quoted number ({@code "99.99"}) to the same length limit Jackson applies to a JSON number token.
 * <p>
 * A JSON number is bounded by the parser's {@link tools.jackson.core.StreamReadConstraints} maximum number length
 * (1000 characters by default) before Jackson converts it. A quoted number reaches the deserializer as a string, which
 * is only bounded by the much larger string-length limit, so a deserializer that parses the text itself must apply the
 * number-length limit first.
 */
final class QuotedNumbers {
    private QuotedNumbers() {
    }

    /**
     * Checks the length of a quoted number's text against the parser's number-length constraint: the floating-point
     * limit when the text has a {@code .}, {@code e} or {@code E}, otherwise the integer limit (a leading sign not
     * counted), as Jackson does for a JSON number token.
     *
     * @param p    the parser the text was read from
     * @param text the trimmed text of the quoted number
     * @throws StreamConstraintsException when the text is longer than the parser allows for a number
     */
    static void checkLength(JsonParser p, String text) throws StreamConstraintsException {
        var constraints = p.streamReadConstraints();
        var length      = text.length();
        if (length <= constraints.getMaxNumberLength()) {
            return;
        }
        if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
            constraints.validateFPLength(length);
        } else {
            var hasSign = text.charAt(0) == '-' || text.charAt(0) == '+';
            constraints.validateIntegerLength(hasSign ? length - 1 : length);
        }
    }
}
