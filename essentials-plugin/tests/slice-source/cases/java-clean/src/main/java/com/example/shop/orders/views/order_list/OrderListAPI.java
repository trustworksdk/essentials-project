package com.example.shop.orders.views.order_list;

import com.example.shop.orders.config.ApiPaths;
import com.example.shop.orders.types.OrderId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/** Several mappings on a VIEW slice are §R2 working: queries over the one read model it owns. */
@RestController
@RequestMapping(ApiPaths.ORDER_LIST)
public class OrderListAPI {

    /** TRAP: an optional filter with a default is not a discriminator and needs no endpoint of its own. */
    @GetMapping
    public List<OrderListView> list(@RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam Optional<String> sort) {
        return List.of();
    }

    /** RULE: `?status=` is bound in THIS handler by params. */
    @GetMapping(params = "status")
    public List<OrderListView> byStatus(@RequestParam String status) {
        return List.of();
    }

    /** RULE: value-pinned — `?status=shipped` ↔ params = "status=shipped". */
    @GetMapping(params = "status=shipped")
    public List<OrderListView> shipped() {
        return List.of();
    }

    /** RULE: multi-parameter — `?from=&to=` ↔ params = {"from", "to"}; a negated param is not one. */
    @GetMapping(params = {"from", "to", "!legacy"})
    public List<OrderListView> between(@RequestParam String from, @RequestParam String to) {
        return List.of();
    }

    /** RULE: a required @RequestParam binds `?q=` on its own route. */
    @GetMapping(value = "/search")
    public List<OrderListView> search(@RequestParam("q") String query) {
        return List.of();
    }

    @GetMapping(path = {"/{orderId}"})
    public OrderListView get(@PathVariable OrderId orderId) {
        return null;
    }
}
