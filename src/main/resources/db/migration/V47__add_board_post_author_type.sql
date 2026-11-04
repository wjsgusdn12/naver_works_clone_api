-- 게시글 작성 주체: MEMBER(구성원이 쓴 글) / SYSTEM(매일 브리핑처럼 서버가 자동으로 올린 글).
-- 시스템 글은 사람 글쓴이가 없으므로 author_membership_id를 비워 둠(구성원 목록·인원 수에 가짜 계정이 섞이지 않게).
-- 기존 글은 모두 MEMBER로 시작(데이터 변경 없음).
ALTER TABLE board_post ADD COLUMN author_type VARCHAR(20) NOT NULL DEFAULT 'MEMBER';
ALTER TABLE board_post ALTER COLUMN author_membership_id DROP NOT NULL;
ALTER TABLE board_post ADD CONSTRAINT ck_board_post_author
    CHECK ((author_type = 'MEMBER' AND author_membership_id IS NOT NULL)
        OR (author_type = 'SYSTEM' AND author_membership_id IS NULL));
