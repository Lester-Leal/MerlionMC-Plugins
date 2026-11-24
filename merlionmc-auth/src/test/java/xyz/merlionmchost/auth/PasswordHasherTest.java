package xyz.merlionmchost.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {

    @Test
    void rightPasswordMatchesWrongOneDoesNot() {
        String stored = PasswordHasher.hash("kopi-craft-2026");
        assertTrue(PasswordHasher.verify("kopi-craft-2026", stored));
        assertFalse(PasswordHasher.verify("kopi-craft-2027", stored));
        assertFalse(PasswordHasher.verify("", stored));
    }

    @Test
    void storedAsArgon2idWithItsParameters() {
        String stored = PasswordHasher.hash("password123");
        assertTrue(stored.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"), stored);
        assertFalse(stored.contains("password123"));
    }

    @Test
    void samePasswordGetsADifferentSaltEachTime() {
        assertNotEquals(PasswordHasher.hash("same-password"), PasswordHasher.hash("same-password"));
    }

    @Test
    void unicodePasswordsWork() {
        String stored = PasswordHasher.hash("mahal-kita-ñ-🙂");
        assertTrue(PasswordHasher.verify("mahal-kita-ñ-🙂", stored));
        assertFalse(PasswordHasher.verify("mahal-kita-n-🙂", stored));
    }

    @Test
    void malformedOrTamperedValuesNeverMatchAndNeverThrow() {
        String good = PasswordHasher.hash("password123");
        assertFalse(PasswordHasher.verify("password123", ""));
        assertFalse(PasswordHasher.verify("password123", "not a hash"));
        assertFalse(PasswordHasher.verify("password123", "$2a$12$bcryptstylevalue"));
        assertFalse(PasswordHasher.verify("password123", good.replace("m=19456", "m=99999999")), "absurd memory is refused");
        assertFalse(PasswordHasher.verify("password123", good.replace("$argon2id$", "$argon2i$")));
        assertFalse(PasswordHasher.verify("password123", good.substring(0, good.length() - 4) + "AAAA"), "changed hash");
    }
}
