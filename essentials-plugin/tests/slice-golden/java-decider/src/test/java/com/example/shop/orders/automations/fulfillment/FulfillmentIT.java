package com.example.shop.orders.automations.fulfillment;

import org.junit.jupiter.api.Test;

/**
 * Integration test for the fulfillment automation — the test floor for an automation slice.
 *
 * An automation reacts to real events and dispatches real commands, so it needs the event store and
 * the command bus. Extend the project's {@code IntegrationTestBase} rather than standing up
 * containers here.
 *
 * Assert the process rules, not the plumbing — the guards in {@code FulfillmentTodoList} are what this
 * slice actually promises.
 */
class FulfillmentIT {

    @Test
    void eventAdvancesTheProcessAndDispatchesTheNextCommand() {
        // TODO: append OrderPlaced; assert the TodoList advanced and the command was dispatched.
    }

    @Test
    void redeliveryOfTheSameEventDoesNotDispatchTwice() {
        // The Inbox redelivers. This is the single most important automation test.
    }

    @Test
    void retriesAreBoundedAndTerminalFailureCompensates() {
        // TODO: drive the failure path; assert attempts stop at the cap and the compensating
        //       command is issued rather than the process silently stalling.
    }
}
