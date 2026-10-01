package com.example.shop.orders.external_systems.billing

import org.junit.jupiter.api.Test

/**
 * The test floor for a translation slice.
 *
 * The translator is pure, so this runs with no database, no Spring context, and no external system.
 * That is the whole reason the mapping lives in its own class.
 *
 * CONTRACT TESTING: a translation slice's real floor is a consumer contract test against
 * Billing's published contract. This plugin bundles no contract-testing tooling — add
 * Pact or Specmatic yourself and flip `tests.contract.present` in slice.yaml once you have.
 */
class BillingTranslatorTest {

    private val translator = BillingTranslator()

    @Test
    fun `inbound payload maps onto the internal command`() {
        // TODO: assert every field, including the type conversions at the boundary.
        //   val command = translator.toCommand(InvoiceIssuedPayload("id-1", "payload"))
        //   assertThat(command).isEqualTo(TODOInternalCommand(...))
    }

    @Test
    fun `outbound event maps onto the external request`() {
        // TODO: assert the external contract shape, not our internal one.
    }

    @Test
    fun `unknown or malformed external input is rejected at the boundary`() {
        // A malformed external message must fail HERE, not three layers in.
    }
}
