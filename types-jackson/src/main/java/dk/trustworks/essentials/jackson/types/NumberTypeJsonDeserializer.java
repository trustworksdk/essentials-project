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

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import dk.trustworks.essentials.types.*;

import java.io.IOException;
import java.math.*;
import java.util.Set;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Deserializes any concrete {@link NumberType} subclass from a JSON number, as the counterpart to
 * {@link NumberTypeJsonSerializer}.
 * <p>
 * Without it, {@link NumberType} subclasses fall through to Jackson 2's own creator detection, which selects a creator
 * by the incoming JSON token's own type and does not widen. A {@link BigDecimalType} declaring only the natural
 * {@code (BigDecimal)} constructor is therefore unreadable from an integral JSON number — {@code "quantity":2} fails
 * with <em>"no int/Int-argument constructor/factory method to deserialize from Number value"</em>. It serializes fine,
 * so the breakage only surfaces when existing events are replayed. Declaring a {@code (double)} constructor clears the
 * error but routes every floating-point token through a {@code double}, silently truncating a decimal beyond
 * {@code double} precision. Reading the value at its own width here removes both problems, and removes the need for
 * consumers to declare anything beyond the natural value-typed constructor.
 * <p>
 * Construction goes through {@link SingleValueType#from(Object, Class)}, so a concrete type's own validation still
 * runs.
 *
 * @param <T> the concrete {@link NumberType} subclass this instance was resolved for
 * @see NumberTypeJsonDeserializers
 */
public class NumberTypeJsonDeserializer<T extends NumberType<?, ?>> extends JsonDeserializer<T> {
    /**
     * The wrapped {@link Number} types that cannot represent a fraction. Jackson's own coercion would quietly discard
     * one; see {@link #deserialize(JsonParser, DeserializationContext)}.
     */
    private static final Set<Class<?>> INTEGRAL_NUMBER_CLASSES = Set.of(BigInteger.class,
                                                                       Long.class,
                                                                       Integer.class,
                                                                       Short.class,
                                                                       Byte.class);

    private final Class<T>                concreteType;
    private final Class<? extends Number> numberClass;

    public NumberTypeJsonDeserializer(Class<T> concreteType) {
        this.concreteType = requireNonNull(concreteType, "No concreteType provided");
        this.numberClass = NumberType.resolveNumberClass(concreteType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        var token = p.currentToken();

        // A quoted number ("2") is readable today through Jackson's string coercion. Keep that path working, or data
        // persisted with WRITE_NUMBERS_AS_STRINGS - or written by a producer that quotes large numbers - stops loading.
        if (token == JsonToken.VALUE_STRING) {
            var text = p.getText().trim();
            try {
                return (T) SingleValueType.from(parseText(text), (Class) concreteType);
            } catch (NumberFormatException | ArithmeticException e) {
                return (T) ctxt.handleWeirdStringValue(concreteType,
                                                       text,
                                                       "not a valid %s",
                                                       numberClass.getSimpleName());
            }
        }

        // Refuse to narrow a fraction into an integral type. Jackson would truncate 2.5 to 2 silently, turning what is
        // a hard failure today into corrupted data on replay.
        if (token == JsonToken.VALUE_NUMBER_FLOAT && INTEGRAL_NUMBER_CLASSES.contains(numberClass)) {
            ctxt.reportInputMismatch(concreteType,
                                     "Cannot deserialize the floating point value %s into %s, which wraps a %s",
                                     p.getText(),
                                     concreteType.getName(),
                                     numberClass.getSimpleName());
        }

        return (T) SingleValueType.from(readNumber(p), (Class) concreteType);
    }

    /**
     * Reads the token at the width the concrete type actually wraps, so no precision is lost on the way in.
     */
    private Number readNumber(JsonParser p) throws IOException {
        if (numberClass == BigDecimal.class) return p.getDecimalValue();
        if (numberClass == BigInteger.class) return p.getBigIntegerValue();
        if (numberClass == Long.class) return p.getLongValue();
        if (numberClass == Integer.class) return p.getIntValue();
        if (numberClass == Short.class) return p.getShortValue();
        if (numberClass == Byte.class) return p.getByteValue();
        if (numberClass == Double.class) return p.getDoubleValue();
        if (numberClass == Float.class) return p.getFloatValue();
        throw new IllegalStateException("Unsupported Number type " + numberClass.getName());
    }

    /**
     * The {@code …ValueExact} calls are what stop a quoted {@code "2.5"} from becoming {@code 2} for an integral type -
     * they throw {@link ArithmeticException} rather than truncate.
     */
    private Number parseText(String text) {
        if (numberClass == BigDecimal.class) return new BigDecimal(text);
        if (numberClass == BigInteger.class) return new BigDecimal(text).toBigIntegerExact();
        if (numberClass == Long.class) return new BigDecimal(text).longValueExact();
        if (numberClass == Integer.class) return new BigDecimal(text).intValueExact();
        if (numberClass == Short.class) return new BigDecimal(text).shortValueExact();
        if (numberClass == Byte.class) return new BigDecimal(text).byteValueExact();
        if (numberClass == Double.class) return Double.valueOf(text);
        if (numberClass == Float.class) return Float.valueOf(text);
        throw new IllegalStateException("Unsupported Number type " + numberClass.getName());
    }
}
