package com.roofbot;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class BotConfig {
    private final String token;
    private final String username;
    private final Set<Long> adminIds;
    private final Path dbPath;
    private final Path photoPath;

    public BotConfig(String token, String username, Set<Long> adminIds, Path dbPath, Path photoPath) {
        this.token = token;
        this.username = username;
        this.adminIds = adminIds;
        this.dbPath = dbPath;
        this.photoPath = photoPath;
    }

    public String token() {
        return token;
    }

    public String username() {
        return username;
    }

    public Set<Long> adminIds() {
        return adminIds;
    }

    public boolean isAdmin(long userId) {
        return adminIds.contains(userId);
    }

    public Path dbPath() {
        return dbPath;
    }

    public Path photoPath() {
        return photoPath;
    }

    public static BotConfig fromEnv() {
        String token = getenvOrThrow("BOT_TOKEN");
        String username = getenvOrThrow("BOT_USERNAME");
        String adminIdsRaw = System.getenv("ADMIN_IDS");
        Set<Long> adminIds = new HashSet<>();
        if (adminIdsRaw != null && !adminIdsRaw.isBlank()) {
            adminIds = Arrays.stream(adminIdsRaw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .collect(Collectors.toSet());
        }
        String dbPathRaw = System.getenv().getOrDefault("DB_PATH", "./data/bot.db");
        String photoPathRaw = "./1.jpg";
        return new BotConfig(token, username, adminIds, Path.of(dbPathRaw), Path.of(photoPathRaw));
    }

    private static String getenvOrThrow(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + key);
        }
        return value;
    }
}
