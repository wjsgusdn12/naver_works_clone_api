package com.jhwjhw.naverworkscloneapi.board.repository;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardFavorite;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardFavoriteRepository extends JpaRepository<BoardFavorite, Long> {
    Optional<BoardFavorite> findByMembershipIdAndBoardId(Long membershipId, Long boardId);

    @Query("select f.board.id from BoardFavorite f where f.membership.id = :me order by f.id")
    List<Long> findBoardIds(@Param("me") Long me);
}
