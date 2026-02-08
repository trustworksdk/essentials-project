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

import dk.trustworks.essentials.components.eventsourced.aggregates.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.shared.functional.tuple.*;
import dk.trustworks.essentials.types.*;

import java.time.Instant;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Simple bi-temporal product price aggregate example.<br>
 * A {@link ProductPrice} is always linked to a {@link ProductId} as its aggregate id.<br>
 * The {@link ProductPrice} aggregate tracks prices over time. Each price entry is associated with {@link PriceId}.<br>
 * The logic works as described below:
 * <ul>
 * <li>Calls to {@link ProductPrice#ProductPrice(ProductId, Money, Instant)} and {@link #adjustPrice(Money, Instant)} automatically creates
 * a new Price entry and assigns it a unique {@link PriceId}</li>
 * <li>{@link ProductPrice#ProductPrice(ProductId, Money, Instant)} applies a {@link InitialPriceSet} event</li>
 * <li>{@link #adjustPrice(Money, Instant)} applies a {@link PriceAdjusted} event
 * <ul><li>
 * Additionally {@link #adjustPrice(Money, Instant)} will adjustment the {@link ProductPriceEvent#priceValidityPeriod} of existing price entries
 * that overlap with a new price. These adjustments are applied as {@link PriceValidityAdjusted} events.
 * </li></ul>
 * </li>
 * </ul>
 */
public class ProductPrice extends AggregateRoot<ProductId, ProductPriceEvent, ProductPrice> {
    public static Instant PRESENT = null;

    /**
     * Simple bi-temporal query example where all prices are kept in a map according to the TimeWindow they're valid within<br>
     * This map supports both {@link #getPriceAt(Instant)} and updates/adjustments to prices in order to figure out which prices (as indicated by their {@link PriceId})
     * needs the {@link TimeWindow} based validity period adjusted
     */
    private Map<TimeWindow, Price> priceOverTime;

    /**
     * Used for rehydration
     */
    public ProductPrice(ProductId forProduct) {
        super(forProduct);
    }

    /**
     * Command method for creating a new {@link ProductPrice} aggregate with an initial initialPrice
     *
     * @param forProduct            the id of the product we're defining prices for over time
     * @param initialPrice          the initial initialPrice
     * @param initialPriceValidFrom when the <code>initialPrice</code> is valid from
     */
    public ProductPrice(ProductId forProduct,
                        Money initialPrice,
                        Instant initialPriceValidFrom) {
        this(forProduct);
        requireNonNull(initialPriceValidFrom, "initialPriceValidFrom is null");
        apply(new InitialPriceSet(forProduct,
                                  PriceId.random(),
                                  initialPrice,
                                  TimeWindow.from(initialPriceValidFrom)));
    }

    /**
     * Update the newPrice from the timestamp provided in <code>newPriceValidFrom</code>
     *
     * @param newPrice          the new newPrice from timestamp <code>newPriceValidFrom</code>
     * @param newPriceValidFrom when the <code>newPrice</code> is valid from
     */
    public void adjustPrice(Money newPrice, Instant newPriceValidFrom) {
        requireNonNull(newPrice, "No newPrice provided");
        requireNonNull(newPriceValidFrom, "No newPriceValidFrom provided");

        if (priceOverTime == null) {
            throw new AggregateException("Cannot adjust price on a non-initialized ProductPrice aggregate. " +
                                         "Use the ProductPrice(ProductId, Money, Instant) constructor to create a new aggregate with an initial price.");
        }

        var existingPriceAt = getPricePairAt(newPriceValidFrom);
        if (existingPriceAt.isEmpty()) {
            // We're setting newPrice prior to the initial newPrice
            var initialPrice = findTheValidityPeriodOfTheNextPriceAfter(newPriceValidFrom).get();
            apply(new PriceAdjusted(aggregateId(),
                                    PriceId.random(),
                                    newPrice,
                                    TimeWindow.between(newPriceValidFrom,
                                                       initialPrice.priceValidity().fromInclusive)
            ));
        } else {
            var previousPrice = findTheValidityPeriodOfThePreviousPriceBefore(newPriceValidFrom);
            var nextPrice     = findTheValidityPeriodOfTheNextPriceAfter(newPriceValidFrom);

            // TODO: The example can easily be expanded with cancellation of price entries in cases where a new entry is identical to an existing entry (or has the same validFrom date as an existing entry)

            // Adjust the validity of the previous price
            previousPrice.ifPresent(previousPriceValidity -> apply(new PriceValidityAdjusted(aggregateId(),
                                                                                             previousPriceValidity.priceId(),
                                                                                             previousPriceValidity.priceValidity().close(newPriceValidFrom))));
            if (nextPrice.isPresent() && !nextPrice.equals(previousPrice)) {
                // Insert the price between the previous and the next price
                apply(new PriceAdjusted(aggregateId(),
                                        PriceId.random(),
                                        newPrice,
                                        TimeWindow.between(newPriceValidFrom,
                                                           nextPrice.get().priceValidity().fromInclusive)
                ));
            } else {
                // This is the latest price
                apply(new PriceAdjusted(aggregateId(),
                                        PriceId.random(),
                                        newPrice,
                                        TimeWindow.from(newPriceValidFrom)
                ));
            }

        }

    }


    @EventHandler
    private void on(InitialPriceSet e) {
        // To support using Objenesis we initialize fields here
        priceOverTime = new HashMap<>();

        priceOverTime.put(e.priceValidityPeriod, Price.of(e.priceId, e.price));
    }

    @EventHandler
    private void on(PriceAdjusted e) {
        // Remove an old entry with the same priceId (in case the priceId has had it validity period adjusted)
        var existingPriceIdEntry = priceOverTime.entrySet()
                                                .stream()
                                                .filter(entry -> entry.getValue().priceId().equals(e.priceId))
                                                .findFirst();
        existingPriceIdEntry.ifPresent(timeWindowPriceEntry -> priceOverTime.remove(timeWindowPriceEntry.getKey()));
        // Add new priceId entry
        priceOverTime.put(e.priceValidityPeriod, Price.of(e.priceId, e.price));
    }

    @EventHandler
    private void on(PriceValidityAdjusted e) {
        var existingPriceIdEntry = priceOverTime.entrySet()
                                                .stream()
                                                .filter(entry -> entry.getValue().priceId().equals(e.priceId))
                                                .findFirst();
        existingPriceIdEntry.ifPresent(timeWindowPriceEntry -> {
            // Remove old entry and add it again using a new validity
            var price = priceOverTime.remove(timeWindowPriceEntry.getKey());
            priceOverTime.put(e.priceValidityPeriod, price);
        });

    }

    /**
     * For testing purpose we make the query method public to this aggregate to learn what the price was at a given timestamp -
     * this will normally be placed in a designated view model, but to keep the example simple we add it to the aggregate
     *
     * @param timestamp the timestamp for which we want the price for the given product. Use value <code>null</code> (use {@link #PRESENT})
     *                  means get the current price
     * @return the product price at <code>timestamp</code> wrapped in an {@link Optional} or {@link Optional#empty()} if the product
     * didn't have a price specified at the given time
     */
    public Optional<Money> getPriceAt(Instant timestamp) {
        var matchingTimeWindow = getPricePairAt(timestamp);

        return matchingTimeWindow.map(timeWindow -> priceOverTime.get(timeWindow).price());
    }

    /**
     * Return the  {@link Price} that is valid at the given timestamp
     *
     * @param timestamp the timestamp for which we want the price for the given product. Use value <code>null</code> (use {@link #PRESENT})
     *                  means get the current price
     * @return the {@link Price} at <code>timestamp</code> wrapped in an {@link Optional} or {@link Optional#empty()} if the product
     * didn't have a price specified at the given time
     */
    private Optional<TimeWindow> getPricePairAt(Instant timestamp) {
        return priceOverTime.keySet()
                            .stream()
                            .filter(timeWindow -> timeWindow.covers(timestamp))
                            .findFirst();
    }

    /**
     * Get all the prices for this product over time
     * @return all the prices for this product over time
     */
    public Map<TimeWindow, Price> getPriceOverTime() {
        return priceOverTime;
    }

    /**
     * Finds the price whose validity period starts AFTER the given timestamp.<br>
     * This is used when inserting a new price to determine where the new price's validity should end.
     * <p>
     * <b>Bi-Temporal Context:</b><br>
     * In a bi-temporal model, prices have validity periods (business time). When inserting a new price,
     * we need to know if there's already a price scheduled to start after our insertion point,
     * so we can set the new price's {@code toExclusive} boundary correctly.
     * </p>
     * <pre>
     * Example: We have two existing prices P1 and P2, and want to insert a new price at time 25.
     *
     * STEP 1 - Current state (two prices with a gap between them):
     *
     *     Time:     10                  40                  ∞
     *               |                   |                   |
     *               [-------P1----------[-------P2----------|---->
     *               |   (100 DKK)       |   (150 DKK)       |
     *               |   valid [10,40[   |   valid [40,∞[    |
     *
     *
     * STEP 2 - Mark where we want to insert the new price (timestamp = 25):
     *
     *     Time:     10        25        40                  ∞
     *               |         |         |                   |
     *               [-------P1----------[-------P2----------|---->
     *               |         ^         |                   |
     *               |         |         |                   |
     *                   insertion point
     *                   (new price here)
     *
     *
     * STEP 3 - This method finds the NEXT price (P2) starting AFTER timestamp 25:
     *
     *     Time:     10        25        40                  ∞
     *               |         |         |                   |
     *               [-------P1----------[-------P2----------|---->
     *               |         ^         |                   |
     *               |         |         +--- P2.fromInclusive (40) is AFTER 25
     *               |         |              so P2 is returned
     *               |    insertion point
     *
     *     This method returns P2 because:
     *     - P2.fromInclusive (40) is AFTER timestamp (25) ✓
     *     - P2 is the NEAREST price starting after timestamp
     *       (if there were prices at 40 and 60, we'd return the one at 40)
     *
     *
     * STEP 4 - The new price's validity is set to [25, 40[ using P2's fromInclusive:
     *
     *     Time:     10        25        40                  ∞
     *               |         |         |                   |
     *               [---P1----[---NEW---[-------P2----------|---->
     *               |         |         |                   |
     *               [10,25[   [25,40[   [40,∞[
     *
     *     Note: P1's validity was also adjusted from [10,40[ to [10,25[
     *           by the companion method findTheValidityPeriodOfThePreviousPriceBefore()
     * </pre>
     *
     * @param timestamp the point in time after which we're looking for the next price
     * @return the {@link PriceValidity} of the next price starting after the timestamp,
     *         or {@link Optional#empty()} if no such price exists (meaning the new price extends to infinity)
     */
    private Optional<PriceValidity> findTheValidityPeriodOfTheNextPriceAfter(Instant timestamp) {
        return priceOverTime.entrySet()
                            .stream()
                            .filter(entry -> entry.getKey().fromInclusive.isAfter(timestamp))
                            .min(Comparator.comparing(entry -> entry.getKey().fromInclusive))
                            .map(entry -> PriceValidity.of(entry.getValue().priceId(),
                                                           entry.getKey()));
    }

    /**
     * Finds the price whose validity period COVERS the given timestamp.<br>
     * This is the price that is currently "active" at the given timestamp and whose validity
     * period needs to be shortened (closed) when inserting a new price at that timestamp.
     * <p>
     * <b>Bi-Temporal Context:</b><br>
     * In a bi-temporal model, when we insert a new price at a specific timestamp, we need to
     * find which existing price is valid at that moment. That price's validity period must be
     * adjusted to end at the new price's start time, maintaining a continuous, non-overlapping
     * price timeline.
     * </p>
     * <pre>
     * Example: We have an existing price P1 valid from 10 to ∞, and want to insert a new price at time 25.
     *
     * STEP 1 - Current state (single open-ended price):
     *
     *     Time:     10                                      ∞
     *               |                                       |
     *               [----------------P1---------------------|---->
     *               |           (100 DKK)                   |
     *               |           valid [10, ∞[               |
     *
     *
     * STEP 2 - Mark where we want to insert the new price (timestamp = 25):
     *
     *     Time:     10        25                            ∞
     *               |         |                             |
     *               [----------------P1---------------------|---->
     *               |         ^                             |
     *               |         |                             |
     *               |    insertion point                    |
     *               |    (new price here)                   |
     *
     *
     * STEP 3 - This method finds the price that COVERS timestamp 25:
     *
     *     Time:     10        25                            ∞
     *               |         |                             |
     *               [----------------P1---------------------|---->
     *               |         ^                             |
     *               |         |                             |
     *               +----P1's TimeWindow [10, ∞[ COVERS 25--+
     *                         because: 10 <= 25 < ∞
     *
     *     This method returns P1 because:
     *     - P1's fromInclusive (10) is <= timestamp (25) ✓
     *     - P1's toExclusive is null (open-ended, extends to ∞) ✓
     *     - Therefore P1 "covers" timestamp 25
     *
     *
     * STEP 4 - P1's validity is CLOSED at timestamp 25 (via PriceValidityAdjusted event):
     *
     *     Time:     10        25                            ∞
     *               |         |                             |
     *               [---P1----]                             |
     *               |         |                             |
     *               [10, 25[  +--- P1 now ends here         |
     *                         |    (toExclusive = 25)       |
     *
     *
     * STEP 5 - Final result after new price (P2) is inserted:
     *
     *     Time:     10        25                            ∞
     *               |         |                             |
     *               [---P1----[------------P2---------------|---->
     *               |         |                             |
     *               [10, 25[  [25, ∞[                       |
     *               100 DKK   120 DKK (new price)           |
     *
     *
     * How "covers" works ({@link TimeWindow#covers(Instant)}):
     *   - Returns true when: fromInclusive <= timestamp AND (toExclusive is null OR timestamp &lt; toExclusive)
     *   - Example: TimeWindow [10, 40[ covers 25 because 10 <= 25 AND 25 &lt; 40
     *   - Example: TimeWindow [10, ∞[ covers 25 because 10 <= 25 AND toExclusive is null
     *   - Example: TimeWindow [10, 25[ does NOT cover 25 because 25 is NOT &lt; 25
     * </pre>
     *
     * @param timestamp the point in time for which we want to find the covering price
     * @return the {@link PriceValidity} of the price valid at the given timestamp,
     *         or {@link Optional#empty()} if no price covers that timestamp
     *         (which means we're inserting before all existing prices)
     */
    private Optional<PriceValidity> findTheValidityPeriodOfThePreviousPriceBefore(Instant timestamp) {
        return priceOverTime.entrySet()
                            .stream()
                            .filter(entry -> entry.getKey().covers(timestamp))
                            .findFirst()
                            .map(entry -> PriceValidity.of(entry.getValue().priceId(),
                                                           entry.getKey()));
    }

    /**
     * {@link PriceId}/{@link Money} pair for internal storage of prices and their validity
     */
    public static class Price extends Pair<PriceId, Money> {

        /**
         * Construct a new {@link Tuple} with 2 elements
         *
         * @param priceId the first element
         * @param price   the second element
         */
        public Price(PriceId priceId, Money price) {
            super(priceId, price);
        }

        public static Price of(PriceId priceId, Money price) {
            return new Price(priceId, price);
        }

        public PriceId priceId() {
            return _1;
        }

        public Money price() {
            return _2;
        }
    }

    /**
     * {@link PriceId}/{@link TimeWindow} pair for capturing when a given {@link PriceId} is valid
     */
    public static class PriceValidity extends Pair<PriceId, TimeWindow> {

        /**
         * Construct a new {@link Tuple} with 2 elements
         *
         * @param priceId       the first element
         * @param priceValidity the second element
         */
        public PriceValidity(PriceId priceId, TimeWindow priceValidity) {
            super(priceId, priceValidity);
        }

        public static PriceValidity of(PriceId priceId, TimeWindow priceValidity) {
            return new PriceValidity(priceId, priceValidity);
        }

        public PriceId priceId() {
            return _1;
        }

        public TimeWindow priceValidity() {
            return _2;
        }
    }
}
