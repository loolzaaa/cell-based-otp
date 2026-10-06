package com.cotp.redis;

import com.cotp.ThrottleStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * {@link ThrottleStore} backed by Redis (Lettuce).
 *
 * <p>Each card is a Redis hash ({@code cotp:throttle:<card>}) with fields {@code failures},
 * {@code locked_until} and {@code last_access}. {@link #incrementFailure(String)} uses the atomic
 * {@code HINCRBY}, so concurrent failures are not lost. {@link #evictIdle(Instant, Duration)} is a
 * no-op (state is explicitly cleared via {@link #reset(String)}).</p>
 */
public final class RedisThrottleStore implements ThrottleStore, AutoCloseable {

    private static final String FIELD_FAILURES = "failures";
    private static final String FIELD_LOCKED_UNTIL = "locked_until";
    private static final String FIELD_LAST_ACCESS = "last_access";

    private final RedisCommands<String, String> commands;
    private final String prefix;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;

    public RedisThrottleStore(RedisCommands<String, String> commands) {
        this(commands, "cotp:");
    }

    public RedisThrottleStore(RedisCommands<String, String> commands, String keyPrefix) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.prefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.client = null;
        this.connection = null;
    }

    private RedisThrottleStore(RedisClient client, StatefulRedisConnection<String, String> connection,
                               String keyPrefix) {
        this.client = client;
        this.connection = connection;
        this.commands = connection.sync();
        this.prefix = keyPrefix;
    }

    public static RedisThrottleStore create(String redisUri) {
        return create(redisUri, "cotp:");
    }

    public static RedisThrottleStore create(String redisUri, String keyPrefix) {
        RedisClient client = RedisClient.create(redisUri);
        return new RedisThrottleStore(client, client.connect(), keyPrefix);
    }

    private String key(String cardId) {
        return prefix + "throttle:" + cardId;
    }

    @Override
    public int failureCount(String cardId) {
        String value = commands.hget(key(cardId), FIELD_FAILURES);
        return value == null ? 0 : Integer.parseInt(value);
    }

    @Override
    public Instant lockedUntil(String cardId) {
        String value = commands.hget(key(cardId), FIELD_LOCKED_UNTIL);
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value));
    }

    @Override
    public void lockUntil(String cardId, Instant until) {
        Objects.requireNonNull(until, "until");
        String k = key(cardId);
        commands.hset(k, FIELD_LOCKED_UNTIL, Long.toString(until.toEpochMilli()));
        commands.hset(k, FIELD_LAST_ACCESS, Long.toString(System.currentTimeMillis()));
    }

    @Override
    public int incrementFailure(String cardId) {
        String k = key(cardId);
        long failures = commands.hincrby(k, FIELD_FAILURES, 1L);
        commands.hset(k, FIELD_LAST_ACCESS, Long.toString(System.currentTimeMillis()));
        return (int) failures;
    }

    @Override
    public void reset(String cardId) {
        commands.del(key(cardId));
    }

    @Override
    public void evictIdle(Instant now, Duration idleTtl) {
        // No-op: state is cleared explicitly via reset(cardId).
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }
}
