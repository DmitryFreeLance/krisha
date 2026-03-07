package com.roofbot;

public record AbandonedSession(
        long userId,
        String stage,
        long lastInteraction,
        String tgUsername,
        String tgFirstName,
        String tgLastName
) {
}
