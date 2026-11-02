package com.jhwjhw.naverworkscloneapi.board;

// 게시판 규칙 위반을 공통 오류 응답으로 전달.
public class BoardException extends RuntimeException {
    private final String code;

    public BoardException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
