package com.jhwjhw.naverworkscloneapi.profile;

import com.jhwjhw.naverworkscloneapi.auth.AuthException;
import com.jhwjhw.naverworkscloneapi.auth.InvalidAuthenticationException;
import com.jhwjhw.naverworkscloneapi.chat.ChatAttachmentStore;
import com.jhwjhw.naverworkscloneapi.chat.ChatImageThumbnail;
import com.jhwjhw.naverworkscloneapi.common.storage.FileStorage;
import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import com.jhwjhw.naverworkscloneapi.identity.repository.MembershipRepository;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

// 프로필 사진. 받은 사진은 원본을 남기지 않고, 가운데를 정사각형으로 잘라 SIDE px JPEG 하나로 줄여 DB에 저장
// (membership_profile_image). 서버 디스크가 아니라 DB라서 어느 PC·서버에서 실행해도 같은 사진이 보임.
// membership.profile_image_key는 사진의 판(version): "db/무작위"면 DB 사진, 그 밖은 예전 방식(디스크 파일) 키.
// 예전 디스크 사진은 그 파일이 있는 서버가 뜰 때 DB로 옮김(migrateLegacy).
// 휴대폰 사진의 회전 정보(EXIF)도 반영. 자바 기본 ImageIO만 사용(JPEG·PNG).
@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileImageService {
    static final int SIDE = 256;
    static final long MAX_SIZE = 5L * 1024 * 1024;
    // 이보다 픽셀이 많은 사진은 거절(압축 폭탄 같은 비정상 사진 방어)
    private static final long MAX_PIXELS = 40_000_000L;
    private static final float JPEG_QUALITY = 0.85f;

    private final MembershipRepository membershipRepository;
    private final FileStorage storage;
    private final MembershipProfileImageRepository images;
    private final ApplicationEventPublisher eventPublisher;

    // DB에 담긴 사진의 키 앞부분
    static final String DB_PREFIX = "db/";

    // 사진 올리기. 이전 사진 파일은 저장이 끝난 뒤 지움.
    @Transactional
    public void change(Long membershipId, MultipartFile file) {
        Membership me = membershipRepository.findById(membershipId).orElseThrow(InvalidAuthenticationException::new);
        if (file == null || file.isEmpty()) throw invalid("사진 파일을 골라 주세요.");
        if (file.getSize() > MAX_SIZE) throw invalid("프로필 사진은 5MB까지 올릴 수 있습니다.");
        byte[] jpeg;
        try {
            String type = imageType(file);
            if (!"image/jpeg".equals(type) && !"image/png".equals(type)) {
                throw invalid("JPG·PNG 사진만 올릴 수 있습니다.");
            }
            int orientation = "image/jpeg".equals(type) ? ChatImageThumbnail.jpegOrientation(file) : 1;
            jpeg = squareJpeg(file, orientation);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof AuthException authException) throw authException;
            log.warn("Profile image decode failed. membershipId={}", membershipId, exception);
            throw invalid("사진을 읽지 못했습니다. 다른 사진으로 시도해 주세요.");
        }
        replace(me, saveToDatabase(membershipId, jpeg));
    }

    // 기본 이미지(이름 글자)로 되돌리기
    @Transactional
    public void remove(Long membershipId) {
        Membership me = membershipRepository.findById(membershipId).orElseThrow(InvalidAuthenticationException::new);
        replace(me, null);
    }

    // 사진이 있는 구성원과 그 판(version). 판이 바뀌면 화면이 새 사진을 다시 받음.
    @Transactional(readOnly = true)
    public Map<Long, String> versions() {
        Map<Long, String> result = new LinkedHashMap<>();
        for (Object[] row : membershipRepository.findProfileImageKeys()) {
            result.put((Long) row[0], versionOf((String) row[1]));
        }
        return result;
    }

    // 사진 파일. 없으면 null. DB 사진이 기본이고, 아직 옮기지 못한 예전 사진은 디스크에서 읽음.
    @Transactional(readOnly = true)
    public Resource load(Long membershipId) {
        String key = membershipRepository.findById(membershipId).map(Membership::getProfileImageKey).orElse(null);
        if (key == null) return null;
        if (!key.startsWith(DB_PREFIX)) return storage.load(key);
        return images.findById(membershipId).map(row -> (Resource) new ByteArrayResource(row.getImage()))
                .orElse(null);
    }

    // 예전 방식(디스크 파일) 사진이 남은 구성원. [구성원 ID, 키]
    @Transactional(readOnly = true)
    public Map<Long, String> legacyKeys() {
        Map<Long, String> result = new LinkedHashMap<>();
        for (Object[] row : membershipRepository.findProfileImageKeys()) {
            String key = (String) row[1];
            if (!key.startsWith(DB_PREFIX)) result.put((Long) row[0], key);
        }
        return result;
    }

    // 예전 디스크 사진 하나를 DB로 옮김. 이 서버에 파일이 없으면(다른 PC에서 올린 사진) 건너뜀 → false.
    // 옮기는 사이 사진이 바뀌었으면 건드리지 않음. 옮긴 뒤 디스크 파일은 지움.
    @Transactional
    public boolean migrateLegacy(Long membershipId, String legacyKey) throws IOException {
        Resource file = storage.load(legacyKey);
        if (file == null || !file.exists()) return false;
        Membership member = membershipRepository.findById(membershipId).orElse(null);
        if (member == null || !legacyKey.equals(member.getProfileImageKey())) return false;
        byte[] jpeg;
        try (InputStream in = file.getInputStream()) {
            jpeg = in.readAllBytes();
        }
        replace(member, saveToDatabase(membershipId, jpeg));
        return true;
    }

    // 사진 내용을 DB에 쓰고(있으면 덮어씀) 새 판 키를 돌려줌
    private String saveToDatabase(Long membershipId, byte[] jpeg) {
        Instant now = Instant.now();
        images.findById(membershipId).ifPresentOrElse(row -> row.replace(jpeg, now),
                () -> images.save(new MembershipProfileImage(membershipId, jpeg, now)));
        return DB_PREFIX + UUID.randomUUID();
    }

    // 저장소 키의 마지막 조각(무작위 이름)을 판으로 씀. 키 전체(폴더 경로)는 화면에 내보내지 않음.
    static String versionOf(String key) {
        return key.substring(key.lastIndexOf('/') + 1);
    }

    // 새 키로 바꾸고, DB 반영이 확정되면 예전 디스크 파일을 지움. DB 사진 키는 파일이 없어 지울 것도 없음.
    // 다른 사람 화면의 아바타도 바로 바뀌도록 커밋 뒤 실시간으로 알림.
    private void replace(Membership me, String newKey) {
        String oldKey = me.getProfileImageKey();
        me.changeProfileImage(newKey);
        eventPublisher.publishEvent(new ProfileImageChangedEvent(me.getId(), newKey == null ? null : versionOf(newKey)));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) deleteQuietly(oldKey);
            }
        });
    }

    private void deleteQuietly(String key) {
        if (key == null || key.startsWith(DB_PREFIX)) return;
        try {
            storage.delete(key);
        } catch (IOException | RuntimeException exception) {
            // 못 지운 파일은 매일 도는 첨부 청소 작업이 고아 파일로 정리함
            log.warn("Failed to delete profile image. key={}", key, exception);
        }
    }

    private static AuthException invalid(String message) {
        return new AuthException("PROFILE_IMAGE_INVALID", message);
    }

    private static String imageType(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return ChatAttachmentStore.imageTypeOf(in.readNBytes(12));
        }
    }

    // 가운데 정사각형을 잘라 SIDE px로 줄이고 회전값을 반영한 JPEG. 큰 사진은 건너뛰며 읽어 메모리를 아낌.
    private static byte[] squareJpeg(MultipartFile file, int orientation) throws IOException {
        BufferedImage source;
        try (InputStream raw = file.getInputStream(); ImageInputStream in = ImageIO.createImageInputStream(raw)) {
            if (in == null) throw new IOException("unreadable");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("no reader");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if ((long) width * height > MAX_PIXELS) throw invalid("사진이 너무 큽니다. 더 작은 사진으로 올려 주세요.");
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, Math.min(width, height) / (SIDE * 2));
                param.setSourceSubsampling(step, step, 0, 0);
                source = reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
        int side = Math.min(source.getWidth(), source.getHeight());
        BufferedImage square = source.getSubimage((source.getWidth() - side) / 2, (source.getHeight() - side) / 2,
                side, side);
        BufferedImage result = new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // 투명 PNG는 흰 바탕
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, SIDE, SIDE);
            graphics.setTransform(rotation(orientation));
            graphics.drawImage(square, 0, 0, SIDE, SIDE, null);
        } finally {
            graphics.dispose();
        }
        return write(result);
    }

    // EXIF 회전값 → 정사각형 안에서 돌리기·뒤집기(정사각형이라 크기는 그대로).
    // 행렬 순서: (m00, m10, m01, m11, m02, m12) → x' = m00·x + m01·y + m02, y' = m10·x + m11·y + m12
    static AffineTransform rotation(int orientation) {
        int s = SIDE;
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, s, 0); // 좌우 뒤집기
            case 3 -> new AffineTransform(-1, 0, 0, -1, s, s); // 180도
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, s); // 위아래 뒤집기
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0); // 대각선 뒤집기
            case 6 -> new AffineTransform(0, 1, -1, 0, s, 0); // 시계 방향 90도
            case 7 -> new AffineTransform(0, -1, -1, 0, s, s); // 반대 대각선 뒤집기
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, s); // 반시계 방향 90도
            default -> new AffineTransform();
        };
    }

    private static byte[] write(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
