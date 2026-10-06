package com.example.golden.wiring;

import java.util.List;

public record ProbeDocument(ProbeId id, List<ProbeId> related) {
}
