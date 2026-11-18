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

import tools.jackson.core.*;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.DeserializationContext;

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
 * <p>
 * {@link #readQuotedNumber(JsonParser, DeserializationContext, Class, Parser, String)} is the one quoted-number rule for
 * a value: {@link NumberTypeJsonDeserializer} and {@link MoneyDeserializer} both read a quoted number through it, so they
 * trim, bound and report such text the same way.
 */
final class QuotedNumbers {
    private QuotedNumbers() {
    }

    /**
     * Turns the trimmed text of a quoted number into the value a deserializer returns.
     */
    @FunctionalInterface
    interface Parser {
        /**
         * @param p    the parser the text was read from, for its read constraints
         * @param text the trimmed text of the quoted number, already held to the number-length limit
         * @return the value read from the text
         * @throws NumberFormatException when the text is not a number
         * @throws ArithmeticException   when the number does not fit the target without loss
         */
        Object parse(JsonParser p, String text);
    }

    /**
     * Reads the current {@link tools.jackson.core.JsonToken#VALUE_STRING} token as a quoted number: trims the text,
     * holds it to the number-length limit ({@link #checkLength(JsonParser, String)}) and parses it with {@code parser}.
     * Text that is not a number, or does not fit the target without loss ({@link NumberFormatException} or
     * {@link ArithmeticException}, from the length check, the parse or the construction {@code parser} does), is reported
     * through {@link DeserializationContext#handleWeirdStringValue}: a registered
     * {@link tools.jackson.databind.deser.DeserializationProblemHandler} may supply a replacement, and without one the
     * caller gets an {@link tools.jackson.databind.exc.InvalidFormatException}. A {@link StreamConstraintsException}
     * propagates.
     *
     * @param p          the parser, positioned on the string token
     * @param ctxt       the deserialization context the problem is reported to
     * @param targetType the type the problem is reported against, and the type a problem handler's replacement must have
     * @param parser     turns the trimmed text into the value
     * @param failureMsg the problem description, without format arguments
     * @return the parsed value, or a problem handler's replacement for text that does not parse
     */
    static Object readQuotedNumber(JsonParser p,
                                   DeserializationContext ctxt,
                                   Class<?> targetType,
                                   Parser parser,
                                   String failureMsg) throws JacksonException {
        var text = p.getString().trim();
        try {
            // Over-long number-shaped text is refused with a StreamConstraintsException, over-long text that is not a
            // number with a NumberFormatException
            checkLength(p, text);
            return parser.parse(p, text);
        } catch (NumberFormatException | ArithmeticException e) {
            return ctxt.handleWeirdStringValue(targetType, text, failureMsg);
        }
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
