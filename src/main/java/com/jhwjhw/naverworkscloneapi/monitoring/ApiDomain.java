package com.jhwjhw.naverworkscloneapi.monitoring;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 요청 수 그래프를 영역(메시지·주소록·게시판·알림, 나머지는 기타)별로 나누기 위한 묶음. API 주소 앞부분으로 나눔.
// 순서는 화면 색 순서(맨 끝 "기타"는 회색). 로그인·프로필 등 나머지 주소는 기타.
public final class ApiDomain {
    public static final List<String> LABELS = List.of("메시지", "주소록", "게시판", "알림", "기타");
    private static final int OTHER = LABELS.size() - 1;

    // 주소 앞부분 → 영역 번호
    private static final Map<String, Integer> PREFIXES = Map.of(
            "/api/v1/chatRoom", 0,
            "/api/v1/addressBook", 1,
            "/api/v1/board", 2,
            "/api/v1/notification", 3);

    private ApiDomain() {
    }

    // "GET /api/v1/board/post/list" 모양의 이름이 속한 영역 번호. 모르는 주소·컨트롤러 도달 전 종료는 기타.
    static int indexOf(String endpoint) {
        int space = endpoint.indexOf(' ');
        String path = space < 0 ? endpoint : endpoint.substring(space + 1);
        for (Map.Entry<String, Integer> entry : PREFIXES.entrySet()) {
            String prefix = entry.getKey();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) return entry.getValue();
        }
        return OTHER;
    }

    // API별 호출 수를 영역별로 더함. 기타는 "전체 - 나머지 영역"이라 영역 합계가 항상 전체 요청 수와 같음.
    static List<Long> split(long total, Map<String, Long> countsByEndpoint) {
        long[] counts = new long[LABELS.size()];
        countsByEndpoint.forEach((endpoint, count) -> counts[indexOf(endpoint)] += count);
        long known = 0;
        for (int index = 0; index < OTHER; index++) known += counts[index];
        counts[OTHER] = Math.max(0, total - known);
        return Arrays.stream(counts).boxed().toList();
    }

    static List<Long> split(MetricsBucket data) {
        Map<String, Long> counts = new HashMap<>();
        data.getEndpoints().forEach((endpoint, stats) -> counts.put(endpoint, stats.getCount()));
        return split(data.getCount(), counts);
    }
}
