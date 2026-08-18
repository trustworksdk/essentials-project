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

package dk.trustworks.essentials.kotlin.types

import dk.trustworks.essentials.shared.FailFast
import dk.trustworks.essentials.shared.MessageFormatter
import java.util.*

/**
 * Immutable ISO-3166 2 character country code. Any values provided to the constructor or [of]
 * will be validated for length and validated to be a known country code by performing a lookup
 * in the set returned from [Locale.getISOCountries] using [java.util.Locale.IsoCountryCode.PART1_ALPHA2].
 *
 * The lookup is case insensitive, but the [value] is retained exactly as provided - it is **not**
 * normalized to UPPER CASE. `CountryCode("dk")` and `CountryCode("DK")` are therefore both valid,
 * but they are not equal to each other. Pass an UPPER CASE value if you need the two to coincide.
 *
 * Note: this differs from the Java [dk.trustworks.essentials.types.CountryCode], which stores the
 * value UPPER CASE.
 */
@JvmInline
value class CountryCode
/**
 * Create a typed [CountryCode] from a String ISO-3166 2 character country code
 *
 * @param value the ISO-3166 2 character country code, retained as-is (see the class documentation
 *              on casing)
 * @throws IllegalArgumentException in case the ISO-3166 2 character country code is not known or otherwise invalid.
 */
constructor(override val value: String) : StringValueType<CountryCode> {

    init {
        validate(value)
    }

    companion object {
        /**
         * Get the Set of String based ISO-3166 2 character country code's
         * @return the Set of String based ISO-3166 2 character country code's
         */
        val allCountryCodes: Set<String> = Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2)

        private fun validate(countryCode: CharSequence): String {
            FailFast.requireNonNull(countryCode, "countryCode is null")
            require(countryCode.length == 2) {
                MessageFormatter.msg(
                    "CountryCode is invalid (must be 2 characters): '{}'",
                    countryCode
                )
            }

            val upperCaseCountryCode = countryCode.toString().uppercase(Locale.getDefault())
            require(allCountryCodes.contains(upperCaseCountryCode)) {
                MessageFormatter.msg(
                    "CountryCode '{}' is not known",
                    countryCode
                )
            }
            return upperCaseCountryCode
        }

        /**
         * Convert a String ISO-3166 2 character country code to a typed [CountryCode]
         *
         * @param countryCode the ISO-3166 2 character country code
         * @return the typed [CountryCode] with the `countryCode` retained as-is (see the class
         *         documentation on casing)
         * @throws IllegalArgumentException in case the ISO-3166 2 character country code is not known or otherwise invalid.
         */
        fun of(countryCode: String): CountryCode {
            return CountryCode(countryCode)
        }
    }
}