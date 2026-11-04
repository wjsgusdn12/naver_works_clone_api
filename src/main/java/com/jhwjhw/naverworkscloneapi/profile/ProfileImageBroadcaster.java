package com.jhwjhw.naverworkscloneapi.profile;

// 프로필 사진 변경을 모든 브라우저에 알리는 안내 방송(접속 상태 방송과 같은 구조).
// - Redis 버전: 방송 채널로 모든 서버에 보내고 각 서버가 자기 브라우저들에게 전달(배포용).
// - 이 서버 버전: 이 서버의 브라우저들에게 바로 전달(로컬 개발용).
public interface ProfileImageBroadcaster {
    String TOPIC = "/topic/profileImage";

    void broadcast(ProfileImageRealtimeEvent event);
}
