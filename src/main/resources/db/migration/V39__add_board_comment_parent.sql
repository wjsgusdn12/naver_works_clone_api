-- 댓글 답글(대댓글). 답글은 한 단계만: parent_id는 늘 맨 위 댓글을 가리킴(NULL이면 맨 위 댓글).
ALTER TABLE board_comment ADD COLUMN parent_id BIGINT;
ALTER TABLE board_comment ADD CONSTRAINT fk_board_comment_parent FOREIGN KEY (parent_id) REFERENCES board_comment (id);
CREATE INDEX idx_board_comment_parent ON board_comment (parent_id);
