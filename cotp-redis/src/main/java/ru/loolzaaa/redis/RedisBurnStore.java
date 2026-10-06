package ru.loolzaaa.redis;

import ru.loolzaaa.BurnStore;
import ru.loolzaaa.CotpException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@link BurnStore} backed by Redis (Lettuce).
 *
 * <p>Atomicity relies on {@code SET ... NX}: a failed set means the cell/challenge was already
 * consumed. Challenge keys carry a native Redis TTL, so {@link #evictExpired(Instant)} is a no-op.
 * State is grouped by card for {@link #reset(String)}. Driver exceptions are wrapped as
 * {@link CotpException}.</p>
 *
 * <p>Key layout (default prefix {@code cotp:}; dynamic components are URL-encoded, so identifiers
 * containing ':' cannot collide):</p>
 * <pre>
 * burn:&lt;card&gt;:&lt;cell&gt;     -> "1"        (SET NX; burned cell)
 * burn:&lt;card&gt;             -> set&lt;cell&gt;  (index for burnedCells/reset)
 * use:&lt;card&gt;:&lt;cell&gt;      -> epochMilli (last use, COOLDOWN)
 * use:&lt;card&gt;              -> set&lt;cell&gt;  (index for reset)
 * ch:&lt;challengeId&gt;        -> &lt;card&gt;     (SET NX PX; used challenge)
 * ch:&lt;card&gt;               -> zset&lt;challengeId&gt; scored by expiry (index for reset; trimmed)
 * </pre>
 */
public final class RedisBurnStore implements BurnStore, AutoCloseable {

    private final RedisCommands<String, String> commands;
    private final String prefix;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;

    /**
     * @param commands injected synchronous commands (caller owns the connection)
     */
    public RedisBurnStore(RedisCommands<String, String> commands) {
        this(commands, "cotp:");
    }

    /**
     * @param commands injected synchronous commands
     * @param keyPrefix Redis key prefix (for isolation/tests)
     */
    public RedisBurnStore(RedisCommands<String, String> commands, String keyPrefix) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.prefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.client = null;
        this.connection = null;
    }

    private RedisBurnStore(RedisClient client, StatefulRedisConnection<String, String> connection,
                           String keyPrefix) {
        this.client = client;
        this.connection = connection;
        this.commands = connection.sync();
        this.prefix = keyPrefix;
    }

    /**
     * @param redisUri Redis URI, e.g. {@code redis://localhost:6379}
     * @return a store owning its connection (call {@link #close()} when done)
     */
    public static RedisBurnStore create(String redisUri) {
        return create(redisUri, "cotp:");
    }

    /**
     * @param redisUri  Redis URI
     * @param keyPrefix Redis key prefix (use a unique value for isolated tests)
     * @return a store owning its connection (call {@link #close()} when done)
     */
    public static RedisBurnStore create(String redisUri, String keyPrefix) {
        RedisClient client = RedisClient.create(redisUri);
        try {
            return new RedisBurnStore(client, client.connect(), keyPrefix);
        } catch (RuntimeException e) {
            try {
                client.shutdown();
            } catch (RuntimeException ignored) {
                // best effort
            }
            throw new CotpException("Failed to connect to Redis", e);
        }
    }

    // ---- keys ----
    // Each dynamic component is URL-encoded so that identifiers containing the ':' separator (for
    // example a cardId like "A:B") cannot collide with another key (e.g. card "A", cell "B").
    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String burnKey(String cardId, String cellId) {
        return prefix + "burn:" + enc(cardId) + ":" + enc(cellId);
    }

    private String burnSet(String cardId) {
        return prefix + "burn:" + enc(cardId);
    }

    private String useKey(String cardId, String cellId) {
        return prefix + "use:" + enc(cardId) + ":" + enc(cellId);
    }

    private String useSet(String cardId) {
        return prefix + "use:" + enc(cardId);
    }

    private String chKey(String challengeId) {
        return prefix + "ch:" + enc(challengeId);
    }

    private String chSet(String cardId) {
        return prefix + "ch:" + enc(cardId);
    }

    private static <T> T guard(String op, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            throw new CotpException("Redis operation failed: " + op, e);
        }
    }

    private static void guardVoid(String op, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            throw new CotpException("Redis operation failed: " + op, e);
        }
    }

    @Override
    public boolean isCellBurned(String cardId, String cellId) {
        return guard("isCellBurned", () -> commands.exists(burnKey(cardId, cellId)) > 0);
    }

    @Override
    public boolean markCellBurned(String cardId, String cellId) {
        return guard("markCellBurned", () -> {
            String result = commands.set(burnKey(cardId, cellId), "1", SetArgs.Builder.nx());
            if ("OK".equals(result)) {
                commands.sadd(burnSet(cardId), cellId);
                return true;
            }
            return false;
        });
    }

    @Override
    public Instant lastCellUse(String cardId, String cellId) {
        return guard("lastCellUse", () -> {
            String value = commands.get(useKey(cardId, cellId));
            return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value));
        });
    }

    @Override
    public void recordCellUse(String cardId, String cellId, Instant at) {
        Objects.requireNonNull(at, "at");
        guardVoid("recordCellUse", () -> {
            commands.set(useKey(cardId, cellId), Long.toString(at.toEpochMilli()));
            commands.sadd(useSet(cardId), cellId);
        });
    }

    @Override
    public boolean isChallengeUsed(String challengeId) {
        return guard("isChallengeUsed", () -> commands.exists(chKey(challengeId)) > 0);
    }

    @Override
    public boolean markChallengeUsed(String cardId, String challengeId, Instant expiresAt) {
        Objects.requireNonNull(expiresAt, "expiresAt");
        return guard("markChallengeUsed", () -> {
            long nowMillis = System.currentTimeMillis();
            long expiryMillis = expiresAt.toEpochMilli();
            long ttlMillis = Math.max(1L, expiryMillis - nowMillis);
            String result = commands.set(chKey(challengeId), cardId, SetArgs.Builder.nx().px(ttlMillis));
            if ("OK".equals(result)) {
                // Index challenges as a ZSET scored by expiry: trim already-expired members so it
                // cannot grow without bound, and let the whole index live until the latest
                // challenge expires. The index TTL must never shrink (a later, shorter-TTL
                // challenge would otherwise drop the index while a longer challenge key lives on).
                commands.zadd(chSet(cardId), (double) expiryMillis, challengeId);
                commands.zremrangebyscore(chSet(cardId), Double.NEGATIVE_INFINITY, (double) nowMillis);
                Long currentTtl = commands.pttl(chSet(cardId));
                long effectiveTtl = (currentTtl == null || currentTtl < 0) ? ttlMillis : Math.max(currentTtl, ttlMillis);
                commands.pexpire(chSet(cardId), effectiveTtl);
                return true;
            }
            return false;
        });
    }

    @Override
    public Set<String> burnedCells(String cardId) {
        return guard("burnedCells", () -> Set.copyOf(new HashSet<>(commands.smembers(burnSet(cardId)))));
    }

    @Override
    public void reset(String cardId) {
        guardVoid("reset", () -> {
            Set<String> burned = commands.smembers(burnSet(cardId));
            for (String cellId : burned) {
                commands.del(burnKey(cardId, cellId));
            }
            if (!burned.isEmpty()) {
                commands.del(burnSet(cardId));
            }
            Set<String> uses = commands.smembers(useSet(cardId));
            for (String cellId : uses) {
                commands.del(useKey(cardId, cellId));
            }
            if (!uses.isEmpty()) {
                commands.del(useSet(cardId));
            }
            Set<String> challenges = new HashSet<>(commands.zrange(chSet(cardId), 0, -1));
            for (String challengeId : challenges) {
                commands.del(chKey(challengeId));
            }
            if (!challenges.isEmpty()) {
                commands.del(chSet(cardId));
            }
        });
    }

    @Override
    public void evictExpired(Instant now) {
        // Challenge keys expire via native Redis TTL.
    }

    @Override
    public void close() {
        guardVoid("close", () -> {
            if (connection != null) {
                connection.close();
            }
            if (client != null) {
                client.shutdown();
            }
        });
    }
}
