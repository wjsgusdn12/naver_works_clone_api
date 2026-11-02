-- 게시판 글 상태: DRAFT(임시저장, 쓴 사람만 봄) · SCHEDULED(등록예약, publish_at에 공개) · PUBLISHED(등록).
-- 기존 글은 모두 등록된 글이라 기본값 PUBLISHED로 채워짐.
ALTER TABLE board_post ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED';
ALTER TABLE board_post ADD COLUMN publish_at TIMESTAMP WITH TIME ZONE NULL;
ALTER TABLE board_post ADD CONSTRAINT ck_board_post_status CHECK (status IN ('DRAFT', 'SCHEDULED', 'PUBLISHED'));
-- 예약 공개 작업이 때가 된 예약 글을 찾을 때
CREATE INDEX idx_board_post_scheduled ON board_post (status, publish_at);
-- 임시저장함·예약함(내 글 중 상태별)
CREATE INDEX idx_board_post_author_status ON board_post (author_membership_id, status, id DESC);
