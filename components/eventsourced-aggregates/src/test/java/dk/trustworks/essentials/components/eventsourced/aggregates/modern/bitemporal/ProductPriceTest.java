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
    private static final Money   PRICE_100     = Money.of("100", CurrencyCode.DKK);
    private static final Money   PRICE_120     = Money.of("120", CurrencyCode.DKK);
    private static final Money   PRICE_130     = Money.of("130", CurrencyCode.DKK);
    private static final Money   PRICE_140     = Money.of("140", CurrencyCode.DKK);
    private static final Money   PRICE_150     = Money.of("150", CurrencyCode.DKK);
    private static final Instant VALID_FROM_0  = Instant.ofEpochMilli(0);
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
        assertThatThrownBy(() -> aggregate.adjustPrice(PRICE_100, VALID_FROM_0)).isInstanceOf(AggregateException.class);
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

    // --------------------------------------------------------------------------------------
    // Initial price and basic query tests
    // --------------------------------------------------------------------------------------

    @Test
    void shouldReturnInitialPriceWithoutAnyAdjustments() {
        // Given a ProductPrice with only an initial price
        var productId = ProductId.random();
        var aggregate = new ProductPrice(productId, PRICE_100, VALID_FROM_20);

        // Then price at and after the initial time returns the initial price
        assertThat(aggregate.getPriceAt(VALID_FROM_20)).isEqualTo(Optional.of(PRICE_100));
        assertThat(aggregate.getPriceAt(VALID_FROM_50)).isEqualTo(Optional.of(PRICE_100));
        // And price before the initial time returns empty
        assertThat(aggregate.getPriceAt(VALID_FROM_10)).isEmpty();
    }

    @Test
    void shouldReturnCurrentPriceWhenQueryingAtPRESENT() {
        // Given a ProductPrice with an initial price and a future adjustment
        var productId = ProductId.random();
        var aggregate = new ProductPrice(productId, PRICE_100, VALID_FROM_20);
        aggregate.adjustPrice(PRICE_120, VALID_FROM_40);

        // When querying at PRESENT (null)
        // Then the latest open-ended price is returned
        assertThat(aggregate.getPriceAt(ProductPrice.PRESENT)).isEqualTo(Optional.of(PRICE_120));
    }

    @Test
    void shouldReturnEmptyWhenQueryingBeforeAnyPriceExists() {
        // Given a ProductPrice with an initial price at time 20
        var productId = ProductId.random();
        var aggregate = new ProductPrice(productId, PRICE_100, VALID_FROM_20);

        // When querying before the initial price
        // Then empty is returned
        assertThat(aggregate.getPriceAt(VALID_FROM_0)).isEmpty();
        assertThat(aggregate.getPriceAt(VALID_FROM_10)).isEmpty();
        assertThat(aggregate.getPriceAt(Instant.ofEpochMilli(19))).isEmpty();
    }

    // --------------------------------------------------------------------------------------
    // TimeWindow boundary tests
    // --------------------------------------------------------------------------------------

    @Test
    void shouldReturnCorrectPriceAtExactBoundaries() {
        // Given a ProductPrice with prices at 20 and 40
        var productId = ProductId.random();
        var aggregate = new ProductPrice(productId, PRICE_100, VALID_FROM_20);
        aggregate.adjustPrice(PRICE_120, VALID_FROM_40);

        // TimeWindow for PRICE_100 is [20, 40[
        // TimeWindow for PRICE_120 is [40, ∞[

        // At exactly fromInclusive (20) -> returns PRICE_100
        assertThat(aggregate.getPriceAt(VALID_FROM_20)).isEqualTo(Optional.of(PRICE_100));

        // Just before toExclusive (39) -> still returns PRICE_100
        assertThat(aggregate.getPriceAt(Instant.ofEpochMilli(39))).isEqualTo(Optional.of(PRICE_100));

        // At exactly toExclusive/next fromInclusive (40) -> returns PRICE_120
        assertThat(aggregate.getPriceAt(VALID_FROM_40)).isEqualTo(Optional.of(PRICE_120));

        // Just before fromInclusive (19) -> returns empty
        assertThat(aggregate.getPriceAt(Instant.ofEpochMilli(19))).isEmpty();
    }

    // --------------------------------------------------------------------------------------
    // Specific branch coverage tests
    // --------------------------------------------------------------------------------------

    @Test
    void shouldSetPriceBeforeInitialPrice() {
        // This tests the existingPriceAt.isEmpty() branch explicitly
        // Given a ProductPrice with an initial price at time 20
        var productId = ProductId.random();
        var aggregate = new ProductPrice(productId, PRICE_120, VALID_FROM_20);

        // When setting a price BEFORE the initial price
        aggregate.adjustPrice(PRICE_100, VALID_FROM_0);

        // Then the new price is valid from time 0 to time 20
        assertThat(aggregate.getPriceAt(VALID_FROM_0)).isEqualTo(Optional.of(PRICE_100));
        assertThat(aggregate.getPriceAt(VALID_FROM_10)).isEqualTo(Optional.of(PRICE_100));
        assertThat(aggregate.getPriceAt(Instant.ofEpochMilli(19))).isEqualTo(Optional.of(PRICE_100));
        // And the initial price is still valid from time 20 onwards
        assertThat(aggregate.getPriceAt(VALID_FROM_20)).isEqualTo(Optional.of(PRICE_120));
        assertThat(aggregate.getPriceAt(VALID_FROM_50)).isEqualTo(Optional.of(PRICE_120));
    }

    @Test
    void shouldSetFuturePriceOnOpenEndedInitialPrice() {
        // This tests the else branch (nextPrice.isEmpty() OR nextPrice.equals(previousPrice))
        // Given a ProductPrice with only an open-ended initial price [20, ∞[
        var productId = ProductId.random();
        var aggregate = new ProductPrice(productId, PRICE_100, VALID_FROM_20);

        // When setting a future price
        aggregate.adjustPrice(PRICE_150, VALID_FROM_50);

        // Then the initial price is now closed at time 50
        assertThat(aggregate.getPriceAt(VALID_FROM_20)).isEqualTo(Optional.of(PRICE_100));
        assertThat(aggregate.getPriceAt(VALID_FROM_40)).isEqualTo(Optional.of(PRICE_100));
        assertThat(aggregate.getPriceAt(Instant.ofEpochMilli(49))).isEqualTo(Optional.of(PRICE_100));
        // And the new price is valid from time 50 to ∞
        assertThat(aggregate.getPriceAt(VALID_FROM_50)).isEqualTo(Optional.of(PRICE_150));
        assertThat(aggregate.getPriceAt(ProductPrice.PRESENT)).isEqualTo(Optional.of(PRICE_150));
    }

    // --------------------------------------------------------------------------------------
    // Event emission tests
    // --------------------------------------------------------------------------------------

    @Test
    void shouldEmitSingleEventForInitialPrice() {
        // Given/When creating a new ProductPrice
        var productId = ProductId.random();
        var aggregate = new ProductPrice(productId, PRICE_100, VALID_FROM_20);

        // Then exactly one event is emitted (InitialPriceSet)
        var uncommittedChanges = aggregate.getUncommittedChanges();
        assertThat(uncommittedChanges.events).hasSize(1);
        assertThat(uncommittedChanges.events.get(0)).isInstanceOf(InitialPriceSet.class);
    }

    @Test
    void shouldEmitTwoEventsWhenSettingFuturePrice() {
        // Given a ProductPrice with an initial price
        var productId = ProductId.random();
        var aggregate = new ProductPrice(productId, PRICE_100, VALID_FROM_20);
        aggregate.markChangesAsCommitted();

        // When setting a future price
        aggregate.adjustPrice(PRICE_120, VALID_FROM_40);

        // Then two events are emitted:
        // 1. PriceValidityAdjusted (to close the initial price)
        // 2. PriceAdjusted (for the new price)
        var uncommittedChanges = aggregate.getUncommittedChanges();
        assertThat(uncommittedChanges.events).hasSize(2);
        assertThat(uncommittedChanges.events.get(0)).isInstanceOf(PriceValidityAdjusted.class);
        assertThat(uncommittedChanges.events.get(1)).isInstanceOf(PriceAdjusted.class);
    }

    @Test
    void shouldEmitSingleEventWhenSettingPriceBeforeInitial() {
        // Given a ProductPrice with an initial price at time 20
        var productId = ProductId.random();
        var aggregate = new ProductPrice(productId, PRICE_120, VALID_FROM_20);
        aggregate.markChangesAsCommitted();

        // When setting a price BEFORE the initial price (existingPriceAt.isEmpty() branch)
        aggregate.adjustPrice(PRICE_100, VALID_FROM_0);

        // Then only one event is emitted (PriceAdjusted for the new earlier price)
        // No PriceValidityAdjusted is needed because no existing price covers time 0
        var uncommittedChanges = aggregate.getUncommittedChanges();
        assertThat(uncommittedChanges.events).hasSize(1);
        assertThat(uncommittedChanges.events.get(0)).isInstanceOf(PriceAdjusted.class);
    }

    // --------------------------------------------------------------------------------------
    // Rehydration test
    // --------------------------------------------------------------------------------------

    @Test
    void shouldPreserveStateAfterRehydration() {
        // Given a ProductPrice with multiple price adjustments
        var productId = ProductId.random();
        var original = new ProductPrice(productId, PRICE_100, VALID_FROM_10);
        original.adjustPrice(PRICE_120, VALID_FROM_30);
        original.adjustPrice(PRICE_140, VALID_FROM_50);

        // When rehydrating a new instance from the events
        var rehydrated = new ProductPrice(productId);
        rehydrated.rehydrate(original.getUncommittedChanges().events.stream());

        // Then the rehydrated aggregate has the same price timeline
        assertThat(rehydrated.getPriceAt(VALID_FROM_0)).isEmpty();
        assertThat(rehydrated.getPriceAt(VALID_FROM_10)).isEqualTo(Optional.of(PRICE_100));
        assertThat(rehydrated.getPriceAt(VALID_FROM_20)).isEqualTo(Optional.of(PRICE_100));
        assertThat(rehydrated.getPriceAt(VALID_FROM_30)).isEqualTo(Optional.of(PRICE_120));
        assertThat(rehydrated.getPriceAt(VALID_FROM_40)).isEqualTo(Optional.of(PRICE_120));
        assertThat(rehydrated.getPriceAt(VALID_FROM_50)).isEqualTo(Optional.of(PRICE_140));
        assertThat(rehydrated.getPriceAt(ProductPrice.PRESENT)).isEqualTo(Optional.of(PRICE_140));
    }

}
