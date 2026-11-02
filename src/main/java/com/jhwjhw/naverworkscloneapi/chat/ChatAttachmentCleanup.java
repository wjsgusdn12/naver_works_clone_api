package com.jhwjhw.naverworkscloneapi.chat;

import com.jhwjhw.naverworkscloneapi.chat.domain.ChatMessageAttachment;
import com.jhwjhw.naverworkscloneapi.chat.repository.ChatMessageAttachmentRepository;
import com.jhwjhw.naverworkscloneapi.common.storage.FileStorage;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// 첨부 파일 청소(매일 새벽 한 번). 디스크에만 남아 자리를 차지하는 파일을 정리함. DB 행은 지우지 않음.
// 1) 보관 기간 끝: 메시지가 삭제됐거나 방이 끝난 지 retention-days가 지난 첨부는 디스크 파일을 지우고 "정리됨" 표시.
//    단, 전달한 복사본처럼 같은 파일을 아직 쓰는 첨부가 있으면 파일은 남겨 둠.
// 2) 고아 파일: DB 어디에도 기록이 없는 파일(저장 직후 서버가 꺼지는 등). 지금 막 올라가는 파일을 지우지 않도록
//    orphan-grace-hours보다 오래된 것만 지움.
// 서버가 여러 대면 각자 돌지만, 지우기·표시 모두 여러 번 해도 결과가 같아 안전함.
@Component
@Slf4j
public class ChatAttachmentCleanup {
    private static final int BATCH = 200;

    private final ChatMessageAttachmentRepository attachmentRepository;
    private final FileStorage storage;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration retention;
    private final Duration orphanGrace;

    @Autowired
    public ChatAttachmentCleanup(ChatMessageAttachmentRepository attachmentRepository, FileStorage storage,
            TransactionTemplate transaction, @Value("${app.attachment.retention-days:30}") long retentionDays,
            @Value("${app.attachment.orphan-grace-hours:24}") long orphanGraceHours) {
        this(attachmentRepository, storage, transaction, Clock.systemUTC(), Duration.ofDays(retentionDays),
                Duration.ofHours(orphanGraceHours));
    }

    ChatAttachmentCleanup(ChatMessageAttachmentRepository attachmentRepository, FileStorage storage,
            TransactionTemplate transaction, Clock clock, Duration retention, Duration orphanGrace) {
        this.attachmentRepository = attachmentRepository;
        this.storage = storage;
        this.transaction = transaction;
        this.clock = clock;
        this.retention = retention;
        this.orphanGrace = orphanGrace;
    }

    // 청소 결과: 정리 표시한 첨부 수, 보관 기간이 끝나 지운 파일 수, 지운 고아 파일 수.
    record Result(int purgedAttachments, int expiredFiles, int orphanFiles) {
    }

    @Scheduled(cron = "${app.attachment.cleanup-cron:0 30 4 * * *}", zone = "${app.monitoring.zone:Asia/Seoul}")
    public void runDaily() {
        try {
            Result result = cleanup();
            log.info("Attachment cleanup finished. purgedAttachments={}, expiredFiles={}, orphanFiles={}",
                    result.purgedAttachments(), result.expiredFiles(), result.orphanFiles());
        } catch (RuntimeException exception) {
            log.error("Attachment cleanup failed.", exception);
        }
    }

    Result cleanup() {
        Instant now = clock.instant();
        int[] expired = purgeExpired(now);
        int orphans = deleteOrphans(now);
        return new Result(expired[0], expired[1], orphans);
    }

    // 1) 보관 기간이 끝난 첨부. 처리한 id 뒤부터 다음 묶음을 읽어, 한 건이 실패해도 멈추거나 같은 건을 반복하지 않음.
    private int[] purgeExpired(Instant now) {
        Instant cutoff = now.minus(retention);
        int purged = 0;
        int files = 0;
        long afterId = 0;
        while (true) {
            long from = afterId;
            List<ChatMessageAttachment> page = transaction.execute(
                    status -> attachmentRepository.findExpired(cutoff, from, PageRequest.of(0, BATCH)));
            if (page == null || page.isEmpty()) break;
            for (ChatMessageAttachment item : page) {
                afterId = item.getId();
                try {
                    Boolean deleted = transaction.execute(status -> purgeOne(item.getId(), cutoff, now));
                    purged++;
                    if (Boolean.TRUE.equals(deleted)) files++;
                } catch (RuntimeException exception) {
                    log.warn("Failed to purge attachment. attachmentId={}", item.getId(), exception);
                }
            }
        }
        return new int[] { purged, files };
    }

    // 첨부 한 건 정리. 같은 파일을 아직 쓰는 첨부가 없을 때만 디스크 파일(원본·썸네일)을 지움. 지웠으면 true.
    private boolean purgeOne(Long attachmentId, Instant cutoff, Instant now) {
        ChatMessageAttachment attachment = attachmentRepository.findById(attachmentId).orElse(null);
        if (attachment == null || attachment.getPurgedAt() != null) return false;
        boolean inUse = attachmentRepository.existsInUse(attachment.getStorageKey(), cutoff);
        if (!inUse) {
            delete(attachment.getStorageKey());
            if (attachment.getThumbnailKey() != null) delete(attachment.getThumbnailKey());
        }
        attachment.markPurged(now);
        return !inUse;
    }

    // 2) 고아 파일. 디스크 목록을 먼저 다 모은 뒤(목록을 읽는 중에 지우지 않음) 묶음마다 DB와 비교.
    private int deleteOrphans(Instant now) {
        List<String> keys = new ArrayList<>();
        try {
            storage.forEachKeyBefore(now.minus(orphanGrace), keys::add);
        } catch (IOException exception) {
            log.warn("Failed to list attachment files.", exception);
            return 0;
        }
        int deleted = 0;
        for (int start = 0; start < keys.size(); start += BATCH) {
            List<String> chunk = keys.subList(start, Math.min(start + BATCH, keys.size()));
            Set<String> referenced = new HashSet<>(attachmentRepository.findReferencedStorageKeys(chunk));
            referenced.addAll(attachmentRepository.findReferencedThumbnailKeys(chunk));
            for (String key : chunk) {
                if (referenced.contains(key)) continue;
                delete(key);
                deleted++;
            }
        }
        return deleted;
    }

    private void delete(String key) {
        try {
            storage.delete(key);
        } catch (IOException exception) {
            throw new IllegalStateException("첨부 파일을 지우지 못했습니다. key=" + key, exception);
        }
    }
}
