package com.jhwjhw.naverworkscloneapi.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:chatLinkTest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
// 링크 모아보기: 본문에서 링크 뽑기, 내부망 접속 차단(SSRF), 제목·대표 이미지 읽기, 목록 API.
// 실제 웹사이트에 접속하지 않도록 접속 부분은 가짜로 바꿔 끼움.
class ChatLinkPreviewTests {
    @Autowired private ChatRoomService service;
    @Autowired private CompanyRepository companies;
    @Autowired private UserAccountRepository accounts;
    @Autowired private MembershipRepository memberships;
    @Autowired private ChatRoomRepository rooms;
    @Autowired private ChatRoomMemberRepository participants;
    @MockitoBean private ChatLinkPreviewFetcher fetcher;

    private Membership author;
    private Membership stranger;
    private ChatRoom room;

    @BeforeEach
    void setUp() throws IOException {
        Company company = companies.save(new Company("링크 테스트 " + UUID.randomUUID()));
        author = createMember(company, "작성자");
        Membership other = createMember(company, "동료");
        stranger = createMember(company, "외부인");
        room = rooms.save(new ChatRoom(company, ChatRoomType.DIRECT, null, null));
        participants.save(new ChatRoomMember(room, author));
        participants.save(new ChatRoomMember(room, other));
        // 제목·이미지가 있는 사이트와 제목 없는 사이트를 흉내
        when(fetcher.fetchPage(any())).thenAnswer(invocation -> {
            URI url = invocation.getArgument(0);
            return url.getHost().equals("news.example.com")
                    ? new ChatLinkPreviewFetcher.Page("오늘의 뉴스", URI.create("https://news.example.com/og.png"))
                    : new ChatLinkPreviewFetcher.Page(null, null);
        });
        when(fetcher.fetchImage(any())).thenReturn(png(1000, 500));
    }

    private Membership createMember(Company company, String name) {
        UserAccount account = accounts.save(new UserAccount(UUID.randomUUID() + "@example.com", "test-hash", name));
        return memberships.save(new Membership(company, account, null, Role.USER));
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    private Long send(Membership sender, String content) {
        return service.sendMessage(sender.getId(), room.getId(),
                new ChatRoomRequest.MessageCreateRequest(content, List.of(), null)).id();
    }

    // 문장 끝 마침표·감싼 괄호는 주소에서 빼고, 주소 안의 짝 맞는 괄호는 둠. 같은 주소는 한 번만.
    @Test
    void urlsAreExtractedFromMessageText() {
        assertThat(ChatLinkPreviewService.extractUrls(
                "회의록은 https://a.com/doc. 위키(https://ko.wikipedia.org/wiki/A_(B)) 그리고 http://b.com/x?y=1, https://a.com/doc"))
                .containsExactly("https://a.com/doc", "https://ko.wikipedia.org/wiki/A_(B)", "http://b.com/x?y=1");
        assertThat(ChatLinkPreviewService.extractUrls("링크 없음 http:// 뿐")).isEmpty();
    }

    // 서버가 대신 접속하는 주소는 외부 공개 주소만. 내부망·루프백·클라우드 메타데이터·다른 포트·다른 방식은 거절.
    @Test
    void privateAndUnsafeAddressesAreBlocked() throws IOException {
        ChatLinkPreviewFetcher.Resolver literal = InetAddress::getAllByName;
        for (String blocked : List.of("http://127.0.0.1/", "http://localhost/", "http://10.1.2.3/",
                "http://172.16.0.1/", "http://192.168.0.1/", "http://169.254.169.254/latest/meta-data/",
                "http://100.64.0.1/", "http://0.0.0.0/", "http://[::1]/", "http://[fd00::1]/",
                "http://[::ffff:127.0.0.1]/", "ftp://93.184.216.34/", "http://93.184.216.34:8080/",
                "http://user@93.184.216.34/")) {
            assertThatThrownBy(() -> ChatLinkPreviewFetcher.requirePublic(URI.create(blocked), literal))
                    .as(blocked).isInstanceOf(IOException.class);
        }
        ChatLinkPreviewFetcher.requirePublic(URI.create("https://93.184.216.34/page"), literal);
        // 이름이 내부 IP로 풀리는 주소(DNS로 속이기)도 거절
        assertThatThrownBy(() -> ChatLinkPreviewFetcher.requirePublic(URI.create("https://evil.example/"),
                host -> new InetAddress[] { InetAddress.getByName("10.0.0.5") })).isInstanceOf(IOException.class);
    }

    // 제목은 og:title → <title> 순, 문자 표기(&amp; 등)를 풀고, 대표 이미지는 상대 주소를 페이지 기준으로 풂.
    @Test
    void titleAndImageAreParsedFromHtml() {
        ChatLinkPreviewFetcher.Page og = ChatLinkPreviewFetcher.parse("""
                <html><head><title>무시됨</title>
                <meta property="og:title" content="Tom &amp; Jerry &#39;특집&#39;">
                <meta content='/img/cover.jpg' property='og:image'></head></html>
                """, URI.create("https://site.example/news/1"));
        assertThat(og.title()).isEqualTo("Tom & Jerry '특집'");
        assertThat(og.imageUrl()).isEqualTo(URI.create("https://site.example/img/cover.jpg"));

        ChatLinkPreviewFetcher.Page plain = ChatLinkPreviewFetcher.parse(
                "<html><head><TITLE>\n  간단한   페이지 </TITLE></head></html>", URI.create("https://x.example/"));
        assertThat(plain.title()).isEqualTo("간단한 페이지");
        assertThat(plain.imageUrl()).isNull();
        assertThat(ChatLinkPreviewFetcher.parse("<html></html>", URI.create("https://x.example/")).title()).isNull();
    }

    // 한국 옛 사이트처럼 EUC-KR로 된 페이지도 글자가 깨지지 않게 HTML 안의 charset을 읽어 풂.
    @Test
    void eucKrPageIsDecoded() {
        byte[] bytes = "<meta charset=\"euc-kr\"><title>한글 제목</title>".getBytes(Charset.forName("EUC-KR"));
        assertThat(ChatLinkPreviewFetcher.decode(bytes, "text/html")).contains("한글 제목");
    }

    // 목록: 최신 메시지부터, 메시지 속 링크마다 한 줄. 삭제한 메시지·참여자가 아닌 사람은 제외.
    // 제목·대표 이미지는 뒤에서 가져와 다음 조회 때 보임. 제목이 없으면 null(화면에서 "제목 없는 웹사이트").
    @Test
    void linksAreListedWithTitleImageAndSender() throws Exception {
        send(author, "참고: https://news.example.com/a 와 https://plain.example.org/b");
        Long deleted = send(author, "지울 링크 https://gone.example.com");
        service.deleteMessage(author.getId(), room.getId(), deleted, 0L);
        send(author, "링크 없는 글");

        ChatRoomResponse.LinkPage page = service.links(author.getId(), room.getId(), null);
        assertThat(page.items()).extracting(ChatRoomResponse.LinkItem::url)
                .containsExactly("https://news.example.com/a", "https://plain.example.org/b");
        assertThat(page.items()).allSatisfy(item -> assertThat(item.senderName()).isEqualTo("작성자"));

        // 뒤에서 가져오기가 끝날 때까지 잠깐 기다림(최대 5초)
        ChatRoomResponse.LinkPage done = page;
        for (int i = 0; i < 50 && done.items().stream().anyMatch(item -> item.previewStatus().equals("PENDING")); i++) {
            Thread.sleep(100);
            done = service.links(author.getId(), room.getId(), null);
        }
        ChatRoomResponse.LinkItem news = done.items().getFirst();
        assertThat(news.previewStatus()).isEqualTo("DONE");
        assertThat(news.title()).isEqualTo("오늘의 뉴스");
        assertThat(news.imagePreviewId()).isNotNull();
        ChatRoomResponse.LinkItem plain = done.items().get(1);
        assertThat(plain.title()).isNull();
        assertThat(plain.imagePreviewId()).isNull();

        // 대표 이미지는 긴 변 480px로 줄여 저장해 둔 것을 줌.
        ChatRoomService.LinkImage image = service.linkImage(news.imagePreviewId());
        assertThat(image.contentType()).isEqualTo("image/png");
        try (InputStream in = image.resource().getInputStream()) {
            assertThat(ImageIO.read(in).getWidth()).isEqualTo(ChatImageThumbnail.MAX_SIDE);
        }

        assertThatThrownBy(() -> service.links(stranger.getId(), room.getId(), null))
                .isInstanceOf(ChatException.class);
    }
}
