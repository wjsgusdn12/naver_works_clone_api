-- 매일 인사 글에 붙는 주요 뉴스(연합뉴스·Google 뉴스 RSS의 제목·언론사·링크).
-- news_section: 만들어 둔 뉴스 부분(편집기 문서 노드 JSON 배열), news_done_at: 끝낸 시각(지난 날짜라 건너뛴 경우 포함).
ALTER TABLE board_daily_greeting
    ADD COLUMN news_section TEXT,
    ADD COLUMN news_done_at TIMESTAMP WITH TIME ZONE;
