package com.jhwjhw.naverworkscloneapi.chat;

import java.util.Set;

// 방 정보(이름·참여자·방장)가 바뀜. 남은 참여자에게는 방 주제로, 새로 들어온 사람(joined)과
// 빠진 사람(removed)에게는 본인 전용 큐로 알림(그 방을 아직 구독하지 않았거나, 더는 구독하면 안 되므로).
public record ChatRoomChangedEvent(Long chatRoomId, Set<Long> joinedMembershipIds, Set<Long> removedMembershipIds) {

    public static ChatRoomChangedEvent updated(Long chatRoomId) {
        return new ChatRoomChangedEvent(chatRoomId, Set.of(), Set.of());
    }

    public static ChatRoomChangedEvent joined(Long chatRoomId, Set<Long> membershipIds) {
        return new ChatRoomChangedEvent(chatRoomId, Set.copyOf(membershipIds), Set.of());
    }

    public static ChatRoomChangedEvent removed(Long chatRoomId, Long membershipId) {
        return new ChatRoomChangedEvent(chatRoomId, Set.of(), Set.of(membershipId));
    }
}
