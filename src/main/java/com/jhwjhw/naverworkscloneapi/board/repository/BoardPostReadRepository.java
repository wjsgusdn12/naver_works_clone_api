package com.jhwjhw.naverworkscloneapi.board.repository;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardPostRead;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardPostReadRepository extends JpaRepository<BoardPostRead, Long> {
    boolean existsByPostIdAndMembershipId(Long postId, Long membershipId);

    // 목록에서 내가 이미 읽은 글
    @Query("select r.post.id from BoardPostRead r where r.membership.id = :me and r.post.id in :postIds")
    List<Long> findReadPostIds(@Param("me") Long me, @Param("postIds") Collection<Long> postIds);

    // 필독 읽음 확인: 이 글을 읽은 사람
    @Query("select r.membership.id from BoardPostRead r where r.post.id = :postId")
    List<Long> findReaderIds(@Param("postId") Long postId);
}
