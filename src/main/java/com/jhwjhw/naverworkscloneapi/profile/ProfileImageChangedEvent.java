package com.jhwjhw.naverworkscloneapi.profile;

// 프로필 사진이 바뀜(저장 트랜잭션 안에서 발행, 커밋된 뒤에만 브라우저로 전달). version이 null이면 사진을 지움.
public record ProfileImageChangedEvent(Long membershipId, String version) {
}
