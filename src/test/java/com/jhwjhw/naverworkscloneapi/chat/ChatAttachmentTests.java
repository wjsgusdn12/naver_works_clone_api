package com.jhwjhw.naverworkscloneapi.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.startsWith;

import com.jhwjhw.naverworkscloneapi.chat.domain.ChatRoom;
import com.jhwjhw.naverworkscloneapi.chat.domain.ChatRoomMember;
import com.jhwjhw.naverworkscloneapi.chat.domain.ChatRoomType;
import com.jhwjhw.naverworkscloneapi.chat.repository.ChatMessageAttachmentRepository;
import com.jhwjhw.naverworkscloneapi.chat.repository.ChatRoomMemberRepository;
import com.jhwjhw.naverworkscloneapi.chat.repository.ChatRoomRepository;
import com.jhwjhw.naverworkscloneapi.common.storage.FileStorage;
import com.jhwjhw.naverworkscloneapi.common.storage.LocalFileStorage;
import com.jhwjhw.naverworkscloneapi.identity.domain.Company;
import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import com.jhwjhw.naverworkscloneapi.identity.domain.Role;
import com.jhwjhw.naverworkscloneapi.identity.domain.UserAccount;
import com.jhwjhw.naverworkscloneapi.identity.repository.CompanyRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.MembershipRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.UserAccountRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:chatAttachmentTest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
// 사진·파일 전송(판별·저장·내려받기), 권한, 삭제·전달·수정 제한, 모아보기 검증.
class ChatAttachmentTests {
    @Autowired private ChatRoomService service;
    @Autowired private CompanyRepository companies;
    @Autowired private UserAccountRepository accounts;
    @Autowired private MembershipRepository memberships;
    @Autowired private ChatRoomRepository rooms;
    @Autowired private ChatRoomMemberRepository participants;
    @Autowired private MockMvc mvc;
    @Autowired private JwtEncoder encoder;
    @Autowired private ChatMessageAttachmentRepository attachmentRepository;
    @Autowired private FileStorage storage;
    @Autowired private TransactionTemplate transaction;

    private Membership author;
    private Membership stranger;
    private ChatRoom room;
    private ChatRoom targetRoom;

    @BeforeEach
    void setUp() {
        Company company = companies.save(new Company("첨부 테스트 " + UUID.randomUUID()));
        author = createMember(company, "작성자");
        Membership other = createMember(company, "동료");
        stranger = createMember(company, "외부인");
        room = rooms.save(new ChatRoom(company, ChatRoomType.DIRECT, null, null));
        participants.save(new ChatRoomMember(room, author));
        participants.save(new ChatRoomMember(room, other));
        targetRoom = rooms.save(new ChatRoom(company, ChatRoomType.SELF, null, null));
        participants.save(new ChatRoomMember(targetRoom, author));
    }

    private Membership createMember(Company company, String name) {
        UserAccount account = accounts.save(new UserAccount(UUID.randomUUID() + "@example.com", "test-hash", name));
        return memberships.save(new Membership(company, account, null, Role.USER));
    }

    private String token(Membership member) {
        Instant now = Instant.now();
        return encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer("naverworks-clone-api-test").subject("membership:" + member.getId())
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("membershipId", member.getId())
                .claim("role", member.getRole().name()).build())).getTokenValue();
    }

    // 가로 3, 세로 2짜리 진짜 PNG.
    private static MockMultipartFile png(String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("file", name, "image/png", out.toByteArray());
    }

    private static MockMultipartFile text(String name, String content, String contentType) {
        return new MockMultipartFile("file", name, contentType, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void imageIsDetectedByContentAndCanBeDownloaded() throws IOException {
        MockMultipartFile file = png("photo.png");
        ChatRoomResponse.Message sent = service.sendAttachment(author.getId(), room.getId(), file, null);
        assertThat(sent.content()).isEqualTo("사진");
        assertThat(sent.attachment().kind()).isEqualTo("IMAGE");
        assertThat(sent.attachment().contentType()).isEqualTo("image/png");
        assertThat(sent.attachment().width()).isEqualTo(3);
        assertThat(sent.attachment().height()).isEqualTo(2);
        assertThat(sent.attachment().size()).isEqualTo(file.getSize());

        ChatRoomService.AttachmentFile download = service.attachmentFile(author.getId(), sent.attachment().id(), false);
        try (InputStream in = download.resource().getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(file.getBytes());
        }
        // 메시지 목록에도 첨부 정보가 같이 내려옴.
        assertThat(service.messages(author.getId(), room.getId(), null, null, null).messages().getLast().attachment())
                .isEqualTo(sent.attachment());
    }

    // 확장자·형식을 사진처럼 꾸며도 내용이 사진이 아니면 일반 파일. SVG도 일반 파일.
    @Test
    void disguisedOrSvgFilesAreTreatedAsPlainFiles() {
        ChatRoomResponse.Message fake = service.sendAttachment(author.getId(), room.getId(),
                text("evil.png", "<script>alert(1)</script>", "image/png"), null);
        assertThat(fake.attachment().kind()).isEqualTo("FILE");
        assertThat(fake.attachment().contentType()).isEqualTo("application/octet-stream");
        assertThat(fake.content()).isEqualTo("evil.png");

        ChatRoomResponse.Message svg = service.sendAttachment(author.getId(), room.getId(),
                text("logo.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>", "image/svg+xml"), null);
        assertThat(svg.attachment().kind()).isEqualTo("FILE");
    }

    @Test
    void fileNameLosesPathPartsAndEmptyFileIsRejected() {
        ChatRoomResponse.Message sent = service.sendAttachment(author.getId(), room.getId(),
                text("../../etc/passwd", "data", "text/plain"), null);
        assertThat(sent.attachment().name()).isEqualTo("passwd");
        assertThat(ChatAttachmentStore.safeName("C:\\Users\\me\\보고서.pdf")).isEqualTo("보고서.pdf");

        assertThatThrownBy(() -> service.sendAttachment(author.getId(), room.getId(),
                new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]), null))
                .isInstanceOf(ChatException.class).hasMessageContaining("빈 파일");
    }

    @Test
    void outsidersCanNeitherUploadNorDownload() throws IOException {
        assertThatThrownBy(() -> service.sendAttachment(stranger.getId(), room.getId(), png("a.png"), null))
                .isInstanceOf(ChatException.class);
        Long attachmentId = service.sendAttachment(author.getId(), room.getId(), png("a.png"), null).attachment().id();
        assertThatThrownBy(() -> service.attachmentFile(stranger.getId(), attachmentId, false))
                .isInstanceOf(ChatException.class);
        assertThatThrownBy(() -> service.attachments(stranger.getId(), room.getId(), "IMAGE", null))
                .isInstanceOf(ChatException.class);
    }

    // 삭제하면 말풍선·모아보기에서 사라지고 내려받기도 막힘.
    @Test
    void deletedMessageHidesAttachment() throws IOException {
        ChatRoomResponse.Message sent = service.sendAttachment(author.getId(), room.getId(), png("a.png"), null);
        ChatRoomResponse.Message deleted = service.deleteMessage(author.getId(), room.getId(), sent.id(), 0L);
        assertThat(deleted.attachment()).isNull();
        assertThatThrownBy(() -> service.attachmentFile(author.getId(), sent.attachment().id(), false))
                .isInstanceOf(ChatException.class).hasMessageContaining("찾을 수 없습니다");
        assertThat(service.attachments(author.getId(), room.getId(), "IMAGE", null).items()).isEmpty();
    }

    @Test
    void attachmentMessageCannotBeEditedButCanBeRepliedTo() {
        ChatRoomResponse.Message sent = service.sendAttachment(author.getId(), room.getId(),
                text("회의록.txt", "내용", "text/plain"), null);
        assertThatThrownBy(() -> service.updateMessage(author.getId(), room.getId(), sent.id(),
                new ChatRoomRequest.MessageUpdateRequest("바꾼 내용", List.of(), 0L)))
                .isInstanceOf(ChatException.class).hasMessageContaining("수정할 수 없습니다");

        ChatRoomResponse.Message reply = service.sendMessage(author.getId(), room.getId(),
                new ChatRoomRequest.MessageCreateRequest("확인했습니다", List.of(), sent.id()));
        assertThat(reply.reply().content()).isEqualTo("회의록.txt");
        // 첨부로 답장할 수도 있음.
        ChatRoomResponse.Message fileReply = service.sendAttachment(author.getId(), room.getId(),
                text("수정본.txt", "내용2", "text/plain"), sent.id());
        assertThat(fileReply.reply().messageId()).isEqualTo(sent.id());
    }

    // 전달하면 같은 파일을 가리키는 새 첨부가 생기고, 전달받은 방에서도 내려받을 수 있음.
    @Test
    void forwardCopiesAttachment() throws IOException {
        ChatRoomResponse.Message sent = service.sendAttachment(author.getId(), room.getId(), png("a.png"), null);
        ChatRoomResponse.Message copy = service.forwardMessage(author.getId(), room.getId(), sent.id(),
                List.of(targetRoom.getId())).getFirst();
        assertThat(copy.forwarded()).isTrue();
        assertThat(copy.attachment().id()).isNotEqualTo(sent.attachment().id());
        assertThat(copy.attachment().name()).isEqualTo("a.png");
        assertThat(service.attachmentFile(author.getId(), copy.attachment().id(), false).resource().exists()).isTrue();
    }

    // 모아보기는 종류별 최신순이고 30개씩 끊어 다음 위치를 알려 줌.
    @Test
    void galleryListsByKindNewestFirstWithPaging() throws IOException {
        for (int i = 0; i < ChatRoomService.ATTACHMENT_PAGE_SIZE + 1; i++) {
            service.sendAttachment(author.getId(), room.getId(), png("p" + i + ".png"), null);
        }
        service.sendAttachment(author.getId(), room.getId(), text("문서.txt", "x", "text/plain"), null);

        ChatRoomResponse.AttachmentPage first = service.attachments(author.getId(), room.getId(), "IMAGE", null);
        assertThat(first.items()).hasSize(ChatRoomService.ATTACHMENT_PAGE_SIZE);
        assertThat(first.items().getFirst().attachment().name()).isEqualTo("p30.png");
        assertThat(first.nextCursor()).isNotNull();
        ChatRoomResponse.AttachmentPage second = service.attachments(author.getId(), room.getId(), "IMAGE",
                first.nextCursor());
        assertThat(second.items()).extracting(item -> item.attachment().name()).containsExactly("p0.png");
        assertThat(second.nextCursor()).isNull();

        ChatRoomResponse.AttachmentPage files = service.attachments(author.getId(), room.getId(), "FILE", null);
        assertThat(files.items()).hasSize(1);
        assertThat(files.items().getFirst().senderName()).isEqualTo("작성자");
        assertThatThrownBy(() -> service.attachments(author.getId(), room.getId(), "VIDEO", null))
                .isInstanceOf(ChatException.class);
    }

    // 실제 HTTP 요청: multipart 업로드, 일반 파일은 항상 다운로드용 형식, 사진만 inline 표시 허용.
    @Test
    void httpUploadAndDownloadHeaders() throws Exception {
        String auth = "Bearer " + token(author);
        mvc.perform(multipart("/api/v1/chatRoom/message/attachment/create").file(text("page.html", "<h1>x</h1>", "text/html"))
                .param("chatRoomId", room.getId().toString()).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.attachment.kind").value("FILE"));
        Long fileId = service.attachments(author.getId(), room.getId(), "FILE", null).items().getFirst().attachment().id();
        mvc.perform(get("/api/v1/chatRoom/message/attachment/download").param("attachmentId", fileId.toString())
                .param("inline", "true").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        Long imageId = service.sendAttachment(author.getId(), room.getId(), png("a.png"), null).attachment().id();
        mvc.perform(get("/api/v1/chatRoom/message/attachment/download").param("attachmentId", imageId.toString())
                .param("inline", "true").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", startsWith("inline")));
        // 참여자가 아니면 내려받을 수 없음.
        mvc.perform(get("/api/v1/chatRoom/message/attachment/download").param("attachmentId", imageId.toString())
                .header("Authorization", "Bearer " + token(stranger)))
                .andExpect(status().isBadRequest());
    }

    // 가로 width, 세로 height짜리 진짜 JPEG. exifOrientation이 1이 아니면 휴대폰 사진처럼 회전 정보(EXIF)를 넣음.
    private static MockMultipartFile jpeg(String name, int width, int height, int exifOrientation) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", out);
        byte[] plain = out.toByteArray();
        if (exifOrientation == 1) return new MockMultipartFile("file", name, "image/jpeg", plain);
        // APP1(FFE1) = "Exif\0\0" + TIFF(MM, IFD0 위치 8) + 항목 1개(0x0112 회전, SHORT) + 다음 IFD 없음
        byte[] exif = {
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 42, 0, 0, 0, 8,
                0, 1,
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) exifOrientation, 0, 0,
                0, 0, 0, 0 };
        ByteArrayOutputStream withExif = new ByteArrayOutputStream();
        withExif.write(plain, 0, 2); // FFD8
        withExif.write(new byte[] { (byte) 0xFF, (byte) 0xE1, 0, (byte) (exif.length + 2) });
        withExif.write(exif);
        withExif.write(plain, 2, plain.length - 2);
        return new MockMultipartFile("file", name, "image/jpeg", withExif.toByteArray());
    }

    private static BufferedImage decode(ChatRoomService.AttachmentFile file) throws IOException {
        try (InputStream in = file.resource().getInputStream()) {
            return ImageIO.read(in);
        }
    }

    // 큰 사진은 긴 변 480px 작은 사본을 따로 저장. 말풍선용(thumbnail=true)은 작은 사본, 내려받기는 원본 그대로.
    @Test
    void largeImageGetsThumbnailAndOriginalStaysIntact() throws IOException {
        MockMultipartFile file = jpeg("big.jpg", 1200, 800, 1);
        ChatRoomResponse.Message sent = service.sendAttachment(author.getId(), room.getId(), file, null);
        Long id = sent.attachment().id();
        assertThat(attachmentRepository.findById(id).orElseThrow().getThumbnailKey()).isNotNull();

        ChatRoomService.AttachmentFile thumbnail = service.attachmentFile(author.getId(), id, true);
        BufferedImage small = decode(thumbnail);
        assertThat(small.getWidth()).isEqualTo(ChatImageThumbnail.MAX_SIDE);
        assertThat(small.getHeight()).isEqualTo(320);
        assertThat(thumbnail.size()).isLessThan(file.getSize());

        ChatRoomService.AttachmentFile original = service.attachmentFile(author.getId(), id, false);
        try (InputStream in = original.resource().getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(file.getBytes());
        }
        // 전달한 복사본도 같은 작은 사본을 씀.
        ChatRoomResponse.Message copy = service.forwardMessage(author.getId(), room.getId(), sent.id(),
                List.of(targetRoom.getId())).getFirst();
        assertThat(attachmentRepository.findById(copy.attachment().id()).orElseThrow().getThumbnailKey())
                .isEqualTo(attachmentRepository.findById(id).orElseThrow().getThumbnailKey());
    }

    // 작은 사진은 작은 사본을 만들지 않고, thumbnail=true로 달라고 해도 원본을 줌.
    @Test
    void smallImageServesOriginalAsThumbnail() throws IOException {
        MockMultipartFile file = png("tiny.png");
        Long id = service.sendAttachment(author.getId(), room.getId(), file, null).attachment().id();
        assertThat(attachmentRepository.findById(id).orElseThrow().getThumbnailKey()).isNull();
        try (InputStream in = service.attachmentFile(author.getId(), id, true).resource().getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(file.getBytes());
        }
    }

    // 휴대폰 세로 사진(회전값 6 = 시계 방향 90도): 화면 자리 크기와 작은 사본 모두 세워서 맞춤.
    @Test
    void exifRotatedPhotoIsStoredUpright() throws IOException {
        MockMultipartFile file = jpeg("portrait.jpg", 600, 300, 6);
        assertThat(ChatImageThumbnail.jpegOrientation(file)).isEqualTo(6);
        ChatRoomResponse.Message sent = service.sendAttachment(author.getId(), room.getId(), file, null);
        assertThat(sent.attachment().width()).isEqualTo(300);
        assertThat(sent.attachment().height()).isEqualTo(600);
        BufferedImage small = decode(service.attachmentFile(author.getId(), sent.attachment().id(), true));
        assertThat(small.getWidth()).isEqualTo(240);
        assertThat(small.getHeight()).isEqualTo(480);
    }

    // 1분 개수 제한과 하루 용량 제한. 전달한 복사본은 새 업로드가 아니라 세지 않음.
    @Test
    void uploadLimiterBlocksBurstsAndDailyQuota() throws IOException {
        ChatRoomResponse.Message first = service.sendAttachment(author.getId(), room.getId(), png("1.png"), null);
        service.sendAttachment(author.getId(), room.getId(), png("2.png"), null);
        service.forwardMessage(author.getId(), room.getId(), first.id(), List.of(targetRoom.getId()));

        ChatUploadLimiter perMinute = new ChatUploadLimiter(attachmentRepository, Clock.systemUTC(), 2,
                Long.MAX_VALUE);
        assertThatThrownBy(() -> perMinute.check(author.getId(), 10))
                .isInstanceOf(ChatException.class).hasMessageContaining("너무 빠르게");
        // 다른 사람은 영향 없음.
        perMinute.check(stranger.getId(), 10);
        // 1분이 지나면 다시 가능(개수 제한은 최근 1분만 셈).
        new ChatUploadLimiter(attachmentRepository,
                Clock.offset(Clock.systemUTC(), Duration.ofMinutes(2)), 2, Long.MAX_VALUE)
                .check(author.getId(), 10);

        long used = attachmentRepository.sumUploadBytesSince(author.getId(), Instant.now().minusSeconds(3600));
        assertThat(used).isEqualTo(png("x.png").getSize() * 2);
        ChatUploadLimiter daily = new ChatUploadLimiter(attachmentRepository, Clock.systemUTC(), 1000,
                used + 100);
        daily.check(author.getId(), 100);
        assertThatThrownBy(() -> daily.check(author.getId(), 101))
                .isInstanceOf(ChatException.class).hasMessageContaining("하루");
    }

    // 디스크에 남을 공간이 기준보다 적으면 저장하지 않고 거절(디스크가 가득 차 서버가 멈추는 것을 막음).
    @Test
    void uploadIsRejectedWhenStorageIsAlmostFull() {
        ChatAttachmentStore almostFull = new ChatAttachmentStore(storage, Long.MAX_VALUE);
        assertThatThrownBy(() -> almostFull.store(text("a.txt", "x", "text/plain")))
                .isInstanceOf(ChatException.class).hasMessageContaining("저장 공간");
    }

    // 보관 기간(30일)이 지난 삭제 메시지의 파일 정리. 전달한 복사본이 살아 있으면 파일은 남기고, 복사본까지 끝나면 지움.
    @Test
    void cleanupPurgesExpiredFilesButKeepsFilesStillInUse() throws IOException {
        ChatRoomResponse.Message sent = service.sendAttachment(author.getId(), room.getId(),
                jpeg("shared.jpg", 1200, 800, 1), null);
        ChatRoomResponse.Message copy = service.forwardMessage(author.getId(), room.getId(), sent.id(),
                List.of(targetRoom.getId())).getFirst();
        String key = attachmentRepository.findById(sent.attachment().id()).orElseThrow().getStorageKey();
        String thumbnailKey = attachmentRepository.findById(sent.attachment().id()).orElseThrow().getThumbnailKey();
        service.deleteMessage(author.getId(), room.getId(), sent.id(), 0L);

        // 31일 뒤의 청소. 고아 파일 정리는 이 테스트 대상이 아니라 아주 길게 잡아 건드리지 않음.
        Clock later = Clock.offset(Clock.systemUTC(), Duration.ofDays(31));
        ChatAttachmentCleanup cleanup = new ChatAttachmentCleanup(attachmentRepository, storage, transaction, later,
                Duration.ofDays(30), Duration.ofDays(3650));
        ChatAttachmentCleanup.Result first = cleanup.cleanup();
        assertThat(first.purgedAttachments()).isEqualTo(1);
        assertThat(first.expiredFiles()).isZero();
        assertThat(attachmentRepository.findById(sent.attachment().id()).orElseThrow().getPurgedAt()).isNotNull();
        // 복사본은 그대로 내려받을 수 있음.
        assertThat(storage.load(key)).isNotNull();
        assertThat(service.attachmentFile(author.getId(), copy.attachment().id(), false).resource().exists()).isTrue();

        // 복사본도 삭제되고 보관 기간이 지나면 원본·작은 사본 파일을 모두 지움. 행은 남고 "정리됨" 표시.
        service.deleteMessage(author.getId(), targetRoom.getId(), copy.id(), 0L);
        ChatAttachmentCleanup.Result second = cleanup.cleanup();
        assertThat(second.purgedAttachments()).isEqualTo(1);
        assertThat(second.expiredFiles()).isEqualTo(1);
        assertThat(storage.load(key)).isNull();
        assertThat(storage.load(thumbnailKey)).isNull();
        assertThat(attachmentRepository.findById(copy.attachment().id()).orElseThrow().getPurgedAt()).isNotNull();
        // 다시 돌려도 할 일이 없음.
        assertThat(cleanup.cleanup().purgedAttachments()).isZero();
    }

    // DB 어디에도 기록이 없는 파일(고아 파일)만 지우고, 첨부가 가리키는 파일은 남김. 별도 임시 폴더로 격리해 검증.
    @Test
    void cleanupDeletesOnlyUnreferencedOrphanFiles(@TempDir Path folder)
            throws IOException {
        LocalFileStorage isolated =
                new LocalFileStorage(folder.toString());
        Long id = service.sendAttachment(author.getId(), room.getId(), png("keep.png"), null).attachment().id();
        String referenced = attachmentRepository.findById(id).orElseThrow().getStorageKey();
        // 같은 키 자리에 파일을 만들어 "DB가 가리키는 파일"을 흉내
        Path keep = folder.resolve(referenced);
        Files.createDirectories(keep.getParent());
        Files.writeString(keep, "kept");
        String orphan = isolated.save(new ByteArrayInputStream("orphan".getBytes(StandardCharsets.UTF_8)));

        // 방금 만든 파일은 고아여도 기다림(올라가는 중일 수 있음)
        ChatAttachmentCleanup now = new ChatAttachmentCleanup(attachmentRepository, isolated, transaction,
                Clock.systemUTC(), Duration.ofDays(30), Duration.ofHours(24));
        assertThat(now.cleanup().orphanFiles()).isZero();

        ChatAttachmentCleanup dayLater = new ChatAttachmentCleanup(attachmentRepository, isolated, transaction,
                Clock.offset(Clock.systemUTC(), Duration.ofHours(25)),
                Duration.ofDays(30), Duration.ofHours(24));
        assertThat(dayLater.cleanup().orphanFiles()).isEqualTo(1);
        assertThat(isolated.load(orphan)).isNull();
        assertThat(isolated.load(referenced)).isNotNull();
    }
}
