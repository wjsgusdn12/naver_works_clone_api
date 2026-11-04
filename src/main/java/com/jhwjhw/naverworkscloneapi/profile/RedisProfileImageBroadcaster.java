package com.jhwjhw.naverworkscloneapi.profile;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

// Redis 버전 안내 방송(배포용): 사진을 바꾼 서버가 채널에 보내면, 듣고 있던 모든 서버가 자기 브라우저들에게 전달.
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class RedisProfileImageBroadcaster implements ProfileImageBroadcaster, MessageListener {
    public static final String CHANNEL = "realtime:profile-image";

    private final StringRedisTemplate redis;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void broadcast(ProfileImageRealtimeEvent event) {
        redis.convertAndSend(CHANNEL, encode(event));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            messagingTemplate.convertAndSend(TOPIC, decode(new String(message.getBody(), StandardCharsets.UTF_8)));
        } catch (RuntimeException exception) {
            log.warn("프로필 사진 방송을 해석하지 못했습니다. reason={}", exception.getMessage());
        }
    }

    // "구성원ID|판" 모양의 짧은 글자. 사진을 지웠으면 판은 빈칸.
    static String encode(ProfileImageRealtimeEvent event) {
        return event.membershipId() + "|" + (event.version() == null ? "" : event.version());
    }

    static ProfileImageRealtimeEvent decode(String text) {
        String[] parts = text.split("\\|", -1);
        return ProfileImageRealtimeEvent.of(Long.valueOf(parts[0]), parts[1].isEmpty() ? null : parts[1]);
    }
}
