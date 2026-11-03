package com.jhwjhw.naverworkscloneapi.board.market;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// 한국 시세: 공공데이터포털 금융위원회_지수시세정보·주식시세정보(무료, 출처 표시 필요).
// 데이터는 기준일 다음 영업일 오후 1시 이후에 올라옴. 응답 숫자는 모두 문자열.
// 응답 항목 이름(basDt·idxNm·clpr·vs·fltRt·trPrc·mrktCtg·itmsNm·mrktTotAmt 등)은 활용 가이드 기준이며,
// 실제 키로 처음 받을 때 로그로 한 번 더 확인할 것(없는 항목은 빈 값으로 처리해 분석에서 빠짐).
public class KoreaMarketClient {
    static final String INDEX_URL = "https://apis.data.go.kr/1160100/GetMarketIndexInfoService_V2/getStockMarketIndex_V2";
    static final String STOCK_URL = "https://apis.data.go.kr/1160100/GetStockSecuritiesInfoService_V2/getStockPriceInfo_V2";
    private static final DateTimeFormatter BAS_DT = DateTimeFormatter.BASIC_ISO_DATE;

    // 지수 하나. tradeValue: 거래대금(원), yearHigh·yearLow: 52주 최고·최저(없으면 null)
    public record IndexQuote(LocalDate date, String name, BigDecimal close, BigDecimal change, BigDecimal rate,
            BigDecimal tradeValue, BigDecimal yearHigh, BigDecimal yearLow) {
    }

    // 종목 하나. market: KOSPI·KOSDAQ·KONEX, tradeValue·marketCap: 원
    public record StockQuote(String name, String market, BigDecimal close, BigDecimal change, BigDecimal rate,
            long volume, BigDecimal tradeValue, BigDecimal marketCap) {
    }

    private final MarketHttp http;
    private final ObjectMapper objectMapper;
    private final String serviceKey;

    public KoreaMarketClient(MarketHttp http, ObjectMapper objectMapper, String serviceKey) {
        this.http = http;
        this.objectMapper = objectMapper;
        this.serviceKey = serviceKey;
    }

    // 지수의 날짜별 종가(추이 그래프용)
    public record IndexClose(LocalDate date, BigDecimal close) {
    }

    // before 전날까지 코스피 지수가 있는 마지막 거래일. 아직 안 올라왔으면 그 앞 거래일이 나옴.
    public Optional<LocalDate> latestTradingDay(LocalDate before) throws IOException {
        return history("코스피", before).stream().map(IndexClose::date).max(LocalDate::compareTo);
    }

    // before 전날까지 약 45일(30거래일 남짓)의 종가, 오래된 날짜부터
    public List<IndexClose> history(String name, LocalDate before) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("idxNm", name);
        params.put("beginBasDt", before.minusDays(45).format(BAS_DT));
        params.put("endBasDt", before.format(BAS_DT));
        params.put("numOfRows", "60");
        List<IndexClose> result = new ArrayList<>();
        for (Map<String, Object> item : items(INDEX_URL, params)) {
            LocalDate date = date(item.get("basDt"));
            BigDecimal close = number(item.get("clpr"));
            if (date != null && close != null && date.isBefore(before) && name.equals(text(item.get("idxNm")))) {
                result.add(new IndexClose(date, close));
            }
        }
        result.sort(java.util.Comparator.comparing(IndexClose::date));
        return result;
    }

    // 그날 코스피·코스닥 지수(이름 순서대로, 없는 것은 빠짐)
    public List<IndexQuote> indices(LocalDate date) throws IOException {
        List<IndexQuote> result = new ArrayList<>();
        for (String name : List.of("코스피", "코스닥")) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("idxNm", name);
            params.put("basDt", date.format(BAS_DT));
            params.put("numOfRows", "5");
            for (Map<String, Object> item : items(INDEX_URL, params)) {
                if (!name.equals(text(item.get("idxNm")))) continue;
                result.add(new IndexQuote(date, name, number(item.get("clpr")), number(item.get("vs")),
                        number(item.get("fltRt")), number(item.get("trPrc")), number(item.get("yrWRcrdHgst")),
                        number(item.get("yrWRcrdLwst"))));
                break;
            }
        }
        return result;
    }

    // 그날 상장 종목 전체(코스피·코스닥, 약 2,700개를 한 번에)
    public List<StockQuote> stocks(LocalDate date) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("basDt", date.format(BAS_DT));
        params.put("numOfRows", "5000");
        List<StockQuote> result = new ArrayList<>();
        for (Map<String, Object> item : items(STOCK_URL, params)) {
            String market = text(item.get("mrktCtg"));
            BigDecimal close = number(item.get("clpr"));
            if (close == null || !("KOSPI".equals(market) || "KOSDAQ".equals(market))) continue;
            BigDecimal volume = number(item.get("trqu"));
            result.add(new StockQuote(text(item.get("itmsNm")), market, close, number(item.get("vs")),
                    number(item.get("fltRt")), volume == null ? 0 : volume.longValue(), number(item.get("trPrc")),
                    number(item.get("mrktTotAmt"))));
        }
        return result;
    }

    // 공통 요청: 결과 코드가 00이 아니면 실패. items.item은 한 건이면 객체, 여러 건이면 배열로 올 수 있어 둘 다 받음.
    private List<Map<String, Object>> items(String base, Map<String, String> params) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        // 키는 공공데이터포털의 "일반 인증키(Decoding)" 값. 여기서 한 번만 인코딩함(+ / = 가 그대로 가면 인증 실패).
        query.put("serviceKey", serviceKey);
        query.put("resultType", "json");
        query.put("pageNo", "1");
        query.putAll(params);
        String queryString = query.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        String body = http.get(URI.create(base + "?" + queryString));
        Map<?, ?> root;
        try {
            root = objectMapper.readValue(body, Map.class);
        } catch (JacksonException exception) {
            // 키 오류 등은 JSON 대신 XML 오류 문서가 옴(내용에 키가 없으므로 앞부분만 남김)
            throw new IOException("Korea market API returned non-JSON: "
                    + body.substring(0, Math.min(200, body.length())).replaceAll("\\s+", " "));
        }
        Map<?, ?> response = map(root.get("response"));
        Map<?, ?> header = map(response.get("header"));
        String code = text(header.get("resultCode"));
        if (code != null && !"00".equals(code)) {
            throw new IOException("Korea market API error " + code + " " + text(header.get("resultMsg")));
        }
        Object item = map(map(response.get("body")).get("items")).get("item");
        List<Map<String, Object>> result = new ArrayList<>();
        if (item instanceof List<?> list) {
            for (Object value : list) if (value instanceof Map<?, ?> row) result.add(cast(row));
        } else if (item instanceof Map<?, ?> row) {
            result.add(cast(row));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> row) {
        return (Map<String, Object>) row;
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    static String text(Object value) {
        if (value == null) return null;
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }

    // "1,234.5" "-.45" "+3" 같은 문자열·숫자를 BigDecimal로. 못 읽으면 null.
    static BigDecimal number(Object value) {
        String text = text(value);
        if (text == null) return null;
        try {
            return new BigDecimal(text.replace(",", "").replace("+", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static LocalDate date(Object value) {
        String text = text(value);
        if (text == null) return null;
        try {
            return LocalDate.parse(text, BAS_DT);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
