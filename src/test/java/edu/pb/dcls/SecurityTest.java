package edu.pb.dcls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the password hashing contract: salted PBKDF2 round-trips and rejects bad input. */
class SecurityTest {

    @Test
    void correctPasswordMatches() {
        Security.Credentials c = Security.credentials("demo1234".toCharArray());
        assertTrue(Security.matches("demo1234".toCharArray(), c.hash(), c.salt()));
    }

    @Test
    void wrongPasswordDoesNotMatch() {
        Security.Credentials c = Security.credentials("demo1234".toCharArray());
        assertFalse(Security.matches("wrongpass".toCharArray(), c.hash(), c.salt()));
    }

    @Test
    void samePasswordGetsDifferentSaltAndHash() {
        Security.Credentials a = Security.credentials("demo1234".toCharArray());
        Security.Credentials b = Security.credentials("demo1234".toCharArray());
        assertNotEquals(a.salt(), b.salt());
        assertNotEquals(a.hash(), b.hash());
    }

    @Test
    void nullHashOrSaltIsRejected() {
        assertFalse(Security.matches("demo1234".toCharArray(), null, "salt"));
        assertFalse(Security.matches("demo1234".toCharArray(), "hash", null));
    }
}
