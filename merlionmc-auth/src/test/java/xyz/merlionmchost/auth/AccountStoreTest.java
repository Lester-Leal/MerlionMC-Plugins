package xyz.merlionmchost.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AccountStoreTest {

    @Test
    void namesAreCaseInsensitiveAndRegisteredOnce(@TempDir Path dir) throws Exception {
        try (AccountStore store = new AccountStore(dir.resolve("accounts.db"))) {
            assertTrue(store.create("Steve", "hash-1", "1.2.3.4", 1000));
            assertFalse(store.create("steve", "hash-2", "5.6.7.8", 2000), "same name, other case: refused");
            assertFalse(store.create("STEVE", "hash-3", "5.6.7.8", 3000));

            AccountStore.Account a = store.find("sTeVe").orElseThrow();
            assertEquals("steve", a.nameKey());
            assertEquals("Steve", a.displayName(), "keeps the name as first registered");
            assertEquals("hash-1", a.hash(), "the first registration wins");
            assertTrue(store.find("Alex").isEmpty());
        }
    }

    @Test
    void recordsLoginsAndSurvivesReopening(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("accounts.db");
        try (AccountStore store = new AccountStore(file)) {
            store.create("Juan", "h", "1.1.1.1", 1000);
            store.recordLogin("JUAN", "2.2.2.2", 5000);
        }
        try (AccountStore store = new AccountStore(file)) {
            AccountStore.Account a = store.find("juan").orElseThrow();
            assertEquals(5000, a.lastLoginAt());
            assertEquals("2.2.2.2", a.lastIp());
            assertEquals(1000, a.registeredAt());
        }
    }

    @Test
    void namesWithQuotesCantBreakTheQuery(@TempDir Path dir) throws Exception {
        try (AccountStore store = new AccountStore(dir.resolve("accounts.db"))) {
            assertTrue(store.find("x' OR '1'='1").isEmpty());
            store.create("Real", "h", "", 1);
            assertTrue(store.find("x' OR '1'='1").isEmpty(), "parameterized queries: no injection");
        }
    }

    @Test
    void changingThePasswordReplacesTheHash(@TempDir Path dir) throws Exception {
        try (AccountStore store = new AccountStore(dir.resolve("accounts.db"))) {
            store.create("Maria", "old-hash", "", 1);
            store.updateHash("MARIA", "new-hash");
            assertEquals("new-hash", store.find("maria").orElseThrow().hash());
        }
    }
}
