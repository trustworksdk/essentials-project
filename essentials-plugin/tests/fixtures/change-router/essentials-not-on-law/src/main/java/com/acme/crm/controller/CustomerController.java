package com.acme.crm.controller;

import com.acme.crm.model.Customer;
import com.acme.crm.service.CustomerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    public record RegisterRequest(String name, String email) {
    }

    public record ChangeEmailRequest(String email) {
    }

    @PostMapping
    public String register(@RequestBody RegisterRequest body) {
        return service.register(body.name(), body.email());
    }

    @PutMapping("/{customerId}/email")
    public void changeEmail(@PathVariable String customerId, @RequestBody ChangeEmailRequest body) {
        service.changeEmail(customerId, body.email());
    }

    @GetMapping
    public List<Customer> all() {
        return service.all();
    }
}
