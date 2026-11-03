package com.jhwjhw.naverworkscloneapi.monitoring;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.annotation.PreDestroy;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// DB 연결 풀(HikariCP) 사용량을 10초마다 재서 1분·5분·1시간 칸으로 모았다가 30초마다 DB(api_pool_bucket)로 보냄.
// DB에 남으므로 대시보드 기간 탭(최근 60분·24시간·7일·30일)마다 같은 칸으로 그리고, 재시작해도 이력이 남음.
// "포화도" 신호: 사용 중 연결이 최대치에 붙거나 연결을 기다리는 요청이 생기면 DB 쪽이 병목이라는 뜻.
// 서버가 여러 대면 칸마다 가장 높은 서버의 값(최대 사용·대기)과 서버 한 대 기준 평균 사용을 보여 줌.
@Component
@Slf4j
public class DbPoolMonitor {
    // 그래프 한 점. 기록이 없는 칸은 값이 모두 null.
    public record Point(Instant startAt, Integer maxActive, Double avgActive, Integer maxWaiting) {
    }

    // 지금 값(이 서버) + 기간 칸별 기록. available=false면 풀 정보를 읽을 수 없는 환경(HikariCP가 아님).
    public record Snapshot(boolean available, int maxSize, int active, int idle, int waiting, List<Point> trend) {
    }

    private final HikariDataSource hikari;
    private final ApiPoolBucketRepository repository;
    private final Clock clock;
    // 단위별로 아직 DB에 보내지 않은 칸
    private Map<ApiPoolBucketRepository.Unit, Map<Long, Accumulator>> pending = emptyPending();

    // 한 칸에 모으는 중인 값
    private static final class Accumulator {
        private int maxActive;
        private long sumActive;
        private long samples;
        private int maxWaiting;
        private int maxSize;

        private void add(int active, int waiting, int size) {
            maxActive = Math.max(maxActive, active);
            sumActive += active;
            samples++;
            maxWaiting = Math.max(maxWaiting, waiting);
            maxSize = Math.max(maxSize, size);
        }

        private void merge(ApiPoolBucketRepository.Cell cell) {
            maxActive = Math.max(maxActive, cell.maxActive());
            sumActive += cell.sumActive();
            samples += cell.samples();
            maxWaiting = Math.max(maxWaiting, cell.maxWaiting());
            maxSize = Math.max(maxSize, cell.maxSize());
        }

        private ApiPoolBucketRepository.Cell cell() {
            return new ApiPoolBucketRepository.Cell(maxActive, sumActive, samples, maxWaiting, maxSize);
        }
    }

    @Autowired
    public DbPoolMonitor(DataSource dataSource, ApiPoolBucketRepository repository) {
        this(unwrap(dataSource), repository, Clock.systemUTC());
    }

    DbPoolMonitor(HikariDataSource hikari, ApiPoolBucketRepository repository, Clock clock) {
        this.hikari = hikari;
        this.repository = repository;
        this.clock = clock;
    }

    private static Map<ApiPoolBucketRepository.Unit, Map<Long, Accumulator>> emptyPending() {
        Map<ApiPoolBucketRepository.Unit, Map<Long, Accumulator>> map = new EnumMap<>(ApiPoolBucketRepository.Unit.class);
        for (ApiPoolBucketRepository.Unit unit : ApiPoolBucketRepository.Unit.values()) map.put(unit, new HashMap<>());
        return map;
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
        record(pool.getActiveConnections(), pool.getThreadsAwaitingConnection(), hikari.getMaximumPoolSize());
    }

    synchronized void record(int active, int waiting, int maxSize) {
        long second = clock.instant().getEpochSecond();
        pending.forEach((unit, cells) -> cells.computeIfAbsent(unit.bucketOf(second), key -> new Accumulator())
                .add(active, waiting, maxSize));
    }

    // 모아 둔 칸을 DB로 보냄. 대시보드를 열 때도 먼저 불러 최신 기록이 빠지지 않게 함.
    @Scheduled(fixedDelay = 30_000)
    public void flush() {
        Map<ApiPoolBucketRepository.Unit, Map<Long, Accumulator>> batch;
        synchronized (this) {
            batch = pending;
            pending = emptyPending();
        }
        // 칸별로 따로 저장해, 실패한 칸만 되돌림(이미 저장된 칸을 두 번 더하지 않게).
        batch.forEach((unit, cells) -> cells.forEach((bucket, accumulator) -> {
            try {
                repository.add(unit, bucket, accumulator.cell());
            } catch (RuntimeException exception) {
                log.warn("DB 연결 풀 기록을 저장하지 못해 다음에 다시 보냅니다. unit={}, reason={}", unit,
                        exception.getMessage());
                synchronized (this) {
                    pending.get(unit).computeIfAbsent(bucket, key -> new Accumulator()).merge(accumulator.cell());
                }
            }
        }));
    }

    // 서버가 꺼질 때 남은 기록을 마지막으로 저장(개발 중 자동 재시작 포함).
    @PreDestroy
    public void flushOnShutdown() {
        flush();
    }

    // 그래프 칸 시작 시각(starts)마다 그 단위 칸의 기록 + 지금 값.
    public Snapshot snapshot(ApiPoolBucketRepository.Unit unit, List<Instant> starts) {
        flush();
        List<Point> trend = new ArrayList<>();
        if (!starts.isEmpty()) {
            long from = unit.bucketOf(starts.getFirst().getEpochSecond());
            long to = unit.bucketOf(starts.getLast().getEpochSecond());
            Map<Long, ApiPoolBucketRepository.Cell> cells = repository.read(unit, from, to);
            for (Instant start : starts) {
                ApiPoolBucketRepository.Cell cell = cells.get(unit.bucketOf(start.getEpochSecond()));
                trend.add(cell == null || cell.samples() == 0 ? new Point(start, null, null, null)
                        : new Point(start, cell.maxActive(), cell.avgActive(), cell.maxWaiting()));
            }
        }
        HikariPoolMXBean pool = pool();
        if (pool == null) return new Snapshot(false, 0, 0, 0, 0, trend);
        return new Snapshot(true, hikari.getMaximumPoolSize(), pool.getActiveConnections(),
                pool.getIdleConnections(), pool.getThreadsAwaitingConnection(), trend);
    }
}
