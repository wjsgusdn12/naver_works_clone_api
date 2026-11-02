package com.jhwjhw.naverworkscloneapi.chat.repository;

import com.jhwjhw.naverworkscloneapi.chat.domain.ChatLinkPreview;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatLinkPreviewRepository extends JpaRepository<ChatLinkPreview, Long> {
    Optional<ChatLinkPreview> findByUrlHash(String urlHash);

    List<ChatLinkPreview> findByUrlHashIn(Collection<String> urlHashes);

    // 청소: 디스크 파일 키 중 링크 대표 이미지가 쓰는 키만 골라냄.
    @Query("select p.imageKey from ChatLinkPreview p where p.imageKey in :keys")
    List<String> findReferencedImageKeys(@Param("keys") Collection<String> keys);
}
