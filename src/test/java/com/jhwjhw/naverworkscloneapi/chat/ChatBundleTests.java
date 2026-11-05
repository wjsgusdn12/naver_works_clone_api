package com.jhwjhw.naverworkscloneapi.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jhwjhw.naverworkscloneapi.chat.domain.ChatRoom;
import com.jhwjhw.naverworkscloneapi.chat.domain.ChatRoomMember;
import com.jhwjhw.naverworkscloneapi.chat.domain.ChatRoomType;
import com.jhwjhw.naverworkscloneapi.chat.repository.ChatRoomMemberRepository;
import com.jhwjhw.naverworkscloneapi.chat.repository.ChatRoomRepository;
import com.jhwjhw.naverworkscloneapi.identity.domain.Company;
import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import com.jhwjhw.naverworkscloneapi.identity.domain.Role;
import com.jhwjhw.naverworkscloneapi.identity.domain.UserAccount;
import com.jhwjhw.naverworkscloneapi.identity.repository.CompanyRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.MembershipRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.UserAccountRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:chatBundleTest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@Transactional
// 사진 묶어 보내기: 같은 묶음 번호가 응답에 실리고, 안 읽은 수는 묶음 하나를 1개로 세며,
// 사진이 아니거나·남의 묶음이거나·형식이 틀린 묶음 번호는 거절.
class ChatBundleTests {
    @Autowired private ChatRoomService service;
    @Autowired private CompanyRepository companies;
    @Autowired private UserAccountRepository accounts;
    @Autowired private MembershipRepository memberships;
    @Autowired private ChatRoomRepository rooms;
    @Autowired private ChatRoomMemberRepository participants;

    private Membership author;
    private Membership other;
    private ChatRoom room;

    @BeforeEach
    void setUp() {
        Company company = companies.save(new Company("묶음 테스트 " + UUID.randomUUID()));
        author = createMember(company, "작성자");
        other = createMember(company, "동료");
        room = rooms.save(new ChatRoom(company, ChatRoomType.DIRECT, null, null));
        participants.save(new ChatRoomMember(room, author));
        participants.save(new ChatRoomMember(room, other));
    }

    private Membership createMember(Company company, String name) {
        UserAccount account = accounts.save(new UserAccount(UUID.randomUUID() + "@example.com", "test-hash", name));
        return memberships.save(new Membership(company, account, null, Role.USER));
    }

    private static MockMultipartFile png(String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("file", name, "image/png", out.toByteArray());
    }

    private long unreadOfOther() {
        return service.list(other.getId()).stream().filter(item -> item.id().equals(room.getId()))
                .findFirst().orElseThrow().unreadCount();
    }

    @Test
    void bundledImagesShareTheKeyAndCountAsOneUnread() throws IOException {
        String key = UUID.randomUUID().toString();
        List<ChatRoomResponse.Message> sent = List.of(
                service.sendAttachment(author.getId(), room.getId(), png("1.png"), null, key),
                service.sendAttachment(author.getId(), room.getId(), png("2.png"), null, key),
                service.sendAttachment(author.getId(), room.getId(), png("3.png"), null, key.toUpperCase()));
        // 대문자로 와도 같은 묶음
        assertThat(sent).extracting(ChatRoomResponse.Message::bundleKey).containsOnly(key);
        // 사진 3장 묶음 하나 + 따로 보낸 글 하나 = 안 읽은 수 2
        service.sendMessage(author.getId(), room.getId(), new ChatRoomRequest.MessageCreateRequest("글", List.of(), null));
        assertThat(unreadOfOther()).isEqualTo(2);
        // 묶지 않은 사진은 묶음 번호 없음
        assertThat(service.sendAttachment(author.getId(), room.getId(), png("4.png"), null).bundleKey()).isNull();
        assertThat(unreadOfOther()).isEqualTo(3);
    }

    @Test
    void invalidBundlesAreRejected() throws IOException {
        String key = UUID.randomUUID().toString();
        service.sendAttachment(author.getId(), room.getId(), png("1.png"), null, key);
        // 남의 묶음에 끼워 넣기
        assertThatThrownBy(() -> service.sendAttachment(other.getId(), room.getId(), png("2.png"), null, key))
                .isInstanceOf(ChatException.class).hasMessageContaining("묶음");
        // 형식이 틀린 번호
        assertThatThrownBy(() -> service.sendAttachment(author.getId(), room.getId(), png("3.png"), null, "abc"))
                .isInstanceOf(ChatException.class).hasMessageContaining("묶음");
        // 사진이 아닌 파일
        MockMultipartFile text = new MockMultipartFile("file", "a.txt", "text/plain",
                "x".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.sendAttachment(author.getId(), room.getId(), text, null,
                UUID.randomUUID().toString()))
                .isInstanceOf(ChatException.class).hasMessageContaining("사진만");
    }
}
