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

package dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.domain;

import java.util.Objects;

public class ShippingDestinationAddress {
    public String recipientName;
    public String street;
    public String zipCode;
    public String city;

    public ShippingDestinationAddress(String recipientName, String street, String zipCode, String city) {
        this.recipientName = recipientName;
        this.street = street;
        this.zipCode = zipCode;
        this.city = city;
    }

    public static ShippingDestinationAddressBuilder builder() {
        return new ShippingDestinationAddressBuilder();
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getStreet() {
        return street;
    }

    public String getZipCode() {
        return zipCode;
    }

    public String getCity() {
        return city;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShippingDestinationAddress that)) return false;
        return Objects.equals(recipientName, that.recipientName)
                && Objects.equals(street, that.street)
                && Objects.equals(zipCode, that.zipCode)
                && Objects.equals(city, that.city);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recipientName, street, zipCode, city);
    }

    @Override
    public String toString() {
        return "ShippingDestinationAddress(recipientName=" + recipientName +
                ", street=" + street +
                ", zipCode=" + zipCode +
                ", city=" + city + ")";
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
