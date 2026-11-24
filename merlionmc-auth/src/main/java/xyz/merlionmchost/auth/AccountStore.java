package xyz.merlionmchost.auth;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Optional;

/**
 * Accounts in plugins/MerlionMCAuth/accounts.db (SQLite; Paper ships the driver). It lives in the server's own folder,
 * so MerlionMC's normal backups include it. One connection, synchronized: calls come from async tasks, rarely at once.
 */
public final class AccountStore implements AutoCloseable {
    public record Account(String nameKey, String displayName, String hash, long registeredAt, long lastLoginAt, String lastIp) {}

    private final Connection db;

    public AccountStore(Path file) throws SQLException {
        db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                  name_key      TEXT PRIMARY KEY,
                  display_name  TEXT NOT NULL,
                  hash          TEXT NOT NULL,
                  registered_at INTEGER NOT NULL,
                  last_login_at INTEGER NOT NULL DEFAULT 0,
                  last_ip       TEXT NOT NULL DEFAULT ''
                )""");
            // One-time codes from the owner (OwnerCodes): needed to register a name after a reset, or a name that
            // played here before the plugin was installed. Only a hash is kept.
            s.execute("""
                CREATE TABLE IF NOT EXISTS codes (
                  name_key   TEXT PRIMARY KEY,
                  code_hash  TEXT NOT NULL,
                  expires_at INTEGER NOT NULL
                )""");
            s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            // The first time this database is opened: when the plugin started protecting names on this server.
            s.execute("INSERT OR IGNORE INTO meta (key, value) VALUES ('installed_at', CAST(strftime('%s','now') AS INTEGER) * 1000)");
        }
    }

    /** When MerlionMCAuth was installed here (ms). Players who first joined before then need an owner's code. */
    public synchronized long installedAt() throws SQLException {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT value FROM meta WHERE key = 'installed_at'")) {
            return r.next() ? Long.parseLong(r.getString(1)) : 0;
        }
    }

    /** Stores a code for a name (replacing any earlier one). */
    public synchronized void putCode(String name, String codeHash, long expiresAt) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("INSERT OR REPLACE INTO codes (name_key, code_hash, expires_at) VALUES (?, ?, ?)")) {
            q.setString(1, key(name));
            q.setString(2, codeHash);
            q.setLong(3, expiresAt);
            q.executeUpdate();
        }
    }

    public record Code(String hash, long expiresAt) {}

    public synchronized Optional<Code> findCode(String name) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("SELECT code_hash, expires_at FROM codes WHERE name_key = ?")) {
            q.setString(1, key(name));
            try (ResultSet r = q.executeQuery()) {
                return r.next() ? Optional.of(new Code(r.getString(1), r.getLong(2))) : Optional.empty();
            }
        }
    }

    /**
     * Registers with an owner's code in one step: the code is used up and the account created together, or neither
     * (two people racing with the same code can't both get the name). False if the code or name was taken meanwhile.
     */
    public synchronized boolean createWithCode(String name, String codeHash, String hash, String ip, long now) throws SQLException {
        db.setAutoCommit(false);
        try {
            int used;
            try (PreparedStatement q = db.prepareStatement("DELETE FROM codes WHERE name_key = ? AND code_hash = ? AND expires_at > ?")) {
                q.setString(1, key(name));
                q.setString(2, codeHash);
                q.setLong(3, now);
                used = q.executeUpdate();
            }
            if (used != 1 || !create(name, hash, ip, now)) {
                db.rollback();
                return false;
            }
            db.commit();
            return true;
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /** Accounts are keyed by the lower-cased name: "Steve" and "steve" are one account. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public synchronized Optional<Account> find(String name) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("SELECT * FROM accounts WHERE name_key = ?")) {
            q.setString(1, key(name));
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new Account(r.getString("name_key"), r.getString("display_name"), r.getString("hash"),
                        r.getLong("registered_at"), r.getLong("last_login_at"), r.getString("last_ip")));
            }
        }
    }

    /** Creates the account; false if the name (in any case) is already registered. */
    public synchronized boolean create(String name, String hash, String ip, long now) throws SQLException {
        try (PreparedStatement q = db.prepareStatement(
                "INSERT OR IGNORE INTO accounts (name_key, display_name, hash, registered_at, last_login_at, last_ip) VALUES (?, ?, ?, ?, ?, ?)")) {
            q.setString(1, key(name));
            q.setString(2, name);
            q.setString(3, hash);
            q.setLong(4, now);
            q.setLong(5, now);
            q.setString(6, ip);
            return q.executeUpdate() == 1;
        }
    }

    public synchronized void recordLogin(String name, String ip, long now) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("UPDATE accounts SET last_login_at = ?, last_ip = ? WHERE name_key = ?")) {
            q.setLong(1, now);
            q.setString(2, ip);
            q.setString(3, key(name));
            q.executeUpdate();
        }
    }

    public synchronized int count() throws SQLException {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM accounts")) {
            return r.next() ? r.getInt(1) : 0;
        }
    }

    /** A page of accounts, most recently active first. */
    public synchronized java.util.List<Account> list(int offset, int limit) throws SQLException {
        java.util.List<Account> out = new java.util.ArrayList<>();
        try (PreparedStatement q = db.prepareStatement("SELECT * FROM accounts ORDER BY last_login_at DESC, name_key LIMIT ? OFFSET ?")) {
            q.setInt(1, limit);
            q.setInt(2, offset);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) {
                    out.add(new Account(r.getString("name_key"), r.getString("display_name"), r.getString("hash"),
                            r.getLong("registered_at"), r.getLong("last_login_at"), r.getString("last_ip")));
                }
            }
        }
        return out;
    }

    /** Deletes the account so the player registers again on their next join. False if there was none. */
    public synchronized boolean delete(String name) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("DELETE FROM accounts WHERE name_key = ?")) {
            q.setString(1, key(name));
            return q.executeUpdate() == 1;
        }
    }

    public synchronized void updateHash(String name, String hash) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("UPDATE accounts SET hash = ? WHERE name_key = ?")) {
            q.setString(1, hash);
            q.setString(2, key(name));
            q.executeUpdate();
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        db.close();
    }
}
