package com.jhwjhw.naverworkscloneapi.chat;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.core.io.InputStreamSource;

// 사진의 작은 사본(썸네일)과 휴대폰 사진의 회전 정보(EXIF Orientation)를 다룸. 자바 기본 ImageIO만 사용.
// - 말풍선·모아보기는 썸네일, 크게 보기·내려받기는 원본(업무용이라 원본 화질은 그대로 보관).
// - 큰 사진도 메모리에 통째로 펼치지 않도록 읽을 때부터 몇 픽셀씩 건너뛰며(subsampling) 줄여 읽음.
public final class ChatImageThumbnail {
    // 썸네일의 긴 변 최대 길이(px). 말풍선(최대 약 240px)을 고해상도 화면에서도 선명하게.
    static final int MAX_SIDE = 480;
    // 이보다 픽셀이 많은 사진은 썸네일을 만들지 않고 원본을 씀(압축 폭탄 같은 비정상 사진 방어).
    static final long MAX_PIXELS = 60_000_000L;
    private static final float JPEG_QUALITY = 0.82f;

    private ChatImageThumbnail() {
    }

    // 만든 썸네일. bytes는 원본과 같은 형식(JPEG→JPEG, PNG→PNG)이라 형식 정보를 따로 저장하지 않음.
    record Result(byte[] bytes) {
    }

    // JPEG·PNG만 만듦. GIF는 움직임이 사라지고 WebP는 자바 기본으로 못 읽어 원본을 씀.
    // 이미 작고 회전도 없는 사진이면 만들 필요가 없어 null. 실패해도 업로드는 계속되도록 null.
    static Result create(InputStreamSource file, String imageType, int width, int height, int orientation) {
        boolean jpeg = "image/jpeg".equals(imageType);
        if (!jpeg && !"image/png".equals(imageType)) return null;
        if ((long) width * height > MAX_PIXELS) return null;
        if (Math.max(width, height) <= MAX_SIDE && orientation == 1) return null;
        try {
            BufferedImage decoded = readSubsampled(file, Math.max(width, height));
            if (decoded == null) return null;
            BufferedImage oriented = orient(scale(decoded, jpeg), orientation, jpeg);
            return new Result(write(oriented, jpeg));
        } catch (IOException | RuntimeException exception) {
            // CMYK JPEG 등 읽을 수 없는 사진은 원본으로 보여 줌.
            return null;
        }
    }

    // 사진 전체를 메모리에 올리지 않고 머리 정보만 읽어 가로·세로를 얻음. 읽지 못하면 null.
    static int[] size(InputStreamSource file) {
        try (InputStream raw = file.getInputStream(); ImageInputStream in = ImageIO.createImageInputStream(raw)) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[] { reader.getWidth(0), reader.getHeight(0) };
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    // 긴 변이 MAX_SIDE의 2배 안팎이 되도록 건너뛰며 읽음(그 뒤 부드럽게 줄여 화질 유지).
    private static BufferedImage readSubsampled(InputStreamSource file, int longest) throws IOException {
        try (InputStream raw = file.getInputStream(); ImageInputStream in = ImageIO.createImageInputStream(raw)) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, longest / (MAX_SIDE * 2));
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    // 긴 변을 MAX_SIDE 이하로 줄임. JPEG는 투명도가 없으므로 RGB, PNG는 투명도 유지.
    private static BufferedImage scale(BufferedImage source, boolean jpeg) {
        double ratio = Math.min(1.0, (double) MAX_SIDE / Math.max(source.getWidth(), source.getHeight()));
        int targetWidth = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        BufferedImage target = new BufferedImage(targetWidth, targetHeight,
                jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    // EXIF 회전값(1~8)대로 돌리거나 뒤집음. 브라우저는 원본을 볼 때 이 값을 적용하므로 썸네일도 같게 맞춤.
    private static BufferedImage orient(BufferedImage source, int orientation, boolean jpeg) {
        if (orientation < 2 || orientation > 8) return source;
        int w = source.getWidth();
        int h = source.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0); // 좌우 뒤집기
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h); // 180도
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h); // 상하 뒤집기
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0); // 대각선 뒤집기
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0); // 시계 방향 90도
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w); // 반대 대각선 뒤집기
            default -> new AffineTransform(0, -1, 1, 0, 0, w); // 8: 반시계 방향 90도
        };
        BufferedImage target = new BufferedImage(swap ? h : w, swap ? w : h,
                jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] write(BufferedImage image, boolean jpeg) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!jpeg) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
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

    // JPEG 머리의 EXIF에서 회전값(1~8)을 읽음. 없거나 읽지 못하면 1(회전 없음).
    // 구조: FFD8 → [FFE1 길이 "Exif\0\0" + TIFF(II/MM, IFD0 위치) → 항목들 중 0x0112] ...
    public static int jpegOrientation(InputStreamSource file) {
        try (InputStream raw = file.getInputStream(); DataInputStream in = new DataInputStream(raw)) {
            if (in.readUnsignedShort() != 0xFFD8) return 1;
            while (true) {
                int marker = in.readUnsignedShort();
                // 사진 데이터(SOS)나 끝(EOI)에 닿으면 EXIF가 없는 것
                if (marker == 0xFFDA || marker == 0xFFD9 || (marker & 0xFF00) != 0xFF00) return 1;
                int length = in.readUnsignedShort() - 2;
                if (length < 0) return 1;
                if (marker != 0xFFE1) {
                    in.skipNBytes(length);
                    continue;
                }
                byte[] segment = in.readNBytes(length);
                return orientationFromExif(segment);
            }
        } catch (IOException | RuntimeException exception) {
            return 1;
        }
    }

    private static int orientationFromExif(byte[] data) {
        // "Exif\0\0" 6바이트 뒤가 TIFF 머리
        if (data.length < 14 || data[0] != 'E' || data[1] != 'x' || data[2] != 'i' || data[3] != 'f') return 1;
        int tiff = 6;
        boolean little = data[tiff] == 'I';
        int ifd = tiff + (int) read(data, tiff + 4, 4, little);
        if (ifd + 2 > data.length) return 1;
        int count = (int) read(data, ifd, 2, little);
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > data.length) return 1;
            if (read(data, entry, 2, little) == 0x0112) {
                int value = (int) read(data, entry + 8, 2, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    // data[offset]부터 size바이트를 바이트 순서(little: II, 아니면 MM)에 맞게 숫자로.
    private static long read(byte[] data, int offset, int size, boolean little) {
        if (offset < 0 || offset + size > data.length) throw new IllegalArgumentException("EXIF 범위 밖");
        long value = 0;
        for (int i = 0; i < size; i++) {
            int b = data[offset + (little ? size - 1 - i : i)] & 0xFF;
            value = (value << 8) | b;
        }
        return value;
    }
}
