package com.jhwjhw.naverworkscloneapi.board.domain;

// 게시글 작성 주체. SYSTEM은 서버가 자동으로 올린 글(매일 브리핑 등)이라 사람 글쓴이가 없음.
public enum PostAuthorType {
    MEMBER, SYSTEM
}
