# COTP PDF

Optional module that renders a [`CotpCard`](../cotp-java) into a **printable PDF**
(badge / credit-card style). It is one half of TODO `[U-C]` (QR + PDF template); Apple Wallet
passes are intentionally out of scope.

The page contains:

* the 5×5 word grid with column headers `A`–`E` and row numbers `1`–`5`, supporting **both**
  the single-word and the TOP/BOTTOM word-pair layouts;
* a **QR code** encoding the public `cardId` (not a secret);
* card metadata: scheme version, generation date, expiry date (if any);
* a short usage instruction ("find cell X, read the word / TOP / BOTTOM, enter it with your PIN").

Only public data is printed: the card id, non-secret metadata and the already-printed grid words.
The `CotpSecret` and any PII are never written to the PDF.

The zero-dependency `cotp-java` core stays untouched: Apache PDFBox and ZXing (both Apache-2.0)
live only in this module. No iText (AGPL) and no LGPL libraries are used.

## Usage

```java
import com.cotp.Cotp;
import com.cotp.CotpCard;
import com.cotp.pdf.CotpCardPdf;

Cotp cotp = new Cotp();
CotpCard card = cotp.generateCard("CARD-42");

CotpCardPdf pdf = new CotpCardPdf();
byte[] bytes = pdf.render(card);            // render in memory

// or stream directly:
try (OutputStream out = Files.newOutputStream(Path.of("card.pdf"))) {
    pdf.write(card, out);
}
```

Customise geometry or the title via `CotpPdfOptions`:

```java
CotpPdfOptions options = CotpPdfOptions.builder()
        .title("ACME Access Card")
        .pageWidth(300f)     // PDF user units (1/72 inch)
        .pageHeight(420f)
        .build();
byte[] bytes = new CotpCardPdf(options).render(card);
```

## Generate a sample PDF

`SampleCardPdf` renders a **deterministic** card (fixed 32-byte secret, fixed card id and fixed
validity dates) to a real PDF file, so the grid is reproducible. The easiest way is the helper
script, which installs the core if needed, builds this module, resolves the classpath and runs the
main class:

```powershell
# default: single-word layout -> cotp-pdf\build\sample-card.pdf
powershell -ExecutionPolicy Bypass -File cotp-pdf\render-sample.ps1

# the TOP/BOTTOM (Avatier) word-pair layout
powershell -ExecutionPolicy Bypass -File cotp-pdf\render-sample.ps1 -Layout WORD_PAIRS

# custom card id / output path / optional PIN note printed as the title
powershell -ExecutionPolicy Bypass -File cotp-pdf\render-sample.ps1 -CardId CARD-42 -Output .\card.pdf
powershell -ExecutionPolicy Bypass -File cotp-pdf\render-sample.ps1 -PinNote "Read the word, then your PIN"
```

The script prints the absolute path, byte size, page count and `%PDF-` header. Open the produced
file with any PDF reader (double-click, or `Start-Process .\cotp-pdf\build\sample-card.pdf`).

Run the main class directly after `dependency:build-classpath`:

```powershell
java -cp "cotp-pdf\target\classes;<deps>" com.cotp.pdf.SampleCardPdf `
    cotp-pdf\build\sample-card.pdf WORD_PAIRS COTP-DEMO-0001
```

Arguments are positional and optional: `[outputPath] [SINGLE|WORD_PAIRS] [cardId] [pinNote]`.
The PIN note is printed as the card title only for visual testing — never pass the real PIN.

## Public API

```java
package com.cotp.pdf;

public final class CotpCardPdf {
    public CotpCardPdf();
    public CotpCardPdf(CotpPdfOptions options);
    public CotpPdfOptions options();
    public byte[] render(CotpCard card);                       // no checked exceptions
    public void write(CotpCard card, OutputStream out) throws IOException;
}

public final class CotpPdfOptions {
    public static CotpPdfOptions defaults();
    public static Builder builder();
    // title() pageWidth() pageHeight() margin() qrSize()
}
```

Failures during rendering are reported as the unchecked `com.cotp.pdf.CotpPdfException`.

## Build

The Maven Wrapper lives in `cotp-java/`. From the repository root:

```powershell
# 1. install the core so cotp-pdf can resolve com.cotp:cotp-java:1.0.0
cotp-java\mvnw.cmd -B -f cotp-java\pom.xml install

# 2. build and test this module
cotp-java\mvnw.cmd -B -f cotp-pdf\pom.xml verify
```

`verify` compiles the module and runs the JUnit 5 tests in `CotpCardPdfTest`, which assert that
the output is a single-page, non-empty PDF (`%PDF-`), that PDFBox text extraction finds a known
grid word, the `cardId`, the column headers/row numbers, and that both layouts render.

## Fonts / encoding

The card uses PDFBox standard-14 Helvetica. BIP-39 words are lowercase ASCII, as are the column
headers, row numbers and card ids, so no font embedding or CJK/RTL handling is required.
Localisation of the instruction text to non-Latin scripts would require embedding a Unicode TTF
(tracked separately as TODO `[U-D]`).
