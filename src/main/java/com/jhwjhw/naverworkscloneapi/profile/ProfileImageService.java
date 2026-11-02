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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

// 프로필 사진. 받은 사진은 원본을 남기지 않고, 가운데를 정사각형으로 잘라 SIDE px JPEG 하나로 줄여 저장.
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
        String key;
        try {
            key = storage.save(new ByteArrayInputStream(jpeg));
        } catch (IOException exception) {
            log.error("Profile image save failed. membershipId={}", membershipId, exception);
            throw new AuthException("PROFILE_IMAGE_SAVE_FAILED", "사진을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        replace(me, key);
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

    // 사진 파일. 없으면 null.
    @Transactional(readOnly = true)
    public Resource load(Long membershipId) {
        return membershipRepository.findById(membershipId).map(Membership::getProfileImageKey)
                .map(storage::load).orElse(null);
    }

    // 저장소 키의 마지막 조각(무작위 이름)을 판으로 씀. 키 전체(폴더 경로)는 화면에 내보내지 않음.
    static String versionOf(String key) {
        return key.substring(key.lastIndexOf('/') + 1);
    }

    // 새 키로 바꾸고, DB 반영이 확정되면 이전 파일을 지움(되돌려지면 새 파일을 지움).
    private void replace(Membership me, String newKey) {
        String oldKey = me.getProfileImageKey();
        me.changeProfileImage(newKey);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) deleteQuietly(oldKey);
                else deleteQuietly(newKey);
            }
        });
    }

    private void deleteQuietly(String key) {
        if (key == null) return;
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
