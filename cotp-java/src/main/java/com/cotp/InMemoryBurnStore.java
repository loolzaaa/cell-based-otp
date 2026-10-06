package com.cotp;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, in-process {@link BurnStore}. Suitable for tests and single-node deployments.
 *
 * <p>All mutating operations use the atomic {@code add}/{@code put} of concurrent collections, so
 * check-and-set is race-free. Challenge ids are indexed by card and carry an expiry so that
 * {@link #evictExpired(Instant)} (and {@link #purge(Instant)}) can reclaim memory; re-enrolment is
 * reflected by {@link #reset(String)}.</p>
 *
 * <p>For multi-node deployments, provide a distributed implementation (for example Redis with native
 * TTL for challenge keys) with the same contract.</p>
 */
public final class InMemoryBurnStore implements BurnStore {

    /** Run a sweep at most once per this many {@link #evictExpired} calls. */
    private static final int EVICT_GATE = 1024;

    private final ConcurrentHashMap<String, Set<String>> burnedCellsByCard = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Instant>> lastCellUseByCard =
            new ConcurrentHashMap<>();
    private final Set<String> usedChallenges = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Set<String>> challengesByCard = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> challengeExpiry = new ConcurrentHashMap<>();
    private final AtomicLong evictCounter = new AtomicLong();

    @Override
    public boolean isCellBurned(String cardId, String cellId) {
        Set<String> set = burnedCellsByCard.get(cardId);
        return set != null && set.contains(cellId);
    }

    @Override
    public boolean markCellBurned(String cardId, String cellId) {
        return burnedCellsByCard.computeIfAbsent(cardId, k -> ConcurrentHashMap.newKeySet()).add(cellId);
    }

    @Override
    public Instant lastCellUse(String cardId, String cellId) {
        Map<String, Instant> map = lastCellUseByCard.get(cardId);
        return map == null ? null : map.get(cellId);
    }

    @Override
    public void recordCellUse(String cardId, String cellId, Instant at) {
        Objects.requireNonNull(at, "at");
        lastCellUseByCard.computeIfAbsent(cardId, k -> new ConcurrentHashMap<>()).put(cellId, at);
    }

    @Override
    public boolean isChallengeUsed(String challengeId) {
        return usedChallenges.contains(challengeId);
    }

    @Override
    public boolean markChallengeUsed(String cardId, String challengeId, Instant expiresAt) {
        Objects.requireNonNull(expiresAt, "expiresAt");
        boolean added = usedChallenges.add(challengeId);
        if (added) {
            challengesByCard.computeIfAbsent(cardId, k -> ConcurrentHashMap.newKeySet()).add(challengeId);
            challengeExpiry.put(challengeId, expiresAt);
        }
        return added;
    }

    @Override
    public Set<String> burnedCells(String cardId) {
        Set<String> set = burnedCellsByCard.get(cardId);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    @Override
    public void reset(String cardId) {
        burnedCellsByCard.remove(cardId);
        lastCellUseByCard.remove(cardId);
        Set<String> challenges = challengesByCard.remove(cardId);
        if (challenges != null) {
            for (String id : challenges) {
                usedChallenges.remove(id);
                challengeExpiry.remove(id);
            }
        }
    }

    @Override
    public void evictExpired(Instant now) {
        // Opportunistic: keep the amortised cost negligible on the hot verification path while still
        // reclaiming memory under sustained load. Call purge(now) for an unconditional sweep.
        if ((evictCounter.incrementAndGet() & (EVICT_GATE - 1)) != 0) {
            return;
        }
        purge(now);
    }

    /**
     * Unconditionally removes used-challenge entries whose expiry is at or before {@code now}.
     * Safe to call from a scheduled task.
     *
     * @param now current instant
     */
    public void purge(Instant now) {
        if (now == null) {
            return;
        }
        for (Map.Entry<String, Instant> entry : challengeExpiry.entrySet()) {
            if (!entry.getValue().isAfter(now)) {
                usedChallenges.remove(entry.getKey());
                challengeExpiry.remove(entry.getKey());
            }
        }
        for (Set<String> ids : challengesByCard.values()) {
            ids.removeIf(id -> !usedChallenges.contains(id));
        }
    }
}
