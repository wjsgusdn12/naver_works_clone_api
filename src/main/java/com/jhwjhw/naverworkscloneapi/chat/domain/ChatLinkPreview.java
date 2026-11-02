package com.jhwjhw.naverworkscloneapi.chat.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

// 링크 미리보기(웹사이트 제목·대표 이미지). 같은 주소는 한 행만 두고 모든 방이 같이 씀.
@Entity
@Table(name = "chat_link_preview")
@Getter
public class ChatLinkPreview {
    public enum Status {
        PENDING, DONE, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 주소의 SHA-256(16진수 64자). 긴 주소도 고정 길이로 찾기 위해.
    @Column(nullable = false, length = 64, unique = true)
    private String urlHash;

    @Column(nullable = false, length = 2048)
    private String url;

    // 웹사이트 제목(og:title 또는 <title>). 없으면 null.
    @Column(length = 300)
    private String title;

    // 서버가 받아 작게 줄여 저장한 대표 이미지 위치 키와 형식. 없으면 null.
    @Column(length = 100)
    private String imageKey;

    @Column(length = 20)
    private String imageType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    private Instant fetchedAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected ChatLinkPreview() {
    }

    public ChatLinkPreview(String urlHash, String url) {
        this.urlHash = urlHash;
        this.url = url;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
    }

    // 가져오기 성공. 제목·이미지는 없을 수 있음.
    public void complete(String title, String imageKey, String imageType, Instant at) {
        this.title = title;
        this.imageKey = imageKey;
        this.imageType = imageType;
        this.status = Status.DONE;
        this.fetchedAt = at;
    }

    // 가져오기 실패(접속 불가·차단된 주소·HTML 아님 등)
    public void fail(Instant at) {
        this.status = Status.FAILED;
        this.fetchedAt = at;
    }
}
