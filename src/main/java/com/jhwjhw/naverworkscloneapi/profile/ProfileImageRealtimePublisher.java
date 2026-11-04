package com.jhwjhw.naverworkscloneapi.profile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 사진 저장이 커밋된 뒤에만 방송(되돌려진 변경을 알리지 않게)
@Component
@RequiredArgsConstructor
@Slf4j
public class ProfileImageRealtimePublisher {
    private final ProfileImageBroadcaster broadcaster;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(ProfileImageChangedEvent event) {
        try {
            broadcaster.broadcast(ProfileImageRealtimeEvent.of(event.membershipId(), event.version()));
        } catch (RuntimeException exception) {
            // 방송이 실패해도 사진 변경은 이미 저장됨. 다른 화면은 실시간 재연결 때 목록을 다시 받아 맞춤.
            log.warn("Profile image broadcast failed. membershipId={}", event.membershipId(), exception);
        }
    }
}
