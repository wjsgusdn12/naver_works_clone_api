-- 사진 작은 사본(썸네일) 위치 키. 썸네일이 없는 사진(작은 사진·GIF·WebP 등)과 일반 파일은 NULL.
ALTER TABLE chat_message_attachment ADD COLUMN thumbnail_key VARCHAR(100) NULL;

-- 보관 기간이 끝나 디스크 파일을 정리한 시각. 행은 지우지 않고 표시만 남김(논리 삭제).
ALTER TABLE chat_message_attachment ADD COLUMN purged_at TIMESTAMP WITH TIME ZONE NULL;

-- 청소 작업: 같은 파일을 가리키는 첨부(전달한 복사본)를 찾을 때.
CREATE INDEX idx_chat_message_attachment_storage_key ON chat_message_attachment (storage_key);

-- 업로드 속도 제한: 최근 업로드를 셀 때.
CREATE INDEX idx_chat_message_attachment_created ON chat_message_attachment (created_at);
