package com.seobin.phonedrop.image;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 상품 하나. data/image/&lt;회원 id&gt;/&lt;상품 id&gt;/product.json 에 그대로 저장된다.
 * 필드는 JSON 직렬화를 위해 public 으로 둔다.
 */
public class Product {

    public long id;
    public String createdAt;
    public String updatedAt;
    public Purchase purchase = new Purchase();
    public List<SizeRow> sizes = new ArrayList<>();
    public Material material = new Material();
    public List<Media> media = new ArrayList<>();
    /** 미디어 id 발급용 일련번호. */
    public int seq;

    /** 사입 정보. 사입명은 장끼 표기를 글자·띄어쓰기·기호 그대로 저장한다(trim 하지 않음). */
    public static class Purchase {
        public String name = "";
        public String store = "";
        public String cost = "";
        /** 장끼 사진 파일명(상품 폴더 기준), 없으면 null. */
        public String receiptFile;
        public String receiptName;
    }

    /** 사이즈 한 행. 단위 cm. 숫자 형식 검사는 하지 않고 입력 그대로 둔다. */
    public static class SizeRow {
        public String label = "";
        public String total = "";
        public String shoulder = "";
        public String chest = "";
        public String sleeve = "";
        public String sleeveWidth = "";
        public String armhole = "";
        public String hem = "";
    }

    /** 소재. 값은 정해진 보기 중 하나, 선택 안 했으면 빈 문자열. */
    public static class Material {
        public String lining = "";
        public String stretch = "";
        public String sheer = "";
        public String touch = "";
        public String fit = "";
        public String thickness = "";
        public List<String> season = new ArrayList<>();
    }

    /** 원본·커버·상세 칸의 파일 하나. */
    public static class Media {
        public String id;
        /** original | cover | detail */
        public String kind;
        /** photo | video */
        public String type;
        /** 화면·다운로드에 쓰는 이름(올린 파일 이름). */
        public String name;
        /** 저장 파일명(files/ 기준). 원본은 올린 그대로 보관한다. */
        public String file;
        /** HEIC 등 자바가 못 읽는 사진을 변환한 작업용 JPG(cache/ 기준). 없으면 null. */
        public String work;
        public long size;
        public String createdAt;
        /** 분석 결과(수치). */
        public Map<String, Object> info = new LinkedHashMap<>();
        /** 캡처·변환 출처. 업로드 원본이면 null. */
        public Source source;
        /** 영상 미리보기용 변환 상태: null | making | ready | failed */
        public String proxy;
    }

    public static class Source {
        public String fromId;
        public String fromName;
        /** 영상 캡처 시각(초). */
        public Double time;
        /** 커버 자르기 영역 "x,y,size" (원본 화면 방향 기준 px). */
        public String crop;
    }
}
