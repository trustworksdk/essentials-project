package com.example.library.lending.use_cases.return_book;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReturnBookAPI {
    @PostMapping("/api/lending/returns")
    public void giveBack(@RequestBody ReturnBook command) {
    }
}
