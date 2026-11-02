-- 글마다 댓글 허용 여부와 댓글 알림(글쓴이에게) 여부. 기존 글은 둘 다 켜진 상태로 채워짐.
ALTER TABLE board_post ADD COLUMN allow_comment BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE board_post ADD COLUMN comment_notify BOOLEAN NOT NULL DEFAULT TRUE;
