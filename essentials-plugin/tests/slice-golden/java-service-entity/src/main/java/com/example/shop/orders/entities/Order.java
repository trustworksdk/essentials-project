package com.example.shop.orders.entities;

import org.springframework.data.annotation.Id;

/**
 * Stand-in for the entity a user writes by hand on the service-entity lane (no template ships for
 * it — see entities/CLAUDE.md). It carries exactly the surface the rendered slices call.
 */
public class Order {

    @Id
    private String id;
    private String placeholder;

    protected Order() {
    }

    public Order(String id, String placeholder) {
        this.id = id;
        this.placeholder = placeholder;
    }

    public boolean applyPlaceholder(String newPlaceholder) {
        if (newPlaceholder.equals(placeholder)) {
            return false;
        }
        placeholder = newPlaceholder;
        return true;
    }
}
