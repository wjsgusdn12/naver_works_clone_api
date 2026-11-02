package com.jhwjhw.naverworkscloneapi.board.repository;

import com.jhwjhw.naverworkscloneapi.board.domain.Board;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardRepository extends JpaRepository<Board, Long> {
    // 모든 회사의 게시판(관리자용). 사용 중지한 부서의 게시판은 뺌.
    @Query("select b from Board b join fetch b.company left join fetch b.department d "
            + "where d is null or d.enabled = true")
    List<Board> findAllActive();

    // 한 회사의 게시판. 사용 중지한 부서의 게시판은 뺌.
    @Query("select b from Board b join fetch b.company left join fetch b.department d "
            + "where b.company.id = :companyId and (d is null or d.enabled = true)")
    List<Board> findActiveByCompanyId(@Param("companyId") Long companyId);

    // 일반 사용자가 볼 수 있는 후보를 한 번에: 내 회사 게시판 + 공통 게시판 전체. 사용 중지한 부서 게시판은 뺌.
    @Query("select b from Board b join fetch b.company left join fetch b.department d "
            + "where (b.company.id = :companyId or b.type in :types) and (d is null or d.enabled = true) order by b.id")
    List<Board> findActiveForCompanyOrTypes(@Param("companyId") Long companyId,
            @Param("types") Collection<BoardType> types);

    // 공통 게시판(공지·자유·문의). 예전에 회사마다 만든 것까지 전부, 먼저 만든 순.
    @Query("select b from Board b join fetch b.company where b.type in :types order by b.id")
    List<Board> findByTypes(@Param("types") Collection<BoardType> types);

    @Query("select b from Board b join fetch b.company left join fetch b.department where b.id = :id")
    Optional<Board> findWithScopeById(@Param("id") Long id);
}
