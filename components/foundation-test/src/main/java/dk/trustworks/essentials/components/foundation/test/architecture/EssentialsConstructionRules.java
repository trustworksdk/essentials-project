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

package dk.trustworks.essentials.components.foundation.test.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.*;
import com.tngtech.archunit.lang.*;

import java.util.*;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors;

/**
 * The construction-ergonomics guard for the Essentials reactor, defined once and consumed by a thin
 * {@code *ConstructionErgonomicsTest} in each module that can see the classes it needs to check.
 * <p>
 * It encodes the policy documented in {@code docs/constructor-ergonomics-and-optional-policy.md} and in
 * {@code .claude/rules/code-style.md}:
 * <ol>
 *     <li>A public or protected constructor declares no {@link Optional} parameter — absence is a neutral default,
 *         a sealed variant, or a builder-resolved nullable.</li>
 *     <li>A public or protected constructor declares at most {@value #MAX_CONSTRUCTOR_PARAMETERS} parameters — above
 *         that, the arguments belong in a {@code XxxDependencies} bundle, a {@code XxxSettings} record, or a builder.</li>
 *     <li>A constructor that breaks either of the above is <em>not</em> deleted. It is kept, marked
 *         {@code @Deprecated(forRemoval = true)} and re-implemented to delegate to the replacement — so the rules
 *         above are phrased as "…&nbsp;or is deprecated for removal".</li>
 *     <li>A constructor deprecated for removal must leave a way in: the declaring class needs a builder or a
 *         compliant non-deprecated constructor. A {@code forRemoval} deprecation with no replacement is a dead end,
 *         not a migration.</li>
 * </ol>
 * <h2>Why the rules are phrased around {@code @Deprecated(forRemoval = true)}</h2>
 * This lets one rule serve the whole migration. During the bridge release it holds because every offender is
 * deprecated; once the deprecated constructors are removed at the next major it becomes absolute, with no edit to
 * the rule. See the "Release mechanics" section of the design document.
 * <h2>Freezing</h2>
 * The reactor does not satisfy these rules yet. Consuming tests therefore wrap them in
 * {@code FreezingArchRule.freeze(...)}, which accepts today's violations from a checked-in violation store and fails
 * only on <em>new</em> ones. Violations that get fixed are dropped from the store automatically, so the store's size
 * is the progress metric for the sweep.
 *
 * @see #constructorsMustNotDeclareOptionalParametersUnlessDeprecatedForRemoval()
 * @see #constructorsMustStayWithinTheParameterCeilingUnlessDeprecatedForRemoval()
 * @see #constructorsDeprecatedForRemovalMustOfferAReplacement()
 */
public final class EssentialsConstructionRules {

    /**
     * The maximum number of parameters a non-deprecated public or protected constructor may declare.
     */
    public static final int MAX_CONSTRUCTOR_PARAMETERS = 5;

    private EssentialsConstructionRules() {
    }

    // ------------------------------------------------------------------------------------------------------------
    // Predicates
    // ------------------------------------------------------------------------------------------------------------

    private static final DescribedPredicate<JavaConstructor> PUBLIC_OR_PROTECTED =
            new DescribedPredicate<>("declared public or protected") {
                @Override
                public boolean test(JavaConstructor constructor) {
                    return constructor.getModifiers().contains(JavaModifier.PUBLIC)
                            || constructor.getModifiers().contains(JavaModifier.PROTECTED);
                }
            };

    /**
     * A {@code record}'s canonical constructor is exempt from <em>both</em> rules.
     * <p>
     * The parameter ceiling, because a record used as a cohesive parameter object is <em>supposed</em> to be wide —
     * capping it would defeat the very refactoring the ceiling exists to encourage.
     * <p>
     * The {@link Optional} rule, because a record component's declared type <em>is</em> its accessor's return type,
     * and the policy explicitly permits {@code Optional} as a return type. Flagging
     * {@code record Descriptor(Optional<EventOrder> latestSnapshot)} would demand that the component become nullable,
     * which would change {@code latestSnapshot()} from returning {@code Optional} to returning {@code null} — the
     * opposite of what the policy asks for everywhere else. The canonical constructor has no freedom here: it takes
     * exactly the components, in order.
     * <p>
     * A record's <em>non</em>-canonical constructors are not exempt — those are ordinary overloads and get no special
     * treatment. Canonicality is detected structurally, by parameter count matching the record's component count,
     * because ArchUnit's {@code JavaClass} does not expose record components directly.
     */
    private static final DescribedPredicate<JavaConstructor> IS_A_RECORDS_CANONICAL_CONSTRUCTOR =
            new DescribedPredicate<>("a record's canonical constructor") {
                @Override
                public boolean test(JavaConstructor constructor) {
                    var owner = constructor.getOwner();
                    if (!owner.isRecord()) {
                        return false;
                    }
                    var componentCount = owner.getFields().stream()
                                              .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC))
                                              .count();
                    return constructor.getRawParameterTypes().size() == componentCount;
                }
            };

    private static final DescribedPredicate<JavaConstructor> DEPRECATED_FOR_REMOVAL =
            new DescribedPredicate<>("deprecated for removal") {
                @Override
                public boolean test(JavaConstructor constructor) {
                    return isDeprecatedForRemoval(constructor);
                }
            };

    // ------------------------------------------------------------------------------------------------------------
    // Conditions
    // ------------------------------------------------------------------------------------------------------------

    private static final ArchCondition<JavaConstructor> NOT_DECLARE_AN_OPTIONAL_PARAMETER_OR_BE_DEPRECATED_FOR_REMOVAL =
            new ArchCondition<>("not declare an Optional parameter, or be annotated @Deprecated(forRemoval = true)") {
                @Override
                public void check(JavaConstructor constructor, ConditionEvents events) {
                    if (isDeprecatedForRemoval(constructor)) {
                        return;
                    }
                    var optionalParameterPositions = optionalParameterPositions(constructor);
                    if (!optionalParameterPositions.isEmpty()) {
                        events.add(SimpleConditionEvent.violated(
                                constructor,
                                String.format("Constructor %s declares Optional parameter(s) at position(s) %s. "
                                                      + "Express absence as a neutral default, a sealed variant, or a builder-resolved nullable — "
                                                      + "or keep this constructor and mark it @Deprecated(forRemoval = true) with a delegating body. "
                                                      + "%s",
                                              constructor.getFullName(),
                                              optionalParameterPositions,
                                              constructor.getSourceCodeLocation())));
                    }
                }
            };

    private static final ArchCondition<JavaConstructor> STAY_WITHIN_THE_PARAMETER_CEILING_OR_BE_DEPRECATED_FOR_REMOVAL =
            new ArchCondition<>(String.format("declare at most %d parameters, or be annotated @Deprecated(forRemoval = true)",
                                              MAX_CONSTRUCTOR_PARAMETERS)) {
                @Override
                public void check(JavaConstructor constructor, ConditionEvents events) {
                    if (isDeprecatedForRemoval(constructor)) {
                        return;
                    }
                    var parameterCount = constructor.getRawParameterTypes().size();
                    if (parameterCount > MAX_CONSTRUCTOR_PARAMETERS) {
                        events.add(SimpleConditionEvent.violated(
                                constructor,
                                String.format("Constructor %s declares %d parameters (ceiling is %d). "
                                                      + "Introduce a XxxDependencies bundle, a XxxSettings record, or a builder for the class — "
                                                      + "or keep this constructor and mark it @Deprecated(forRemoval = true) with a delegating body. "
                                                      + "%s",
                                              constructor.getFullName(),
                                              parameterCount,
                                              MAX_CONSTRUCTOR_PARAMETERS,
                                              constructor.getSourceCodeLocation())));
                    }
                }
            };

    private static final ArchCondition<JavaClass> OFFER_A_NON_DEPRECATED_WAY_TO_CONSTRUCT =
            new ArchCondition<>("offer a non-deprecated way to construct — a builder() factory or a compliant constructor") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    var deprecatedForRemoval = javaClass.getConstructors().stream()
                                                        .filter(EssentialsConstructionRules::isDeprecatedForRemoval)
                                                        .toList();
                    if (deprecatedForRemoval.isEmpty()) {
                        return;
                    }
                    if (hasBuilderFactory(javaClass) || hasCompliantNonDeprecatedConstructor(javaClass)) {
                        return;
                    }
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            String.format("Class %s deprecates %d constructor(s) for removal but offers no replacement: "
                                                  + "no static builder() factory, and no non-deprecated public/protected constructor within the "
                                                  + "%d-parameter ceiling and free of Optional parameters. A forRemoval deprecation must name a way out. "
                                                  + "%s",
                                          javaClass.getName(),
                                          deprecatedForRemoval.size(),
                                          MAX_CONSTRUCTOR_PARAMETERS,
                                          javaClass.getSourceCodeLocation())));
                }
            };

    // ------------------------------------------------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------------------------------------------------

    /**
     * No public or protected constructor declares an {@link Optional} parameter, unless it is kept only as a
     * deprecated bridge.
     *
     * @return the rule
     */
    public static ArchRule constructorsMustNotDeclareOptionalParametersUnlessDeprecatedForRemoval() {
        return constructors().that(PUBLIC_OR_PROTECTED.and(DescribedPredicate.not(IS_A_RECORDS_CANONICAL_CONSTRUCTOR)))
                             .should(NOT_DECLARE_AN_OPTIONAL_PARAMETER_OR_BE_DEPRECATED_FOR_REMOVAL)
                             .because("Optional belongs in return types, not in construction — see docs/constructor-ergonomics-and-optional-policy.md")
                             .allowEmptyShould(true);
    }

    /**
     * No public or protected constructor declares more than {@value #MAX_CONSTRUCTOR_PARAMETERS} parameters, unless
     * it is a record's canonical constructor or is kept only as a deprecated bridge.
     *
     * @return the rule
     */
    public static ArchRule constructorsMustStayWithinTheParameterCeilingUnlessDeprecatedForRemoval() {
        return constructors().that(PUBLIC_OR_PROTECTED.and(DescribedPredicate.not(IS_A_RECORDS_CANONICAL_CONSTRUCTOR)))
                             .should(STAY_WITHIN_THE_PARAMETER_CEILING_OR_BE_DEPRECATED_FOR_REMOVAL)
                             .because("above five parameters the arguments are a parameter object or a builder, not a signature — "
                                              + "see docs/constructor-ergonomics-and-optional-policy.md")
                             .allowEmptyShould(true);
    }

    /**
     * Every class that deprecates a constructor for removal offers a non-deprecated way to construct it. This is the
     * half of the policy that keeps the sweep a migration rather than a cull: deprecating without a replacement
     * strands the caller.
     *
     * @return the rule
     */
    public static ArchRule constructorsDeprecatedForRemovalMustOfferAReplacement() {
        return classes().that(new DescribedPredicate<JavaClass>("declare a constructor deprecated for removal") {
                            @Override
                            public boolean test(JavaClass javaClass) {
                                return javaClass.getConstructors().stream().anyMatch(DEPRECATED_FOR_REMOVAL);
                            }
                        })
                        .should(OFFER_A_NON_DEPRECATED_WAY_TO_CONSTRUCT)
                        .because("a @Deprecated(forRemoval = true) constructor must name a way out, or consumers cannot migrate")
                        .allowEmptyShould(true);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------

    private static boolean isDeprecatedForRemoval(JavaConstructor constructor) {
        return constructor.tryGetAnnotationOfType(Deprecated.class)
                          .map(Deprecated::forRemoval)
                          .orElse(false);
    }

    private static List<Integer> optionalParameterPositions(JavaConstructor constructor) {
        var parameterTypes = constructor.getRawParameterTypes();
        var positions      = new ArrayList<Integer>();
        for (var i = 0; i < parameterTypes.size(); i++) {
            if (parameterTypes.get(i).isEquivalentTo(Optional.class)) {
                positions.add(i);
            }
        }
        return positions;
    }

    /**
     * A builder is recognised structurally: a static, non-deprecated {@code builder(...)} method.
     * <p>
     * The <em>enclosing</em> class counts too, walking outwards. A nested implementation class is routinely
     * constructed through a factory on the type it implements — {@code StatefulAggregateRepository.builder(eventStore)}
     * returns a {@code StatefulAggregateRepository$DefaultStatefulAggregateRepository} — and that is a perfectly good
     * way out for a caller, even though the method is not declared on the nested class itself.
     * <p>
     * The javadoc {@code @deprecated} tag naming the replacement is a review checklist item — bytecode cannot carry it.
     */
    private static boolean hasBuilderFactory(JavaClass javaClass) {
        var candidate = Optional.of(javaClass);
        while (candidate.isPresent()) {
            var current = candidate.get();
            var declaresBuilder = current.getMethods().stream()
                                         .anyMatch(method -> method.getName().equals("builder")
                                                 && method.getModifiers().contains(JavaModifier.STATIC)
                                                 && !method.isAnnotatedWith(Deprecated.class));
            if (declaresBuilder) {
                return true;
            }
            candidate = current.getEnclosingClass();
        }
        return false;
    }

    private static boolean hasCompliantNonDeprecatedConstructor(JavaClass javaClass) {
        return javaClass.getConstructors().stream()
                        .filter(PUBLIC_OR_PROTECTED)
                        .filter(constructor -> !isDeprecatedForRemoval(constructor))
                        .anyMatch(constructor -> constructor.getRawParameterTypes().size() <= MAX_CONSTRUCTOR_PARAMETERS
                                && optionalParameterPositions(constructor).isEmpty());
    }
}
