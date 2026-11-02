-- 관리자 모드. 체험용 프로젝트라 모든 구성원이 관리자 모드로 시작하고, 상단바에서 직접 끌 수 있음.
-- 실제 권한(토큰의 role)은 이 값으로 정함. 기존 role 컬럼은 회사를 만든 사람인지 기록용으로 남김.
ALTER TABLE membership ADD COLUMN admin_mode BOOLEAN NOT NULL DEFAULT TRUE;
