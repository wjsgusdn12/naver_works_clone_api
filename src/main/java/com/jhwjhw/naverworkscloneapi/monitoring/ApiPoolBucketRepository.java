package com.jhwjhw.naverworkscloneapi.monitoring;

import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// DB 연결 풀 사용량을 1분·5분·1시간 칸으로 DB에 기록하고 읽음. 칸 자리는 단위별로 정해진 개수를 돌려 씀.
// 같은 자리에 옛 칸이 남아 있으면 새 값으로 덮어씀 → 지우는 쿼리 없이 행 수가 고정됨.
// 서버 여러 대가 같은 행에 써도 맞도록 요청 집계(ApiMetricBucketRepository)와 같은 방식:
//   1) UPDATE (같은 칸이면 큰 값·더하기, 옛 칸이면 덮어쓰기)  2) 바뀐 행이 없으면 INSERT  3) 중복 오류면 다시 1)
@Repository
@RequiredArgsConstructor
public class ApiPoolBucketRepository {
    // 칸 길이와 돌려 쓰는 자리 수(대시보드 기간보다 조금 넉넉히)
    public enum Unit {
        // 1분 70자리(70분): 최근 60분
        MINUTE(60, 70),
        // 5분 300자리(25시간): 최근 24시간
        FIVE_MINUTES(300, 300),
        // 1시간 744자리(31일): 최근 7일·30일
        HOUR(3600, 744);

        final long seconds;
        final int slots;

        Unit(long seconds, int slots) {
            this.seconds = seconds;
            this.slots = slots;
        }

        public long bucketOf(long epochSecond) {
            return Math.floorDiv(epochSecond, seconds);
        }
    }

    // 한 칸에 모은 값. 평균 사용 = sumActive / samples.
    public record Cell(int maxActive, long sumActive, long samples, int maxWaiting, int maxSize) {
        public Double avgActive() {
            return samples == 0 ? null : Math.round(sumActive * 10.0 / samples) / 10.0;
        }
    }

    private static final String UPDATE_SQL = "UPDATE api_pool_bucket SET "
            + "max_active = CASE WHEN bucket = ? THEN GREATEST(max_active, ?) ELSE ? END, "
            + "sum_active = CASE WHEN bucket = ? THEN sum_active + ? ELSE ? END, "
            + "samples = CASE WHEN bucket = ? THEN samples + ? ELSE ? END, "
            + "max_waiting = CASE WHEN bucket = ? THEN GREATEST(max_waiting, ?) ELSE ? END, "
            + "max_size = CASE WHEN bucket = ? THEN GREATEST(max_size, ?) ELSE ? END, "
            + "bucket = ? WHERE unit = ? AND slot = ? AND bucket <= ?";
    private static final String INSERT_SQL = "INSERT INTO api_pool_bucket "
            + "(unit, slot, bucket, max_active, sum_active, samples, max_waiting, max_size) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String READ_SQL = "SELECT bucket, max_active, sum_active, samples, max_waiting, max_size "
            + "FROM api_pool_bucket WHERE unit = ? AND bucket BETWEEN ? AND ?";

    private final JdbcTemplate jdbc;

    // 한 칸을 기록(같은 칸이면 합침, 옛 칸이면 덮어씀)
    public void add(Unit unit, long bucket, Cell cell) {
        int slot = (int) Math.floorMod(bucket, (long) unit.slots);
        Object[] updateArgs = {
                bucket, cell.maxActive(), cell.maxActive(),
                bucket, cell.sumActive(), cell.sumActive(),
                bucket, cell.samples(), cell.samples(),
                bucket, cell.maxWaiting(), cell.maxWaiting(),
                bucket, cell.maxSize(), cell.maxSize(),
                bucket, unit.name(), slot, bucket };
        if (jdbc.update(UPDATE_SQL, updateArgs) != 0) return;
        try {
            jdbc.update(INSERT_SQL, unit.name(), slot, bucket, cell.maxActive(), cell.sumActive(), cell.samples(),
                    cell.maxWaiting(), cell.maxSize());
        } catch (DuplicateKeyException exception) {
            // 다른 서버가 방금 같은 행을 만들었으면 거기에 합침.
            jdbc.update(UPDATE_SQL, updateArgs);
        }
    }

    // fromBucket~toBucket 범위의 칸을 읽음. 기록이 없는 칸은 빠짐.
    public Map<Long, Cell> read(Unit unit, long fromBucket, long toBucket) {
        Map<Long, Cell> result = new HashMap<>();
        jdbc.query(READ_SQL, resultSet -> {
            result.put(resultSet.getLong("bucket"), new Cell(resultSet.getInt("max_active"),
                    resultSet.getLong("sum_active"), resultSet.getLong("samples"), resultSet.getInt("max_waiting"),
                    resultSet.getInt("max_size")));
        }, unit.name(), fromBucket, toBucket);
        return result;
    }
}
