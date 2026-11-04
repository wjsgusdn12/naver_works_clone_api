-- 프로필 사진 내용(256px 정사각형 JPEG, 장당 수십 KB)을 DB에 보관. 어느 PC·서버에서 실행해도 같은 사진이 보임.
-- 구성원당 한 행이고, 사진을 바꾸면 덮어씀. 기본 이미지로 되돌리면 membership.profile_image_key만 비우고
-- 이 행은 남겨 둠(지우지 않음, 다음에 올리면 덮어씀).
-- membership.profile_image_key가 'db/'로 시작하면 이 표의 사진, 아니면 예전 방식(서버 디스크 파일)의 키.
CREATE TABLE membership_profile_image (
    membership_id BIGINT PRIMARY KEY REFERENCES membership (id),
    image BYTEA NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
