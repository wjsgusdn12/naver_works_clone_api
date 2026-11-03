package com.jhwjhw.naverworkscloneapi.monitoring;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// DB의 1분·5분 칸과 1시간 요약을 읽어 대시보드 숫자로 정리(모두 DB라 서버·Redis를 재시작해도 남음).
// 기간은 모두 "지금부터 거꾸로" 밀려가는 창(정각·자정이 지나도 그래프가 비지 않음). 맨 오른쪽 점이 지금 칸.
// - 최근 60분: 1분 점 60개
// - 최근 24시간: 5분 점 288개
// - 최근 7일: 1시간 점 168개 / 최근 30일: 1시간 점 720개(1시간 요약뿐이라 정시 단위로 밀려감)
// 숫자 카드·순위 합계는 그래프 점과 같은 칸을 더한 값(서로 항상 맞아떨어짐).
// 잘게 쪼갠 기록이 없는 구간(예: 기능 추가 전)은 큰 단위 기록(5분·1시간 합계)을
// 그 구간의 칸마다 고르게 나눠 채우고 추정값(estimated)으로 표시.
@Service
public class ApiDashboardService {
    static final int TOP_COUNT = 5;
    // 몇 번 안 불린 API가 "가장 느림"에 오르지 않도록 최소 호출 수.
    static final int SLOW_MIN_REQUESTS = 5;
    private static final long BUCKETS_PER_HOUR = 3600 / ApiMetricsRecorder.BUCKET_SECONDS;
    static final int DAY_BUCKETS = 24 * (int) BUCKETS_PER_HOUR;
    static final int WEEK_HOURS = 24 * 7;
    static final int MONTH_HOURS = 24 * 30;
    static final int HOUR_MINUTES = 60;
    private static final int DAY_HOURS = 24;
    private static final int MINUTES_PER_BUCKET = (int) (ApiMetricsRecorder.BUCKET_SECONDS / 60);

    private final ApiMetricsRecorder recorder;
    private final ApiMetricsStore store;
    private final ServerStatusReporter reporter;
    private final DataSource dataSource;
    private final ApiMetricHourlyRepository hourlyRepository;
    private final ApiMetricBucketRepository bucketRepository;
    private final DbPoolMonitor poolMonitor;
    // 하루·한 주를 자르는 기준 시간대
    private final ZoneId zone;

    public ApiDashboardService(ApiMetricsRecorder recorder, ApiMetricsStore store, ServerStatusReporter reporter,
            DataSource dataSource, ApiMetricHourlyRepository hourlyRepository,
            ApiMetricBucketRepository bucketRepository, DbPoolMonitor poolMonitor,
            @Value("${app.monitoring.zone:Asia/Seoul}") String zone) {
        this.poolMonitor = poolMonitor;
        this.recorder = recorder;
        this.store = store;
        this.reporter = reporter;
        this.dataSource = dataSource;
        this.hourlyRepository = hourlyRepository;
        this.bucketRepository = bucketRepository;
        this.zone = ZoneId.of(zone);
    }

    // 대시보드 기간 탭. 화면이 고른 기간 하나만 계산해 보냄(응답 크기·DB 조회를 줄임).
    public enum Range {
        HOUR, DAY, WEEK, MONTH
    }

    public ApiDashboardResponse dashboard(Range range) {
        Instant now = Instant.now();
        // 이 서버가 아직 보내지 않은 최근 기록과 상태를 먼저 반영(다른 서버의 1시간 요약은 최대 1분 늦게 반영).
        recorder.flush();
        recorder.flushHourly();
        reporter.report();

        Period period = switch (range) {
            case HOUR -> hourPeriod(now);
            case DAY -> dayPeriod(now);
            // 순위 옆 작은 추이선: 최근 7일은 24시간씩 7칸, 최근 30일은 1시간 칸 그대로
            case WEEK -> hourlyPeriod(now, WEEK_HOURS, DAY_HOURS);
            case MONTH -> hourlyPeriod(now, MONTH_HOURS, 1);
        };

        List<ApiDashboardResponse.Server> servers = store.servers(now).stream()
                .map(status -> new ApiDashboardResponse.Server(status.instanceId(), status.startedAt(),
                        Duration.between(status.startedAt(), now).toSeconds(), status.heapUsedMb(), status.heapMaxMb(),
                        status.realtimeConnections(), status.instanceId().equals(reporter.instanceId())))
                .toList();

        MetricsBucket previous = previousTotal(range, period.trend().getFirst().startAt(), now);
        return new ApiDashboardResponse(now, zone.getId(), store.mode(), databaseStatus(), servers, range.name(),
                summary(period.total()), summary(previous), period.trend(), period.ranking(), poolMonitor.snapshot());
    }

    // 한 기간의 계산 결과: 숫자 카드용 합계, 그래프 점, 순위.
    private record Period(MetricsBucket total, List<ApiDashboardResponse.TrendPoint> trend,
            ApiDashboardResponse.Ranking ranking) {
    }

    // 직전 같은 길이 기간의 합계(숫자 카드 증감 비교용). 지금 창 [windowStart, now)를 기간 길이만큼 앞으로 민 구간.
    // 최근 60분은 5분 칸, 나머지는 1시간 요약으로 셈. 구간 경계에 걸친 칸은 겹친 시간 비율만큼만 더함(추정).
    private MetricsBucket previousTotal(Range range, Instant windowStart, Instant now) {
        Duration length = switch (range) {
            case HOUR -> Duration.ofMinutes(HOUR_MINUTES);
            case DAY -> Duration.ofHours(DAY_HOURS);
            case WEEK -> Duration.ofHours(WEEK_HOURS);
            case MONTH -> Duration.ofHours(MONTH_HOURS);
        };
        Instant from = windowStart.minus(length);
        Instant to = now.minus(length);
        MetricsBucket total = new MetricsBucket();
        if (range == Range.HOUR) {
            long first = ApiMetricsRecorder.bucketOf(from);
            bucketRepository.read(ApiMetricBucketRepository.Ring.FIVE_MINUTES, first, ApiMetricsRecorder.bucketOf(to))
                    .forEach((bucket, data) -> {
                        Instant start = Instant.ofEpochSecond(bucket * ApiMetricsRecorder.BUCKET_SECONDS);
                        total.add(portion(data, overlapSeconds(start, ApiMetricsRecorder.BUCKET_SECONDS, from, to),
                                ApiMetricsRecorder.BUCKET_SECONDS));
                    });
            return total;
        }
        hourlyRepository.readTotals(ApiMetricsRecorder.hourOf(from)).forEach((hour, data) -> {
            if (hour.isBefore(to)) total.add(portion(data, overlapSeconds(hour, 3600, from, to), 3600));
        });
        return total;
    }

    // [start, start+spanSeconds) 칸이 [from, to)와 겹치는 초
    private static long overlapSeconds(Instant start, long spanSeconds, Instant from, Instant to) {
        long begin = Math.max(start.getEpochSecond(), from.getEpochSecond());
        long end = Math.min(start.getEpochSecond() + spanSeconds, to.getEpochSecond());
        return Math.max(0, end - begin);
    }

    // 칸 하나 중 겹친 시간(overlap/span) 비율만큼의 몫(요청이 칸 안에 고르게 왔다고 보는 추정). 다 겹치면 그대로.
    static MetricsBucket portion(MetricsBucket data, long overlap, long span) {
        if (overlap >= span) return data;
        MetricsBucket part = new MetricsBucket();
        if (overlap <= 0) return part;
        double ratio = (double) overlap / span;
        part.addTotals(Math.round(data.getCount() * ratio), Math.round(data.getClientErrors() * ratio),
                Math.round(data.getServerErrors() * ratio), Math.round(data.getDurationSumMs() * ratio));
        long[] latency = data.getLatencyCounts();
        for (int bin = 0; bin < latency.length; bin++) part.addLatency(bin, Math.round(latency[bin] * ratio));
        return part;
    }

    // 최근 60분: 지금 분을 포함해 1분 칸 60개(정각이 지나도 비지 않고 계속 밀려감).
    // 숫자 카드·순위 합계도 그래프와 같은 60칸을 더한 값(1분 기록이 없는 5분 구간은 5분 칸으로 채움).
    private Period hourPeriod(Instant now) {
        long lastMinute = ApiMetricsRecorder.minuteOf(now);
        long firstMinute = lastMinute - HOUR_MINUTES + 1;
        Instant firstStart = Instant.ofEpochSecond(firstMinute * ApiMetricsRecorder.MINUTE_SECONDS);
        long firstBucket = ApiMetricsRecorder.bucketOf(firstStart);
        // 첫 칸이 5분 칸 안에서 몇 번째 분인지(5분 칸 경계에 맞춰 묶기 위해)
        int offset = (int) (firstMinute - firstBucket * MINUTES_PER_BUCKET);
        Map<Long, MetricsBucket> minutes = bucketRepository.read(ApiMetricBucketRepository.Ring.ONE_MINUTE,
                firstMinute, lastMinute);
        Map<Long, MetricsBucket> buckets = bucketRepository.read(ApiMetricBucketRepository.Ring.FIVE_MINUTES,
                firstBucket, ApiMetricsRecorder.bucketOf(now));
        FineSeries fine = fineSeries(HOUR_MINUTES, offset, MINUTES_PER_BUCKET, 1,
                index -> Instant.ofEpochSecond((firstMinute + index) * ApiMetricsRecorder.MINUTE_SECONDS),
                index -> minutes.get(firstMinute + index), group -> buckets.get(firstBucket + group));
        ApiDashboardResponse.Ranking ranking = ranking(fine.total().getEndpoints(), fine.bins(),
                names -> fine.series());
        return new Period(fine.total(), fine.points(), ranking);
    }

    // 최근 24시간: 지금 5분 칸까지 5분 칸 288개. 숫자 카드·순위 합계도 그래프와 같은 288칸을 더한 값.
    // 5분 기록이 없는 1시간은 1시간 요약(API별 행 포함)을 12칸으로 나눠 채움(창이 시간 중간에서 시작하면 그 시간은 창 안 칸만).
    private Period dayPeriod(Instant now) {
        long lastBucket = ApiMetricsRecorder.bucketOf(now);
        long firstBucket = lastBucket - DAY_BUCKETS + 1;
        Instant firstStart = Instant.ofEpochSecond(firstBucket * ApiMetricsRecorder.BUCKET_SECONDS);
        Instant firstHour = ApiMetricsRecorder.hourOf(firstStart);
        // 첫 칸이 1시간 안에서 몇 번째 5분 칸인지(1시간 경계에 맞춰 묶기 위해)
        int offset = (int) (Duration.between(firstHour, firstStart).toMinutes() / MINUTES_PER_BUCKET);
        Map<Instant, MetricsBucket> hours = hourlyRepository.readHours(firstHour, now);
        Map<Long, MetricsBucket> buckets = bucketRepository.read(ApiMetricBucketRepository.Ring.FIVE_MINUTES,
                firstBucket, lastBucket);
        FineSeries fine = fineSeries(DAY_BUCKETS, offset, (int) BUCKETS_PER_HOUR, MINUTES_PER_BUCKET,
                index -> Instant.ofEpochSecond((firstBucket + index) * ApiMetricsRecorder.BUCKET_SECONDS),
                index -> buckets.get(firstBucket + index), group -> hours.get(firstHour.plus(Duration.ofHours(group))));
        ApiDashboardResponse.Ranking ranking = ranking(fine.total().getEndpoints(), fine.bins(),
                names -> fine.series());
        return new Period(fine.total(), fine.points(), ranking);
    }

    // 최근 7일·30일: 지금 시간까지 1시간 요약 hours칸(정시 단위로 밀려감).
    // 순위 옆 작은 추이선은 binHours시간씩 묶은 칸.
    private Period hourlyPeriod(Instant now, int hours, int binHours) {
        Instant lastHour = ApiMetricsRecorder.hourOf(now);
        Instant from = lastHour.minus(Duration.ofHours(hours - 1L));
        Instant to = lastHour.plus(Duration.ofHours(1));
        HourlyTrend hourly = hourlyTrend(from, to);
        int binCount = hours / binHours;
        List<Instant> bins = IntStream.range(0, binCount)
                .mapToObj(bin -> from.plus(Duration.ofHours((long) bin * binHours))).toList();
        ApiDashboardResponse.Ranking ranking = ranking(hourlyRepository.readEndpointTotals(from, to), bins,
                names -> hourlySeries(from, to, binHours, binCount, names));
        return new Period(hourly.total(), hourly.points(), ranking);
    }

    private record HourlyTrend(MetricsBucket total, List<ApiDashboardResponse.TrendPoint> points) {
    }

    // [from, to) 기간을 1시간 요약 한 칸씩 점으로.
    private HourlyTrend hourlyTrend(Instant from, Instant to) {
        Map<Instant, MetricsBucket> hours = hourlyRepository.readTotals(from);
        MetricsBucket total = new MetricsBucket();
        List<ApiDashboardResponse.TrendPoint> points = new ArrayList<>();
        for (Instant hour = from; hour.isBefore(to); hour = hour.plus(Duration.ofHours(1))) {
            MetricsBucket data = hours.getOrDefault(hour, new MetricsBucket());
            total.add(data);
            points.add(point(hour, data, 60));
        }
        return new HourlyTrend(total, points);
    }

    // 잘게 쪼갠 그래프 한 벌: 점, 칸 시작 시각, API별 [호출 수, 걸린 시간 합](순위 옆 작은 추이선용),
    // 모든 칸을 더한 합계(total).
    private record FineSeries(List<ApiDashboardResponse.TrendPoint> points, List<Instant> bins,
            Map<String, long[][]> series, MetricsBucket total) {
    }

    // count개의 작은 칸으로 그래프를 만듦. 큰 칸(group) 하나는 작은 칸 perGroup개이고,
    // 첫 칸은 첫 큰 칸 안에서 offset번째 자리(큰 칸 경계에 맞춰 묶음, 최근 60분처럼 경계 중간에서 시작할 때).
    // 묶음에 작은 칸 기록이 하나도 없고 큰 칸 기록이 있으면, 큰 칸 값을 perGroup칸으로 고르게 나눠 그 구간 칸에 채움(추정값).
    private static FineSeries fineSeries(int count, int offset, int perGroup, int fineMinutes,
            IntFunction<Instant> startOf, IntFunction<MetricsBucket> fineAt, IntFunction<MetricsBucket> groupAt) {
        List<ApiDashboardResponse.TrendPoint> points = new ArrayList<>();
        List<Instant> bins = new ArrayList<>();
        Map<String, long[][]> series = new HashMap<>();
        MetricsBucket total = new MetricsBucket();
        for (int group = 0; group * perGroup < count + offset; group++) {
            // 이 큰 칸의 첫 자리가 그래프에서 몇 번째 칸인지(첫 묶음은 음수일 수 있음)
            int groupFirst = group * perGroup - offset;
            int first = Math.max(0, groupFirst);
            int last = Math.min(groupFirst + perGroup, count);
            boolean fineEmpty = IntStream.range(first, last)
                    .allMatch(index -> fineAt.apply(index) == null || fineAt.apply(index).isEmpty());
            MetricsBucket coarse = groupAt.apply(group);
            boolean useCoarse = fineEmpty && coarse != null && !coarse.isEmpty();
            for (int index = first; index < last; index++) {
                Instant start = startOf.apply(index);
                bins.add(start);
                if (useCoarse) {
                    MetricsBucket cell = splitBucket(coarse, perGroup, index - groupFirst);
                    // 나눈 칸은 응답 시간 구간도 따로 나눠 p95가 흔들리므로 그 구간 전체의 p95를 그대로 씀.
                    points.add(new ApiDashboardResponse.TrendPoint(start, cell.getCount(),
                            cell.getClientErrors() + cell.getServerErrors(),
                            average(cell.getDurationSumMs(), cell.getCount()),
                            cell.getCount() == 0 ? 0 : coarse.p95Ms(), fineMinutes, latencyOf(cell), true,
                            cell.getClientErrors(), cell.getServerErrors()));
                    collect(series, cell, index, count);
                    total.add(cell);
                    continue;
                }
                MetricsBucket data = fineAt.apply(index) == null ? new MetricsBucket() : fineAt.apply(index);
                points.add(point(start, data, fineMinutes));
                collect(series, data, index, count);
                total.add(data);
            }
        }
        return new FineSeries(points, bins, series, total);
    }

    // total을 parts칸에 고르게 나눈 index번째 몫(나머지는 앞 칸부터 1씩). 몫을 모두 더하면 total.
    private static long share(long total, int parts, int index) {
        return total / parts + (index < total % parts ? 1 : 0);
    }

    // total을 비율(앞 칸까지 누적 before, 이 칸 weight, 전체 weightTotal)로 나눈 몫. 몫을 모두 더하면 total.
    private static long proportional(long total, long before, long weight, long weightTotal) {
        if (weightTotal == 0) return 0;
        return Math.floorDiv(total * (before + weight), weightTotal) - Math.floorDiv(total * before, weightTotal);
    }

    // 큰 칸 하나를 parts칸으로 고르게 나눈 index번째 칸. 걸린 시간 합은 요청 수 비율대로 나눠 평균이 그대로 유지됨.
    private static MetricsBucket splitBucket(MetricsBucket coarse, int parts, int index) {
        MetricsBucket cell = new MetricsBucket();
        long before = 0;
        for (int part = 0; part < index; part++) before += share(coarse.getCount(), parts, part);
        long count = share(coarse.getCount(), parts, index);
        cell.addTotals(count, share(coarse.getClientErrors(), parts, index),
                share(coarse.getServerErrors(), parts, index),
                proportional(coarse.getDurationSumMs(), before, count, coarse.getCount()));
        long[] latency = coarse.getLatencyCounts();
        for (int bin = 0; bin < latency.length; bin++) cell.addLatency(bin, share(latency[bin], parts, index));
        coarse.getEndpoints().forEach((endpoint, stats) -> {
            long endpointBefore = 0;
            for (int part = 0; part < index; part++) endpointBefore += share(stats.getCount(), parts, part);
            long endpointCount = share(stats.getCount(), parts, index);
            cell.endpoint(endpoint).add(endpointCount, share(stats.getClientErrors(), parts, index),
                    share(stats.getServerErrors(), parts, index),
                    proportional(stats.getDurationSumMs(), endpointBefore, endpointCount, stats.getCount()));
        });
        return cell;
    }

    private static List<Long> latencyOf(MetricsBucket data) {
        List<Long> latency = new ArrayList<>();
        for (long count : data.getLatencyCounts()) latency.add(count);
        return latency;
    }

    // 한 칸(index)의 API별 [호출 수, 걸린 시간 합]을 series에 더함.
    private static void collect(Map<String, long[][]> series, MetricsBucket data, int index, int size) {
        data.getEndpoints().forEach((endpoint, stats) -> {
            long[][] values = series.computeIfAbsent(endpoint, key -> new long[2][size]);
            values[0][index] += stats.getCount();
            values[1][index] += stats.getDurationSumMs();
        });
    }

    // DB의 API별 1시간 기록을 from부터 binHours시간씩 묶은 binCount칸으로(고른 API만).
    private Map<String, long[][]> hourlySeries(Instant from, Instant to, int binHours, int binCount,
            Set<String> endpoints) {
        Map<String, long[][]> series = new HashMap<>();
        for (ApiMetricHourlyRepository.EndpointHour row : hourlyRepository.readEndpointHours(from, to, endpoints)) {
            int bin = (int) (Duration.between(from, row.hourStart()).toHours() / binHours);
            if (bin < 0 || bin >= binCount) continue;
            long[][] values = series.computeIfAbsent(row.endpoint(), key -> new long[2][binCount]);
            values[0][bin] += row.requests();
            values[1][bin] += row.durationSumMs();
        }
        return series;
    }

    // 기간의 API별 합계로 순위를 만듦: 많이 호출된 5개, 평균이 느린 5개(5회 이상 호출만, 없으면 전부 중에서).
    // 작은 추이선은 순위에 든 API만 읽어 옴(seriesOf).
    private static ApiDashboardResponse.Ranking ranking(Map<String, MetricsBucket.EndpointStats> totals,
            List<Instant> bins, Function<Set<String>, Map<String, long[][]>> seriesOf) {
        List<Map.Entry<String, MetricsBucket.EndpointStats>> entries = new ArrayList<>(totals.entrySet());
        List<String> top = entries.stream()
                .sorted(Comparator.comparingLong(
                        (Map.Entry<String, MetricsBucket.EndpointStats> entry) -> entry.getValue().getCount()).reversed())
                .limit(TOP_COUNT).map(Map.Entry::getKey).toList();
        List<Map.Entry<String, MetricsBucket.EndpointStats>> slowCandidates = entries.stream()
                .filter(entry -> entry.getValue().getCount() >= SLOW_MIN_REQUESTS).toList();
        List<String> slow = (slowCandidates.isEmpty() ? entries : slowCandidates).stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, MetricsBucket.EndpointStats> entry) -> average(
                        entry.getValue().getDurationSumMs(), entry.getValue().getCount())).reversed())
                .limit(TOP_COUNT).map(Map.Entry::getKey).toList();
        Set<String> needed = new LinkedHashSet<>(top);
        needed.addAll(slow);
        Map<String, long[][]> series = seriesOf.apply(needed);
        Function<String, ApiDashboardResponse.EndpointStat> toStat = endpoint -> {
            MetricsBucket.EndpointStats stats = totals.get(endpoint);
            long[][] values = series.getOrDefault(endpoint, new long[2][bins.size()]);
            List<Long> requests = new ArrayList<>();
            List<Long> averages = new ArrayList<>();
            for (int index = 0; index < bins.size(); index++) {
                requests.add(values[0][index]);
                averages.add(average(values[1][index], values[0][index]));
            }
            return new ApiDashboardResponse.EndpointStat(endpoint, stats.getCount(), stats.getErrors(),
                    average(stats.getDurationSumMs(), stats.getCount()), requests, averages);
        };
        return new ApiDashboardResponse.Ranking(bins, top.stream().map(toStat).toList(),
                slow.stream().map(toStat).toList());
    }

    // startBucket부터 시작하는 5분 구간 하나를 추이 한 점으로.
    private static ApiDashboardResponse.TrendPoint point(Instant start, MetricsBucket data, int spanMinutes) {
        return new ApiDashboardResponse.TrendPoint(start, data.getCount(),
                data.getClientErrors() + data.getServerErrors(), average(data.getDurationSumMs(), data.getCount()),
                data.p95Ms(), spanMinutes, latencyOf(data), false, data.getClientErrors(), data.getServerErrors());
    }

    private static ApiDashboardResponse.Summary summary(MetricsBucket bucket) {
        double errorRate = bucket.getCount() == 0 ? 0
                : Math.round(bucket.getServerErrors() * 1000.0 / bucket.getCount()) / 10.0;
        return new ApiDashboardResponse.Summary(bucket.getCount(), bucket.getClientErrors(), bucket.getServerErrors(),
                errorRate, average(bucket.getDurationSumMs(), bucket.getCount()), bucket.p95Ms());
    }

    private static long average(long sum, long count) {
        return count == 0 ? 0 : Math.round((double) sum / count);
    }

    // DB에 실제로 연결해 2초 안에 응답하는지 확인.
    private String databaseStatus() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2) ? "UP" : "DOWN";
        } catch (SQLException exception) {
            return "DOWN";
        }
    }
}
