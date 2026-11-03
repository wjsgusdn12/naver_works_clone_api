package com.jhwjhw.naverworkscloneapi.board.market;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// 미국 시세: Alpha Vantage 무료 키(하루 25번, 1분 5번까지). 그래서 부를 때마다 간격을 두고(기본 13초),
// 한 번 받은 일별 시세는 MarketReportService가 하루 동안 재사용함.
// 응답 키("Time Series (Daily)"·"4. close"·"top_gainers" 등)는 문서 예시 기준이며 처음 받을 때 확인할 것.
public class UsMarketClient {
    static final String URL = "https://www.alphavantage.co/query";

    // 하루치 종가·거래량
    public record DailyBar(LocalDate date, BigDecimal close, long volume) {
    }

    // 급등·급락·거래 상위 한 줄. changePercent는 % 숫자(예: 12.34)
    public record Mover(String ticker, BigDecimal price, BigDecimal changePercent, long volume) {
    }

    // date: 이 목록이 기준으로 한 거래일(last_updated의 날짜)
    public record Movers(LocalDate date, List<Mover> gainers, List<Mover> losers, List<Mover> active) {
    }

    // 하루 호출 수를 다 쓴 경우(그날은 더 부르지 않음)
    public static class LimitReachedException extends IOException {
        LimitReachedException(String message) {
            super(message);
        }
    }

    private final MarketHttp http;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final Duration gap;
    private long lastCallNanos;

    public UsMarketClient(MarketHttp http, ObjectMapper objectMapper, String apiKey, Duration gap) {
        this.http = http;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.gap = gap;
    }

    // 최근 약 100거래일의 일별 시세, 최신 날짜부터
    public List<DailyBar> daily(String symbol) throws IOException {
        Map<?, ?> root = call("function=TIME_SERIES_DAILY&symbol=" + encode(symbol));
        if (!(root.get("Time Series (Daily)") instanceof Map<?, ?> series)) {
            throw new IOException("Alpha Vantage daily response has no time series. symbol=" + symbol);
        }
        List<DailyBar> bars = new ArrayList<>();
        for (Map.Entry<?, ?> entry : series.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> values)) continue;
            BigDecimal close = KoreaMarketClient.number(values.get("4. close"));
            BigDecimal volume = KoreaMarketClient.number(values.get("5. volume"));
            try {
                LocalDate date = LocalDate.parse(entry.getKey().toString());
                if (close != null) bars.add(new DailyBar(date, close, volume == null ? 0 : volume.longValue()));
            } catch (RuntimeException ignored) {
                // 날짜가 아닌 키는 건너뜀
            }
        }
        bars.sort(Comparator.comparing(DailyBar::date).reversed());
        return bars;
    }

    // 장 마감 기준 급등·급락·거래 상위(무료는 마감 후 하루 한 번 갱신, 과거 날짜는 없음)
    public Movers movers() throws IOException {
        Map<?, ?> root = call("function=TOP_GAINERS_LOSERS");
        String updated = KoreaMarketClient.text(root.get("last_updated"));
        LocalDate date;
        try {
            date = LocalDate.parse(updated == null ? "" : updated.substring(0, Math.min(10, updated.length())));
        } catch (RuntimeException exception) {
            throw new IOException("Alpha Vantage movers response has no last_updated date.");
        }
        return new Movers(date, movers(root.get("top_gainers")), movers(root.get("top_losers")),
                movers(root.get("most_actively_traded")));
    }

    private static List<Mover> movers(Object raw) {
        List<Mover> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return result;
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> row)) continue;
            String ticker = KoreaMarketClient.text(row.get("ticker"));
            BigDecimal price = KoreaMarketClient.number(row.get("price"));
            String percent = KoreaMarketClient.text(row.get("change_percentage"));
            BigDecimal change = KoreaMarketClient.number(percent == null ? null : percent.replace("%", ""));
            BigDecimal volume = KoreaMarketClient.number(row.get("volume"));
            if (ticker != null && price != null && change != null) {
                result.add(new Mover(ticker, price, change, volume == null ? 0 : volume.longValue()));
            }
        }
        return result;
    }

    // 1분 호출 한도에 걸리지 않게 앞 호출과 간격을 두고 부름. 한도·오류 안내는 200 응답 안의 글로 옴.
    private Map<?, ?> call(String query) throws IOException {
        waitGap();
        String body = http.get(URI.create(URL + "?" + query + "&apikey=" + encode(apiKey)));
        Map<?, ?> root;
        try {
            root = objectMapper.readValue(body, Map.class);
        } catch (JacksonException exception) {
            throw new IOException("Alpha Vantage returned non-JSON.");
        }
        for (String key : List.of("Note", "Information")) {
            if (root.get(key) != null) throw new LimitReachedException("Alpha Vantage limit: " + root.get(key));
        }
        if (root.get("Error Message") != null) throw new IOException("Alpha Vantage error: " + root.get("Error Message"));
        return root;
    }

    private synchronized void waitGap() throws IOException {
        long waitNanos = lastCallNanos == 0 ? 0 : gap.toNanos() - (System.nanoTime() - lastCallNanos);
        if (waitNanos > 0) {
            try {
                Thread.sleep(Duration.ofNanos(waitNanos));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for Alpha Vantage rate limit", exception);
            }
        }
        lastCallNanos = System.nanoTime();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
