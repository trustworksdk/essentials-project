package com.example.billing.views.invoice_export;

import com.example.billing.external.Routes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** UNPARSED: the path is a constant declared outside the scanned tree, so the route is unknown. */
@RestController
public class InvoiceExportAPI {

    @GetMapping(Routes.EXPORT)
    public String export() {
        return "";
    }
}
