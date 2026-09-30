package com.acme.shop.orders.use_cases.place_order;

import com.acme.shop.orders.aggregates.Orders;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.springframework.stereotype.Component;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of THIS slice (rules/slice-design.md §R1) — and on the aggregate
 * lane it is deliberately the thinnest of the three lanes' handlers.
 *
 * ONE {@code @CmdHandler} METHOD, ONE COMMAND TYPE. A handler carrying methods for two command types
 * is §R1's router wearing a handler's clothes — it splits into that many slices. Adding a command
 * means adding a directory, never a method here.
 *
 * THE SHAPE IS THREE STEPS: **load, call one method, done.** There is no explicit save: the
 * aggregate is loaded inside the command bus's unit of work, and the events it applied are appended
 * when that unit of work commits. (A *creation* slice is the exception — it constructs the aggregate
 * and hands it to {@code saveNew}, because there is nothing to load. See the commented variant below.)
 *
 * THE DECISION IS NOT HERE. No {@code if} about domain state belongs in this file — the guard lives
 * on the aggregate, where it runs before {@code apply} so a rejected command leaves no event behind
 * (§ The aggregate's own bar). A handler that grows business rules has turned the aggregate into a
 * data holder, which is exactly the god-service this lane prevents. If you are tempted, the rule
 * you are about to write is the aggregate's.
 *
 * IDEMPOTENCY IS THE AGGREGATE'S JOB TOO. The command bus delivers at least once. The
 * boolean-returning invariant method returns {@code false} when the state was already reached, so a
 * redelivered command applies no second event.
 *
 * WIRING — normally nothing to write. {@code ReactiveHandlersBeanPostProcessor} auto-registers any
 * {@code CommandHandler} bean with the single {@code CommandBus} bean, so {@code @Component} plus a
 * scanned package is the whole of it. The obligation is a *check*: confirm
 * {@code reactive-bean-post-processor-enabled} (default {@code true}) has not been switched off,
 * because disabling it silently unwires every handler in the application
 * (§ Wiring is part of done).
 *
 * TRANSACTION — no {@code @Transactional} here. The {@code DurableLocalCommandBus} forwards the
 * command inside a unit of work already, which is what makes the load-mutate-append sequence atomic.
 */
@Component
public class PlaceOrderHandler extends AnnotatedCommandHandler {

    private final Orders orders;

    public PlaceOrderHandler(Orders orders) {
        this.orders = requireNonNull(orders, "No orders provided");
    }

    @CmdHandler
    public void handle(PlaceOrder cmd) {
        requireNonNull(cmd, "No cmd provided");

        var order = orders.getOrder(cmd.id());

        // TODO: call the ONE invariant method this slice's intent maps to. The aggregate decides;
        //       this line is the whole of the handler's job.
        order.applyPlaceholder(cmd.placeholder());
    }

    /*
     * CREATION-SLICE VARIANT — use this shape when the slice's intent is "bring the aggregate into
     * existence". There is nothing to load, the constructor is what emits the first event, and the
     * existence check is the idempotency guard:
     *
     * @CmdHandler
     * public void handle(PlaceOrder cmd) {
     *     requireNonNull(cmd, "No cmd provided");
     *     if (orders.isOrderMissing(cmd.id())) {
     *         orders.saveNew(new Order(cmd.id(), cmd.placeholder()));
     *     }
     * }
     */
}
