package com.roofbot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public class Db {
    private final Path dbPath;

    public Db(Path dbPath) {
        this.dbPath = dbPath;
    }

    public void init() throws SQLException {
        try {
            Path parent = dbPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new SQLException("Failed to create DB directory", e);
        }

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("""
                CREATE TABLE IF NOT EXISTS calc_sessions (
                  user_id INTEGER PRIMARY KEY,
                  step TEXT,
                  q1 TEXT,
                  q2 TEXT,
                  q3 TEXT,
                  name TEXT,
                  phone TEXT,
                  started_at INTEGER,
                  updated_at INTEGER,
                  last_interaction INTEGER,
                  abandoned INTEGER DEFAULT 0,
                  abandoned_stage TEXT,
                  abandoned_at INTEGER,
                  abandoned_notified INTEGER DEFAULT 0,
                  tg_username TEXT,
                  tg_first_name TEXT,
                  tg_last_name TEXT
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS leads (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER,
                  username TEXT,
                  first_name TEXT,
                  last_name TEXT,
                  q1 TEXT,
                  q2 TEXT,
                  q3 TEXT,
                  name TEXT,
                  phone TEXT,
                  created_at INTEGER
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS media_cache (
                  key TEXT PRIMARY KEY,
                  file_id TEXT,
                  updated_at INTEGER
                )
            """);
        }
    }

    private Connection connect() throws SQLException {
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        return DriverManager.getConnection(url);
    }

    public Session getSession(long userId) throws SQLException {
        String sql = "SELECT user_id, step, q1, q2, q3, name, phone, started_at, updated_at, last_interaction, abandoned, abandoned_stage, abandoned_at, abandoned_notified, tg_username, tg_first_name, tg_last_name FROM calc_sessions WHERE user_id = ?";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Session(
                        rs.getLong("user_id"),
                        rs.getString("step"),
                        rs.getString("q1"),
                        rs.getString("q2"),
                        rs.getString("q3"),
                        rs.getString("name"),
                        rs.getString("phone"),
                        rs.getLong("started_at"),
                        rs.getLong("updated_at"),
                        rs.getLong("last_interaction"),
                        rs.getInt("abandoned") == 1,
                        rs.getString("abandoned_stage"),
                        rs.getLong("abandoned_at"),
                        rs.getInt("abandoned_notified") == 1,
                        rs.getString("tg_username"),
                        rs.getString("tg_first_name"),
                        rs.getString("tg_last_name")
                );
            }
        }
    }

    public void createOrResetSession(long userId, String step, UserProfile profile) throws SQLException {
        String sql = """
            INSERT INTO calc_sessions (user_id, step, q1, q2, q3, name, phone, started_at, updated_at, last_interaction, abandoned, abandoned_stage, abandoned_at, abandoned_notified, tg_username, tg_first_name, tg_last_name)
            VALUES (?, ?, NULL, NULL, NULL, NULL, NULL, ?, ?, ?, 0, NULL, NULL, 0, ?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET
              step=excluded.step,
              q1=NULL,
              q2=NULL,
              q3=NULL,
              name=NULL,
              phone=NULL,
              started_at=excluded.started_at,
              updated_at=excluded.updated_at,
              last_interaction=excluded.last_interaction,
              abandoned=0,
              abandoned_stage=NULL,
              abandoned_at=NULL,
              abandoned_notified=0,
              tg_username=excluded.tg_username,
              tg_first_name=excluded.tg_first_name,
              tg_last_name=excluded.tg_last_name
        """;
        long now = Instant.now().toEpochMilli();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, step);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.setString(6, profile.username());
            ps.setString(7, profile.firstName());
            ps.setString(8, profile.lastName());
            ps.executeUpdate();
        }
    }

    public void updateStep(long userId, String step) throws SQLException {
        String sql = "UPDATE calc_sessions SET step = ?, updated_at = ? WHERE user_id = ?";
        long now = Instant.now().toEpochMilli();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, step);
            ps.setLong(2, now);
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
    }

    public void updateAnswer(long userId, String field, String value, String nextStep) throws SQLException {
        String sql = "UPDATE calc_sessions SET " + field + " = ?, step = ?, updated_at = ? WHERE user_id = ?";
        long now = Instant.now().toEpochMilli();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.setString(2, nextStep);
            ps.setLong(3, now);
            ps.setLong(4, userId);
            ps.executeUpdate();
        }
    }

    public void updateName(long userId, String name, String nextStep) throws SQLException {
        updateAnswer(userId, "name", name, nextStep);
    }

    public void updatePhone(long userId, String phone) throws SQLException {
        String sql = "UPDATE calc_sessions SET phone = ?, step = ?, updated_at = ? WHERE user_id = ?";
        long now = Instant.now().toEpochMilli();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, phone);
            ps.setString(2, "DONE");
            ps.setLong(3, now);
            ps.setLong(4, userId);
            ps.executeUpdate();
        }
    }

    public void touchInteraction(long userId, UserProfile profile) throws SQLException {
        String sql = "UPDATE calc_sessions SET last_interaction = ?, tg_username = ?, tg_first_name = ?, tg_last_name = ? WHERE user_id = ?";
        long now = Instant.now().toEpochMilli();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setString(2, profile.username());
            ps.setString(3, profile.firstName());
            ps.setString(4, profile.lastName());
            ps.setLong(5, userId);
            ps.executeUpdate();
        }
    }

    public void markAbandoned(long userId, String stage) throws SQLException {
        String sql = "UPDATE calc_sessions SET abandoned = 1, abandoned_stage = ?, abandoned_at = ?, abandoned_notified = 0, updated_at = ? WHERE user_id = ?";
        long now = Instant.now().toEpochMilli();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, stage);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.setLong(4, userId);
            ps.executeUpdate();
        }
    }

    public void clearAbandoned(long userId) throws SQLException {
        String sql = "UPDATE calc_sessions SET abandoned = 0, abandoned_stage = NULL, abandoned_at = NULL, abandoned_notified = 0 WHERE user_id = ?";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    public List<AbandonedSession> findAbandonedToNotify(long olderThanMillis) throws SQLException {
        String sql = """
            SELECT user_id, abandoned_stage, last_interaction, tg_username, tg_first_name, tg_last_name
            FROM calc_sessions
            WHERE abandoned = 1 AND abandoned_notified = 0 AND last_interaction <= ?
        """;
        List<AbandonedSession> list = new ArrayList<>();
        long threshold = Instant.now().toEpochMilli() - olderThanMillis;
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, threshold);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new AbandonedSession(
                            rs.getLong("user_id"),
                            rs.getString("abandoned_stage"),
                            rs.getLong("last_interaction"),
                            rs.getString("tg_username"),
                            rs.getString("tg_first_name"),
                            rs.getString("tg_last_name")
                    ));
                }
            }
        }
        return list;
    }

    public void markAbandonedNotified(long userId) throws SQLException {
        String sql = "UPDATE calc_sessions SET abandoned_notified = 1 WHERE user_id = ?";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    public void saveLead(UserProfile profile, Session session) throws SQLException {
        String sql = """
            INSERT INTO leads (user_id, username, first_name, last_name, q1, q2, q3, name, phone, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        long now = Instant.now().toEpochMilli();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, profile.userId());
            ps.setString(2, profile.username());
            ps.setString(3, profile.firstName());
            ps.setString(4, profile.lastName());
            ps.setString(5, session.q1());
            ps.setString(6, session.q2());
            ps.setString(7, session.q3());
            ps.setString(8, session.name());
            ps.setString(9, session.phone());
            ps.setLong(10, now);
            ps.executeUpdate();
        }
    }

    public List<Lead> getLastLeads(int limit) throws SQLException {
        String sql = """
            SELECT id, user_id, username, first_name, last_name, q1, q2, q3, name, phone, created_at
            FROM leads
            ORDER BY id DESC
            LIMIT ?
        """;
        List<Lead> leads = new ArrayList<>();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    leads.add(new Lead(
                            rs.getLong("id"),
                            rs.getLong("user_id"),
                            rs.getString("username"),
                            rs.getString("first_name"),
                            rs.getString("last_name"),
                            rs.getString("q1"),
                            rs.getString("q2"),
                            rs.getString("q3"),
                            rs.getString("name"),
                            rs.getString("phone"),
                            rs.getLong("created_at")
                    ));
                }
            }
        }
        return leads;
    }

    public Stats getStats() throws SQLException {
        long total;
        long today;
        try (Connection conn = connect()) {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) AS cnt FROM leads")) {
                total = rs.next() ? rs.getLong("cnt") : 0;
            }

            long startOfDay = LocalDate.now(ZoneId.systemDefault())
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) AS cnt FROM leads WHERE created_at >= ?")) {
                ps.setLong(1, startOfDay);
                try (ResultSet rs = ps.executeQuery()) {
                    today = rs.next() ? rs.getLong("cnt") : 0;
                }
            }
        }
        return new Stats(total, today);
    }

    public String getMediaFileId(String key) throws SQLException {
        String sql = "SELECT file_id FROM media_cache WHERE key = ?";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("file_id");
                }
            }
        }
        return null;
    }

    public void upsertMediaFileId(String key, String fileId) throws SQLException {
        String sql = """
            INSERT INTO media_cache (key, file_id, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET
              file_id=excluded.file_id,
              updated_at=excluded.updated_at
        """;
        long now = Instant.now().toEpochMilli();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, fileId);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }
}
