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
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.StreamConstraintsException;

/**
 * Holds a quoted number ({@code "99.99"}) to the same length limit Jackson applies to a JSON number token.
 * <p>
 * A JSON number is bounded by the parser's {@link tools.jackson.core.StreamReadConstraints} maximum number length
 * (1000 characters by default) before Jackson converts it. A quoted number reaches the deserializer as a string, and a
 * map key as a property name, which are only bounded by the much larger string- and name-length limits, so a
 * deserializer that parses the text itself must apply the number-length limit first.
 * <p>
 * Only text shaped like a number is held to that limit. Longer text with any other character is not a number at all,
 * and is reported as one that does not parse, without parsing it.
 */
final class QuotedNumbers {
    private QuotedNumbers() {
    }

    /**
     * Checks the length of a quoted number's text against the parser's number-length constraint.
     *
     * @param p    the parser the text was read from
     * @param text the trimmed text of the quoted number
     * @throws StreamConstraintsException when the text is shaped like a number and longer than the parser allows for one
     * @throws NumberFormatException      when the text is longer than the parser allows for a number and has a character
     *                                    no number has
     * @see #checkLength(StreamReadConstraints, String)
     */
    static void checkLength(JsonParser p, String text) throws StreamConstraintsException {
        checkLength(p.streamReadConstraints(), text);
    }

    /**
     * Checks the length of a number's text against a number-length constraint: the floating-point limit when the text
     * has a {@code .}, {@code e} or {@code E}, otherwise the integer limit (a leading sign not counted), as Jackson does
     * for a JSON number token. Text within the limit is not inspected.
     * <p>
     * Text over the limit that has a character other than {@code 0-9}, {@code +}, {@code -}, {@code .}, {@code e} or
     * {@code E} is not a number, so it is refused with the {@link NumberFormatException} parsing it would have thrown —
     * which a caller reports the way it reports any text that is not a number — but without parsing it first.
     *
     * @param constraints the read constraints in force
     * @param text        the trimmed text of the number
     * @throws StreamConstraintsException when the text is shaped like a number and longer than the constraints allow
     * @throws NumberFormatException      when the text is longer than the constraints allow for a number and has a
     *                                    character no number has
     */
    static void checkLength(StreamReadConstraints constraints, String text) throws StreamConstraintsException {
        var length = text.length();
        if (length <= constraints.getMaxNumberLength()) {
            return;
        }
        if (!isNumberShaped(text)) {
            throw new NumberFormatException("Not a number: text of " + length + " characters with a non-numeric character");
        }
        if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
            constraints.validateFPLength(length);
        } else {
            var hasSign = text.charAt(0) == '-' || text.charAt(0) == '+';
            constraints.validateIntegerLength(hasSign ? length - 1 : length);
        }
    }

    private static boolean isNumberShaped(String text) {
        for (var i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            if ((c < '0' || c > '9') && c != '+' && c != '-' && c != '.' && c != 'e' && c != 'E') {
                return false;
            }
        }
        return true;
    }
}
