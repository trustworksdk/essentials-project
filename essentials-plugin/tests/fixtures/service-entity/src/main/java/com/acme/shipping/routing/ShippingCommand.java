package com.acme.shipping.routing;

import com.acme.shipping.types.OrderId;

/**
 * FINDING (gate 17): a routing marker on the service-entity lane.
 *
 * <p>Nothing implements it and nothing can use it: {@code routing/} exists to answer two questions
 * the <em>decider</em> configurator asks — which deciders serve an aggregate type, and which event
 * stream a command loads. This BC has no deciders and no event store; the command bus routes by
 * command type to a {@code @CmdHandler} method. A decider-style vestige, left behind by someone
 * scaffolding from the wrong lane, and a misleading signal about this BC's write style.
 */
public interface ShippingCommand {
    OrderId id();
}
