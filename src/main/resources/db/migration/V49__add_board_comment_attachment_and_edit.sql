-- 댓글 첨부: 게시판 첨부(board_attachment)를 댓글에도 붙임. 댓글 첨부는 post_id(그 글)와 comment_id를 함께 가져
-- 내려받기 권한(글을 볼 수 있는 사람)·청소 보호(글에 붙은 첨부)를 글 첨부와 똑같이 씀. 글 첨부는 comment_id가 NULL.
ALTER TABLE board_attachment ADD COLUMN comment_id BIGINT NULL;
ALTER TABLE board_attachment ADD CONSTRAINT fk_board_attachment_comment FOREIGN KEY (comment_id) REFERENCES board_comment (id);
CREATE INDEX idx_board_attachment_comment ON board_attachment (comment_id);

-- 댓글 수정: 마지막으로 고친 시각(화면에 "수정됨" 표시). 고친 적 없으면 NULL.
ALTER TABLE board_comment ADD COLUMN edited_at TIMESTAMP WITH TIME ZONE NULL;
