package com.roofbot;

public record Session(
        long userId,
        String step,
        String q1,
        String q2,
        String q3,
        String contactMethod,
        String name,
        String phone,
        String email,
        long startedAt,
        long updatedAt,
        long lastInteraction,
        boolean abandoned,
        String abandonedStage,
        long abandonedAt,
        boolean abandonedNotified,
        String tgUsername,
        String tgFirstName,
        String tgLastName
) {
}
