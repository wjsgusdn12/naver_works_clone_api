package com.jhwjhw.naverworkscloneapi.board;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

// 증시 보고용 꾸밈 블록(히어로·배너·콜아웃·지수 카드·등락 막대·히트맵·순위)의 속성 검사.
// 정해 둔 이름의 속성만, 글자는 길이 제한·제어 문자 제거, 숫자는 유한한 값만, 목록은 개수 제한으로 새로 만듦.
// 화면은 이 값을 글자·숫자로만 그려서(HTML로 넣지 않음) 스크립트가 들어갈 길이 없음.
final class MarketBlocks {
    // 자식 노드 없이 속성만 가진 블록. callout만 안에 문단을 가짐.
    static final Set<String> LEAF_TYPES = Set.of("marketHero", "marketBanner", "marketStats", "marketBreadth",
            "marketHeatmap", "marketRanks");
    static final String CALLOUT = "callout";

    private static final Set<String> MARKETS = Set.of("us", "kr");
    private static final Set<String> MOODS = Set.of("up", "down", "flat");
    private static final Set<String> TONES = Set.of("info", "up", "down", "flat", "muted");
    private static final int MAX_TEXT = 120;

    private MarketBlocks() {
    }

    static boolean isLeaf(String type) {
        return LEAF_TYPES.contains(type);
    }

    // 검사한 속성. text에는 검색·미리보기용 글자를 덧붙임.
    static Map<String, Object> attrs(String type, Map<?, ?> source, Consumer<String> text) {
        Map<String, Object> result = new LinkedHashMap<>();
        switch (type) {
            case "marketHero" -> {
                put(result, "title", source.get("title"), text);
                put(result, "date", source.get("date"), text);
            }
            case "marketBanner" -> {
                putEnum(result, "market", source.get("market"), MARKETS);
                put(result, "title", source.get("title"), text);
                put(result, "date", source.get("date"), text);
                putEnum(result, "mood", source.get("mood"), MOODS);
                put(result, "moodLabel", source.get("moodLabel"), text);
            }
            case CALLOUT -> putEnum(result, "tone", source.get("tone"), TONES);
            case "marketStats" -> result.put("items", list(source.get("items"), 8, item -> {
                Map<String, Object> clean = new LinkedHashMap<>();
                put(clean, "label", item.get("label"), text);
                put(clean, "value", item.get("value"), text);
                put(clean, "change", item.get("change"), text);
                putNumber(clean, "rate", item.get("rate"), text);
                put(clean, "sub", item.get("sub"), text);
                List<Double> spark = new ArrayList<>();
                if (item.get("spark") instanceof List<?> values) {
                    for (Object value : values) {
                        if (spark.size() >= 60) break;
                        Double number = number(value);
                        if (number != null) spark.add(number);
                    }
                }
                if (spark.size() >= 2) clean.put("spark", spark);
                return clean;
            }));
            case "marketBreadth" -> {
                for (String key : List.of("up", "flat", "down")) {
                    Double number = number(source.get(key));
                    result.put(key, number == null ? 0 : Math.max(0, Math.min(100_000, number.longValue())));
                }
                put(result, "label", source.get("label"), text);
                text.accept("오름 " + result.get("up") + " 보합 " + result.get("flat") + " 내림 " + result.get("down"));
            }
            case "marketHeatmap" -> {
                put(result, "title", source.get("title"), text);
                result.put("items", list(source.get("items"), 20, item -> {
                    Map<String, Object> clean = new LinkedHashMap<>();
                    put(clean, "label", item.get("label"), text);
                    put(clean, "sub", item.get("sub"), text);
                    putNumber(clean, "rate", item.get("rate"), text);
                    return clean;
                }));
            }
            case "marketRanks" -> result.put("groups", list(source.get("groups"), 4, group -> {
                Map<String, Object> clean = new LinkedHashMap<>();
                put(clean, "title", group.get("title"), text);
                putEnum(clean, "tone", group.get("tone"), TONES);
                clean.put("items", list(group.get("items"), 10, item -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    put(row, "name", item.get("name"), text);
                    put(row, "value", item.get("value"), text);
                    putNumber(row, "rate", item.get("rate"), text);
                    put(row, "sub", item.get("sub"), text);
                    return row;
                }));
                return clean;
            }));
            default -> {
            }
        }
        text.accept("\n");
        return result;
    }

    private interface Item {
        Map<String, Object> clean(Map<?, ?> item);
    }

    private static List<Map<String, Object>> list(Object raw, int max, Item item) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(raw instanceof List<?> values)) return result;
        for (Object value : values) {
            if (result.size() >= max) break;
            if (value instanceof Map<?, ?> map) result.add(item.clean(map));
        }
        return result;
    }

    private static void put(Map<String, Object> target, String key, Object value, Consumer<String> text) {
        if (!(value instanceof String string)) return;
        String clean = string.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (clean.isEmpty()) return;
        if (clean.length() > MAX_TEXT) clean = clean.substring(0, MAX_TEXT);
        target.put(key, clean);
        text.accept(clean + " ");
    }

    private static void putEnum(Map<String, Object> target, String key, Object value, Set<String> allowed) {
        if (value instanceof String string && allowed.contains(string)) target.put(key, string);
    }

    private static void putNumber(Map<String, Object> target, String key, Object value, Consumer<String> text) {
        Double number = number(value);
        if (number == null) return;
        target.put(key, number);
        text.accept(String.format(java.util.Locale.ROOT, "%+.2f%% ", number));
    }

    private static Double number(Object value) {
        if (!(value instanceof Number number)) return null;
        double result = number.doubleValue();
        return Double.isFinite(result) && Math.abs(result) < 1e15 ? result : null;
    }
}
