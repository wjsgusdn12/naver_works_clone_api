package com.jhwjhw.naverworkscloneapi.board.domain;

// 게시판 글 상태. 목록·검색·알림은 PUBLISHED만 대상.
public enum PostStatus {
    DRAFT, // 임시저장: 쓴 사람만 임시저장함에서 봄
    SCHEDULED, // 등록예약: 쓴 사람만 예약함에서 보다가 publishAt에 공개
    PUBLISHED // 등록
}
