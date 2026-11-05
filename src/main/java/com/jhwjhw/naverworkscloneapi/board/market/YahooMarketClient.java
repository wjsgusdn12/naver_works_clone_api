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

    static final String SCREENER = "https://query1.finance.yahoo.com/v1/finance/screener/predefined/saved?count=%d&scrIds=%s";

    // 급등·급락·거래 많은 종목(Yahoo 미리 정한 스크리너). 날짜는 시세 시각(뉴욕)의 날짜 — 장 마감 뒤에는 그날 것.
    // 거래대금 순위 목록은 없어서, 거래량 상위를 넉넉히(100개) 받아 주가×거래량으로 다시 줄 세우는 후보로 씀.
    public UsMarketClient.Movers movers() throws IOException {
        List<UsMarketClient.Mover> gainers = screener("day_gainers", 25);
        List<UsMarketClient.Mover> losers = screener("day_losers", 25);
        List<UsMarketClient.Mover> active = screener("most_actives", 100);
        LocalDate date = lastDate;
        if (date == null) throw new IOException("Yahoo screener has no market time.");
        return new UsMarketClient.Movers(date, gainers, losers, active);
    }

    // 마지막으로 읽은 스크리너의 시세 날짜(세 목록이 같은 날 것)
    private LocalDate lastDate;

    private List<UsMarketClient.Mover> screener(String id, int count) throws IOException {
        String body = http.get(URI.create(SCREENER.formatted(count, id)));
        Map<?, ?> root;
        try {
            root = objectMapper.readValue(body, Map.class);
        } catch (JacksonException exception) {
            throw new IOException("Yahoo screener returned non-JSON. id=" + id);
        }
        Map<?, ?> finance = map(root.get("finance"));
        if (!(finance.get("result") instanceof List<?> results) || results.isEmpty()) {
            throw new IOException("Yahoo screener has no result. id=" + id);
        }
        List<UsMarketClient.Mover> result = new ArrayList<>();
        if (!(map(results.getFirst()).get("quotes") instanceof List<?> quotes)) return result;
        for (Object value : quotes) {
            Map<?, ?> quote = map(value);
            String ticker = KoreaMarketClient.text(quote.get("symbol"));
            BigDecimal price = KoreaMarketClient.number(quote.get("regularMarketPrice"));
            BigDecimal change = KoreaMarketClient.number(quote.get("regularMarketChangePercent"));
            BigDecimal volume = KoreaMarketClient.number(quote.get("regularMarketVolume"));
            if (ticker == null || price == null || change == null) continue;
            String name = KoreaMarketClient.text(quote.get("shortName"));
            if (quote.get("regularMarketTime") instanceof Number seconds) {
                lastDate = Instant.ofEpochSecond(seconds.longValue()).atZone(NEW_YORK).toLocalDate();
            }
            result.add(new UsMarketClient.Mover(ticker, name, price, change.setScale(2, java.math.RoundingMode.HALF_UP),
                    volume == null ? 0 : volume.longValue()));
        }
        return result;
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }
}
