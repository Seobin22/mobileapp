package com.seobin.phonedrop.image.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 내보내기 방식 하나. 새 방식을 붙이려면 이 인터페이스를 구현한 @Component 클래스 파일 하나만 추가하면 된다.
 * 화면은 GET /api/image/export-methods 목록으로 버튼을 자동으로 그린다.
 * 내보낼 파일 고르기·파일명 붙이기는 공통으로 처리되어 {@link Request#files()} 로 전달된다.
 */
public interface ExportMethod {

    /** URL 에 쓰는 id(영문 소문자). 예: zip */
    String id();

    /** 버튼 글자. 예: zip 다운로드 */
    String label();

    /** 버튼 순서(작을수록 앞). */
    default int order() {
        return 100;
    }

    /** 내보내기를 실행하고 결과를 응답으로 쓴다(파일 다운로드, 완료 메시지 등). */
    void export(Request request, HttpServletResponse response) throws IOException;

    /** 내보낼 파일 하나: 붙일 이름과 실제 경로. */
    record File(String name, Path path, String kind) {
    }

    /**
     * @param baseName 상품 이름(사입명 첫 부분). 파일명 앞에 붙는다. 예: 커프스T
     * @param scope    cover | detail | all
     */
    record Request(long productId, String baseName, String scope, List<File> files) {
    }
}
