package com.jhwjhw.naverworkscloneapi.board.repository;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardComment;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardCommentRepository extends JpaRepository<BoardComment, Long> {
    // 글의 댓글(지운 것 제외), 오래된 순
    @Query("select c from BoardComment c join fetch c.author a join fetch a.userAccount "
            + "where c.post.id = :postId and c.deletedAt is null order by c.id")
    List<BoardComment> findVisibleByPostId(@Param("postId") Long postId);

    // 댓글 검색 결과: 글마다 내용이 검색어와 맞는 댓글. [글 ID, 댓글 내용], 오래된 순
    @Query("select c.post.id, c.content from BoardComment c where c.post.id in :postIds and c.deletedAt is null "
            + "and lower(c.content) like :keyword escape '\\' order by c.id")
    List<Object[]> findMatchingContents(@Param("postIds") Collection<Long> postIds,
            @Param("keyword") String keyword);
}
