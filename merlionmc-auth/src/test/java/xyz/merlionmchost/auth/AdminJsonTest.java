package xyz.merlionmchost.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdminJsonTest {

    @Test
    void oneLineWithoutHashesOrIps() {
        var a = new AccountStore.Account("steve", "Steve", "$argon2id$secret", 1000, 2000, "203.0.113.9");
        String line = AdminJson.page(List.of(a), 1, 1, n -> n.equals("Steve"));
        assertEquals("MMAUTH-JSON {\"v\":1,\"total\":1,\"page\":1,\"pages\":1,\"accounts\":[{\"name\":\"Steve\",\"registered\":1000,\"lastLogin\":2000,\"online\":true}]}", line);
        assertFalse(line.contains("argon2"));
        assertFalse(line.contains("203.0.113.9"));
        assertFalse(line.contains("\n"));
    }

    @Test
    void escapesAndPages() {
        // a"b\c plus a control character
        String input = "a" + '"' + "b" + '\\' + "c" + (char) 1;
        assertEquals("\"a\\\"b\\\\c\\u0001\"", AdminJson.string(input));
        String empty = AdminJson.page(List.of(), 0, 1, n -> false);
        assertTrue(empty.contains("\"pages\":1") && empty.contains("\"accounts\":[]"));
        assertTrue(AdminJson.page(List.of(), 26, 2, n -> false).contains("\"pages\":2"));
    }

    @Test
    void storeListsCountsAndDeletes(@TempDir Path dir) throws Exception {
        try (AccountStore store = new AccountStore(dir.resolve("accounts.db"))) {
            for (int i = 0; i < 30; i++) store.create("Player" + i, "h", "", i);
            assertEquals(30, store.count());
            assertEquals(25, store.list(0, 25).size());
            assertEquals(5, store.list(25, 25).size());
            assertEquals("Player29", store.list(0, 25).get(0).displayName(), "most recently active first");
            assertTrue(store.delete("PLAYER3"));
            assertFalse(store.delete("Player3"), "already gone");
            assertEquals(29, store.count());
            assertTrue(store.find("player3").isEmpty());
        }
    }
}
