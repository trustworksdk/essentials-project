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

package dk.trustworks.essentials.components.eventsourced.aggregates.modern.bitemporal;

import dk.trustworks.essentials.components.eventsourced.aggregates.AggregateException;
import dk.trustworks.essentials.types.CurrencyCode;
import dk.trustworks.essentials.types.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ProductPriceTest {
   private static final Money PRICE_100 = Money.of("100", CurrencyCode.DKK);
   private static final Money PRICE_110 = Money.of("110", CurrencyCode.DKK);
   private static final Money PRICE_120 = Money.of("120", CurrencyCode.DKK);
   private static final Money PRICE_130 = Money.of("130", CurrencyCode.DKK);
   private static final Money PRICE_140 = Money.of("140", CurrencyCode.DKK);
   private static final Money PRICE_150 = Money.of("150", CurrencyCode.DKK);
   private static final Instant VALID_FROM_0 = Instant.ofEpochMilli(0);
   private static final Instant VALID_FROM_10 = Instant.ofEpochMilli(10);
   private static final Instant VALID_FROM_20 = Instant.ofEpochMilli(20);
   private static final Instant VALID_FROM_30 = Instant.ofEpochMilli(30);
   private static final Instant VALID_FROM_40 = Instant.ofEpochMilli(40);
   private static final Instant VALID_FROM_50 = Instant.ofEpochMilli(50);


    @Test
    void shouldFailWhenAdjustingNonHydratedProductPrice() {
        // Given a non-hydrated ProductPrice without any price
        var productId = ProductId.random();
        var aggregate = new ProductPrice(productId);
        // When adjusting the price THEN fail with an AggregateIsNotInitialized (hydrated) exception
        assertThatThrownBy(()-> aggregate.adjustPrice(PRICE_100, VALID_FROM_0)).isInstanceOf(AggregateException.class);
    }

    @Test
    void aggregateEmitsMultipleEventsWhenAnIntermediatePriceIsChanged() {
        // Given a ProductPrice with a price of 120 at time 20
        var productId = ProductId.random();
        var aggregate = new ProductPrice(productId, PRICE_120, VALID_FROM_20);
        // And a price of 140 after time 40
        aggregate.adjustPrice(PRICE_140, VALID_FROM_40);
        aggregate.markChangesAsCommitted();

        // When adjusting the price in between time 20 and 40
        aggregate.adjustPrice(PRICE_130, VALID_FROM_30);

        // Then multiple events are emitted
        var uncommittedChanges = aggregate.getUncommittedChanges();
        assertThat(uncommittedChanges.events).hasSizeGreaterThan(1);

        // Then price at time T changes accordingly
        assertThat(aggregate.getPriceAt(VALID_FROM_20)).isEqualTo(Optional.of(PRICE_120));
        assertThat(aggregate.getPriceAt(VALID_FROM_30)).isEqualTo(Optional.of(PRICE_130));
        assertThat(aggregate.getPriceAt(VALID_FROM_40)).isEqualTo(Optional.of(PRICE_140));
        assertThat(aggregate.getPriceAt(VALID_FROM_50)).isEqualTo(Optional.of(PRICE_140));
    }

    @Test
    void shouldSupportThatPriceIsChangedMoreThanOnce() {
        // Given a ProductPrice with a price of 120 at time 20
        var productId = ProductId.random();
        var aggregate = new ProductPrice(productId, PRICE_120, VALID_FROM_20);
        aggregate.adjustPrice(PRICE_130, VALID_FROM_30);
        // When adjusting the price once more
        aggregate.adjustPrice(PRICE_150, VALID_FROM_50);
        // Then price at time T changes accordingly
        assertThat(aggregate.getPriceAt(VALID_FROM_20)).isEqualTo(Optional.of(PRICE_120));
        assertThat(aggregate.getPriceAt(VALID_FROM_30)).isEqualTo(Optional.of(PRICE_130));
        assertThat(aggregate.getPriceAt(VALID_FROM_50)).isEqualTo(Optional.of(PRICE_150));
    }

    @Test
    void shouldSupportThatPriceIsChangedMoreThanOnceBetweenInitialAndLastPrice() {
        // Given a ProductPrice with a price of 120 at time 20
        var productId = ProductId.random();
        var aggregate = new ProductPrice(productId, PRICE_120, VALID_FROM_20);
        // When adjusting the price multiple times
        aggregate.adjustPrice(PRICE_100, VALID_FROM_0);
        aggregate.adjustPrice(PRICE_130, VALID_FROM_30);
        aggregate.adjustPrice(PRICE_150, VALID_FROM_50);
        aggregate.adjustPrice(PRICE_140, VALID_FROM_40);
        // Then price at time T changes accordingly
        assertThat(aggregate.getPriceAt(VALID_FROM_0)).isEqualTo(Optional.of(PRICE_100));
        assertThat(aggregate.getPriceAt(VALID_FROM_20)).isEqualTo(Optional.of(PRICE_120));
        assertThat(aggregate.getPriceAt(VALID_FROM_30)).isEqualTo(Optional.of(PRICE_130));
        assertThat(aggregate.getPriceAt(VALID_FROM_40)).isEqualTo(Optional.of(PRICE_140));
        assertThat(aggregate.getPriceAt(VALID_FROM_50)).isEqualTo(Optional.of(PRICE_150));
    }

}
