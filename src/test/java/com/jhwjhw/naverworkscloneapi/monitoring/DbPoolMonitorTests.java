package com.jhwjhw.naverworkscloneapi.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

// DB 연결 풀 기록: 1분·5분·1시간 칸마다 최대·평균·대기 최대를 모아 DB로 보내고,
// 그래프 칸 시작 시각에 맞춰 읽음(기록 없는 칸은 null). 풀 정보가 없으면 available=false.
class DbPoolMonitorTests {
    // DB 대신 메모리에 담는 가짜 저장소(같은 칸이면 합침)
    private static final class FakeRepository extends ApiPoolBucketRepository {
        private final Map<String, Cell> cells = new HashMap<>();

        private FakeRepository() {
            super(null);
        }

        @Override
        public void add(Unit unit, long bucket, Cell cell) {
            cells.merge(unit + ":" + bucket, cell, (before, added) -> new Cell(
                    Math.max(before.maxActive(), added.maxActive()), before.sumActive() + added.sumActive(),
                    before.samples() + added.samples(), Math.max(before.maxWaiting(), added.maxWaiting()),
                    Math.max(before.maxSize(), added.maxSize())));
        }

        @Override
        public Map<Long, Cell> read(Unit unit, long fromBucket, long toBucket) {
            Map<Long, Cell> result = new HashMap<>();
            for (long bucket = fromBucket; bucket <= toBucket; bucket++) {
                Cell cell = cells.get(unit + ":" + bucket);
                if (cell != null) result.put(bucket, cell);
            }
            return result;
        }
    }

    @Test
    void keepsMaxAverageWaitingPerUnitAndAlignsToChartStarts() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T05:00:05Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        FakeRepository repository = new FakeRepository();
        DbPoolMonitor monitor = new DbPoolMonitor(null, repository, clock);

        monitor.record(2, 0, 10);
        monitor.record(6, 1, 10);
        monitor.flush();
        // 보낸 뒤 같은 칸에 더 기록해도 합쳐짐
        monitor.record(4, 0, 10);
        now.set(Instant.parse("2026-11-03T05:01:10Z"));
        monitor.record(8, 0, 10);

        Instant first = Instant.parse("2026-11-03T05:00:00Z");
        Instant second = Instant.parse("2026-11-03T05:01:00Z");
        Instant empty = Instant.parse("2026-11-03T05:02:00Z");
        var minutes = monitor.snapshot(ApiPoolBucketRepository.Unit.MINUTE, List.of(first, second, empty));
        assertThat(minutes.available()).isFalse();
        assertThat(minutes.trend()).hasSize(3);
        assertThat(minutes.trend().get(0).maxActive()).isEqualTo(6);
        assertThat(minutes.trend().get(0).avgActive()).isEqualTo(4.0);
        assertThat(minutes.trend().get(0).maxWaiting()).isEqualTo(1);
        assertThat(minutes.trend().get(1).maxActive()).isEqualTo(8);
        assertThat(minutes.trend().get(2).maxActive()).isNull();
        assertThat(minutes.trend().get(2).avgActive()).isNull();

        // 1시간 칸에는 네 번이 모두 모임: 최대 8, 평균 (2+6+4+8)/4 = 5
        var hours = monitor.snapshot(ApiPoolBucketRepository.Unit.HOUR, List.of(first));
        assertThat(hours.trend().getFirst().maxActive()).isEqualTo(8);
        assertThat(hours.trend().getFirst().avgActive()).isEqualTo(5.0);
    }
}
