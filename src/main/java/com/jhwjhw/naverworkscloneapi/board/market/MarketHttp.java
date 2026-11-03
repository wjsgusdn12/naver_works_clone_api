package com.jhwjhw.naverworkscloneapi.board.market;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

// 시세 API를 부르는 아주 작은 HTTP 창구. 테스트에서는 가짜 응답으로 바꿔 끼움.
@FunctionalInterface
public interface MarketHttp {
    String get(URI uri) throws IOException;

    static MarketHttp jdk() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json").GET().build();
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
}
