package xyz.merlionmchost.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OwnerCodesTest {

    @Test
    void codesAreReadableRandomAndForgivingToType() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String c = OwnerCodes.generate();
            assertTrue(c.matches("[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}"), c);
            seen.add(c);
        }
        assertEquals(1000, seen.size(), "no repeats in 1000");
        assertEquals(OwnerCodes.hash("ABCD-2345"), OwnerCodes.hash(" abcd 2345 "));
        assertNotEquals(OwnerCodes.hash("ABCD-2345"), OwnerCodes.hash("ABCD-2346"));
    }

    @Test
    void whoNeedsACode() {
        long installed = 1_000_000;
        assertTrue(OwnerCodes.needsCode(true, false, 0, installed), "a pending code (after a reset) always");
        assertTrue(OwnerCodes.needsCode(false, true, 500_000, installed), "played here before the plugin");
        assertFalse(OwnerCodes.needsCode(false, true, 2_000_000, installed), "first joined after the plugin");
        assertFalse(OwnerCodes.needsCode(false, false, 0, installed), "brand new player");
    }

    @Test
    void registeringWithACodeUsesItUpOnce(@TempDir Path dir) throws Exception {
        try (AccountStore store = new AccountStore(dir.resolve("accounts.db"))) {
            assertTrue(store.installedAt() > 0);
            store.putCode("Steve", OwnerCodes.hash("ABCD-2345"), 10_000);
            assertFalse(store.createWithCode("Steve", OwnerCodes.hash("ZZZZ-9999"), "h", "", 5_000), "wrong code");
            assertTrue(store.find("steve").isEmpty(), "and nothing was created");
            assertFalse(store.createWithCode("Steve", OwnerCodes.hash("ABCD-2345"), "h", "", 20_000), "expired");
            store.putCode("Steve", OwnerCodes.hash("ABCD-2345"), 10_000);
            assertTrue(store.createWithCode("Steve", OwnerCodes.hash("ABCD-2345"), "h", "", 5_000));
            assertTrue(store.find("steve").isPresent());
            assertTrue(store.findCode("steve").isEmpty(), "the code is used up");
            assertFalse(store.createWithCode("Steve", OwnerCodes.hash("ABCD-2345"), "h2", "", 6_000), "can't be used twice");
        }
    }
}
