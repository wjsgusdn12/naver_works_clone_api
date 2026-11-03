package com.jhwjhw.naverworkscloneapi.monitoring;

import jakarta.persistence.*;
import lombok.Getter;

// api_pool_bucket 테이블 모양(DB 연결 풀 한 칸). 읽고 쓰기는 "큰 값·더하기·덮어쓰기" SQL이 필요해
// ApiPoolBucketRepository가 JdbcTemplate으로 처리하고, 이 클래스는 테이블 구조 확인(validate)용.
@Entity
@Table(name = "api_pool_bucket",
        uniqueConstraints = @UniqueConstraint(name = "uk_api_pool_bucket", columnNames = { "unit", "slot" }),
        indexes = @Index(name = "idx_api_pool_bucket_unit_bucket", columnList = "unit, bucket"))
@Getter
public class ApiPoolBucket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 칸 길이(MINUTE·FIVE_MINUTES·HOUR)
    @Column(nullable = false, length = 20)
    private String unit;

    // 돌려 쓰는 자리 번호(칸 번호 % 자리 수)
    @Column(nullable = false)
    private int slot;

    // 지금 이 자리에 담긴 칸 번호(1970년부터 그 단위로 센 번호)
    @Column(nullable = false)
    private long bucket;

    @Column(name = "max_active", nullable = false)
    private int maxActive;
    @Column(name = "sum_active", nullable = false)
    private long sumActive;
    @Column(nullable = false)
    private long samples;
    @Column(name = "max_waiting", nullable = false)
    private int maxWaiting;
    @Column(name = "max_size", nullable = false)
    private int maxSize;

    protected ApiPoolBucket() {
    }
}
