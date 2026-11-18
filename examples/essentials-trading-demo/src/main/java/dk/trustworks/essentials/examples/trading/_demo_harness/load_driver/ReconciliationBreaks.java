/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dk.trustworks.essentials.examples.trading._demo_harness.load_driver;

import dk.trustworks.essentials.examples.trading.reconciliation.types.CaseState;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.*;

/**
 * F-5 (design phase 6 §10): the reconciliation breaks the simulators seeded into their statements, against what the
 * brokerage found in them and the cases it opened - the phase 5 ITs' one-for-one comparison, over a whole load run.
 * <ul>
 *     <li>clearing: per statement, our breaks as the clearinghouse's kinds with their execution ids, against
 *     {@code GET /test/clearing/breaks}</li>
 *     <li>bank: per statement, our breaks' kinds against {@code GET /test/statements/breaks} (the gateway's seeded
 *     breaks carry no reference we keep)</li>
 *     <li>cases: one per break of a statement whose business day was followed by a close (cases open at the next
 *     close), and none for a break that is not there</li>
 * </ul>
 */
final class ReconciliationBreaks {
    private final BrokerageHttp http;
    private final String        brokerageUrl;
    private final String        exchangeUrl;
    private final String        gatewayUrl;

    ReconciliationBreaks(BrokerageHttp http, String brokerageUrl, String exchangeUrl, String gatewayUrl) {
        this.http = http;
        this.brokerageUrl = brokerageUrl;
        this.exchangeUrl = exchangeUrl;
        this.gatewayUrl = gatewayUrl;
    }

    record Result(Map<String, Boolean> checks, Map<String, Object> evidence) {
    }

    /**
     * @param lastClosed the latest business date the exchange has closed; a statement of an earlier date has had its
     *                   next close, so its breaks must have cases
     */
    Result compare(LocalDate lastClosed) {
        var mismatches = new ArrayList<String>();
        var breaks     = new HashSet<String>();   // statementId|kind|reference, ours
        var withCases  = new HashSet<String>();   // the subset that must have a case by now
        var seeded     = new TreeMap<String, Integer>();
        var found      = new TreeMap<String, Integer>();

        for (var statement : get(brokerageUrl + "/api/admin/reconciliation/clearing-statements")) {
            var statementId = statement.path("statementId").asString();
            var date        = LocalDate.parse(statement.path("businessDate").asString());
            var ours        = new ArrayList<String>();
            for (var b : get(brokerageUrl + "/api/admin/reconciliation/clearing-statements/" + statementId + "/breaks")) {
                var kind = b.path("kind").asString();
                ours.add(switch (kind) {
                    case "UNKNOWN_TRADE" -> "PHANTOM_TRADE";
                    case "MISSING_TRADE" -> "OMITTED_TRADE";
                    case "QUANTITY_MISMATCH" -> "QUANTITY_CHANGED";
                    case "PRICE_MISMATCH" -> "PRICE_CHANGED";
                    default -> kind;
                } + ":" + b.path("reference").asString());
                track(breaks, withCases, statementId, kind, b.path("reference").asString(), date, lastClosed);
            }
            var theirs = new ArrayList<String>();
            for (var statementBreaks : get(exchangeUrl + "/test/clearing/breaks?date=" + date + "&participant=" + statement.path("participantId").asString())) {
                for (var b : statementBreaks.path("breaks")) {
                    if (!b.path("kind").asString().equals("LATE_STATEMENT")) {
                        theirs.add(b.path("kind").asString() + ":" + b.path("executionId").asString());
                    }
                }
            }
            compare("clearing " + statementId, ours, theirs, mismatches, seeded, found);
        }

        for (var statement : get(brokerageUrl + "/api/admin/reconciliation/bank-statements")) {
            var statementId = statement.path("statementId").asString();
            var date        = LocalDate.parse(statement.path("businessDate").asString());
            var ours        = new ArrayList<String>();
            for (var b : get(brokerageUrl + "/api/admin/reconciliation/bank-statements/" + statementId + "/breaks")) {
                var kind = b.path("kind").asString();
                ours.add(switch (kind) {
                    case "AMOUNT_MISMATCH" -> "AMOUNT_CHANGED";
                    case "UNKNOWN_ENTRY" -> "PHANTOM_ENTRY";
                    case "MISSING_ENTRY" -> "OMITTED_ENTRY";
                    default -> kind;
                });
                track(breaks, withCases, statementId, kind, b.path("reference").asString(), date, lastClosed);
            }
            var theirs = new ArrayList<String>();
            for (var statementBreaks : get(gatewayUrl + "/test/statements/breaks?date=" + date + "&currency=" + statement.path("currency").asString())) {
                for (var b : statementBreaks.path("breaks")) {
                    if (!b.path("kind").asString().equals("LATE_STATEMENT")) {
                        theirs.add(b.path("kind").asString());
                    }
                }
            }
            compare("bank " + statementId, ours, theirs, mismatches, seeded, found);
        }

        // Per state: the unfiltered list is capped at the newest 500, a full run opens more than that
        var cases = new HashSet<String>();
        for (var state : CaseState.values()) {
            for (var c : get(brokerageUrl + "/api/admin/reconciliation/cases?state=" + state)) {
                if (!c.path("kind").asString().equals("MISSING_STATEMENT")) {
                    cases.add(c.path("statementId").asString() + "|" + c.path("kind").asString() + "|" + c.path("reference").asString());
                }
            }
        }
        var withoutCase  = new TreeSet<>(withCases);
        withoutCase.removeAll(cases);
        var withoutBreak = new TreeSet<>(cases);
        withoutBreak.removeAll(breaks);

        var checks = new LinkedHashMap<String, Boolean>();
        checks.put("breaks seeded", seeded.values().stream().mapToInt(Integer::intValue).sum() > 0);
        checks.put("every seeded break found one for one, none invented", mismatches.isEmpty());
        checks.put("a case for every break by the close after its statement", withoutCase.isEmpty());
        checks.put("no case without a break", withoutBreak.isEmpty());

        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("seededByKind", seeded);
        evidence.put("foundByKind", found);
        evidence.put("breaks", breaks.size());
        evidence.put("breaksDueACase", withCases.size());
        evidence.put("cases", cases.size());
        evidence.put("mismatches", mismatches.stream().limit(20).toList());
        evidence.put("withoutCase", withoutCase.stream().limit(20).toList());
        evidence.put("withoutBreak", withoutBreak.stream().limit(20).toList());
        return new Result(checks, evidence);
    }

    private static void track(Set<String> breaks, Set<String> withCases, String statementId, String kind, String reference, LocalDate date, LocalDate lastClosed) {
        var key = statementId + "|" + kind + "|" + reference;
        breaks.add(key);
        if (date.isBefore(lastClosed)) {
            withCases.add(key);
        }
    }

    private static void compare(String what, List<String> ours, List<String> theirs, List<String> mismatches, Map<String, Integer> seeded, Map<String, Integer> found) {
        theirs.forEach(b -> seeded.merge(b.contains(":") ? b.substring(0, b.indexOf(':')) : b, 1, Integer::sum));
        ours.forEach(b -> found.merge(b.contains(":") ? b.substring(0, b.indexOf(':')) : b, 1, Integer::sum));
        var missed   = new ArrayList<>(theirs);
        var invented = new ArrayList<String>();
        for (var b : ours) {
            if (!missed.remove(b)) {
                invented.add(b);
            }
        }
        missed.forEach(b -> mismatches.add(what + ": missed " + b));
        invented.forEach(b -> mismatches.add(what + ": invented " + b));
    }

    private JsonNode get(String url) {
        var response = url.startsWith(brokerageUrl) ? http.operatorGet("FAULTS", url) : http.plainGet("FAULTS", url);
        if (!response.ok()) {
            throw new IllegalStateException("GET " + url + ": " + response);
        }
        return http.json(response);
    }
}
