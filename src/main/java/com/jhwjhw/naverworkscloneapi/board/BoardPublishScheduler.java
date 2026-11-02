package com.jhwjhw.naverworkscloneapi.board;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 등록예약 글을 예약 시각에 공개. 1분마다 확인하므로 실제 공개는 예약 시각부터 최대 1분 늦을 수 있음.
@Component
@RequiredArgsConstructor
@Slf4j
public class BoardPublishScheduler {
    private final BoardService boardService;

    @Scheduled(fixedDelay = 60_000)
    public void publishDue() {
        try {
            int published = boardService.publishDueScheduled();
            if (published > 0) log.info("Scheduled board posts published. count={}", published);
        } catch (RuntimeException exception) {
            // 다음 차례에 다시 시도(한 번 실패로 예약 작업이 멈추지 않게)
            log.warn("Scheduled board post publish failed.", exception);
        }
    }
}
