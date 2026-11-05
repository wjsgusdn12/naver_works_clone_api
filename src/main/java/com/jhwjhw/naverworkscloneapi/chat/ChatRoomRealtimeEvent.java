package com.jhwjhw.naverworkscloneapi.chat;

// type: ROOM_UPDATED(방 주제, 남은 참여자) · ROOM_JOINED / ROOM_REMOVED(본인 전용 큐).
// 바뀐 내용은 싣지 않음 — 화면은 이 신호를 받으면 방 목록·상세를 다시 조회(권한 검사도 그때 다시 됨).
public record ChatRoomRealtimeEvent(String type, Long chatRoomId) {
}
