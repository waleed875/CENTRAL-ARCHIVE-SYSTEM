package com.centralArchiveSystem.Archive.import_.migration.setting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsCryptoTest {

    private static final String SECRET = "test-encryption-key-value";

    @Test
    void roundTripsAValue() {
        SettingsCrypto crypto = new SettingsCrypto(SECRET);

        String envelope = crypto.encrypt("LEGACY_DEFAULT");

        assertNotNull(envelope);
        assertTrue(envelope.startsWith("v1:"), "envelope must be versioned: " + envelope);
        assertFalse(envelope.contains("LEGACY_DEFAULT"), "clear text must not survive into the envelope");
        assertEquals("LEGACY_DEFAULT", crypto.decrypt(envelope));
    }

    @Test
    void nullAndBlankStayNullSoNoValueIsRepresentable() {
        SettingsCrypto crypto = new SettingsCrypto(SECRET);

        assertNull(crypto.encrypt(null));
        assertNull(crypto.encrypt("   "));
        assertNull(crypto.decrypt(null));
        assertNull(crypto.decrypt(""));
    }

    @Test
    void usesAFreshIvPerEncryptSoTheCiphertextIsNotRepeatable() {
        SettingsCrypto crypto = new SettingsCrypto(SECRET);

        String first = crypto.encrypt("LEGACY_DEFAULT");
        String second = crypto.encrypt("LEGACY_DEFAULT");

        assertNotEquals(first, second, "two encryptions of the same value must not be identical");
        assertEquals(crypto.decrypt(first), crypto.decrypt(second));
    }

    @Test
    void aDifferentKeyCannotDecrypt() {
        String envelope = new SettingsCrypto(SECRET).encrypt("LEGACY_DEFAULT");

        SettingsCrypto other = new SettingsCrypto("some-other-key");

        assertThrows(IllegalStateException.class, () -> other.decrypt(envelope));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        SettingsCrypto crypto = new SettingsCrypto(SECRET);
        String envelope = crypto.encrypt("LEGACY_DEFAULT");
        String[] parts = envelope.split(":");

        // Flip a character in the sealed payload; GCM must notice.
        char[] payload = parts[2].toCharArray();
        payload[0] = payload[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + ":" + parts[1] + ":" + new String(payload);

        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered));
    }

    @Test
    void rejectsAnUnknownEnvelopeVersion() {
        assertThrows(IllegalStateException.class,
                () -> new SettingsCrypto(SECRET).decrypt("v9:aXY=:cGF5bG9hZA=="));
        assertThrows(IllegalStateException.class,
                () -> new SettingsCrypto(SECRET).decrypt("not-an-envelope"));
    }
}
