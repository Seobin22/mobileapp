package com.seobin.phonedrop.image;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.Inflater;

/**
 * 사진 파일의 헤더·메타데이터를 직접 읽는다(라이브러리 없이 JPEG/PNG 지원).
 * 픽셀은 읽지 않는다. HEIC 등 나머지 형식은 {@link #sniff} 로 형식만 알아내고 exiftool 결과로 채운다.
 */
public class PhotoMeta {

    public String format;          // JPG, PNG, HEIC, WEBP ...
    public int width;              // 저장된 가로(px, 회전 적용 전)
    public int height;
    public Integer bitDepth;       // 채널당 비트
    public String colorMode;       // RGB, Grayscale, CMYK, RGBA ...
    public String profile;         // ICC 프로파일 이름
    public byte[] icc;
    public Double dpiX;
    public Double dpiY;
    public String make;
    public String model;
    public String takenAt;         // 촬영 일시(EXIF DateTimeOriginal, 없으면 DateTime)
    public int orientation = 1;    // EXIF orientation 1~8
    public boolean orientationPresent;
    public Integer exifColorSpace; // 1 = sRGB, 65535 = Uncalibrated

    /** 파일 앞부분으로 형식을 판별한다. 영상이면 "VIDEO". 모르면 null. */
    public static String sniff(Path file) throws IOException {
        byte[] h = new byte[16];
        int n;
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            n = f.read(h);
        }
        if (n < 12) {
            return null;
        }
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8) {
            return "JPG";
        }
        if ((h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return "PNG";
        }
        String s4 = new String(h, 4, 4, StandardCharsets.ISO_8859_1);
        if (s4.equals("ftyp")) {
            String brand = new String(h, 8, 4, StandardCharsets.ISO_8859_1).trim().toLowerCase();
            return switch (brand) {
                case "heic", "heix", "hevc", "heim", "heis", "hevm", "hevs", "mif1", "msf1" -> "HEIC";
                case "avif", "avis" -> "AVIF";
                default -> "VIDEO";
            };
        }
        String s0 = new String(h, 0, 4, StandardCharsets.ISO_8859_1);
        if (s0.equals("RIFF")) {
            String kind = new String(h, 8, 4, StandardCharsets.ISO_8859_1);
            return kind.equals("WEBP") ? "WEBP" : kind.startsWith("AVI") ? "VIDEO" : null;
        }
        if ((h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3) {
            return "VIDEO"; // webm / mkv
        }
        if (s0.startsWith("GIF8")) {
            return "GIF";
        }
        if (h[0] == 'B' && h[1] == 'M') {
            return "BMP";
        }
        if ((h[0] == 'I' && h[1] == 'I' && h[2] == 42) || (h[0] == 'M' && h[1] == 'M' && h[3] == 42)) {
            return "TIFF";
        }
        return null;
    }

    public static PhotoMeta read(Path file, String format) throws IOException {
        PhotoMeta m = new PhotoMeta();
        m.format = format;
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            if ("JPG".equals(format)) {
                m.readJpeg(f);
            } else if ("PNG".equals(format)) {
                m.readPng(f);
            }
        }
        if (m.icc != null && m.profile == null) {
            m.profile = iccDescription(m.icc);
        }
        return m;
    }

    // ---------------------------------------------------------------- JPEG

    private void readJpeg(RandomAccessFile f) throws IOException {
        f.seek(2);
        ByteArrayOutputStream icc = new ByteArrayOutputStream();
        while (f.getFilePointer() < f.length()) {
            int b = f.read();
            if (b != 0xFF) {
                continue;
            }
            int marker = f.read();
            while (marker == 0xFF) {
                marker = f.read();
            }
            if (marker < 0 || marker == 0xD9 || marker == 0xDA) {
                break; // EOI 또는 스캔 시작: 헤더 끝
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue;
            }
            int len = f.readUnsignedShort() - 2;
            if (len < 0) {
                break;
            }
            long next = f.getFilePointer() + len;
            if (marker == 0xE0 && len >= 14) {
                byte[] d = readBytes(f, Math.min(len, 16));
                if (new String(d, 0, 4, StandardCharsets.ISO_8859_1).equals("JFIF") && dpiX == null) {
                    int unit = d[7] & 0xFF;
                    int x = u16(d, 8, true);
                    int y = u16(d, 10, true);
                    if (unit == 1 && x > 0) {
                        dpiX = (double) x;
                        dpiY = (double) y;
                    } else if (unit == 2 && x > 0) {
                        dpiX = x * 2.54;
                        dpiY = y * 2.54;
                    }
                }
            } else if (marker == 0xE1 && len > 6) {
                byte[] d = readBytes(f, len);
                if (d[0] == 'E' && d[1] == 'x' && d[2] == 'i' && d[3] == 'f' && d[4] == 0) {
                    readTiff(d, 6);
                }
            } else if (marker == 0xE2 && len > 14) {
                byte[] d = readBytes(f, len);
                if (new String(d, 0, 11, StandardCharsets.ISO_8859_1).equals("ICC_PROFILE")) {
                    icc.write(d, 14, d.length - 14);
                }
            } else if (isSof(marker) && len >= 6) {
                byte[] d = readBytes(f, 6);
                bitDepth = d[0] & 0xFF;
                height = u16(d, 1, true);
                width = u16(d, 3, true);
                int comps = d[5] & 0xFF;
                colorMode = switch (comps) {
                    case 1 -> "Grayscale";
                    case 3 -> "RGB";
                    case 4 -> "CMYK";
                    default -> comps + "채널";
                };
            }
            f.seek(next);
        }
        if (icc.size() > 0) {
            this.icc = icc.toByteArray();
        }
    }

    private static boolean isSof(int m) {
        return m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
    }

    // ---------------------------------------------------------------- PNG

    private void readPng(RandomAccessFile f) throws IOException {
        f.seek(8);
        while (f.getFilePointer() + 8 <= f.length()) {
            long len = f.readInt() & 0xFFFFFFFFL;
            byte[] t = readBytes(f, 4);
            String type = new String(t, StandardCharsets.ISO_8859_1);
            long next = f.getFilePointer() + len + 4; // 데이터 + CRC
            switch (type) {
                case "IHDR" -> {
                    byte[] d = readBytes(f, 13);
                    width = (int) u32(d, 0, true);
                    height = (int) u32(d, 4, true);
                    bitDepth = d[8] & 0xFF;
                    colorMode = switch (d[9]) {
                        case 0 -> "Grayscale";
                        case 2 -> "RGB";
                        case 3 -> "Indexed(팔레트)";
                        case 4 -> "Grayscale+Alpha";
                        case 6 -> "RGBA";
                        default -> "알 수 없음";
                    };
                }
                case "pHYs" -> {
                    byte[] d = readBytes(f, 9);
                    if (d[8] == 1) {
                        dpiX = u32(d, 0, true) * 0.0254;
                        dpiY = u32(d, 4, true) * 0.0254;
                    }
                }
                case "sRGB" -> {
                    if (profile == null) {
                        profile = "sRGB";
                    }
                }
                case "iCCP" -> {
                    if (len < 4_000_000) {
                        byte[] d = readBytes(f, (int) len);
                        int z = 0;
                        while (z < d.length && d[z] != 0) {
                            z++;
                        }
                        if (z + 2 < d.length) {
                            icc = inflate(d, z + 2, d.length - z - 2);
                            if (icc == null) {
                                profile = new String(d, 0, z, StandardCharsets.ISO_8859_1);
                            }
                        }
                    }
                }
                case "eXIf" -> {
                    if (len < 4_000_000) {
                        readTiff(readBytes(f, (int) len), 0);
                    }
                }
                case "IEND" -> {
                    return;
                }
                default -> {
                }
            }
            f.seek(next);
        }
    }

    private static byte[] inflate(byte[] d, int off, int len) {
        try {
            Inflater inf = new Inflater();
            inf.setInput(d, off, len);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                out.write(buf, 0, n);
                if (out.size() > 4_000_000) {
                    break;
                }
            }
            inf.end();
            return out.size() > 0 ? out.toByteArray() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- EXIF (TIFF)

    private void readTiff(byte[] d, int base) {
        try {
            boolean be = d[base] == 'M';
            long ifd0 = u32(d, base + 4, be);
            long exifIfd = readIfd(d, base, base + ifd0, be, false);
            if (exifIfd > 0) {
                readIfd(d, base, base + exifIfd, be, true);
            }
        } catch (RuntimeException e) {
            // 깨진 EXIF 는 무시한다.
        }
    }

    /** IFD 항목을 읽는다. IFD0 이면 Exif IFD 위치를 돌려준다. */
    private long readIfd(byte[] d, int base, long pos, boolean be, boolean exif) {
        int p = (int) pos;
        int count = u16(d, p, be);
        long exifPointer = 0;
        for (int i = 0; i < count; i++) {
            int e = p + 2 + i * 12;
            int tag = u16(d, e, be);
            int type = u16(d, e + 2, be);
            long n = u32(d, e + 4, be);
            int size = switch (type) {
                case 3 -> 2;
                case 4, 9 -> 4;
                case 5, 10 -> 8;
                default -> 1;
            };
            int valueAt = n * size <= 4 ? e + 8 : base + (int) u32(d, e + 8, be);
            if (!exif) {
                switch (tag) {
                    case 0x010F -> make = ascii(d, valueAt, n);
                    case 0x0110 -> model = ascii(d, valueAt, n);
                    case 0x0112 -> {
                        orientation = u16(d, valueAt, be);
                        orientationPresent = true;
                        if (orientation < 1 || orientation > 8) {
                            orientation = 1;
                        }
                    }
                    case 0x011A -> dpiX = rational(d, valueAt, be);
                    case 0x011B -> dpiY = rational(d, valueAt, be);
                    case 0x0128 -> {
                        int unit = u16(d, valueAt, be);
                        if (unit == 3 && dpiX != null) { // 센티미터 단위
                            dpiX *= 2.54;
                            dpiY = dpiY == null ? null : dpiY * 2.54;
                        }
                    }
                    case 0x0132 -> {
                        if (takenAt == null) {
                            takenAt = ascii(d, valueAt, n);
                        }
                    }
                    case 0x8769 -> exifPointer = u32(d, valueAt, be);
                    default -> {
                    }
                }
            } else {
                switch (tag) {
                    case 0x9003 -> takenAt = ascii(d, valueAt, n);
                    case 0xA001 -> exifColorSpace = u16(d, valueAt, be);
                    default -> {
                    }
                }
            }
        }
        return exifPointer;
    }

    // ---------------------------------------------------------------- ICC

    /** ICC 프로파일 이름(desc 태그). */
    static String iccDescription(byte[] p) {
        try {
            int count = (int) u32(p, 128, true);
            for (int i = 0; i < count && i < 100; i++) {
                int e = 132 + i * 12;
                String sig = new String(p, e, 4, StandardCharsets.ISO_8859_1);
                if (!sig.equals("desc")) {
                    continue;
                }
                int off = (int) u32(p, e + 4, true);
                String type = new String(p, off, 4, StandardCharsets.ISO_8859_1);
                if (type.equals("desc")) {
                    int len = (int) u32(p, off + 8, true);
                    return ascii(p, off + 12, len);
                }
                if (type.equals("mluc")) {
                    int records = (int) u32(p, off + 8, true);
                    int recSize = (int) u32(p, off + 12, true);
                    String first = null;
                    for (int r = 0; r < records; r++) {
                        int rec = off + 16 + r * recSize;
                        String lang = new String(p, rec, 2, StandardCharsets.ISO_8859_1);
                        int len = (int) u32(p, rec + 4, true);
                        int at = off + (int) u32(p, rec + 8, true);
                        String text = new String(p, at, len, StandardCharsets.UTF_16BE).replace("\0", "").strip();
                        if (lang.equals("en")) {
                            return text;
                        }
                        if (first == null) {
                            first = text;
                        }
                    }
                    return first;
                }
            }
        } catch (RuntimeException e) {
            // 읽을 수 없는 프로파일
        }
        return "이름 없는 프로파일";
    }

    /** ICC 헤더의 색 공간. */
    static String iccColorSpace(byte[] p) {
        if (p == null || p.length < 20) {
            return null;
        }
        return new String(p, 16, 4, StandardCharsets.ISO_8859_1).strip();
    }

    // ---------------------------------------------------------------- 공통

    private static byte[] readBytes(RandomAccessFile f, int n) throws IOException {
        byte[] b = new byte[n];
        f.readFully(b);
        return b;
    }

    static int u16(byte[] d, int i, boolean be) {
        return be ? ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF) : ((d[i + 1] & 0xFF) << 8) | (d[i] & 0xFF);
    }

    static long u32(byte[] d, int i, boolean be) {
        return be
                ? ((long) (d[i] & 0xFF) << 24) | ((d[i + 1] & 0xFF) << 16) | ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF)
                : ((long) (d[i + 3] & 0xFF) << 24) | ((d[i + 2] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8) | (d[i] & 0xFF);
    }

    private static Double rational(byte[] d, int i, boolean be) {
        long num = u32(d, i, be);
        long den = u32(d, i + 4, be);
        return den == 0 ? null : (double) num / den;
    }

    private static String ascii(byte[] d, int i, long n) {
        int len = (int) Math.min(n, d.length - i);
        String s = new String(d, i, Math.max(0, len), StandardCharsets.UTF_8);
        int z = s.indexOf('\0');
        return (z >= 0 ? s.substring(0, z) : s).strip();
    }
}
