package com.seobin.phonedrop.privatestore;

import java.io.IOException;
import java.net.URI;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.seobin.phonedrop.user.UserSession;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 안드로이드 공유 메뉴(private.webmanifest 의 share_target)가 보내는 POST /private-share 의 대체 경로.
 * 평소에는 서비스 워커(private-sw.js)가 이 요청을 가로채 기기 안에 두므로 서버까지 오지 않는다.
 * 서비스 워커가 없을 때(사이트 데이터 삭제 등)만 여기로 와서, 로그인돼 있으면 바로 저장하고 화면으로 돌려보낸다.
 */
@RestController
public class PrivateShareController {

    private final PrivateFileController drive;
    private final UserSession session;

    public PrivateShareController(PrivateFileController drive, UserSession session) {
        this.drive = drive;
        this.session = session;
    }

    @PostMapping("/private-share")
    public ResponseEntity<Void> share(@RequestParam(value = "files", required = false) MultipartFile[] files,
                                      HttpServletRequest request) throws IOException {
        if (session.current(request).isEmpty()) {
            return redirect("/private.html?share=login");
        }
        long count = files == null ? 0 : Stream.of(files).filter(f -> !f.isEmpty()).count();
        try {
            drive.upload(files == null ? new MultipartFile[0] : files, false, null, request);
        } catch (ResponseStatusException e) {
            return redirect("/private.html?share=failed");
        }
        return redirect("/private.html?shared=" + count);
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(location)).build();
    }
}
