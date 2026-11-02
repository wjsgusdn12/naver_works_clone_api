-- V32에서 만든 board_attachment.inline 컬럼 이름을 inline_image로 바꿈(엔티티 필드 inlineImage와 맞춤).
-- inline은 SQL 예약어와 겹칠 수 있어 피함. V32는 이미 적용됐으므로 고치지 않고 이 파일로 바꿈.
ALTER TABLE board_attachment RENAME COLUMN inline TO inline_image;
