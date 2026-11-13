package com.seobin.phonedrop.image;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.seobin.phonedrop.image.Product.Media;
import com.seobin.phonedrop.image.Product.Source;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** 업로드 분석, 미리보기, 커버·상세 변환, 영상 장면 캡처, 영상 미리보기 변환. */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    static final int COVER_SIZE = 900;
    static final long COVER_MAX_BYTES = 2L * 1024 * 1024;
    static final int DETAIL_WIDTH = 880;
    static final long DETAIL_MAX_BYTES = 10L * 1024 * 1024;
    /** 보조 판정 기준. 선명도는 긴 변 1600px 기준 라플라시안 분산. */
    static final double SHARPNESS_MIN = 100;
    static final double BRIGHTNESS_MIN = 60;
    static final double BRIGHTNESS_MAX = 200;
    static final int STATS_LONG_SIDE = 1600;
    static final int[] PREVIEW_SIZES = {400, 1600};

    /** HDR(HLG/PQ) 영상을 SDR 로 바꾸는 필터. 아이폰 HDR 영상 캡처가 허옇게 뜨지 않게 한다. */
    private static final String TONEMAP = "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,"
            + "tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv";

    private final ProductStore store;
    private final ExternalTools tools;
    private final ExecutorService proxyWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "image-proxy");
        t.setDaemon(true);
        return t;
    });

    public MediaService(ProductStore store, ExternalTools tools) {
        this.store = store;
        this.tools = tools;
    }

    @PreDestroy
    void stop() {
        proxyWorker.shutdownNow();
    }

    /** 픽셀을 읽을 파일과 그 파일에 적용할 회전. HEIC 는 변환한 작업용 JPG(이미 회전 적용됨). */
    record PixelSource(Path file, int width, int height, int orientation) {
        ImageOps.Size display() {
            return ImageOps.oriented(width, height, orientation);
        }
    }

    // ================================================================ 업로드 분석

    /**
     * files/ 에 저장된 원본을 분석해 미디어 정보를 채운다(사진: 메타데이터·픽셀 통계·미리보기, 영상: ffprobe·썸네일).
     */
    public void analyze(long userId, long productId, Media m) throws IOException {
        Path file = store.filesDir(userId, productId).resolve(m.file);
        Path cache = store.cacheDir(userId, productId);
        m.size = Files.size(file);
        String format = PhotoMeta.sniff(file);
        if (format == null) {
            throw new ResponseStatusException(BAD_REQUEST, "사진·영상으로 인식할 수 없는 파일입니다: " + m.name);
        }
        if (format.equals("VIDEO")) {
            m.type = "video";
            m.info = analyzeVideo(file, m.size);
            videoThumbnails(file, cache, m);
        } else {
            m.type = "photo";
            tools.heavy(() -> {
                analyzePhoto(file, format, cache, m);
                return null;
            });
        }
    }

    private void analyzePhoto(Path file, String format, Path cache, Media m) throws IOException {
        PhotoMeta meta = PhotoMeta.read(file, format);
        boolean javaReadable = format.equals("JPG") || format.equals("PNG") || format.equals("GIF")
                || format.equals("BMP") || format.equals("TIFF");
        if (!format.equals("JPG") && !format.equals("PNG")) {
            fillFromExiftool(file, meta);
        }
        PixelSource src;
        if (javaReadable) {
            ImageOps.Size raw = ImageOps.rawSize(file);
            if (meta.width == 0) {
                meta.width = raw.width();
                meta.height = raw.height();
            }
            src = new PixelSource(file, raw.width(), raw.height(), meta.orientation);
        } else {
            Path work = cache.resolve(m.id + "_work.jpg");
            convertToJpeg(file, format, work);
            ImageOps.Size raw = ImageOps.rawSize(work);
            if (meta.width == 0) {
                meta.width = raw.width();
                meta.height = raw.height();
            }
            m.work = work.getFileName().toString();
            src = new PixelSource(work, raw.width(), raw.height(), 1);
        }

        ImageOps.Size disp = src.display();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("format", format);
        info.put("bytes", m.size);
        info.put("width", disp.width());
        info.put("height", disp.height());
        info.put("rawWidth", meta.width);
        info.put("rawHeight", meta.height);
        info.put("orientation", meta.orientation);
        info.put("orientationPresent", meta.orientationPresent);
        putRatio(info, disp.width(), disp.height());
        info.put("colorMode", meta.colorMode);
        info.put("bitDepth", meta.bitDepth);
        String profile = meta.profile;
        if (profile == null && meta.exifColorSpace != null && meta.exifColorSpace == 1) {
            profile = "sRGB (EXIF 색 공간 표시)";
        }
        info.put("profile", profile);
        info.put("dpiX", round(meta.dpiX, 1));
        info.put("dpiY", round(meta.dpiY, 1));
        info.put("make", meta.make);
        info.put("model", meta.model);
        info.put("takenAt", meta.takenAt);

        // 픽셀 통계: 긴 변 1600px 로 맞춘 이미지에서 잰다.
        try {
            int longSide = Math.max(src.width(), src.height());
            BufferedImage img = ImageOps.read(src.file(), null, Math.max(1, longSide / STATS_LONG_SIDE));
            if (Math.max(img.getWidth(), img.getHeight()) > STATS_LONG_SIDE) {
                double k = (double) STATS_LONG_SIDE / Math.max(img.getWidth(), img.getHeight());
                img = ImageOps.resize(img, Math.max(1, (int) Math.round(img.getWidth() * k)),
                        Math.max(1, (int) Math.round(img.getHeight() * k)));
            }
            ImageOps.Stats st = ImageOps.stats(img);
            info.put("sharpness", round(st.sharpness(), 1));
            info.put("brightness", (int) Math.round(st.brightness()));
            info.put("statsSample", st.sampleWidth() + "×" + st.sampleHeight());
            // 미리보기는 같은 이미지에서 만든다.
            BufferedImage oriented = ImageOps.orient(img, src.orientation());
            for (int size : PREVIEW_SIZES) {
                writePreview(oriented, cache.resolve(m.id + "_" + size + ".jpg"), size);
            }
        } catch (IOException e) {
            info.put("pixelError", e.getMessage());
        }

        info.put("cover", coverPlan(disp.width(), disp.height()));
        info.put("detail", Map.of("width", DETAIL_WIDTH,
                "height", (int) Math.round(disp.height() * (double) DETAIL_WIDTH / disp.width())));
        info.put("checks", photoChecks(info, disp));
        info.put("thresholds", Map.of("sharpness", SHARPNESS_MIN, "brightnessMin", BRIGHTNESS_MIN,
                "brightnessMax", BRIGHTNESS_MAX, "cover", COVER_SIZE, "detail", DETAIL_WIDTH));
        info.put("src", Map.of("w", src.width(), "h", src.height(), "o", src.orientation(),
                "work", m.work != null));
        m.info = info;
    }

    private static Map<String, Object> photoChecks(Map<String, Object> info, ImageOps.Size disp) {
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("cover", Math.min(disp.width(), disp.height()) >= COVER_SIZE);
        checks.put("detail", disp.width() >= DETAIL_WIDTH);
        if (info.get("sharpness") instanceof Double s) {
            checks.put("sharpness", s >= SHARPNESS_MIN);
        }
        if (info.get("brightness") instanceof Integer b) {
            checks.put("brightness", b >= BRIGHTNESS_MIN && b <= BRIGHTNESS_MAX);
        }
        return checks;
    }

    /** 900×900 커버로 만들 때 잘려 나가는 양(가운데 기준). */
    static Map<String, Object> coverPlan(int w, int h) {
        Map<String, Object> plan = new LinkedHashMap<>();
        int side = Math.min(w, h);
        int cut = Math.abs(w - h);
        plan.put("axis", h > w ? "위아래" : w > h ? "좌우" : "없음");
        plan.put("cutStart", cut / 2);
        plan.put("cutEnd", cut - cut / 2);
        plan.put("square", side);
        plan.put("scale", side > COVER_SIZE ? "축소" : side < COVER_SIZE ? "확대" : "그대로");
        return plan;
    }

    static void putRatio(Map<String, Object> info, int w, int h) {
        int g = BigInteger.valueOf(w).gcd(BigInteger.valueOf(h)).intValue();
        int a = w / Math.max(1, g);
        int b = h / Math.max(1, g);
        double value = (double) w / h;
        String ratio = a + ":" + b;
        boolean approx = false;
        if (Math.max(a, b) > 50) {
            int[][] common = {{1, 1}, {4, 5}, {5, 4}, {3, 4}, {4, 3}, {2, 3}, {3, 2}, {9, 16}, {16, 9}, {1, 2}, {2, 1}};
            double best = Double.MAX_VALUE;
            for (int[] c : common) {
                double diff = Math.abs(value - (double) c[0] / c[1]) / value;
                if (diff < best) {
                    best = diff;
                    if (diff < 0.01) {
                        ratio = c[0] + ":" + c[1];
                        approx = true;
                    }
                }
            }
        }
        info.put("ratio", ratio);
        info.put("ratioApprox", approx);
        info.put("ratioValue", round(value, 4));
    }

    private void fillFromExiftool(Path file, PhotoMeta meta) {
        if (!tools.has("exiftool")) {
            return;
        }
        try {
            ExternalTools.Result r = tools.run(List.of(tools.require("exiftool"), "-j", "-n",
                    "-ImageWidth", "-ImageHeight", "-Make", "-Model", "-DateTimeOriginal", "-CreateDate",
                    "-Orientation", "-ProfileDescription", "-XResolution", "-YResolution", "-ResolutionUnit",
                    "-BitDepthLuma", "-BitsPerSample", "-ColorSpaceData", "-HasAlpha", file.toString()),
                    Duration.ofSeconds(30));
            if (r.exit() != 0) {
                return;
            }
            JsonNode n = store.json.readTree(r.text()).get(0);
            if (n == null) {
                return;
            }
            meta.width = n.path("ImageWidth").asInt(meta.width);
            meta.height = n.path("ImageHeight").asInt(meta.height);
            meta.make = text(n, "Make", meta.make);
            meta.model = text(n, "Model", meta.model);
            meta.takenAt = text(n, "DateTimeOriginal", text(n, "CreateDate", meta.takenAt));
            if (n.has("Orientation")) {
                int o = n.path("Orientation").asInt(1);
                meta.orientation = o >= 1 && o <= 8 ? o : 1;
                meta.orientationPresent = true;
            }
            meta.profile = text(n, "ProfileDescription", meta.profile);
            if (n.has("XResolution")) {
                double unit = n.path("ResolutionUnit").asInt(2) == 3 ? 2.54 : 1;
                meta.dpiX = n.path("XResolution").asDouble() * unit;
                meta.dpiY = n.path("YResolution").asDouble(meta.dpiX / unit) * unit;
            }
            if (n.has("BitDepthLuma")) {
                meta.bitDepth = n.path("BitDepthLuma").asInt();
            } else if (n.has("BitsPerSample")) {
                meta.bitDepth = n.path("BitsPerSample").asInt();
            }
            String cs = text(n, "ColorSpaceData", null);
            if (cs != null) {
                meta.colorMode = cs.strip().equals("GRAY") ? "Grayscale" : cs.strip();
            } else if (meta.colorMode == null) {
                meta.colorMode = "YCbCr→RGB";
            }
        } catch (Exception e) {
            log.warn("exiftool 실패: {}", e.getMessage());
        }
    }

    private static String text(JsonNode n, String field, String fallback) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return fallback;
        }
        String s = v.asString().strip();
        return s.isEmpty() ? fallback : s;
    }

    /** 자바가 못 읽는 사진(HEIC 등)을 작업용 JPG 로 바꾼다. 원본은 그대로 둔다. */
    private void convertToJpeg(Path file, String format, Path out) throws IOException {
        List<String> errors = new ArrayList<>();
        if ((format.equals("HEIC") || format.equals("AVIF")) && tools.has("heif-convert")) {
            ExternalTools.Result r = tools.run(List.of(tools.require("heif-convert"), "-q", "95",
                    file.toString(), out.toString()), Duration.ofSeconds(120));
            if (r.exit() == 0 && Files.isRegularFile(out)) {
                return;
            }
            errors.add("heif-convert: " + r.stderrTail());
        }
        if (tools.has("ffmpeg")) {
            ExternalTools.Result r = tools.run(List.of(tools.require("ffmpeg"), "-hide_banner", "-v", "error",
                    "-y", "-i", file.toString(), "-frames:v", "1", "-q:v", "2", out.toString()), Duration.ofSeconds(120));
            if (r.exit() == 0 && Files.isRegularFile(out)) {
                return;
            }
            errors.add("ffmpeg: " + r.stderrTail());
        }
        throw new IOException(format + " 사진을 변환하지 못했습니다. " + String.join(" / ", errors));
    }

    /** 장끼 사진 미리보기(긴 변 1600px, 회전 적용). HEIC 도 브라우저에서 보이게 JPG 로 만든다. */
    public void receiptPreview(Path file, String format, Path out) throws IOException {
        tools.heavy(() -> {
            Path src = file;
            int orientation = 1;
            Path work = null;
            if (format.equals("JPG") || format.equals("PNG") || format.equals("GIF") || format.equals("BMP")
                    || format.equals("TIFF")) {
                orientation = PhotoMeta.read(file, format).orientation;
            } else {
                work = out.resolveSibling("receipt_work.tmp.jpg");
                convertToJpeg(file, format, work);
                src = work;
            }
            try {
                ImageOps.Size raw = ImageOps.rawSize(src);
                BufferedImage img = ImageOps.read(src, null,
                        ImageOps.subsampleFor(Math.max(raw.width(), raw.height()), 1600));
                writePreview(ImageOps.orient(img, orientation), out, 1600);
            } finally {
                if (work != null) {
                    Files.deleteIfExists(work);
                }
            }
            return null;
        });
    }

    private static void writePreview(BufferedImage oriented, Path out, int maxSide) throws IOException {
        int w = oriented.getWidth();
        int h = oriented.getHeight();
        double k = Math.min(1.0, (double) maxSide / Math.max(w, h));
        BufferedImage img = k < 1 ? ImageOps.resize(oriented, Math.max(1, (int) Math.round(w * k)),
                Math.max(1, (int) Math.round(h * k))) : oriented;
        Files.write(out, ImageOps.jpeg(img, 0.85f));
    }

    // ================================================================ 영상

    private Map<String, Object> analyzeVideo(Path file, long bytes) throws IOException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("bytes", bytes);
        if (!tools.has("ffprobe")) {
            info.put("error", "서버에 ffprobe 가 없어 영상을 분석하지 못했습니다.");
            return info;
        }
        ExternalTools.Result r = tools.run(List.of(tools.require("ffprobe"), "-v", "error", "-print_format", "json",
                "-show_format", "-show_streams", file.toString()), Duration.ofSeconds(60));
        if (r.exit() != 0) {
            info.put("error", "영상 분석 실패: " + r.stderrTail());
            return info;
        }
        JsonNode root = store.json.readTree(r.text());
        JsonNode format = root.path("format");
        JsonNode video = null;
        JsonNode audio = null;
        for (JsonNode s : root.path("streams")) {
            String type = s.path("codec_type").asString();
            if (type.equals("video") && video == null && s.path("disposition").path("attached_pic").asInt() == 0) {
                video = s;
            } else if (type.equals("audio") && audio == null) {
                audio = s;
            }
        }
        info.put("container", text(format, "format_long_name", text(format, "format_name", null)));
        double duration = parseDouble(text(format, "duration", null));
        if (video == null) {
            info.put("error", "영상 트랙이 없습니다.");
            return info;
        }
        if (duration <= 0) {
            duration = parseDouble(text(video, "duration", null));
        }
        int w = video.path("width").asInt();
        int h = video.path("height").asInt();
        int rotation = 0;
        for (JsonNode sd : video.path("side_data_list")) {
            if (sd.has("rotation")) {
                rotation = sd.path("rotation").asInt();
            }
        }
        if (rotation == 0 && video.path("tags").has("rotate")) {
            rotation = video.path("tags").path("rotate").asInt();
        }
        boolean swap = Math.abs(rotation) % 180 == 90;
        int dw = swap ? h : w;
        int dh = swap ? w : h;
        String fpsText = text(video, "avg_frame_rate", "0/0");
        double fps = fraction(fpsText);
        if (fps <= 0) {
            fpsText = text(video, "r_frame_rate", "0/0");
            fps = fraction(fpsText);
        }
        long frames = video.path("nb_frames").asLong(0);
        boolean estimated = false;
        if (frames <= 0 && fps > 0 && duration > 0) {
            frames = Math.round(duration * fps);
            estimated = true;
        }
        long bitrate = parseLong(text(format, "bit_rate", null));
        long videoBitrate = parseLong(text(video, "bit_rate", null));

        info.put("width", w);
        info.put("height", h);
        info.put("rotation", rotation);
        info.put("displayWidth", dw);
        info.put("displayHeight", dh);
        info.put("portrait", dh > dw);
        info.put("duration", round(duration, 3));
        info.put("fps", round(fps, 3));
        info.put("fpsText", fpsText);
        info.put("frames", frames);
        info.put("framesEstimated", estimated);
        info.put("videoCodec", codecName(text(video, "codec_name", null)));
        info.put("videoProfile", text(video, "profile", null));
        info.put("pixFmt", text(video, "pix_fmt", null));
        info.put("audioCodec", audio == null ? null : codecName(text(audio, "codec_name", null)));
        info.put("bitrate", bitrate > 0 ? bitrate : null);
        info.put("videoBitrate", videoBitrate > 0 ? videoBitrate : null);
        String transfer = text(video, "color_transfer", "");
        info.put("hdr", transfer.equals("arib-std-b67") ? "HLG" : transfer.equals("smpte2084") ? "PQ(HDR10)" : null);
        info.put("codecRaw", text(video, "codec_name", null));
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("cover", Math.min(dw, dh) >= COVER_SIZE);
        checks.put("detail", dw >= DETAIL_WIDTH);
        info.put("checks", checks);
        info.put("thresholds", Map.of("cover", COVER_SIZE, "detail", DETAIL_WIDTH));
        return info;
    }

    private void videoThumbnails(Path file, Path cache, Media m) throws IOException {
        if (!tools.has("ffmpeg") || m.info.containsKey("error")) {
            return;
        }
        double duration = m.info.get("duration") instanceof Double d ? d : 0;
        double at = Math.min(1.0, duration / 3);
        Path big = cache.resolve(m.id + "_1600.jpg");
        tools.heavy(() -> {
            frame(file, at, big, m.info.get("hdr") != null, "scale=1600:1600:force_original_aspect_ratio=decrease");
            return null;
        });
        if (Files.isRegularFile(big)) {
            BufferedImage img = ImageOps.read(big, null, 1);
            writePreview(img, cache.resolve(m.id + "_400.jpg"), 400);
        }
    }

    /** 영상의 time 초 위치 프레임 한 장을 원본 해상도 JPG 로 뽑는다(회전은 ffmpeg 가 자동 적용). */
    private void frame(Path video, double time, Path out, boolean hdr, String extraFilter) throws IOException {
        String ffmpeg = tools.require("ffmpeg");
        List<String> filters = new ArrayList<>();
        if (hdr) {
            filters.add(TONEMAP);
        }
        if (extraFilter != null) {
            filters.add(extraFilter);
        }
        String t = String.format(Locale.ROOT, "%.3f", Math.max(0, time));
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-v", "error", "-y",
                "-ss", t, "-i", video.toString(), "-frames:v", "1", "-q:v", "2"));
        if (!filters.isEmpty()) {
            cmd.addAll(List.of("-vf", String.join(",", filters)));
        }
        cmd.add(out.toString());
        ExternalTools.Result r = tools.run(cmd, Duration.ofSeconds(90));
        if ((r.exit() != 0 || !Files.isRegularFile(out)) && hdr) {
            log.warn("HDR 변환 캡처 실패, 변환 없이 다시 시도: {}", r.stderrTail());
            frame(video, time, out, false, extraFilter);
            return;
        }
        if (r.exit() != 0 || !Files.isRegularFile(out) || Files.size(out) == 0) {
            throw new IOException("프레임 추출 실패: " + r.stderrTail());
        }
    }

    /** 영상 장면 캡처: 서버에서 원본 해상도로 프레임을 뽑아 원본 칸에 새 사진으로 추가한다. */
    public Media capture(long userId, long productId, Media video, double time) throws IOException {
        if (!"video".equals(video.type)) {
            throw new ResponseStatusException(BAD_REQUEST, "영상에서만 캡처할 수 있습니다.");
        }
        double duration = video.info.get("duration") instanceof Double d ? d : Double.MAX_VALUE;
        if (time < 0 || time > duration + 0.001) {
            throw new ResponseStatusException(BAD_REQUEST, "영상 길이를 벗어난 시각입니다.");
        }
        Path videoFile = store.filesDir(userId, productId).resolve(video.file);
        String id = store.update(userId, productId, p -> "m" + (++p.seq));
        Path out = store.filesDir(userId, productId).resolve(id + ".jpg");
        boolean hdr = video.info.get("hdr") != null;
        // 화면에 보이던 프레임(시작 시각 = time)을 정확히 뽑도록 1/4 프레임 앞에서 찾는다.
        // ffmpeg 는 -ss 이후 첫 프레임을 내보내므로 반올림 오차로 다음 프레임이 나오는 것을 막는다.
        double fps = video.info.get("fps") instanceof Double f && f > 0 ? f : 0;
        double seek = fps > 0 ? Math.max(0, time - 0.25 / fps) : time;
        tools.heavy(() -> {
            frame(videoFile, seek, out, hdr, null);
            return null;
        });
        Media m = new Media();
        m.id = id;
        m.kind = "original";
        m.file = out.getFileName().toString();
        m.name = baseName(video.name) + "_" + stamp(time).replace(":", "m").replace(".", "s") + ".jpg";
        m.createdAt = ProductStore.now();
        m.source = new Source();
        m.source.fromId = video.id;
        m.source.fromName = video.name;
        m.source.time = round(time, 3);
        try {
            analyze(userId, productId, m);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(out);
            throw e;
        }
        if (hdr) {
            m.info.put("toneMapped", true);
        }
        return m;
    }

    /** 브라우저가 재생하지 못하는 영상(HEVC 등)용 미리보기 H.264 영상을 뒤에서 만든다. 캡처는 계속 원본에서 한다. */
    public void startProxy(long userId, long productId, Media video) throws IOException {
        Path in = store.filesDir(userId, productId).resolve(video.file);
        Path out = store.cacheDir(userId, productId).resolve(video.id + "_proxy.mp4");
        boolean hdr = video.info.get("hdr") != null;
        proxyWorker.submit(() -> {
            String state = "failed";
            try {
                Path tmp = out.resolveSibling(video.id + "_proxy.tmp.mp4");
                List<String> cmd = new ArrayList<>();
                tools.find("nice").ifPresent(n -> cmd.addAll(List.of(n, "-n", "10")));
                String scale = "scale='if(gt(iw,ih),-2,min(720,iw))':'if(gt(iw,ih),min(720,ih),-2)'";
                cmd.addAll(List.of(tools.require("ffmpeg"), "-hide_banner", "-v", "error", "-y", "-i", in.toString(),
                        "-vf", (hdr ? TONEMAP + "," : "") + scale + ",format=yuv420p",
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "26", "-threads", "1",
                        "-an", "-movflags", "+faststart", tmp.toString()));
                ExternalTools.Result r = tools.run(cmd, Duration.ofMinutes(20));
                if (r.exit() == 0 && Files.isRegularFile(tmp)) {
                    Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    state = "ready";
                } else {
                    log.warn("영상 미리보기 변환 실패: {}", r.stderrTail());
                    Files.deleteIfExists(tmp);
                }
            } catch (Exception e) {
                log.warn("영상 미리보기 변환 실패", e);
            }
            String finalState = state;
            try {
                store.update(userId, productId, p -> {
                    p.media.stream().filter(x -> x.id.equals(video.id)).forEach(x -> x.proxy = finalState);
                    return null;
                });
            } catch (RuntimeException e) {
                // 그 사이 상품이나 영상이 지워졌으면 무시
            }
        });
    }

    // ================================================================ 슬라이드 동영상

    public void requireFfmpeg() {
        tools.require("ffmpeg");
    }

    /**
     * 고른 사진들을 순서대로 이어 붙인 슬라이드 MP4 를 뒤에서 만든다.
     * 화면 크기는 첫 사진 비율(긴 변 최대 1920px). 다른 크기의 사진은 자르거나 늘리지 않고 가운데에 두고 남는 곳은 검은색.
     */
    public void startSlideshow(long userId, long productId, List<Media> photos, double seconds, String outId) {
        proxyWorker.submit(() -> {
            String state = "failed";
            String error = null;
            Map<String, Object> info = new LinkedHashMap<>();
            long size = 0;
            Path work = null;
            Path out = null;
            try {
                work = Files.createDirectories(store.tmpDir(userId, productId).resolve("slides-" + outId));
                ImageOps.Size first = pixelSource(userId, productId, photos.get(0)).display();
                double k = Math.min(1.0, 1920.0 / Math.max(first.width(), first.height()));
                int w = even(first.width() * k);
                int h = even(first.height() * k);
                StringBuilder list = new StringBuilder();
                String sec = String.format(Locale.ROOT, "%.3f", seconds);
                for (int i = 0; i < photos.size(); i++) {
                    PixelSource src = pixelSource(userId, productId, photos.get(i));
                    String name = String.format(Locale.ROOT, "f%04d.jpg", i + 1);
                    Path frame = work.resolve(name);
                    tools.heavy(() -> {
                        Files.write(frame, ImageOps.jpeg(slideFrame(src, w, h), 0.92f));
                        return null;
                    });
                    list.append("file '").append(name).append("'\nduration ").append(sec).append('\n');
                    if (i == photos.size() - 1) {
                        list.append("file '").append(name).append("'\n"); // concat 은 마지막 장을 한 번 더 적어야 길이가 맞다
                    }
                }
                Files.writeString(work.resolve("list.txt"), list);
                Path tmp = work.resolve("out.mp4");
                List<String> cmd = new ArrayList<>();
                tools.find("nice").ifPresent(n -> cmd.addAll(List.of(n, "-n", "10")));
                cmd.addAll(List.of(tools.require("ffmpeg"), "-nostdin", "-hide_banner", "-v", "error", "-y",
                        "-f", "concat", "-safe", "0", "-i", work.resolve("list.txt").toString(),
                        "-vf", "fps=30,format=yuv420p", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                        "-tune", "stillimage", "-threads", "1", "-movflags", "+faststart", "-an", tmp.toString()));
                ExternalTools.Result r = tools.run(cmd, Duration.ofMinutes(30));
                if (r.exit() != 0 || !Files.isRegularFile(tmp)) {
                    throw new IOException("동영상 인코딩 실패: " + r.stderrTail());
                }
                out = store.filesDir(userId, productId).resolve(outId + ".mp4");
                Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Path cache = store.cacheDir(userId, productId);
                BufferedImage firstFrame = ImageOps.read(work.resolve("f0001.jpg"), null, 1);
                for (int s : PREVIEW_SIZES) {
                    writePreview(firstFrame, cache.resolve(outId + "_" + s + ".jpg"), s);
                }
                size = Files.size(out);
                info.put("width", w);
                info.put("height", h);
                info.put("count", photos.size());
                info.put("seconds", seconds);
                info.put("duration", Math.round(seconds * photos.size() * 1000) / 1000.0);
                info.put("bytes", size);
                state = "ready";
            } catch (Exception e) {
                log.warn("슬라이드 동영상 만들기 실패", e);
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                deleteTree(work);
            }
            String finalState = state;
            String finalError = error;
            long finalSize = size;
            boolean kept;
            try {
                kept = store.update(userId, productId, p -> {
                    for (Media x : p.media) {
                        if (x.id.equals(outId)) {
                            x.status = finalState;
                            x.error = finalError;
                            x.size = finalSize;
                            x.info.putAll(info);
                            return true;
                        }
                    }
                    return false;
                });
            } catch (RuntimeException e) {
                kept = false;
            }
            if (!kept && out != null) { // 만드는 사이에 지웠으면 결과 파일도 지운다
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // 무시
                }
            }
        });
    }

    /** 사진 한 장을 w×h 검은 화면 가운데에 놓는다. 화면보다 크면 비율대로 줄이고, 작으면 그대로 둔다. */
    private static BufferedImage slideFrame(PixelSource src, int w, int h) throws IOException {
        ImageOps.Size disp = src.display();
        double k = Math.min(1.0, Math.min((double) w / disp.width(), (double) h / disp.height()));
        int tw = Math.max(1, (int) Math.round(disp.width() * k));
        int th = Math.max(1, (int) Math.round(disp.height() * k));
        BufferedImage img = ImageOps.read(src.file(), null,
                ImageOps.subsampleFor(Math.max(disp.width(), disp.height()), Math.max(tw, th)));
        boolean swap = src.orientation() >= 5;
        img = ImageOps.orient(ImageOps.resize(img, swap ? th : tw, swap ? tw : th), src.orientation());
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = canvas.createGraphics();
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, 0, w, h);
        g.drawImage(img, (w - img.getWidth()) / 2, (h - img.getHeight()) / 2, null);
        g.dispose();
        return canvas;
    }

    private static int even(double v) {
        return Math.max(2, (int) Math.round(v / 2) * 2);
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // 임시 폴더 정리 실패는 무시
        }
    }

    // ================================================================ 커버 · 상세

    PixelSource pixelSource(long userId, long productId, Media m) throws IOException {
        if (!"photo".equals(m.type)) {
            throw new ResponseStatusException(BAD_REQUEST, "사진에서만 만들 수 있습니다. 영상은 장면을 캡처해 사진으로 만든 뒤 사용하세요.");
        }
        Path file = m.work != null ? store.cacheDir(userId, productId).resolve(m.work)
                : store.filesDir(userId, productId).resolve(m.file);
        Object src = m.info.get("src");
        if (src instanceof Map<?, ?> s) {
            return new PixelSource(file, ((Number) s.get("w")).intValue(), ((Number) s.get("h")).intValue(),
                    ((Number) s.get("o")).intValue());
        }
        ImageOps.Size raw = ImageOps.rawSize(file);
        return new PixelSource(file, raw.width(), raw.height(), 1);
    }

    /** 원본의 정사각형 영역(화면 방향 좌표)을 잘라 900×900 JPG(2MB 이하)로 만든다. */
    public Media makeCover(long userId, long productId, Media original, int x, int y, int size) throws IOException {
        PixelSource src = pixelSource(userId, productId, original);
        ImageOps.Size disp = src.display();
        size = Math.min(size, Math.min(disp.width(), disp.height()));
        if (size < 1) {
            throw new ResponseStatusException(BAD_REQUEST, "자르기 영역이 잘못되었습니다.");
        }
        x = Math.max(0, Math.min(x, disp.width() - size));
        y = Math.max(0, Math.min(y, disp.height() - size));
        Rectangle raw = ImageOps.toRaw(x, y, size, size, src.width(), src.height(), src.orientation());
        int fx = x, fy = y, fs = size;
        return derive(userId, productId, original, "cover", () -> {
            BufferedImage img = ImageOps.read(src.file(), raw, ImageOps.subsampleFor(fs, COVER_SIZE));
            img = ImageOps.orient(ImageOps.resize(img, COVER_SIZE, COVER_SIZE), src.orientation());
            return ImageOps.jpegWithin(img, COVER_MAX_BYTES);
        }, fx + "," + fy + "," + fs);
    }

    /** 상세 한 장의 최대 픽셀 수(서버 메모리 보호). 4800만 화소 사진 전체도 들어간다. */
    static final long DETAIL_MAX_PIXELS = 50_000_000L;

    /**
     * 원본의 사각형 영역(화면 방향 좌표)을 잘라 상세 JPG(10MB 이하)로 만든다.
     * 늘리거나 줄이지 않는다: 자른 영역의 원본 픽셀 그대로(해상도 유지). 용량은 JPG 품질로만 맞춘다.
     */
    public Media makeDetail(long userId, long productId, Media original, int x, int y, int w, int h) throws IOException {
        PixelSource src = pixelSource(userId, productId, original);
        ImageOps.Size disp = src.display();
        x = Math.max(0, Math.min(x, disp.width() - 1));
        y = Math.max(0, Math.min(y, disp.height() - 1));
        w = Math.min(w, disp.width() - x);
        h = Math.min(h, disp.height() - y);
        if (w < 1 || h < 1) {
            throw new ResponseStatusException(BAD_REQUEST, "자르기 영역이 잘못되었습니다.");
        }
        if ((long) w * h > DETAIL_MAX_PIXELS) {
            throw new ResponseStatusException(BAD_REQUEST, "영역이 너무 큽니다(최대 5000만 화소). 조금 더 잘라 주세요.");
        }
        Rectangle raw = ImageOps.toRaw(x, y, w, h, src.width(), src.height(), src.orientation());
        return derive(userId, productId, original, "detail", () -> {
            BufferedImage img = ImageOps.orient(ImageOps.read(src.file(), raw, 1), src.orientation());
            return ImageOps.jpegWithin(img, DETAIL_MAX_BYTES);
        }, x + "," + y + "," + w + "," + h);
    }

    private Media derive(long userId, long productId, Media original, String kind,
                         ExternalTools.Work<ImageOps.Encoded> work, String crop) throws IOException {
        String id = store.update(userId, productId, p -> "m" + (++p.seq));
        Path cache = store.cacheDir(userId, productId);
        Path out = store.filesDir(userId, productId).resolve(id + ".jpg");
        record Made(ImageOps.Encoded enc, int width, int height) {
        }
        Made made = tools.heavy(() -> {
            ImageOps.Encoded e = work.run();
            Files.write(out, e.bytes());
            ImageOps.Size dims = ImageOps.rawSize(out);
            BufferedImage img = ImageOps.read(out, null,
                    ImageOps.subsampleFor(Math.max(dims.width(), dims.height()), 1600));
            for (int size : PREVIEW_SIZES) {
                writePreview(img, cache.resolve(id + "_" + size + ".jpg"), size);
            }
            return new Made(e, dims.width(), dims.height());
        });
        ImageOps.Encoded enc = made.enc();
        Media m = new Media();
        m.id = id;
        m.kind = kind;
        m.type = "photo";
        m.file = out.getFileName().toString();
        m.name = kind + "_" + baseName(original.name) + ".jpg";
        m.size = enc.bytes().length;
        m.createdAt = ProductStore.now();
        m.source = new Source();
        m.source.fromId = original.id;
        m.source.fromName = original.name;
        m.source.crop = crop;
        m.info.put("format", "JPG");
        m.info.put("bytes", m.size);
        m.info.put("width", made.width());
        m.info.put("height", made.height());
        m.info.put("quality", Math.round(enc.quality() * 100));
        m.info.put("maxBytes", kind.equals("cover") ? COVER_MAX_BYTES : DETAIL_MAX_BYTES);
        return m;
    }

    // ================================================================ 공통

    static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 초 → "00:03.200" */
    static String stamp(double t) {
        long ms = Math.round(t * 1000);
        return String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60000, (ms / 1000) % 60, ms % 1000);
    }

    private static Double round(Double v, int digits) {
        if (v == null || v.isNaN() || v.isInfinite()) {
            return null;
        }
        double k = Math.pow(10, digits);
        return Math.round(v * k) / k;
    }

    private static double fraction(String s) {
        String[] p = s.split("/");
        try {
            double num = Double.parseDouble(p[0]);
            double den = p.length > 1 ? Double.parseDouble(p[1]) : 1;
            return den == 0 ? 0 : num / den;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String s) {
        try {
            return s == null ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String codecName(String c) {
        if (c == null) {
            return null;
        }
        return switch (c) {
            case "h264" -> "H.264";
            case "hevc" -> "H.265(HEVC)";
            case "av1" -> "AV1";
            case "vp9" -> "VP9";
            case "vp8" -> "VP8";
            case "mpeg4" -> "MPEG-4";
            case "prores" -> "ProRes";
            case "aac" -> "AAC";
            case "mp3" -> "MP3";
            case "opus" -> "Opus";
            case "pcm_s16le", "pcm_s24le" -> "PCM";
            case "alac" -> "ALAC";
            case "ac3" -> "AC-3";
            default -> c.toUpperCase(Locale.ROOT);
        };
    }
}
