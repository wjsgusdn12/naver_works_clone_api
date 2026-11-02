package com.jhwjhw.naverworkscloneapi.board.repository;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardComment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardCommentRepository extends JpaRepository<BoardComment, Long> {
    // 글의 댓글(지운 것 제외), 오래된 순
    @Query("select c from BoardComment c join fetch c.author a join fetch a.userAccount "
            + "where c.post.id = :postId and c.deletedAt is null order by c.id")
    List<BoardComment> findVisibleByPostId(@Param("postId") Long postId);
}
