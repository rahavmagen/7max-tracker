package com.sevenmax.tracker.service;

import com.sevenmax.tracker.event.NewDepositEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lets the chip-loading automation long-poll for new Grow/KashCash deposits instead of short-polling
 * every N seconds. A waiting request is held open (via DeferredResult) until either a new pending
 * deposit appears or it times out - the caller is expected to immediately re-request either way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepositWaitService {

    private final GrowDepositService growDepositService;
    private final KashcashService kashcashService;

    private final List<DeferredResult<List<Map<String, Object>>>> waiters = new CopyOnWriteArrayList<>();

    public DeferredResult<List<Map<String, Object>>> waitForPending(long timeoutMs) {
        List<Map<String, Object>> immediate = combinedPending();
        DeferredResult<List<Map<String, Object>>> result = new DeferredResult<>(timeoutMs, List.of());
        if (!immediate.isEmpty()) {
            result.setResult(immediate);
            return result;
        }
        waiters.add(result);
        result.onCompletion(() -> waiters.remove(result));
        return result;
    }

    @EventListener
    public void onNewDeposit(NewDepositEvent event) {
        if (waiters.isEmpty()) return;
        List<Map<String, Object>> pending = combinedPending();
        if (pending.isEmpty()) return; // shouldn't happen right after a deposit, but be defensive
        List<DeferredResult<List<Map<String, Object>>>> toNotify = new ArrayList<>(waiters);
        for (DeferredResult<List<Map<String, Object>>> w : toNotify) {
            w.setResult(pending);
        }
        log.info("DepositWaitService: woke {} waiter(s) for new {} deposit", toNotify.size(), event.source());
    }

    private List<Map<String, Object>> combinedPending() {
        List<Map<String, Object>> combined = new ArrayList<>();
        for (Map<String, Object> d : growDepositService.getPending()) {
            Map<String, Object> tagged = new LinkedHashMap<>(d);
            tagged.put("source", "GROW");
            combined.add(tagged);
        }
        for (Map<String, Object> d : kashcashService.getPending()) {
            Map<String, Object> tagged = new LinkedHashMap<>(d);
            tagged.put("source", "KASHCASH");
            combined.add(tagged);
        }
        return combined;
    }
}
