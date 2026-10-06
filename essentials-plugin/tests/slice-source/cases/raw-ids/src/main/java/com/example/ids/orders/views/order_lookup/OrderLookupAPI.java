package com.example.ids.orders.views.order_lookup;

import com.example.ids.orders.types.OrderId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders/lookup")
public class OrderLookupAPI {

    /** TRAP: typed id. */
    @GetMapping("/{orderId}")
    public OrderLookupView get(@PathVariable OrderId orderId) {
        return null;
    }

    /** RULE: the bound name decides as well as the parameter's — `id`, as a primitive long. */
    @GetMapping("/legacy/{id}")
    public OrderLookupView legacy(@PathVariable("id") long key) {
        return null;
    }

    /** RULE: Optional<String> and a UUID are still raw scalars. */
    @GetMapping(params = "customerId")
    public List<OrderLookupView> byCustomer(@RequestParam("customerId") Optional<String> customer,
                                            @RequestParam(required = false) UUID batchId) {
        return List.of();
    }

    /** TRAP: none of these is an id — `status`, `sku`, `paid`, `valid`, `q`. */
    @GetMapping("/by-sku/{sku}")
    public List<OrderLookupView> bySku(@PathVariable String sku,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "false") boolean paid,
                                       @RequestParam(required = false) String valid,
                                       @RequestParam(required = false) String q) {
        return List.of();
    }
}
