package com.jhwjhw.naverworkscloneapi.board.repository;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardPostImportant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardPostImportantRepository extends JpaRepository<BoardPostImportant, Long> {
    Optional<BoardPostImportant> findByPostIdAndMembershipId(Long postId, Long membershipId);

    boolean existsByPostIdAndMembershipId(Long postId, Long membershipId);

    // 목록 한 페이지에서 내가 중요 표시한 글(쿼리 한 번)
    @Query("select i.post.id from BoardPostImportant i where i.membership.id = :me and i.post.id in :postIds")
    List<Long> findMarkedPostIds(@Param("me") Long me, @Param("postIds") Collection<Long> postIds);
}
