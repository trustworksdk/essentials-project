package com.example.library.lending.views.loans_on_shelf;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class LoansOnShelfAPI {
    @GetMapping("/api/lending/loans")
    public List<String> loans() {
        return List.of();
    }
}
