package com.example.library.lending.external_systems.catalogue_feed;

import com.example.library.lending.use_cases.register_copy.RegisterCopy;
import org.springframework.stereotype.Component;

@Component
public class CatalogueFeedTranslator {
    public RegisterCopy toCommand(CatalogueEntryReceived entry) {
        return new RegisterCopy(entry.branch() + "-" + entry.barcode(), entry.isbn());
    }
}
