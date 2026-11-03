package com.jhwjhw.naverworkscloneapi.monitoring;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// DB 연결 풀(HikariCP) 사용량을 10초마다 재서 1분 단위로 최근 60분을 이 서버 메모리에 보관.
// "포화도" 신호: 사용 중 연결이 최대치에 붙거나 연결을 기다리는 요청이 생기면 DB 쪽이 병목이라는 뜻.
// 기록은 서버마다 따로이고 재시작하면 비워짐(긴 기간 이력은 지표 시스템 몫이라 여기서는 최근 60분만).
@Component
@Slf4j
public class DbPoolMonitor {
    static final int KEEP_MINUTES = 60;

    // 1분 칸: 사용 중 연결 최대·평균, 연결을 기다린 요청 최대
    public record Minute(Instant startAt, int maxActive, double avgActive, int maxWaiting) {
    }

    // 지금 값 + 최근 60분. available=false면 풀 정보를 읽을 수 없는 환경(HikariCP가 아님).
    public record Snapshot(boolean available, int maxSize, int active, int idle, int waiting, List<Minute> recent) {
    }

    private final HikariDataSource hikari;
    private final Clock clock;
    private final Deque<Bucket> buckets = new ArrayDeque<>();

    private static final class Bucket {
        private final Instant startAt;
        private int maxActive;
        private long sumActive;
        private int samples;
        private int maxWaiting;

        private Bucket(Instant startAt) {
            this.startAt = startAt;
        }
    }

    @Autowired
    public DbPoolMonitor(DataSource dataSource) {
        this(unwrap(dataSource), Clock.systemUTC());
    }

    DbPoolMonitor(HikariDataSource hikari, Clock clock) {
        this.hikari = hikari;
        this.clock = clock;
    }

    private static HikariDataSource unwrap(DataSource dataSource) {
        try {
            if (dataSource instanceof HikariDataSource direct) return direct;
            if (dataSource.isWrapperFor(HikariDataSource.class)) return dataSource.unwrap(HikariDataSource.class);
        } catch (SQLException exception) {
            log.debug("DataSource is not HikariCP. reason={}", exception.getMessage());
        }
        return null;
    }

    private HikariPoolMXBean pool() {
        return hikari == null ? null : hikari.getHikariPoolMXBean();
    }

    @Scheduled(fixedRate = 10_000)
    public void sample() {
        HikariPoolMXBean pool = pool();
        if (pool == null) return;
        record(pool.getActiveConnections(), pool.getThreadsAwaitingConnection());
    }

    synchronized void record(int active, int waiting) {
        Instant minute = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        Bucket bucket = buckets.peekLast();
        if (bucket == null || !bucket.startAt.equals(minute)) {
            bucket = new Bucket(minute);
            buckets.addLast(bucket);
        }
        bucket.maxActive = Math.max(bucket.maxActive, active);
        bucket.sumActive += active;
        bucket.samples++;
        bucket.maxWaiting = Math.max(bucket.maxWaiting, waiting);
        Instant oldest = minute.minus(KEEP_MINUTES - 1, ChronoUnit.MINUTES);
        while (!buckets.isEmpty() && buckets.peekFirst().startAt.isBefore(oldest)) buckets.removeFirst();
    }

    public synchronized Snapshot snapshot() {
        HikariPoolMXBean pool = pool();
        List<Minute> recent = new ArrayList<>();
        for (Bucket bucket : buckets) {
            recent.add(new Minute(bucket.startAt, bucket.maxActive,
                    bucket.samples == 0 ? 0 : Math.round(bucket.sumActive * 10.0 / bucket.samples) / 10.0,
                    bucket.maxWaiting));
        }
        if (pool == null) return new Snapshot(false, 0, 0, 0, 0, recent);
        return new Snapshot(true, hikari.getMaximumPoolSize(), pool.getActiveConnections(),
                pool.getIdleConnections(), pool.getThreadsAwaitingConnection(), recent);
    }
}
