package com.jhwjhw.naverworkscloneapi.profile;

// 브라우저로 보내는 "이 사람 사진이 바뀜" 소식. version이 null이면 기본 이미지(이름 글자)로 돌아감.
public record ProfileImageRealtimeEvent(String type, Long membershipId, String version) {
    public static final String TYPE = "PROFILE_IMAGE_CHANGED";

    public static ProfileImageRealtimeEvent of(Long membershipId, String version) {
        return new ProfileImageRealtimeEvent(TYPE, membershipId, version);
    }
}
