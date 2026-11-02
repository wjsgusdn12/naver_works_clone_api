-- 프로필 사진. 첨부와 같은 파일 저장소의 키(가운데를 정사각형으로 잘라 줄인 JPEG). NULL이면 이름 글자 아바타.
ALTER TABLE membership ADD COLUMN profile_image_key VARCHAR(200);
