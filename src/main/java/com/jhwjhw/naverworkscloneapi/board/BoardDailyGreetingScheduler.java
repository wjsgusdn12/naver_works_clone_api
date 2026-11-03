package com.jhwjhw.naverworkscloneapi.board;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 매일 정해진 시각(기본 오전 9시)이 지나면 그날 자유게시판 인사 글을 한 번 올림.
// 5분마다 확인하므로 그 시각에 서버가 꺼져 있었어도 켜진 뒤 올라감. 하루 한 번은 날짜 기록으로 보장.
@Component
@Slf4j
public class BoardDailyGreetingScheduler {
    private final BoardDailyGreetingService greetingService;
    private final Clock clock;
    private final ZoneId zone;
    private final LocalTime postAt;
    private final boolean enabled;

    public BoardDailyGreetingScheduler(BoardDailyGreetingService greetingService,
            @Value("${app.monitoring.zone:Asia/Seoul}") String zone,
            @Value("${app.board.daily-greeting.time:09:00}") String postAt,
            @Value("${app.board.daily-greeting.enabled:true}") boolean enabled) {
        this.greetingService = greetingService;
        this.clock = Clock.systemUTC();
        this.zone = ZoneId.of(zone);
        this.postAt = LocalTime.parse(postAt);
        this.enabled = enabled;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    public void postToday() {
        if (!enabled) return;
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        if (now.toLocalTime().isBefore(postAt)) return;
        try {
            if (greetingService.postIfMissing(now.toLocalDate())) {
                log.info("Daily greeting posted. date={}", now.toLocalDate());
            }
        } catch (DataIntegrityViolationException exception) {
            // 다른 서버가 같은 날 먼저 올림(이쪽 글은 함께 취소됨)
            log.debug("Daily greeting already posted by another server. date={}", now.toLocalDate());
        } catch (RuntimeException exception) {
            // 다음 차례에 다시 시도
            log.warn("Daily greeting post failed.", exception);
        }
    }
}
