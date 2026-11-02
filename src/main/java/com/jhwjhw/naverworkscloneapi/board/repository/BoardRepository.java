package com.jhwjhw.naverworkscloneapi.board.repository;

import com.jhwjhw.naverworkscloneapi.board.domain.Board;
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

    @Query("select b from Board b join fetch b.company left join fetch b.department where b.id = :id")
    Optional<Board> findWithScopeById(@Param("id") Long id);
}
