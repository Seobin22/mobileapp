package com.seobin.phonedrop.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * 픽셀 처리. 메모리(-Xmx512m)를 아끼려고 큰 사진은 필요한 영역만, 필요한 만큼 줄여서(subsampling) 읽는다.
 * 좌표는 두 종류: raw(파일에 저장된 방향) / 화면(EXIF orientation 적용 후).
 */
public final class ImageOps {

    private ImageOps() {
    }

    public record Size(int width, int height) {
    }

    public record Encoded(byte[] bytes, float quality) {
    }

    public record Stats(double sharpness, double brightness, int sampleWidth, int sampleHeight) {
    }

    /** 저장된 크기(헤더만 읽음). */
    public static Size rawSize(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader = reader(in);
            try {
                reader.setInput(in, true, true);
                return new Size(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    /** raw 좌표의 영역(null 이면 전체)을 subsample 배율로 줄여 읽는다. */
    public static BufferedImage read(Path file, Rectangle region, int subsample) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader = reader(in);
            try {
                reader.setInput(in, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                if (region != null) {
                    param.setSourceRegion(region);
                }
                int s = Math.max(1, subsample);
                param.setSourceSubsampling(s, s, 0, 0);
                return flatten(reader.read(0, param));
            } catch (javax.imageio.IIOException e) {
                throw new IOException("이 사진의 픽셀을 읽을 수 없습니다(지원하지 않는 색 형식일 수 있음): " + e.getMessage(), e);
            } finally {
                reader.dispose();
            }
        }
    }

    private static ImageReader reader(ImageInputStream in) throws IOException {
        if (in == null) {
            throw new IOException("파일을 열 수 없습니다.");
        }
        Iterator<ImageReader> it = ImageIO.getImageReaders(in);
        if (!it.hasNext()) {
            throw new IOException("읽을 수 없는 사진 형식입니다.");
        }
        return it.next();
    }

    /** 투명 영역은 흰색으로 채우고, 색 공간은 sRGB(TYPE_INT_RGB)로 맞춘다. */
    static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** 화면 방향의 크기. orientation 5~8 은 가로·세로가 바뀐다. */
    public static Size oriented(int rawW, int rawH, int orientation) {
        return orientation >= 5 ? new Size(rawH, rawW) : new Size(rawW, rawH);
    }

    /** 화면 좌표의 사각형을 raw 좌표로 바꾼다. W,H 는 raw 크기. */
    public static Rectangle toRaw(int x, int y, int w, int h, int rawW, int rawH, int o) {
        return switch (o) {
            case 2 -> new Rectangle(rawW - x - w, y, w, h);
            case 3 -> new Rectangle(rawW - x - w, rawH - y - h, w, h);
            case 4 -> new Rectangle(x, rawH - y - h, w, h);
            case 5 -> new Rectangle(y, x, h, w);
            case 6 -> new Rectangle(y, rawH - x - w, h, w);
            case 7 -> new Rectangle(rawW - y - h, rawH - x - w, h, w);
            case 8 -> new Rectangle(rawW - y - h, x, h, w);
            default -> new Rectangle(x, y, w, h);
        };
    }

    /** raw 방향의 이미지를 화면 방향으로 돌린다. */
    public static BufferedImage orient(BufferedImage src, int o) {
        if (o <= 1 || o > 8) {
            return src;
        }
        double w = src.getWidth();
        double h = src.getHeight();
        // raw (x,y) → 화면 (X,Y). 90° 단위 회전·뒤집기라 픽셀을 옮기기만 한다(보간 없음, 해상도 그대로).
        AffineTransform t = switch (o) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            default -> new AffineTransform(0, -1, 1, 0, 0, w); // 8
        };
        boolean swap = o >= 5;
        int type = src.getType() == BufferedImage.TYPE_3BYTE_BGR ? BufferedImage.TYPE_3BYTE_BGR : BufferedImage.TYPE_INT_RGB;
        BufferedImage dst = new BufferedImage(swap ? src.getHeight() : src.getWidth(),
                swap ? src.getWidth() : src.getHeight(), type);
        new AffineTransformOp(t, AffineTransformOp.TYPE_NEAREST_NEIGHBOR).filter(flatten(src), dst);
        return dst;
    }

    /** 투명·특수 색 형식만 흰 배경 RGB 로 바꾸고, 일반 RGB(JPEG 의 3바이트 형식 포함)는 그대로 둔다(메모리 절약). */
    static BufferedImage flatten(BufferedImage src) {
        int t = src.getType();
        return t == BufferedImage.TYPE_INT_RGB || t == BufferedImage.TYPE_3BYTE_BGR ? src : toRgb(src);
    }

    /** 고품질 리사이즈: 절반씩 줄이다가 마지막에 bicubic. 확대도 bicubic. */
    public static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage cur = toRgb(src);
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            cur = draw(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        if (cur.getWidth() != w || cur.getHeight() != h) {
            cur = draw(cur, w, h);
        }
        return cur;
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** 결과의 가장 긴 변이 target 의 2배 이상 남도록 하는 subsampling 배율. */
    public static int subsampleFor(int sourceLong, int targetLong) {
        return Math.max(1, sourceLong / Math.max(1, targetLong * 2));
    }

    public static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(buf)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(flatten(img), null, null), param);
        } finally {
            writer.dispose();
        }
        return buf.toByteArray();
    }

    /** 용량 제한 안으로 들어올 때까지 JPG 품질을 낮춘다(0.92 → 0.05씩, 최저 0.30). */
    public static Encoded jpegWithin(BufferedImage img, long maxBytes) throws IOException {
        for (float q = 0.92f; q >= 0.299f; q -= 0.05f) {
            byte[] b = jpeg(img, q);
            if (b.length <= maxBytes) {
                return new Encoded(b, Math.round(q * 100) / 100f);
            }
        }
        throw new IOException("JPG 품질을 30%까지 낮춰도 " + (maxBytes / 1024 / 1024) + "MB 를 넘습니다.");
    }

    /**
     * 선명도(라플라시안 분산)와 평균 밝기(0~255). 회색조 변환은 0.299R + 0.587G + 0.114B.
     * 이미지 크기에 따라 값이 달라지므로 항상 긴 변을 1600px 이하로 줄인 이미지에서 잰다.
     */
    public static Stats stats(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        float[] g = new float[w * h];
        int[] row = new int[w];
        double sum = 0;
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                float v = 0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF);
                g[y * w + x] = v;
                sum += v;
            }
        }
        double lapSum = 0;
        double lapSq = 0;
        long n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                double l = g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i];
                lapSum += l;
                lapSq += l * l;
                n++;
            }
        }
        double mean = n == 0 ? 0 : lapSum / n;
        double variance = n == 0 ? 0 : lapSq / n - mean * mean;
        return new Stats(variance, sum / ((double) w * h), w, h);
    }
}
