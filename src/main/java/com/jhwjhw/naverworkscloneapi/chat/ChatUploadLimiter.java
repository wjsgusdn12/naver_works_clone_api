package com.jhwjhw.naverworkscloneapi.chat;

import com.jhwjhw.naverworkscloneapi.chat.repository.ChatMessageAttachmentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 한 사람이 짧은 시간에 너무 많이 올려 서버 디스크를 채우지 못하게 막음.
// 화면의 "한 번에 10개" 제한은 브라우저에만 있어 서버 주소를 직접 부르면 우회되므로 서버에서 다시 검사.
// DB의 첨부 기록으로 세기 때문에 서버가 여러 대여도 같은 기준이 적용됨.
// (동시에 여러 개를 올리면 검사와 저장 사이에 몇 개가 더 들어갈 수 있음. 디스크 보호가 목적이라 허용)
@Component
@Slf4j
public class ChatUploadLimiter {
    private final ChatMessageAttachmentRepository attachmentRepository;
    private final Clock clock;
    // 1분에 올릴 수 있는 파일 수
    private final long perMinute;
    // 24시간 동안 올릴 수 있는 총용량(바이트)
    private final long dailyBytes;

    @Autowired
    public ChatUploadLimiter(ChatMessageAttachmentRepository attachmentRepository,
            @Value("${app.attachment.uploads-per-minute:30}") long perMinute,
            @Value("${app.attachment.daily-upload-bytes:1073741824}") long dailyBytes) {
        this(attachmentRepository, Clock.systemUTC(), perMinute, dailyBytes);
    }

    ChatUploadLimiter(ChatMessageAttachmentRepository attachmentRepository, Clock clock, long perMinute,
            long dailyBytes) {
        this.attachmentRepository = attachmentRepository;
        this.clock = clock;
        this.perMinute = perMinute;
        this.dailyBytes = dailyBytes;
    }

    // 이번 파일(size바이트)을 올려도 되는지 확인. 넘으면 429(요청이 너무 많음)로 안내.
    public void check(Long membershipId, long size) {
        Instant now = clock.instant();
        if (attachmentRepository.countUploadsSince(membershipId, now.minus(Duration.ofMinutes(1))) >= perMinute) {
            log.warn("Attachment rate limited. membershipId={}, perMinute={}", membershipId, perMinute);
            throw new ChatException("CHAT_ATTACHMENT_RATE_LIMITED",
                    "파일을 너무 빠르게 보내고 있습니다. 1분 뒤에 다시 시도해 주세요.");
        }
        long used = attachmentRepository.sumUploadBytesSince(membershipId, now.minus(Duration.ofDays(1)));
        if (used + size > dailyBytes) {
            log.warn("Attachment daily quota exceeded. membershipId={}, usedBytes={}, dailyBytes={}", membershipId,
                    used, dailyBytes);
            throw new ChatException("CHAT_ATTACHMENT_DAILY_LIMIT",
                    "하루에 보낼 수 있는 파일 용량(" + dailyBytes / 1024 / 1024 + "MB)을 넘었습니다. 내일 다시 시도해 주세요.");
        }
    }
}
