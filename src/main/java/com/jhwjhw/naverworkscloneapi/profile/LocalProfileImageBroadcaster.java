package com.jhwjhw.naverworkscloneapi.profile;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

// 이 서버 버전 안내 방송(로컬 개발용)
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class LocalProfileImageBroadcaster implements ProfileImageBroadcaster {
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void broadcast(ProfileImageRealtimeEvent event) {
        messagingTemplate.convertAndSend(TOPIC, event);
    }
}
