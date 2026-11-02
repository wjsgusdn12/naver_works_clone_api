package com.jhwjhw.naverworkscloneapi.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jhwjhw.naverworkscloneapi.auth.AuthException;
import com.jhwjhw.naverworkscloneapi.identity.domain.Company;
import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import com.jhwjhw.naverworkscloneapi.identity.domain.Role;
import com.jhwjhw.naverworkscloneapi.identity.domain.UserAccount;
import com.jhwjhw.naverworkscloneapi.identity.repository.CompanyRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.MembershipRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.UserAccountRepository;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:profileImageTest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@Transactional
// 프로필 사진: 정사각형 256px JPEG로 줄여 저장, 형식·크기 제한, 기본 이미지로 되돌리기. 모두 가상 데이터.
class ProfileImageTests {
    @Autowired private ProfileImageService service;
    @Autowired private CompanyRepository companies;
    @Autowired private UserAccountRepository accounts;
    @Autowired private MembershipRepository memberships;

    private Membership me;

    @BeforeEach
    void setUp() {
        Company company = companies.save(new Company("가상 프로필 회사"));
        UserAccount account = accounts.save(new UserAccount("profile-me@example.com", "test-hash", "나"));
        me = memberships.save(new Membership(company, account, null, Role.USER));
    }

    @Test
    void uploadedPhotoIsCroppedToSquareJpegAndCanBeRemoved() throws Exception {
        // 가로로 긴 PNG(가운데만 빨강) → 가운데를 잘라 256px 정사각형 JPEG
        BufferedImage wide = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = wide.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, 800, 400);
        graphics.setColor(Color.RED);
        graphics.fillRect(200, 0, 400, 400);
        graphics.dispose();
        service.change(me.getId(), new MockMultipartFile("file", "가상.png", "image/png", png(wide)));

        assertThat(service.versions()).containsOnlyKeys(me.getId());
        try (InputStream in = service.load(me.getId()).getInputStream()) {
            byte[] bytes = in.readAllBytes();
            assertThat(bytes[0] & 0xFF).isEqualTo(0xFF); // JPEG
            BufferedImage saved = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            assertThat(saved.getWidth()).isEqualTo(ProfileImageService.SIDE);
            assertThat(saved.getHeight()).isEqualTo(ProfileImageService.SIDE);
            // 양 끝 파란 부분은 잘려 나가고 빨강만 남음
            Color corner = new Color(saved.getRGB(5, 5));
            assertThat(corner.getRed()).isGreaterThan(200);
            assertThat(corner.getBlue()).isLessThan(60);
        }

        // 바꾸면 판이 달라짐
        String first = service.versions().get(me.getId());
        service.change(me.getId(), new MockMultipartFile("file", "가상2.png", "image/png", png(wide)));
        assertThat(service.versions().get(me.getId())).isNotEqualTo(first);

        service.remove(me.getId());
        assertThat(service.versions()).isEmpty();
        assertThat(service.load(me.getId())).isNull();
    }

    @Test
    void onlyJpegAndPngUpToFiveMegabytesAreAccepted() {
        assertThatThrownBy(() -> service.change(me.getId(),
                new MockMultipartFile("file", "가상.gif", "image/gif", "GIF89a-가상".getBytes())))
                .isInstanceOf(AuthException.class).hasMessage("JPG·PNG 사진만 올릴 수 있습니다.");
        assertThatThrownBy(() -> service.change(me.getId(),
                new MockMultipartFile("file", "가상.txt", "text/plain", "그냥 글자".getBytes())))
                .isInstanceOf(AuthException.class).hasMessage("JPG·PNG 사진만 올릴 수 있습니다.");
        assertThatThrownBy(() -> service.change(me.getId(), new MockMultipartFile("file", "큰.png", "image/png",
                new byte[(int) ProfileImageService.MAX_SIZE + 1])))
                .isInstanceOf(AuthException.class).hasMessage("프로필 사진은 5MB까지 올릴 수 있습니다.");
        // PNG 머리만 있고 내용이 깨진 파일
        assertThatThrownBy(() -> service.change(me.getId(), new MockMultipartFile("file", "깨짐.png", "image/png",
                "\u0089PNG\r\n\u001a\n깨진 내용".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))))
                .isInstanceOf(AuthException.class).hasMessage("사진을 읽지 못했습니다. 다른 사진으로 시도해 주세요.");
    }

    // 휴대폰 회전값: 6(시계 방향 90도)이면 원래 왼쪽 위가 오른쪽 위로
    @Test
    void exifRotationMapsCornersInsideTheSquare() {
        int s = ProfileImageService.SIDE;
        Point2D topLeft = new Point2D.Double(0, 0);
        assertThat(ProfileImageService.rotation(6).transform(topLeft, null)).isEqualTo(new Point2D.Double(s, 0));
        assertThat(ProfileImageService.rotation(8).transform(topLeft, null)).isEqualTo(new Point2D.Double(0, s));
        assertThat(ProfileImageService.rotation(3).transform(topLeft, null)).isEqualTo(new Point2D.Double(s, s));
        assertThat(ProfileImageService.rotation(1).transform(topLeft, null)).isEqualTo(topLeft);
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
