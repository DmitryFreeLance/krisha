package com.roofbot;

public record Lead(
        long id,
        long userId,
        String username,
        String firstName,
        String lastName,
        String q1,
        String q2,
        String q3,
        String name,
        String phone,
        long createdAt
) {
}
