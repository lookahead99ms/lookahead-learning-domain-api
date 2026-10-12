package com.lookahead.domain.review.platform;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** Small real images with location metadata added, for DLV-928 tests. */
final class PlatformReviewTestImages {
    static final String SECRET = "GPS 51.5007N 0.1246W";
    private PlatformReviewTestImages() {}

    static byte[] png() { return png(false); }

    static byte[] png(boolean withText) {
        byte[] plain = encode("png");
        if (!withText) return plain;
        byte[] text = (" Comment\0" + SECRET).getBytes(StandardCharsets.ISO_8859_1);
        var chunk = new ByteArrayOutputStream();
        int length = text.length;
        chunk.write(length >>> 24); chunk.write(length >>> 16); chunk.write(length >>> 8); chunk.write(length);
        chunk.writeBytes("tEXt".getBytes(StandardCharsets.ISO_8859_1));
        chunk.writeBytes(text);
        chunk.writeBytes(new byte[4]);
        return insert(plain, 33, chunk.toByteArray()); // after the signature and IHDR
    }

    static byte[] jpegWithExif() {
        byte[] plain = encode("jpg");
        byte[] payload = ("Exif\0\0" + SECRET).getBytes(StandardCharsets.ISO_8859_1);
        int length = payload.length + 2;
        var segment = new ByteArrayOutputStream();
        segment.write(0xff); segment.write(0xe1); segment.write(length >>> 8); segment.write(length);
        segment.writeBytes(payload);
        return insert(plain, 2, segment.toByteArray());
    }

    static byte[] webpWithExif() {
        var body = new ByteArrayOutputStream();
        body.writeBytes("WEBP".getBytes(StandardCharsets.ISO_8859_1));
        chunk(body, "VP8X", new byte[]{0x08, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        chunk(body, "VP8L", new byte[]{0x2f, 0, 0, 0, 0});
        chunk(body, "EXIF", SECRET.getBytes(StandardCharsets.ISO_8859_1));
        byte[] content = body.toByteArray();
        var out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.ISO_8859_1));
        le(out, content.length);
        out.writeBytes(content);
        return out.toByteArray();
    }

    static boolean contains(byte[] bytes, String text) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(text);
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        le(out, data.length);
        out.writeBytes(data);
        if ((data.length & 1) == 1) out.write(0);
    }

    private static void le(ByteArrayOutputStream out, int value) {
        out.write(value); out.write(value >>> 8); out.write(value >>> 16); out.write(value >>> 24);
    }

    private static byte[] encode(String format) {
        try {
            var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            image.setRGB(1, 1, 0x3366cc);
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] insert(byte[] bytes, int at, byte[] extra) {
        byte[] result = new byte[bytes.length + extra.length];
        System.arraycopy(bytes, 0, result, 0, at);
        System.arraycopy(extra, 0, result, at, extra.length);
        System.arraycopy(bytes, at, result, at + extra.length, bytes.length - at);
        return result;
    }
}
