package ru.loolzaaa.pdf;

/**
 * Immutable rendering options for {@link CotpCardPdf}.
 *
 * <p>All dimensions are in PDF user-space units (1/72 inch). Use {@link #defaults()} or the
 * {@link #builder()} to obtain a configuration; the defaults produce a portrait, badge-sized
 * single-page card.</p>
 */
public final class CotpPdfOptions {

    private static final String DEFAULT_TITLE = "COTP Challenge Card";
    private static final float DEFAULT_PAGE_WIDTH = 400f;
    private static final float DEFAULT_PAGE_HEIGHT = 560f;
    private static final float DEFAULT_MARGIN = 24f;
    private static final int DEFAULT_QR_SIZE = 256;

    private final String title;
    private final float pageWidth;
    private final float pageHeight;
    private final float margin;
    private final int qrSize;

    private CotpPdfOptions(Builder b) {
        this.title = b.title;
        this.pageWidth = b.pageWidth;
        this.pageHeight = b.pageHeight;
        this.margin = b.margin;
        this.qrSize = b.qrSize;

        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (pageWidth < 100f || pageHeight < 100f) {
            throw new IllegalArgumentException("page size must be at least 100x100 units");
        }
        if (margin < 0f || margin * 2f >= Math.min(pageWidth, pageHeight)) {
            throw new IllegalArgumentException("margin is out of range for the page size");
        }
        if (qrSize < 21) {
            throw new IllegalArgumentException("qrSize must be at least 21 pixels (minimum QR version)");
        }
    }

    /** @return the default options. */
    public static CotpPdfOptions defaults() {
        return builder().build();
    }

    /** @return a fresh builder pre-populated with the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the card title printed at the top of the page. */
    public String title() {
        return title;
    }

    /** @return page width in PDF units. */
    public float pageWidth() {
        return pageWidth;
    }

    /** @return page height in PDF units. */
    public float pageHeight() {
        return pageHeight;
    }

    /** @return page margin in PDF units. */
    public float margin() {
        return margin;
    }

    /** @return QR symbol size in pixels before placement. */
    public int qrSize() {
        return qrSize;
    }

    /** Mutable builder for {@link CotpPdfOptions}. */
    public static final class Builder {
        private String title = DEFAULT_TITLE;
        private float pageWidth = DEFAULT_PAGE_WIDTH;
        private float pageHeight = DEFAULT_PAGE_HEIGHT;
        private float margin = DEFAULT_MARGIN;
        private int qrSize = DEFAULT_QR_SIZE;

        private Builder() {
        }

        /** Sets the card title. */
        public Builder title(String title) {
            if (title == null) {
                throw new IllegalArgumentException("title must not be null");
            }
            this.title = title;
            return this;
        }

        /** Sets the page width in PDF units. */
        public Builder pageWidth(float pageWidth) {
            this.pageWidth = pageWidth;
            return this;
        }

        /** Sets the page height in PDF units. */
        public Builder pageHeight(float pageHeight) {
            this.pageHeight = pageHeight;
            return this;
        }

        /** Sets the page margin in PDF units. */
        public Builder margin(float margin) {
            this.margin = margin;
            return this;
        }

        /** Sets the QR symbol size in pixels. */
        public Builder qrSize(int qrSize) {
            this.qrSize = qrSize;
            return this;
        }

        /** @return immutable options. */
        public CotpPdfOptions build() {
            return new CotpPdfOptions(this);
        }
    }
}
