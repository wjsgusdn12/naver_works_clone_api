package com.jhwjhw.naverworkscloneapi.board.repository;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardPost;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardPostRepository extends JpaRepository<BoardPost, Long> {
    // 글 목록(최신순, cursor보다 작은 id). 볼 수 있는 게시판(boardIds) 안에서만 찾고,
    // 관리자가 아니면 남의 문의하기 글(hiddenType)은 뺌. 필터는 false면 적용하지 않음.
    @Query("select p from BoardPost p join fetch p.board b join fetch p.author a join fetch a.userAccount "
            + "where b.id in :boardIds and p.deletedAt is null and p.id < :cursor "
            + "and (:admin = true or b.type <> :hiddenType or a.id = :me) "
            + "and (:mustRead = false or p.mustRead = true) "
            + "and (:important = false or p.important = true) "
            + "and (:excludeImportant = false or p.important = false) "
            + "and (:mine = false or a.id = :me) "
            + "order by p.id desc")
    List<BoardPost> findPage(@Param("boardIds") Collection<Long> boardIds, @Param("me") Long me,
            @Param("admin") boolean admin, @Param("hiddenType") BoardType hiddenType,
            @Param("mustRead") boolean mustRead, @Param("important") boolean important,
            @Param("excludeImportant") boolean excludeImportant, @Param("mine") boolean mine,
            @Param("cursor") long cursor, Pageable pageable);

    // 휴지통: since 이후에 지운 글. 관리자가 아니면 내가 지운 글만.
    @Query("select p from BoardPost p join fetch p.board b join fetch p.author a join fetch a.userAccount "
            + "where b.id in :boardIds and p.deletedAt is not null and p.deletedAt > :since and p.id < :cursor "
            + "and (:admin = true or p.deletedBy.id = :me) "
            + "order by p.id desc")
    List<BoardPost> findTrash(@Param("boardIds") Collection<Long> boardIds, @Param("me") Long me,
            @Param("admin") boolean admin, @Param("since") Instant since, @Param("cursor") long cursor,
            Pageable pageable);

    @Query("select p from BoardPost p join fetch p.board b join fetch b.company left join fetch b.department "
            + "join fetch p.author a join fetch a.userAccount left join fetch a.department where p.id = :id")
    Optional<BoardPost> findDetailById(@Param("id") Long id);

    // 조회수 1 올리기. 수정 충돌 검사(version)와 겹치지 않게 엔티티를 거치지 않고 바로 바꿈.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BoardPost p set p.viewCount = p.viewCount + 1 where p.id = :id")
    void increaseViewCount(@Param("id") Long id);

    // 댓글 수 바꾸기(delta: +1·-1). 위와 같은 이유로 version을 올리지 않음(댓글이 달려도 글 수정이 충돌로 막히지 않게).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BoardPost p set p.commentCount = p.commentCount + :delta "
            + "where p.id = :id and p.commentCount + :delta >= 0")
    void changeCommentCount(@Param("id") Long id, @Param("delta") int delta);

    // 문의하기 글에 관리자가 처음 답변한 시각. 이미 있으면 그대로.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BoardPost p set p.answeredAt = :at where p.id = :id and p.answeredAt is null")
    void markAnswered(@Param("id") Long id, @Param("at") Instant at);
}
