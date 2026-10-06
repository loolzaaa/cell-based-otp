import com.cotp.AesGcmSecretCipher;
import com.cotp.ClientError;
import com.cotp.Cotp;
import com.cotp.CotpCard;
import com.cotp.CotpCardPrinter;
import com.cotp.CotpChallenge;
import com.cotp.CotpCredential;
import com.cotp.CotpCredentialCodec;
import com.cotp.CotpEnrollment;
import com.cotp.CotpPolicyResolver;
import com.cotp.CotpScheme;
import com.cotp.SchemeRegistry;
import com.cotp.VerificationResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Self-contained, compilable COTP integration example.
 *
 * <p>It exercises the full server-side lifecycle with the real library:</p>
 * <ol>
 *   <li>{@code new Cotp(scheme, resolver)} — default 5x5/BIP-39 scheme and per-context policies;</li>
 *   <li>{@code enroll(...)} — generate a card, print it, obtain a PIN hash + credential;</li>
 *   <li>seal the credential with {@link AesGcmSecretCipher} and unseal it again (persistence);</li>
 *   <li>{@code challenge(card, "kiosk_login", now)} — issue a cell;</li>
 *   <li>{@code authenticate(...)} — verify the printed word + PIN;</li>
 *   <li>{@link VerificationResult#toClientError()} — the coarse, anti-oracle client outcome;</li>
 *   <li>a wrong-word attempt to show that factor-specific failures collapse to one generic error.</li>
 * </ol>
 *
 * <p>Build/run (JDK 17+; the default scheme loads the bundled dictionary from the classpath).
 * Note {@code Select-Object -ExpandProperty FullName}: on Windows PowerShell 5.1 a bare
 * {@code FileInfo} argument stringifies to the file name only:</p>
 * <pre>
 * javac -encoding UTF-8 -d out (Get-ChildItem -Recurse cotp-java\src\main\java -Filter *.java ^
 *     | Where-Object Name -ne 'module-info.java' | Select-Object -ExpandProperty FullName) ^
 *     examples\IntegrationExample.java
 * java -cp "out;cotp-java\src\main\resources" IntegrationExample
 * </pre>
 */
public final class IntegrationExample {

    private IntegrationExample() {
    }

    public static void main(String[] args) {
        // 1. Facade: default scheme (5x5, BIP-39, HMAC-SHA-256) and the recommended
        //    per-context policies (kiosk_login -> COOLDOWN 24h, stepup -> CHALLENGE 60s,
        //    service_desk -> CELL, default -> CELL).
        Cotp cotp = new Cotp(CotpScheme.defaults(), CotpPolicyResolver.defaults());

        // 2. Enrolment. The PIN is never stored; only its PBKDF2-HMAC-SHA256 hash is returned.
        char[] pin = "1234".toCharArray();
        CotpEnrollment enrollment = cotp.enroll("COTP-DEMO-0001", pin);
        System.out.println("Enrolled: " + enrollment.card());
        System.out.print(CotpCardPrinter.render(enrollment.card()));

        // 3. Persist the credential (it contains the raw card_secret) encrypted at rest.
        AesGcmSecretCipher cipher = AesGcmSecretCipher.generate();
        String encodedCredential = enrollment.credentialEncoded();          // COTP1|...
        byte[] sealedBytes = cipher.encrypt(encodedCredential.getBytes(StandardCharsets.UTF_8));
        String storedSecret = Base64.getEncoder().encodeToString(sealedBytes);
        System.out.println("Stored (encrypted) credential length=" + storedSecret.length()
                + " chars; pinHash saved separately.");

        // 4. Later request / another node: unseal and deterministically rebuild the card.
        byte[] openedBytes = Base64.getDecoder().decode(storedSecret);
        CotpCredential credential = CotpCredentialCodec.decode(
                new String(cipher.decrypt(openedBytes), StandardCharsets.UTF_8));
        CotpCard restoredCard = credential.toCard(SchemeRegistry.defaults());
        System.out.println("Restored: " + restoredCard
                + "; scheme=" + credential.schemeId());

        // 5. Challenge + correct answer. The user reads the word printed at the challenged cell.
        Instant now = Instant.now();
        CotpChallenge challenge = cotp.challenge(restoredCard, "kiosk_login", now);
        String word = restoredCard.wordAt(challenge.cell());
        VerificationResult result = cotp.authenticate(
                restoredCard, enrollment.pinHash(), challenge, word, pin, now);
        ClientError client = result.toClientError();
        System.out.println("Challenge: cell=" + challenge.cellId()
                + " mode=" + challenge.mode() + " expiresAt=" + challenge.expiresAt());
        System.out.println("Verify (correct): result=" + result
                + " client=" + client + " success=" + client.isSuccess());

        // 6. Anti-oracle: a wrong word produces the same generic ClientError as a wrong PIN would.
        CotpChallenge second = cotp.challenge(restoredCard, "kiosk_login", now);
        VerificationResult bad = cotp.authenticate(
                restoredCard, enrollment.pinHash(), second, "definitely-not-the-word", pin, now);
        System.out.println("Verify (wrong word): result=" + bad
                + " client=" + bad.toClientError());

        String accept = client == ClientError.NONE ? "OK" : "UNEXPECTED";
        System.out.println("FLOW: " + accept);
    }
}
