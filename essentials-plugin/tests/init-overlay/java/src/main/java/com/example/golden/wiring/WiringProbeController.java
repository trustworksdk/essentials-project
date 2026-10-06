package com.example.golden.wiring;

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CI-only (never rendered into a user project). Gives the generated spec a real endpoint, and injects the beans a
 * slice controller typically needs, so the `openapi` spec-export profile is proven to start with them.
 */
@RestController
public class WiringProbeController {
    private final JSONSerializer jsonSerializer;
    private final CommandBus     commandBus;

    public WiringProbeController(JSONSerializer jsonSerializer, CommandBus commandBus) {
        this.jsonSerializer = jsonSerializer;
        this.commandBus = commandBus;
    }

    /** S4 binds the path variable; S3.3 writes it back as a JSON string through the web mapper. */
    @GetMapping("/api/wiring/{id}")
    public ProbeDocument echo(@PathVariable ProbeId id) {
        return new ProbeDocument(id, List.of(id));
    }

    /** What the persistence serializer writes for the same document (S3.2). */
    @GetMapping(value = "/api/wiring/{id}/persisted", produces = "text/plain")
    public String persisted(@PathVariable ProbeId id) {
        return jsonSerializer.serialize(new ProbeDocument(id, List.of(id)));
    }

    @GetMapping(value = "/api/wiring/command-bus", produces = "text/plain")
    public String commandBus() {
        return commandBus.getClass().getSimpleName();
    }
}
