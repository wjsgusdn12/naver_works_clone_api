package com.jhwjhw.naverworkscloneapi.chat;

import com.jhwjhw.naverworkscloneapi.chat.domain.ChatLinkPreview;
import com.jhwjhw.naverworkscloneapi.chat.repository.ChatLinkPreviewRepository;
import com.jhwjhw.naverworkscloneapi.common.storage.FileStorage;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

// 메시지 속 링크의 미리보기(제목·대표 이미지) 관리.
// - 웹사이트 접속은 느릴 수 있어 요청 처리와 따로(작업 스레드 2개) 진행. 화면은 그동안 "불러오는 중"으로 보여 줌.
// - 같은 주소는 한 번만 가져와 DB에 두고 모든 방이 같이 씀. 대표 이미지는 서버가 받아 작은 사본으로 저장해
//   사용자 브라우저가 외부 사이트에 직접 접속하지 않게 함(사용자 IP 노출·추적 방지).
// - 가져오다 서버가 꺼져 "가져오는 중"에 멈춘 것은 2분 뒤, 실패한 것은 하루 뒤 다시 시도.
@Component
@Slf4j
public class ChatLinkPreviewService {
    // http/https로 시작해 공백·따옴표·꺾쇠 전까지. 메시지 하나에서 최대 10개.
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'`]+", Pattern.CASE_INSENSITIVE);
    private static final int MAX_URLS_PER_MESSAGE = 10;
    static final int MAX_URL_LENGTH = 2048;
    private static final Duration STUCK_AFTER = Duration.ofMinutes(2);
    private static final Duration RETRY_FAILED_AFTER = Duration.ofDays(1);

    private final ChatLinkPreviewRepository previewRepository;
    private final ChatLinkPreviewFetcher fetcher;
    private final FileStorage storage;
    private final TransactionTemplate transaction;
    // 지금 이 서버에서 가져오는 중인 주소(같은 주소를 겹쳐 가져오지 않게)
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    // 대기열이 꽉 차면(한꺼번에 링크가 많이 올라오면) 새 요청은 버리고 다음 조회 때 다시 시도
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(200), runnable -> {
                Thread thread = new Thread(runnable, "link-preview");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());

    public ChatLinkPreviewService(ChatLinkPreviewRepository previewRepository, ChatLinkPreviewFetcher fetcher,
            FileStorage storage, TransactionTemplate transaction) {
        this.previewRepository = previewRepository;
        this.fetcher = fetcher;
        this.storage = storage;
        this.transaction = transaction;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // 메시지 글에서 링크를 순서대로 뽑음(중복 제외). 문장 끝 마침표·닫는 괄호 등은 주소에서 뺌.
    static List<String> extractUrls(String content) {
        if (content == null || content.isEmpty()) return List.of();
        Set<String> urls = new LinkedHashSet<>();
        Matcher matcher = URL.matcher(content);
        while (matcher.find() && urls.size() < MAX_URLS_PER_MESSAGE) {
            String url = trimTrailing(matcher.group());
            if (url.length() <= MAX_URL_LENGTH && url.indexOf("://") + 3 < url.length()) urls.add(url);
        }
        return List.copyOf(urls);
    }

    private static String trimTrailing(String url) {
        String result = url;
        while (!result.isEmpty()) {
            char last = result.charAt(result.length() - 1);
            if (".,;:!?'\"".indexOf(last) >= 0) {
                result = result.substring(0, result.length() - 1);
            } else if ((last == ')' || last == ']' || last == '}') && !balanced(result, last)) {
                // "(https://a.com/x)"처럼 감싼 괄호는 빼고, 위키 주소처럼 주소 안의 짝 맞는 괄호는 둠
                result = result.substring(0, result.length() - 1);
            } else {
                break;
            }
        }
        return result;
    }

    private static boolean balanced(String text, char close) {
        char open = close == ')' ? '(' : close == ']' ? '[' : '{';
        long opens = text.chars().filter(c -> c == open).count();
        long closes = text.chars().filter(c -> c == close).count();
        return opens >= closes;
    }

    static String hash(String url) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // 주소별 미리보기. 아직 없거나 다시 시도할 때가 된 것은 뒤에서 가져오기를 시작(결과는 다음 조회 때 보임).
    public Map<String, ChatLinkPreview> previews(Collection<String> urls) {
        Map<String, String> hashToUrl = new HashMap<>();
        urls.forEach(url -> hashToUrl.put(hash(url), url));
        Map<String, ChatLinkPreview> result = new HashMap<>();
        Instant now = Instant.now();
        for (ChatLinkPreview preview : previewRepository.findByUrlHashIn(hashToUrl.keySet())) {
            result.put(preview.getUrl(), preview);
            if (needsFetch(preview, now)) request(preview.getUrl());
            hashToUrl.remove(preview.getUrlHash());
        }
        hashToUrl.values().forEach(this::request);
        return result;
    }

    private static boolean needsFetch(ChatLinkPreview preview, Instant now) {
        return switch (preview.getStatus()) {
            case DONE -> false;
            case PENDING -> preview.getCreatedAt().isBefore(now.minus(STUCK_AFTER));
            case FAILED -> preview.getFetchedAt() == null || preview.getFetchedAt().isBefore(now.minus(RETRY_FAILED_AFTER));
        };
    }

    // 메시지를 보낸 뒤(DB 저장이 끝난 뒤) 그 안의 링크를 미리 가져오기 시작. 대개 목록을 열기 전에 준비됨.
    public void requestAfterCommit(String content) {
        List<String> urls = extractUrls(content);
        if (urls.isEmpty()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            urls.forEach(this::request);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                urls.forEach(ChatLinkPreviewService.this::request);
            }
        });
    }

    private void request(String url) {
        String key = hash(url);
        if (!inFlight.add(key)) return;
        try {
            executor.execute(() -> {
                try {
                    refresh(url);
                } catch (RuntimeException exception) {
                    log.warn("Link preview failed. url={}", url, exception);
                } finally {
                    inFlight.remove(key);
                }
            });
        } catch (RuntimeException exception) {
            inFlight.remove(key);
        }
    }

    // 한 주소의 미리보기를 지금 가져와 저장(테스트는 이걸 바로 부름). 이미 완료된 주소면 아무것도 안 함.
    // 두 서버가 같은 새 주소를 동시에 처음 저장하면 한쪽은 중복 오류로 끝나고 다른 쪽 결과를 같이 씀.
    void refresh(String url) {
        String key = hash(url);
        Long id = transaction.execute(status -> {
            ChatLinkPreview existing = previewRepository.findByUrlHash(key).orElse(null);
            if (existing != null) {
                return existing.getStatus() == ChatLinkPreview.Status.DONE ? null : existing.getId();
            }
            return previewRepository.save(new ChatLinkPreview(key, url)).getId();
        });
        if (id == null) return;
        String title = null;
        String imageKey = null;
        String imageType = null;
        boolean failed = false;
        try {
            ChatLinkPreviewFetcher.Page page = fetcher.fetchPage(URI.create(url));
            title = page.title();
            if (page.imageUrl() != null) {
                StoredImage image = storeImage(page.imageUrl());
                if (image != null) {
                    imageKey = image.key();
                    imageType = image.type();
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            failed = true;
            log.info("Link preview unavailable. url={}, reason={}", url, exception.getMessage());
        }
        boolean fetchFailed = failed;
        String finalTitle = title;
        String finalImageKey = imageKey;
        String finalImageType = imageType;
        transaction.executeWithoutResult(status -> previewRepository.findById(id).ifPresent(preview -> {
            Instant now = Instant.now();
            if (fetchFailed) {
                preview.fail(now);
            } else {
                preview.complete(finalTitle, finalImageKey, finalImageType, now);
            }
        }));
    }

    private record StoredImage(String key, String type) {
    }

    // 대표 이미지를 받아 JPEG·PNG면 작은 사본(긴 변 480px)으로 저장. 다른 형식·받기 실패면 null(빈 이미지 자리).
    private StoredImage storeImage(URI imageUrl) {
        try {
            byte[] bytes = fetcher.fetchImage(imageUrl);
            if (bytes == null || bytes.length == 0) return null;
            String type = ChatAttachmentStore.imageTypeOf(Arrays.copyOf(bytes, Math.min(bytes.length, 12)));
            if (!"image/jpeg".equals(type) && !"image/png".equals(type)) return null;
            ByteArrayResource source = new ByteArrayResource(bytes);
            int[] size = ChatImageThumbnail.size(source);
            if (size == null || (long) size[0] * size[1] > ChatImageThumbnail.MAX_PIXELS) return null;
            int orientation = "image/jpeg".equals(type) ? ChatImageThumbnail.jpegOrientation(source) : 1;
            ChatImageThumbnail.Result thumbnail = ChatImageThumbnail.create(source, type, size[0], size[1],
                    orientation);
            byte[] stored = thumbnail != null ? thumbnail.bytes() : bytes;
            return new StoredImage(storage.save(new ByteArrayInputStream(stored)), type);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    // 저장한 대표 이미지(없으면 null).
    public Resource image(ChatLinkPreview preview) {
        return preview.getImageKey() == null ? null : storage.load(preview.getImageKey());
    }
}
