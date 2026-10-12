package com.lookahead.domain.review.platform;

import com.lookahead.domain.review.platform.PlatformReviewModels.Image;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Set;

/**
 * Checks an uploaded image by its file signature and removes metadata that can carry a location
 * (EXIF, XMP, IPTC, text chunks) without re-encoding the picture. Throws IllegalArgumentException
 * with a reader-facing message when the bytes are not a PNG, JPEG or WebP image of the declared type.
 */
final class PlatformReviewImages {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final Set<String> PNG_METADATA = Set.of("eXIf", "tEXt", "zTXt", "iTXt", "tIME");
    private static final Set<String> WEBP_METADATA = Set.of("EXIF", "XMP ");

    private PlatformReviewImages() {}

    static Image clean(String declaredType, byte[] bytes) {
        String detected = detect(bytes);
        if (detected == null) throw invalid("Attach PNG, JPEG or WebP images only.");
        if (!detected.equals(declaredType)) throw invalid("An image's contents do not match its file type.");
        try {
            return switch (detected) {
                case "image/png" -> new Image(detected, "png", png(bytes));
                case "image/jpeg" -> new Image(detected, "jpg", jpeg(bytes));
                default -> new Image(detected, "webp", webp(bytes));
            };
        } catch (IndexOutOfBoundsException truncated) {
            throw invalid("An image is damaged or incomplete.");
        }
    }

    static String detect(byte[] bytes) {
        if (bytes.length >= 8 && Arrays.equals(bytes, 0, 8, PNG, 0, 8)) return "image/png";
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
        if (bytes.length >= 12 && ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 4).equals("WEBP")) return "image/webp";
        return null;
    }

    private static byte[] png(byte[] in) {
        var out = new ByteArrayOutputStream(in.length);
        out.write(in, 0, 8);
        int at = 8;
        boolean ended = false;
        while (!ended) {
            long length = Integer.toUnsignedLong(int32(in, at));
            if (length > in.length - at - 12L) throw new IndexOutOfBoundsException();
            String type = ascii(in, at + 4, 4);
            int total = 12 + (int) length;
            if (!PNG_METADATA.contains(type)) out.write(in, at, total);
            ended = type.equals("IEND");
            at += total;
        }
        return out.toByteArray();
    }

    private static byte[] jpeg(byte[] in) {
        var out = new ByteArrayOutputStream(in.length);
        out.write(in, 0, 2);
        int at = 2;
        while (true) {
            if ((in[at] & 0xff) != 0xff) throw new IndexOutOfBoundsException();
            int marker = in[at + 1] & 0xff;
            if (marker == 0xff) { at++; continue; } // fill byte
            if (marker == 0xd9 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
                out.write(in, at, 2);
                if (marker == 0xd9) return out.toByteArray();
                at += 2;
                continue;
            }
            int length = ((in[at + 2] & 0xff) << 8) | (in[at + 3] & 0xff);
            if (length < 2 || at + 2 + length > in.length) throw new IndexOutOfBoundsException();
            if (marker == 0xda) { // start of scan: the rest is image data
                out.write(in, at, in.length - at);
                return out.toByteArray();
            }
            // APP1 = EXIF/XMP, APP13 = IPTC/Photoshop, COM = free-text comment
            if (marker != 0xe1 && marker != 0xed && marker != 0xfe) out.write(in, at, 2 + length);
            at += 2 + length;
        }
    }

    private static byte[] webp(byte[] in) {
        long declared = Integer.toUnsignedLong(int32le(in, 4)) + 8;
        if (declared < 12 || declared > in.length) throw new IndexOutOfBoundsException();
        // The validated RIFF boundary fits this Java array and therefore an int.
        int end = Math.toIntExact(declared);
        var out = new ByteArrayOutputStream(in.length);
        out.write(in, 0, 12);
        int at = 12;
        while (at < end) {
            if (end - at < 8) throw new IndexOutOfBoundsException();
            String type = ascii(in, at, 4);
            long size = Integer.toUnsignedLong(int32le(in, at + 4));
            long padded = size + (size & 1);
            long remaining = (long) end - at - 8L;
            if (padded > remaining) throw new IndexOutOfBoundsException();
            int total = Math.toIntExact(8L + padded);
            if (!WEBP_METADATA.contains(type)) {
                byte[] chunk = Arrays.copyOfRange(in, at, at + total);
                if (type.equals("VP8X") && size >= 1) chunk[8] &= (byte) ~0x0c; // clear the EXIF and XMP flags
                out.write(chunk, 0, chunk.length);
            }
            at += total;
        }
        byte[] result = out.toByteArray();
        int riff = result.length - 8;
        result[4] = (byte) riff; result[5] = (byte) (riff >>> 8); result[6] = (byte) (riff >>> 16); result[7] = (byte) (riff >>> 24);
        return result;
    }

    private static int int32(byte[] in, int at) {
        return ((in[at] & 0xff) << 24) | ((in[at + 1] & 0xff) << 16) | ((in[at + 2] & 0xff) << 8) | (in[at + 3] & 0xff);
    }

    private static int int32le(byte[] in, int at) {
        return (in[at] & 0xff) | ((in[at + 1] & 0xff) << 8) | ((in[at + 2] & 0xff) << 16) | ((in[at + 3] & 0xff) << 24);
    }

    private static String ascii(byte[] in, int at, int length) {
        if (at + length > in.length) throw new IndexOutOfBoundsException();
        return new String(in, at, length, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
