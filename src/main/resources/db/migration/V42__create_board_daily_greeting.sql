-- 매일 자유게시판에 올리는 인사 글 기록. 날짜가 기본 키라 서버가 여러 대여도 하루에 한 번만 올라감.
CREATE TABLE board_daily_greeting (
    greeting_date DATE PRIMARY KEY,
    post_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_board_daily_greeting_post FOREIGN KEY (post_id) REFERENCES board_post (id)
);
