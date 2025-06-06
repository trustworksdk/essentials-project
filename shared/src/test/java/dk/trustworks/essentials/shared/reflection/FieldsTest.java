/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.shared.reflection;

import dk.trustworks.essentials.shared.reflection.test_subjects.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class FieldsTest {
    @Test
    void GenericClass_fields() {
        // When
        var fields = Fields.fields(GenericClass.class);

        // Then
        assertThat(
                Fields.findField(fields,
                                 "genericClassField",
                                 Long.class
                                )).isPresent();


        assertThat(fields.size()).isEqualTo(1);
        assertThat(fields.stream().filter(Accessibles::isAccessible).count()).isEqualTo(1);
    }

    @Test
    void ConcreteClass_fields() {
        // When
        var fields = Fields.fields(ConcreteClass.class);
        // Then
        assertThat(
                Fields.findField(fields,
                                 "genericClassField",
                                 Long.class
                                )).isPresent();

        assertThat(
                Fields.findField(fields,
                                 "concreteClassField",
                                 String.class
                                )).isPresent();


        assertThat(fields.size()).isEqualTo(2);
        assertThat(fields.stream().filter(Accessibles::isAccessible).count()).isEqualTo(2);
    }

    @Test
    void BaseTestReflectionClass_fields() {
        // When
        var fields = Fields.fields(BaseTestReflectionClass.class);

        // Then
        assertThat(
                Fields.findField(fields,
                                 "baseField",
                                 String.class
                                )).isPresent();

        assertThat(fields.size()).isEqualTo(1);
        assertThat(fields.stream().filter(Accessibles::isAccessible).count()).isEqualTo(1);
    }

    @Test
    void TestReflectionClass_fields() {
        // When
        var fields = Fields.fields(TestReflectionClass.class);

        // Then
        // We only see the most specific field "baseField" from the TestReflectionClass
        // and NOT the baseField from BaseTestReflectionClass
        assertThat(
                Fields.findField(fields,
                                 "baseField",
                                 Double.class
                                )).isPresent();

        assertThat(
                Fields.findField(fields,
                                 "additionalField",
                                 String.class
                                )).isPresent();

        assertThat(fields.size()).isEqualTo(2);
        assertThat(fields.stream().filter(Accessibles::isAccessible).count()).isEqualTo(2);
    }
}
