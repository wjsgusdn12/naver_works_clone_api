package com.jhwjhw.naverworkscloneapi.board.market;

import com.jhwjhw.naverworkscloneapi.board.market.UsMarketClient.DailyBar;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// 미국 일별 시세: Yahoo Finance 차트 주소(키·하루 한도 없음, 공식 API가 아니라 막히거나 형식이 바뀔 수 있음).
// 막히면 BoardMarketReportService가 Alpha Vantage로 대신 받음. 최근 6개월치를 받아 추이·등락을 셈.
public class YahooMarketClient {
    static final String URL = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final MarketHttp http;
    private final ObjectMapper objectMapper;

    public YahooMarketClient(MarketHttp http, ObjectMapper objectMapper) {
        this.http = http;
        this.objectMapper = objectMapper;
    }

    // 최근 약 6개월 일별 종가·거래량, 최신 날짜부터(Alpha Vantage와 같은 모양)
    public List<DailyBar> daily(String symbol) throws IOException {
        String body = http.get(URI.create(URL + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "?range=6mo&interval=1d"));
        Map<?, ?> root;
        try {
            root = objectMapper.readValue(body, Map.class);
        } catch (JacksonException exception) {
            throw new IOException("Yahoo chart returned non-JSON. symbol=" + symbol);
        }
        Map<?, ?> chart = map(root.get("chart"));
        if (!(chart.get("result") instanceof List<?> results) || results.isEmpty()) {
            throw new IOException("Yahoo chart has no result. symbol=" + symbol);
        }
        Map<?, ?> result = map(results.getFirst());
        if (!(result.get("timestamp") instanceof List<?> timestamps)) {
            throw new IOException("Yahoo chart has no timestamps. symbol=" + symbol);
        }
        Map<?, ?> quote = map(map(result.get("indicators")).get("quote") instanceof List<?> quotes
                && !quotes.isEmpty() ? quotes.getFirst() : null);
        List<?> closes = quote.get("close") instanceof List<?> list ? list : List.of();
        List<?> volumes = quote.get("volume") instanceof List<?> list ? list : List.of();
        List<DailyBar> bars = new ArrayList<>();
        for (int i = 0; i < timestamps.size() && i < closes.size(); i++) {
            BigDecimal close = KoreaMarketClient.number(closes.get(i));
            if (close == null || !(timestamps.get(i) instanceof Number seconds)) continue;
            // 장 시작 시각(뉴욕 시간)의 날짜가 그 거래일
            LocalDate date = Instant.ofEpochSecond(seconds.longValue()).atZone(NEW_YORK).toLocalDate();
            BigDecimal volume = i < volumes.size() ? KoreaMarketClient.number(volumes.get(i)) : null;
            bars.add(new DailyBar(date, close.setScale(4, java.math.RoundingMode.HALF_UP),
                    volume == null ? 0 : volume.longValue()));
        }
        if (bars.isEmpty()) throw new IOException("Yahoo chart has no closes. symbol=" + symbol);
        // 같은 날짜가 두 번 오면(장중 마지막 값 등) 뒤의 것을 씀
        Map<LocalDate, DailyBar> byDate = new java.util.LinkedHashMap<>();
        for (DailyBar bar : bars) byDate.put(bar.date(), bar);
        List<DailyBar> unique = new ArrayList<>(byDate.values());
        unique.sort(Comparator.comparing(DailyBar::date).reversed());
        return unique;
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }
}
