package com.jhwjhw.naverworkscloneapi.board.market;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

// 미국 종목 기호 → 한글 이름. 직접 정리한 표(resources/board/market/us-stock-names-ko.tsv)를 한 번 읽어 둠.
// 표에 없는 종목은 null(부르는 쪽이 영문 이름을 씀).
public final class UsStockNames {
    private static final String FILE = "/board/market/us-stock-names-ko.tsv";
    private static final Map<String, String> NAMES = load();

    private UsStockNames() {
    }

    public static String korean(String ticker) {
        return ticker == null ? null : NAMES.get(ticker.toUpperCase(java.util.Locale.ROOT));
    }

    private static Map<String, String> load() {
        Map<String, String> names = new HashMap<>();
        try (InputStream input = UsStockNames.class.getResourceAsStream(FILE)) {
            if (input == null) return Map.of();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t", 2);
                if (parts.length == 2 && !parts[1].isBlank()) names.put(parts[0].strip(), parts[1].strip());
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return Map.copyOf(names);
    }
}
