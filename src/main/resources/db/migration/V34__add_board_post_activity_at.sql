-- 게시판 "글/댓글 업데이트 순" 정렬용: 글을 쓰거나 고치거나 댓글이 달린 마지막 시각.
-- 이전 글은 비어 있고(NULL), 정렬할 때 등록 시각으로 대신함.
ALTER TABLE board_post ADD COLUMN activity_at TIMESTAMP WITH TIME ZONE NULL;
CREATE INDEX idx_board_post_activity ON board_post (board_id, activity_at DESC);
