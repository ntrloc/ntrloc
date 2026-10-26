package org.ntrloc.graph.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.security.SecurityProperties;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    @Test
    void aSecret_decryptsBackToItself_andEncryptsDifferentlyEachTime() {
        SecretCipher cipher = cipherWith(randomKey());

        String first = cipher.encrypt("bind-password");
        String second = cipher.encrypt("bind-password");

        assertThat(first).isNotEqualTo(second).doesNotContain("bind-password");
        assertThat(cipher.decrypt(first)).isEqualTo("bind-password");
        assertThat(cipher.decrypt(second)).isEqualTo("bind-password");
    }

    @Test
    void aDifferentKey_cantDecryptIt() {
        String stored = cipherWith(randomKey()).encrypt("bind-password");

        assertThatThrownBy(() -> cipherWith(randomKey()).decrypt(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAlteredValue_failsToDecrypt() {
        SecretCipher cipher = cipherWith(randomKey());
        byte[] bytes = Base64.getDecoder().decode(cipher.encrypt("bind-password").substring("v1:".length()));
        bytes[bytes.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt("v1:" + Base64.getEncoder().encodeToString(bytes)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withNoKey_nothingCanBeEncrypted() {
        SecretCipher cipher = cipherWith(null);

        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("bind-password")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aKeyOfTheWrongLength_stopsStartup() {
        assertThatThrownBy(() -> cipherWith(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }

    private static SecretCipher cipherWith(String key) {
        SecurityProperties properties = new SecurityProperties();
        properties.setSecretKey(key);
        return new SecretCipher(properties);
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
