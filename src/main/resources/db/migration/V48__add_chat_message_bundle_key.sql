-- 사진 묶어 보내기: 한 번에 보낸 사진들(메시지 여러 개)에 같은 묶음 번호(브라우저가 만든 UUID)를 붙여
-- 화면이 한 말풍선(격자)으로 보여 줌. 사진마다 메시지는 그대로라 삭제·전달·모아보기 로직은 바뀌지 않음.
-- 묶지 않은 메시지는 NULL.
ALTER TABLE chat_message ADD COLUMN bundle_key VARCHAR(36) NULL;
