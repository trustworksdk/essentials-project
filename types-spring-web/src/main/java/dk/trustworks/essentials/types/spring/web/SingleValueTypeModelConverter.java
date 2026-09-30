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

package dk.trustworks.essentials.types.spring.web;

import com.fasterxml.jackson.core.type.ResolvedType;
import dk.trustworks.essentials.types.*;
import io.swagger.v3.core.converter.*;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.media.Schema;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.time.LocalTime;
import java.util.*;

/**
 * springdoc / swagger-core {@link ModelConverter} that describes Essentials semantic types in the generated OpenAPI
 * document the way they are actually written as JSON, instead of as the Java objects they are.
 * <p>
 * Without it springdoc introspects a {@link CharSequenceType} as a bean and publishes it as an object with
 * {@code bytes}, {@code empty} and {@code value} properties, and every client generated from the document types each id
 * wrongly. With it each type collapses to the schema springdoc gives the value it wraps, so an {@code OrderId extends
 * CharSequenceType<OrderId>} becomes {@code {"type": "string"}} and a {@code Quantity extends LongType<Quantity>} becomes
 * {@code {"type": "integer", "format": "int64"}}, inline, wherever it is used: a property, a list or map element, a
 * request parameter or a response body.
 * <p>
 * Which types collapse follows what {@code EssentialTypesJacksonModule} ({@code types-jackson3}) writes as a bare JSON
 * scalar, so the document and the wire agree:
 * <ul>
 *     <li>{@link CharSequenceType} &rarr; {@code string}</li>
 *     <li>{@link NumberType}: {@link LongType} &rarr; {@code integer}/{@code int64}; {@link IntegerType}, {@link ShortType}
 *     and {@link ByteType} &rarr; {@code integer}/{@code int32} (the JSON is a number, so not swagger's {@code byte}
 *     string); {@link BigIntegerType} &rarr; {@code integer}; {@link BigDecimalType} &rarr; {@code number};
 *     {@link DoubleType} / {@link FloatType} &rarr; {@code number}/{@code double} / {@code float}</li>
 *     <li>{@link JSR310SingleValueType} &rarr; what springdoc publishes for the {@code java.time} value itself:
 *     {@code string}/{@code date-time} for {@link InstantType}, {@link LocalDateTimeType}, {@link OffsetDateTimeType} and
 *     {@link ZonedDateTimeType}, {@code string}/{@code date} for {@link LocalDateType}. {@link LocalTimeType} &rarr;
 *     {@code string}/{@code partial-time}: swagger-core would describe the {@code LocalTime} as an object with
 *     {@code hour}, {@code minute}, ... properties, where Jackson 3 writes {@code "10:15:30"}</li>
 *     <li>A Kotlin {@code @JvmInline value class}, Essentials interface or not &rarr; the schema of the value it wraps,
 *     which is what {@code jackson-module-kotlin} writes</li>
 * </ul>
 * Any other {@link SingleValueType} (a {@link BooleanType}, say) is left alone, because the Jackson module has no
 * scalar serializer for it and it really is written as {@code {"value": ...}}. {@link Money} stays a component, trimmed to
 * the {@code amount} and {@code currency} it is written with: swagger-core would also publish a {@code scale} property,
 * because it reads {@code setScale(int)} as a setter.
 * <p>
 * <b>Kotlin property names.</b> Kotlin mangles the JVM getter of a value-class property ({@code getId-nb-kci0}), and
 * swagger-core's Jackson 2 introspection names the property after that getter unless Jackson 2's own Kotlin module is
 * on the classpath. A Boot 4 application has only the Jackson 3 one, so springdoc publishes {@code "id-nb-kci0"} where
 * the wire carries {@code "id"}. This converter renames such properties (and their {@code required} entries) back to the
 * Kotlin property name. It needs no {@code kotlin-reflect}.
 * <p>
 * <b>Registration.</b> springdoc adds every {@link ModelConverter} bean to its model resolution, so register it as a
 * bean in the application that runs springdoc:
 * <pre>{@code
 * @Bean
 * SingleValueTypeModelConverter singleValueTypeModelConverter() {
 *     return new SingleValueTypeModelConverter();
 * }
 * }</pre>
 * Kotlin: {@code @Bean fun singleValueTypeModelConverter() = SingleValueTypeModelConverter()}. Without Spring, add it to
 * swagger-core directly: {@code ModelConverters.getInstance(openapi31).addConverter(new SingleValueTypeModelConverter())}.
 * Nothing registers it automatically. springdoc is a {@code provided} dependency of this module: the application
 * supplies it.
 */
public final class SingleValueTypeModelConverter implements ModelConverter {
    private static final String      KOTLIN_JVM_INLINE   = "kotlin.jvm.JvmInline";
    /**
     * swagger-core's name for an RFC 3339 <code>partial-time</code> (<code>10:15:30</code>), which is how Jackson 3 writes a {@link LocalTime}
     */
    private static final String      PARTIAL_TIME_FORMAT = "partial-time";
    private static final Set<String> MONEY_PROPERTIES    = Set.of("amount", "currency");
    /**
     * Guards the unwrapping loop against a value type that (directly or indirectly) wraps itself
     */
    private static final int MAX_UNWRAP_DEPTH = 8;

    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        if (type == null || !chain.hasNext()) {
            return null;
        }
        var rawClass = rawClassOf(type.getType());
        var wireType = rawClass != null ? wireValueTypeOf(rawClass) : null;
        if (wireType != null) {
            // Same hand-over springdoc's own Kotlin inline-class converter does: the value type, with the context
            // annotations kept so a @Schema(description = ...) on the property still applies, resolved inline.
            var isLocalTime = wireType == LocalTime.class;
            var valueType = new AnnotatedType()
                    .type(isLocalTime ? String.class : wireType)
                    .ctxAnnotations(type.getCtxAnnotations())
                    .jsonViewAnnotation(type.getJsonViewAnnotation())
                    .resolveAsRef(false);
            var schema = chain.next().resolve(valueType, context, chain);
            if (isLocalTime && schema != null) {
                schema.setFormat(PARTIAL_TIME_FORMAT);
            }
            return schema;
        }

        var schema = chain.next().resolve(type, context, chain);
        if (rawClass != null && schema != null) {
            if (rawClass == Money.class) {
                retainMoneyProperties(schema, context);
            } else {
                restoreKotlinPropertyNames(rawClass, schema, context);
            }
        }
        return schema;
    }

    private static void retainMoneyProperties(Schema<?> schema, ModelConverterContext context) {
        var model = modelOf(schema, context);
        if (model != null && model.getProperties() != null) {
            model.getProperties().keySet().retainAll(MONEY_PROPERTIES);
        }
    }

    /**
     * The component a <code>$ref</code> schema points at, or <code>schema</code> itself when it is inline
     */
    private static Schema<?> modelOf(Schema<?> schema, ModelConverterContext context) {
        var ref = schema.get$ref();
        return ref == null ? schema : context.getDefinedModels().get(ref.substring(ref.lastIndexOf('/') + 1));
    }

    /**
     * The type whose schema describes how <code>type</code> is written as JSON, or <code>null</code> when
     * <code>type</code> is not a semantic type that is written as a bare scalar
     */
    private static Type wireValueTypeOf(Class<?> type) {
        Type current = null;
        var currentClass = type;
        for (var depth = 0; depth < MAX_UNWRAP_DEPTH; depth++) {
            var unwrapped = scalarValueTypeOf(currentClass);
            if (unwrapped == null) {
                return current;
            }
            current = unwrapped;
            currentClass = rawClassOf(unwrapped);
            if (currentClass == null) {
                return current;
            }
        }
        return current;
    }

    private static Type scalarValueTypeOf(Class<?> type) {
        if (CharSequenceType.class.isAssignableFrom(type)) {
            return String.class;
        }
        if (NumberType.class.isAssignableFrom(type)) {
            return numberValueTypeOf(type);
        }
        if (JSR310SingleValueType.class.isAssignableFrom(type)) {
            // EssentialTypesJacksonModule writes these through a @JsonValue mix-in on value(). getMethod picks the
            // override with the most specific return type, i.e. the java.time type rather than the erased Object.
            try {
                var valueType = type.getMethod("value").getReturnType();
                return valueType == Object.class ? null : valueType;
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
        if (isKotlinValueClass(type)) {
            var backingFields = Arrays.stream(type.getDeclaredFields())
                                      .filter(field -> !Modifier.isStatic(field.getModifiers()))
                                      .toList();
            return backingFields.size() == 1 ? backingFields.getFirst().getGenericType() : null;
        }
        return null;
    }

    private static Type numberValueTypeOf(Class<?> type) {
        Class<? extends Number> numberClass;
        try {
            numberClass = NumberType.resolveNumberClass(type);
        } catch (IllegalArgumentException e) {
            // A NumberType outside the built-in hierarchy: still written as a JSON number
            return Number.class;
        }
        // swagger-core maps Byte to a base64 "byte" string; the JSON Essentials writes is a number
        if (numberClass == Byte.class || numberClass == Short.class) {
            return Integer.class;
        }
        return numberClass;
    }

    private static boolean isKotlinValueClass(Class<?> type) {
        // By name, so a Java-only application needs no Kotlin on its classpath
        for (Annotation annotation : type.getDeclaredAnnotations()) {
            if (annotation.annotationType().getName().equals(KOTLIN_JVM_INLINE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * swagger-core hands over a {@link Class}, a {@link ParameterizedType} or a Jackson 2 {@code JavaType}; the latter
     * is a {@link ResolvedType}, which is all this needs of Jackson 2
     */
    private static Class<?> rawClassOf(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ResolvedType resolvedType) {
            return resolvedType.getRawClass();
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Kotlin mangled property names
    // ------------------------------------------------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void restoreKotlinPropertyNames(Class<?> type, Schema<?> schema, ModelConverterContext context) {
        var model = modelOf(schema, context);
        if (model == null || model.getProperties() == null) {
            return;
        }
        Map<String, Schema> properties = (Map) model.getProperties();
        // A '-' cannot occur in a Java or (unquoted) Kotlin identifier, only in a name the Kotlin compiler mangled
        if (properties.keySet().stream().noneMatch(name -> name.indexOf('-') > 0)) {
            return;
        }

        var renamed = new LinkedHashMap<String, Schema>();
        properties.forEach((name, propertySchema) -> {
            var kotlinName = name.indexOf('-') > 0 ? kotlinPropertyNameFor(type, name) : null;
            if (kotlinName == null) {
                renamed.putIfAbsent(name, propertySchema);
            } else if (!properties.containsKey(kotlinName)) {
                renamed.putIfAbsent(kotlinName, propertySchema);
                if (model.getRequired() != null) {
                    model.getRequired().replaceAll(required -> required.equals(name) ? kotlinName : required);
                }
            }
            // else: the real name is already published, the mangled one is a duplicate of it
        });
        model.setProperties(renamed);
    }

    /**
     * Maps a property name swagger-core derived from a mangled getter (<code>id-nb-kci0</code> from
     * <code>getId-nb-kci0()</code>) back to the Kotlin property (<code>id</code>), or <code>null</code> if no getter matches
     */
    private static String kotlinPropertyNameFor(Class<?> type, String derivedName) {
        var dash        = derivedName.indexOf('-');
        var derivedBase = derivedName.substring(0, dash);
        var hashSuffix  = derivedName.substring(dash);
        for (Method method : type.getMethods()) {
            var methodName = method.getName();
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers()) || !methodName.endsWith(hashSuffix)) {
                continue;
            }
            var jvmName = methodName.substring(0, methodName.length() - hashSuffix.length());
            if (jvmName.startsWith("get") && jvmName.length() > 3 && jvmName.substring(3).equalsIgnoreCase(derivedBase)) {
                return kotlinNameOfGetter(type, jvmName);
            }
            if (jvmName.startsWith("is") && jvmName.length() > 2 && jvmName.substring(2).equalsIgnoreCase(derivedBase)) {
                // Kotlin names the getter of an `isXxx` property after the property itself
                return jvmName;
            }
        }
        return null;
    }

    private static String kotlinNameOfGetter(Class<?> type, String getterName) {
        // The backing field carries the exact Kotlin property name (`URL` stays `URL`)
        for (var current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                var name = field.getName();
                if (!name.isEmpty() && getterName.equals("get" + Character.toUpperCase(name.charAt(0)) + name.substring(1))) {
                    return name;
                }
            }
        }
        // No backing field (a computed property): Kotlin's own rule, first character lower-cased
        var base = getterName.substring(3);
        return Character.toLowerCase(base.charAt(0)) + base.substring(1);
    }
}
