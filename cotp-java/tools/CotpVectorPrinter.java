package ru.loolzaaa;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

/**
 * Development utility that prints the reference test vectors of COTP v1
 * (HKDF RFC 5869 vector, derived grid key, per-cell indices and the final grid).
 *
 * <p>Run after compiling the library:</p>
 * <pre>java -cp build/classes ru.loolzaaa.CotpVectorPrinter</pre>
 */
public final class CotpVectorPrinter {

    private static final HexFormat HEX = HexFormat.of();

    private CotpVectorPrinter() {
    }

    public static void main(String[] args) {
        printHkdfRfc5869();
        printCotpVector();
    }

    private static void printHkdfRfc5869() {
        byte[] ikm = repeat((byte) 0x0b, 22);
        byte[] salt = hex("000102030405060708090a0b0c");
        byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");
        byte[] prk = Hkdf.extract(salt, ikm);
        byte[] okm = Hkdf.expand(prk, info, 42);
        System.out.println("== RFC 5869 Test Case 1 (SHA-256) ==");
        System.out.println("IKM  = " + HEX.formatHex(ikm));
        System.out.println("salt = " + HEX.formatHex(salt));
        System.out.println("info = " + HEX.formatHex(info));
        System.out.println("PRK  = " + HEX.formatHex(prk));
        System.out.println("OKM  = " + HEX.formatHex(okm));
        System.out.println("EXPECTED PRK = 077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");
        System.out.println("EXPECTED OKM = 3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
        System.out.println();
    }

    private static void printCotpVector() {
        byte[] secret = new byte[32];
        for (int i = 0; i < secret.length; i++) {
            secret[i] = (byte) i;
        }
        String cardId = "COTP-DEMO-0001";
        CotpScheme scheme = CotpScheme.defaults();
        byte[] kGrid = CotpDerivation.gridKey(secret, cardId, scheme);

        System.out.println("== COTP v1 reference vector ==");
        System.out.println("secret (32 bytes) = " + HEX.formatHex(secret));
        System.out.println("secret Base32     = " + Base32.encode(secret));
        System.out.println("cardId            = " + cardId);
        System.out.println("version           = " + scheme.version());
        System.out.println("dictionary        = BIP-39 English (" + scheme.dictionary().size() + " words)");
        System.out.println("K_grid (hex)      = " + HEX.formatHex(kGrid));
        System.out.println();

        System.out.println("-- per-cell first-attempt (nonce=0) index/word --");
        for (Cell cell : scheme.cells()) {
            int idx = CotpDerivation.wordIndex(kGrid, cell, 0, scheme);
            System.out.printf("%-4s idx=%-4d word=%s%n", cell.id(), idx, scheme.dictionary().wordAt(idx));
        }
        System.out.println();

        Map<String, String> grid = CotpDerivation.buildGrid(kGrid, scheme);
        System.out.println("-- final grid (deduplicated, canonical order) --");
        for (Map.Entry<String, String> e : grid.entrySet()) {
            System.out.printf("%-4s = %s%n", e.getKey(), e.getValue());
        }
        System.out.println();
        System.out.println("-- ASCII card --");
        CotpCard card = new CotpCardGenerator(scheme).generate(cardId, CotpSecret.of(secret));
        System.out.println(CotpCardPrinter.render(card));
    }

    private static byte[] repeat(byte b, int n) {
        byte[] out = new byte[n];
        java.util.Arrays.fill(out, b);
        return out;
    }

    private static byte[] hex(String s) {
        return HEX.parseHex(s);
    }
}
