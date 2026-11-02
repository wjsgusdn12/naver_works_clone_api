package com.jhwjhw.naverworkscloneapi.board.repository;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardAttachment;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardAttachmentRepository extends JpaRepository<BoardAttachment, Long> {
    List<BoardAttachment> findByPostIdOrderById(Long postId);

    // 목록의 클립 표시: 본문 속 사진이 아닌 첨부가 있는 글
    @Query("select distinct a.post.id from BoardAttachment a where a.post.id in :postIds and a.inlineImage = false")
    List<Long> findPostIdsWithFiles(@Param("postIds") Collection<Long> postIds);

    // 파일 검색 결과: 글마다 이름이 검색어와 맞는 첨부 파일. [글 ID, 파일 이름], 올린 순
    @Query("select a.post.id, a.originalName from BoardAttachment a where a.post.id in :postIds "
            + "and a.inlineImage = false and lower(a.originalName) like :keyword escape '\\' order by a.id")
    List<Object[]> findMatchingNames(@Param("postIds") Collection<Long> postIds, @Param("keyword") String keyword);

    // 너무 자주 올리는 것 막기: 최근 since 이후 이 사람이 올린 수
    long countByUploaderIdAndCreatedAtAfter(Long uploaderId, Instant since);

    // 청소 작업: 글에 붙은 첨부가 쓰는 디스크 파일(원본·썸네일). 글에 안 붙은 첨부의 파일은 고아로 정리됨.
    @Query("select a.storageKey from BoardAttachment a where a.post is not null and a.storageKey in :keys")
    List<String> findReferencedStorageKeys(@Param("keys") Collection<String> keys);

    @Query("select a.thumbnailKey from BoardAttachment a where a.post is not null and a.thumbnailKey in :keys")
    List<String> findReferencedThumbnailKeys(@Param("keys") Collection<String> keys);
}
