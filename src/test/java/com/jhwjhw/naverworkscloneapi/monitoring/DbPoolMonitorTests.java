package com.jhwjhw.naverworkscloneapi.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

// DB 연결 풀 사용량 기록: 1분 칸마다 최대·평균·대기 최대, 60분 지난 칸은 버림, 풀 정보가 없으면 available=false.
class DbPoolMonitorTests {
    @Test
    void keepsMinuteMaxAverageAndLastSixtyMinutes() {
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
        DbPoolMonitor monitor = new DbPoolMonitor(null, clock);

        monitor.record(2, 0);
        monitor.record(6, 1);
        monitor.record(4, 0);
        var first = monitor.snapshot().recent().getFirst();
        assertThat(first.startAt()).isEqualTo(Instant.parse("2026-11-03T05:00:00Z"));
        assertThat(first.maxActive()).isEqualTo(6);
        assertThat(first.avgActive()).isEqualTo(4.0);
        assertThat(first.maxWaiting()).isEqualTo(1);
        assertThat(monitor.snapshot().available()).isFalse();

        // 61분 뒤 기록하면 처음 칸은 60분 창을 벗어나 버려짐
        now.set(Instant.parse("2026-11-03T06:01:00Z"));
        monitor.record(1, 0);
        assertThat(monitor.snapshot().recent()).hasSize(1);
        assertThat(monitor.snapshot().recent().getFirst().startAt()).isEqualTo(Instant.parse("2026-11-03T06:01:00Z"));
    }
}
