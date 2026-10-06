package ru.loolzaaa;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Local {@link SecretCipher} based on AES-256-GCM (JDK only).
 *
 * <p>Each {@link #encrypt(byte[])} call draws a fresh random 96-bit IV which is prepended to the
 * ciphertext; authentication tag length is 128 bits. The output layout is
 * {@code IV (12 bytes) || ciphertext+tag}. This is a compact, dependency-free default; in
 * production the AES key should itself be protected by a KMS/HSM (injected at start-up), or the
 * whole {@link SecretCipher} replaced by a KMS-backed implementation.</p>
 */
public final class AesGcmSecretCipher implements SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param key AES key of 16, 24 or 32 bytes
     */
    public AesGcmSecretCipher(byte[] key) {
        if (key == null || (key.length != 16 && key.length != 24 && key.length != 32)) {
            throw new IllegalArgumentException("AES key must be 16, 24 or 32 bytes, got "
                    + (key == null ? "null" : key.length));
        }
        this.key = key.clone();
    }

    /** @return a cipher with a fresh random 256-bit key (for tests / ephemeral use). */
    public static AesGcmSecretCipher generate() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return new AesGcmSecretCipher(k);
    }

    /** @return a defensive copy of the raw key (for wrapping/rotating it in a KMS). */
    public byte[] keyBytes() {
        return key.clone();
    }

    @Override
    public byte[] encrypt(byte[] plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext);
            byte[] out = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(sealed, 0, out, IV_BYTES, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new CotpCryptoException("AES-GCM encryption failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) {
        // A valid value is IV (12 bytes) || ciphertext || tag (16 bytes); reject anything shorter
        // deterministically (some JDK providers throw ProviderException/ShortBufferException for a
        // truncated input instead of a checked GeneralSecurityException).
        if (ciphertext == null || ciphertext.length < IV_BYTES + TAG_BYTES) {
            throw new CotpCryptoException("Invalid AES-GCM ciphertext");
        }
        try {
            byte[] iv = Arrays.copyOfRange(ciphertext, 0, IV_BYTES);
            byte[] sealed = Arrays.copyOfRange(ciphertext, IV_BYTES, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(sealed);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new CotpCryptoException("AES-GCM decryption failed (wrong key or tampered ciphertext)", e);
        }
    }
}
