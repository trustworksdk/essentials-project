package com.acme.crm.service;

import com.acme.crm.model.Customer;
import com.acme.crm.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CustomerService {
    private final CustomerRepository customers;

    public CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    @Transactional
    public String register(String name, String email) {
        var customer = new Customer(UUID.randomUUID().toString(), name, email);
        customers.save(customer);
        return customer.getId();
    }

    @Transactional
    public void changeEmail(String customerId, String email) {
        var customer = customers.findById(customerId).orElseThrow();
        customer.changeEmail(email);
    }

    @Transactional(readOnly = true)
    public List<Customer> all() {
        return customers.findAll();
    }
}
