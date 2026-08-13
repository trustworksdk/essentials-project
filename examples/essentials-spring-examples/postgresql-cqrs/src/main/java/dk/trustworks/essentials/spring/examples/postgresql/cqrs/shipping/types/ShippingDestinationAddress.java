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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.types;

/**
 * Where an order is to be delivered. An immutable value object with no identity of its own -- two addresses with the
 * same fields are the same address.
 *
 * <p>It is carried in {@code ShippingOrderRegistered}, which means it is persisted in the event stream: renaming a
 * component is a stored-data change, not just a refactor.
 */
public record ShippingDestinationAddress(String recipientName,
                                         String street,
                                         String zipCode,
                                         String city) {

    public static ShippingDestinationAddressBuilder builder() {
        return new ShippingDestinationAddressBuilder();
    }

    public static final class ShippingDestinationAddressBuilder {
        private String recipientName;
        private String street;
        private String zipCode;
        private String city;

        public ShippingDestinationAddressBuilder setRecipientName(String recipientName) {
            this.recipientName = recipientName;
            return this;
        }

        public ShippingDestinationAddressBuilder setStreet(String street) {
            this.street = street;
            return this;
        }

        public ShippingDestinationAddressBuilder setZipCode(String zipCode) {
            this.zipCode = zipCode;
            return this;
        }

        public ShippingDestinationAddressBuilder setCity(String city) {
            this.city = city;
            return this;
        }

        public ShippingDestinationAddress build() {
            return new ShippingDestinationAddress(recipientName, street, zipCode, city);
        }
    }
}
