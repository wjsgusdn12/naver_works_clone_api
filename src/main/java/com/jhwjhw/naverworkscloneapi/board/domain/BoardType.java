package com.jhwjhw.naverworkscloneapi.board.domain;

// 게시판 종류. NOTICE·FREE·INQUIRY는 회사와 상관없는 프로젝트 전체 공통 게시판,
// COMPANY는 회사마다 하나, DEPARTMENT는 부서마다 하나.
public enum BoardType {
    NOTICE, // 공지사항: 전체 사용자가 보고 관리자만 씀
    FREE, // 자유게시판: 전체 사용자
    INQUIRY, // 문의하기: 글은 쓴 사람과 관리자만 봄, 관리자가 댓글로 답변
    COMPANY, // 회사 게시판(이름 변경 가능)
    DEPARTMENT; // 부서 게시판: 그 부서 사람만(이름 변경 가능)

    // 회사와 상관없는 공통 게시판인지
    public boolean common() {
        return this == NOTICE || this == FREE || this == INQUIRY;
    }

    // 관리자가 이름을 바꿀 수 있는 게시판인지
    public boolean renamable() {
        return this == COMPANY || this == DEPARTMENT;
    }
}
