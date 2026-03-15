package com.sevenmax.tracker.service;

import java.util.List;
import java.util.Map;

public class BalanceMismatchException extends RuntimeException {

    private final List<Map<String, Object>> mismatches;

    public BalanceMismatchException(List<Map<String, Object>> mismatches) {
        super("Balance validation failed for " + mismatches.size() + " player(s)");
        this.mismatches = mismatches;
    }

    public List<Map<String, Object>> getMismatches() {
        return mismatches;
    }
}
