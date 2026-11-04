package com.jhwjhw.naverworkscloneapi.profile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 서버가 뜰 때 예전 방식(서버 디스크 파일)으로 남은 프로필 사진을 DB로 옮김.
// 파일은 사진을 올렸던 PC·서버에만 있으므로, 그 서버가 한 번 뜨면 옮겨지고 이후 어느 PC에서나 보임.
// 이 서버에 파일이 없는 사진은 건너뜀(그 파일이 있는 서버가 뜰 때 옮김).
@Component
@RequiredArgsConstructor
@Slf4j
public class ProfileImageMigration {
    private final ProfileImageService service;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        int moved = 0;
        int skipped = 0;
        for (var entry : service.legacyKeys().entrySet()) {
            try {
                if (service.migrateLegacy(entry.getKey(), entry.getValue())) moved++;
                else skipped++;
            } catch (Exception exception) {
                skipped++;
                log.warn("프로필 사진을 DB로 옮기지 못했습니다. membershipId={}, reason={}", entry.getKey(),
                        exception.getMessage());
            }
        }
        if (moved + skipped > 0) {
            log.info("예전 프로필 사진을 DB로 옮김: 옮김 {}건, 이 서버에 파일이 없어 건너뜀 {}건", moved, skipped);
        }
    }
}
