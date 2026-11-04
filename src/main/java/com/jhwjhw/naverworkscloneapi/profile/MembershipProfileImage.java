package com.jhwjhw.naverworkscloneapi.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

// 구성원 한 명의 프로필 사진 내용(256px JPEG). 구성원당 한 행, 바꾸면 덮어씀.
@Entity
@Table(name = "membership_profile_image")
@Getter
public class MembershipProfileImage {
    @Id
    @Column(name = "membership_id")
    private Long membershipId;

    // PostgreSQL에서는 BYTEA(길이 무관). length는 테스트용 H2가 기본 255바이트로 만들지 않게 하는 값.
    @Column(nullable = false, length = 1_048_576)
    private byte[] image;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MembershipProfileImage() {
    }

    public MembershipProfileImage(Long membershipId, byte[] image, Instant updatedAt) {
        this.membershipId = membershipId;
        this.image = image;
        this.updatedAt = updatedAt;
    }

    public void replace(byte[] image, Instant updatedAt) {
        this.image = image;
        this.updatedAt = updatedAt;
    }
}
