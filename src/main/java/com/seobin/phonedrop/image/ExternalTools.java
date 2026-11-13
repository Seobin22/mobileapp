package com.seobin.phonedrop.image;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * 서버에 설치된 명령(ffmpeg, ffprobe, exiftool, heif-convert)을 실행한다.
 * 서버가 CPU 1개·메모리 1GB 라서 무거운 작업(이미지 디코딩·ffmpeg)은 {@link #heavy} 로 한 번에 하나씩만 돌린다.
 */
@Component
public class ExternalTools {

    public record Result(int exit, byte[] stdout, String stderrTail) {
        public String text() {
            return new String(stdout, StandardCharsets.UTF_8);
        }
    }

    /** 무거운 작업 동시 실행 개수 제한. */
    private final Semaphore heavy = new Semaphore(1, true);
    private final Map<String, Optional<String>> found = new ConcurrentHashMap<>();

    public interface Work<T> {
        T run() throws IOException;
    }

    /** 무거운 작업을 차례대로 실행한다. 3분 넘게 기다리면 실패로 돌려준다. */
    public <T> T heavy(Work<T> work) throws IOException {
        try {
            if (!heavy.tryAcquire(180, TimeUnit.SECONDS)) {
                throw new ResponseStatusException(SERVICE_UNAVAILABLE, "다른 작업이 오래 걸리고 있습니다. 잠시 뒤 다시 시도해 주세요.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("중단됨", e);
        }
        try {
            return work.run();
        } finally {
            heavy.release();
        }
    }

    /** PATH 에서 명령을 찾는다. 없으면 빈 값. */
    public Optional<String> find(String name) {
        return found.computeIfAbsent(name, n -> {
            String path = System.getenv("PATH");
            if (path == null) {
                return Optional.empty();
            }
            for (String dir : path.split(File.pathSeparator)) {
                for (String candidate : List.of(n, n + ".exe")) {
                    Path p = Path.of(dir, candidate);
                    if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                        return Optional.of(p.toString());
                    }
                }
            }
            return Optional.empty();
        });
    }

    public boolean has(String name) {
        return find(name).isPresent();
    }

    public String require(String name) {
        return find(name).orElseThrow(() -> new ResponseStatusException(SERVICE_UNAVAILABLE,
                "서버에 " + name + " 이(가) 설치되어 있지 않습니다."));
    }

    /** 명령을 실행하고 표준출력을 모은다. timeout 이 지나면 강제 종료한다. */
    public Result run(List<String> command, Duration timeout) throws IOException {
        Path err = Files.createTempFile("phonedrop-tool-", ".err");
        try {
            Process process = new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.from(new File(isWindows() ? "NUL" : "/dev/null")))
                    .redirectError(err.toFile())
                    .start();
            CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> {
                try (InputStream in = process.getInputStream()) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    in.transferTo(buf);
                    return buf.toByteArray();
                } catch (IOException e) {
                    return new byte[0];
                }
            });
            boolean done;
            try {
                done = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("중단됨", e);
            }
            if (!done) {
                process.destroyForcibly();
                throw new IOException(Path.of(command.get(0)).getFileName() + " 시간 초과(" + timeout.toSeconds() + "초)");
            }
            String tail = Files.readString(err, StandardCharsets.UTF_8);
            if (tail.length() > 600) {
                tail = tail.substring(tail.length() - 600);
            }
            return new Result(process.exitValue(), out.join(), tail.strip());
        } finally {
            Files.deleteIfExists(err);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
