-- 매일 인사 글에 붙는 증시 분석(미국은 아침, 한국은 오후 2시 이후 보강).
-- us_section·kr_section: 만들어 둔 분석 부분(편집기 문서 노드 JSON 배열). 나머지 부분을 다시 받지 않고 글을 합칠 때 씀.
-- us_done_at·kr_done_at: 그 부분을 끝낸 시각(못 받고 포기한 경우 포함). null이면 아직 할 일.
-- lease_until: 서버 한 대만 작업하도록 잡아 두는 기한. 서버가 작업 중 꺼져도 기한이 지나면 다른 서버가 이어받음.
ALTER TABLE board_daily_greeting
    ADD COLUMN us_section TEXT,
    ADD COLUMN kr_section TEXT,
    ADD COLUMN us_done_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN kr_done_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE;
