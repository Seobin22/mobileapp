package com.seobin.phonedrop.assistant.push;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import com.seobin.phonedrop.assistant.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * FCM HTTP v1 API 직접 호출. firebase-admin(의존성이 크다) 대신 서비스 계정 키로 JWT 를 서명해 토큰을 받는다.
 * OAuth 토큰 만료 계산은 OS 연동부라 실제 시각을 쓴다.
 */
public class FirebasePush implements Push {

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String projectId;
    private final String clientEmail;
    private final String tokenUri;
    private final PrivateKey privateKey;

    private String accessToken;
    private Instant accessTokenExpiry = Instant.EPOCH;

    public FirebasePush(Path credentialsFile) {
        try {
            JsonNode key = Json.parse(Files.readString(credentialsFile));
            this.projectId = key.path("project_id").asString();
            this.clientEmail = key.path("client_email").asString();
            this.tokenUri = key.path("token_uri").asString("https://oauth2.googleapis.com/token");
            String pem = key.path("private_key").asString()
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            this.privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Firebase 서비스 계정 키를 읽을 수 없습니다: " + credentialsFile, e);
        }
    }

    @Override
    public void send(String token, Map<String, String> data) {
        ObjectNode message = Json.object();
        ObjectNode body = message.putObject("message");
        body.put("token", token);
        ObjectNode dataNode = body.putObject("data");
        data.forEach(dataNode::put);
        body.putObject("android").put("priority", "high");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(message)))
                .build();
        HttpResponse<String> response = call(request);
        if (response.statusCode() == 200) {
            return;
        }
        String responseBody = response.body();
        if (response.statusCode() == 404 || responseBody.contains("UNREGISTERED")
                || (response.statusCode() == 400 && responseBody.contains("registration token"))) {
            throw new InvalidTokenException(responseBody);
        }
        throw new IllegalStateException("FCM 발송 실패 " + response.statusCode() + ": " + responseBody);
    }

    private synchronized String accessToken() {
        Instant now = Instant.now();
        if (accessToken != null && now.isBefore(accessTokenExpiry.minusSeconds(60))) {
            return accessToken;
        }
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        ObjectNode claims = Json.object();
        claims.put("iss", clientEmail);
        claims.put("scope", SCOPE);
        claims.put("aud", tokenUri);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.getEpochSecond() + 3600);
        String unsigned = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + b64.encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(unsigned.getBytes(StandardCharsets.UTF_8));
            String jwt = unsigned + "." + b64.encodeToString(signer.sign());
            String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer",
                    StandardCharsets.UTF_8) + "&assertion=" + jwt;
            HttpResponse<String> response = call(HttpRequest.newBuilder()
                    .uri(URI.create(tokenUri))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Google OAuth 토큰 발급 실패 " + response.statusCode());
            }
            JsonNode tokenJson = Json.parse(response.body());
            accessToken = tokenJson.path("access_token").asString();
            accessTokenExpiry = now.plusSeconds(tokenJson.path("expires_in").asLong(3600));
            return accessToken;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("JWT 서명 실패", e);
        }
    }

    private HttpResponse<String> call(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("FCM 연결 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FCM 호출 중단", e);
        }
    }
}
