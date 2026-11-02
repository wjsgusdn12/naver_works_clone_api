package com.jhwjhw.naverworkscloneapi.board.repository;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardPostReaction;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardPostReactionRepository extends JpaRepository<BoardPostReaction, Long> {
    Optional<BoardPostReaction> findByPostIdAndMembershipId(Long postId, Long membershipId);

    // 글 상세: 이 글의 리액션 전체(누른 순서). 누른 사람 이름까지 한 쿼리로.
    @Query("select r from BoardPostReaction r join fetch r.membership m join fetch m.userAccount "
            + "where r.post.id = :postId order by r.createdAt, r.id")
    List<BoardPostReaction> findWithMemberByPostId(@Param("postId") Long postId);

    // 글 목록 한 페이지의 리액션 수를 쿼리 한 번으로. [글 ID, 개수]
    @Query("select r.post.id, count(r) from BoardPostReaction r where r.post.id in :postIds group by r.post.id")
    List<Object[]> countByPostIds(@Param("postIds") Collection<Long> postIds);
}
