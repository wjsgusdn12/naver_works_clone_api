package com.jhwjhw.naverworkscloneapi.board.market;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.function.Predicate;
import java.util.function.Supplier;

// 시세 API를 부르는 아주 작은 HTTP 창구. 테스트에서는 가짜 응답으로 바꿔 끼움.
@FunctionalInterface
public interface MarketHttp {
    String get(URI uri) throws IOException;

    static MarketHttp jdk() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return uri -> {
            // 일부 사이트(Yahoo 등)는 브라우저 흉내 없는 요청을 막아 일반적인 User-Agent를 붙임
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json, application/xml, text/xml, */*")
                    .header("User-Agent", "Mozilla/5.0 (compatible; naverworks-clone-market-report/1.0)").GET().build();
            try {
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() / 100 != 2) {
                    // 주소에 키가 들어 있어 주소는 남기지 않고 호스트·상태 코드만
                    throw new IOException("Market API " + uri.getHost() + " returned " + response.statusCode());
                }
                return response.body();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Market API call interrupted", exception);
            }
        };
    }

    // 하루 동안 같은 주소의 응답을 파일로 보관(개발 중 서버가 자주 다시 켜져도 하루 한도를 다시 쓰지 않게).
    // 파일 이름은 주소(키 포함)의 SHA-256 앞부분이라 키가 파일 이름에 드러나지 않음. keep이 false인 응답(한도 안내 등)은 보관 안 함.
    static MarketHttp dailyFileCache(MarketHttp inner, Path root, Supplier<LocalDate> today, Predicate<String> keep) {
        return uri -> {
            Path dir = root.resolve(today.get().toString());
            Path file = dir.resolve(hash(uri.toString()) + ".txt");
            if (Files.isRegularFile(file)) return Files.readString(file, StandardCharsets.UTF_8);
            String body = inner.get(uri);
            if (keep.test(body)) {
                try {
                    Files.createDirectories(dir);
                    Files.writeString(file, body, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // 보관 실패는 무시(다음에 다시 받음)
                }
            }
            return body;
        };
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
