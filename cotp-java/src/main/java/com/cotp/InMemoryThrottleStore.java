package com.cotp;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, in-process {@link ThrottleStore} for tests and single-node deployments.
 *
 * <p>Tracks last-access time so that idle state can be reclaimed by {@link #evictIdle}. For
 * multi-node deployments use a distributed store with the same contract.</p>
 */
public final class InMemoryThrottleStore implements ThrottleStore {

    private static final int EVICT_GATE = 1024;

    private static final class State {
        final AtomicInteger failures = new AtomicInteger();
        volatile Instant lockedUntil;
        volatile Instant lastAccess = Instant.EPOCH;
    }

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final AtomicLong evictCounter = new AtomicLong();

    private State state(String cardId) {
        return states.computeIfAbsent(cardId, k -> new State());
    }

    private void touch(State s, Instant now) {
        if (now != null) {
            s.lastAccess = now;
        }
    }

    @Override
    public int failureCount(String cardId) {
        return state(cardId).failures.get();
    }

    @Override
    public Instant lockedUntil(String cardId) {
        return state(cardId).lockedUntil;
    }

    @Override
    public void lockUntil(String cardId, Instant until) {
        State s = state(cardId);
        s.lockedUntil = until;
        touch(s, until);
    }

    @Override
    public int incrementFailure(String cardId) {
        return state(cardId).failures.incrementAndGet();
    }

    @Override
    public void reset(String cardId) {
        states.remove(cardId);
    }

    @Override
    public void evictIdle(Instant now, Duration idleTtl) {
        if (now == null || idleTtl == null) {
            return;
        }
        if ((evictCounter.incrementAndGet() & (EVICT_GATE - 1)) != 0) {
            return;
        }
        purge(now, idleTtl);
    }

    /**
     * Unconditionally removes state that is not locked and has not been accessed for
     * {@code idleTtl}. Safe to call from a scheduled task.
     */
    public void purge(Instant now, Duration idleTtl) {
        Instant threshold = now.minus(idleTtl);
        for (Map.Entry<String, State> entry : states.entrySet()) {
            State s = entry.getValue();
            boolean locked = s.lockedUntil != null && now.isBefore(s.lockedUntil);
            if (!locked && s.lastAccess.isBefore(threshold)) {
                states.remove(entry.getKey(), s);
            }
        }
    }
}
