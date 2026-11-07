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

package dk.trustworks.essentials.examples.webshop.config

import dk.trustworks.essentials.types.Amount
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.math.BigDecimal

/**
 * Stores an [Amount] as a SQL `numeric`, not as a floating-point double.
 *
 * **Why this exists instead of the framework's `AmountAttributeConverter`.** That one extends
 * `BaseBigDecimalTypeAttributeConverter`, which is declared `AttributeConverter<T, Double>`:
 *
 * ```
 * public Double convertToDatabaseColumn(T attribute) { return attribute.doubleValue(); }
 * public T convertToEntityAttribute(Double dbData)   { return SingleValueType.from(BigDecimal.valueOf(dbData), …); }
 * ```
 *
 * So every amount reaches PostgreSQL as `double precision` and comes back through
 * `BigDecimal.valueOf(double)`. Two consequences, and the second is the one that matters:
 *
 * - **Scale is not preserved.** `1999.50` comes back as `1999.5`, which is the same number but not an
 *   equal `BigDecimal` - and that is what made an integration test here fail on a total.
 * - **Arithmetic becomes floating point.** `select sum(price) from …` is then float summation, and a
 *   large enough figure loses significant digits. That is not acceptable for money, and a demo whose
 *   subject is getting this right should not model it that way.
 *
 * With a `numeric(19,2)` column (see the `@Column` on each field that uses this) an amount round-trips
 * exactly and the database sums it as a decimal.
 *
 * `autoApply` is deliberately **off**: `AmountAttributeConverter` in `types-springdata-jpa` is
 * `autoApply = true`, and two auto-applied converters for one type is ambiguous. Every field names this
 * one explicitly, which is also how a reader knows which of the two is in play.
 *
 * See `docs/bigdecimal-attribute-converter-improvements.md` for the fix proposed to the framework itself.
 */
@Converter
class MoneyAttributeConverter : AttributeConverter<Amount, BigDecimal> {

    override fun convertToDatabaseColumn(attribute: Amount?): BigDecimal? = attribute?.value()

    override fun convertToEntityAttribute(dbData: BigDecimal?): Amount? = dbData?.let { Amount.of(it) }
}
