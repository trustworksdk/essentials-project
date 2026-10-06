package com.example.store.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardingController {
    @GetMapping("/{path:[^\\.]*}")
    public String forward() {
        return "forward:/index.html";
    }
}
