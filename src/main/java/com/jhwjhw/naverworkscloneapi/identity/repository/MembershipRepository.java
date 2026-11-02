package com.jhwjhw.naverworkscloneapi.identity.repository;

import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import com.jhwjhw.naverworkscloneapi.identity.domain.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.domain.Specification;

// 사용자 계정이 속한 회사·부서·역할 정보를 조회·정리할 때 사용하는 JPA Repository.
public interface MembershipRepository extends JpaRepository<Membership, Long>, JpaSpecificationExecutor<Membership> {

    // 초기 정책: 한 계정은 한 회사에만 소속되므로 단일 소속만 조회.
    Optional<Membership> findByUserAccountId(Long userAccountId);

    // WebSocket 이벤트에서는 요청 트랜잭션이 없으므로 표시 이름을 함께 조회.
    @EntityGraph(attributePaths = {"userAccount"})
    Optional<Membership> findWithUserAccountById(Long membershipId);

    // 주소록 한 행을 만드는 데 필요한 다대일 관계를 한 번에 읽어 N+1 조회를 방지.
    @Override
    @EntityGraph(attributePaths = {"company", "userAccount", "department", "jobGrade"})
    Page<Membership> findAll(Specification<Membership> specification, Pageable pageable);

    // 부서 존재 여부 조회
    boolean existsByDepartmentIdAndUserAccountEnabledTrue(Long departmentId);

    // 회사·부서의 사용 중인 구성원(게시판 필독 읽음 확인 대상). 이름순.
    @Query("select membership from Membership membership join fetch membership.userAccount account "
            + "left join fetch membership.department where membership.company.id = :companyId "
            + "and account.enabled = true order by account.displayName")
    List<Membership> findEnabledByCompanyId(@Param("companyId") Long companyId);

    @Query("select membership from Membership membership join fetch membership.userAccount account "
            + "left join fetch membership.department where membership.department.id = :departmentId "
            + "and account.enabled = true order by account.displayName")
    List<Membership> findEnabledByDepartmentId(@Param("departmentId") Long departmentId);

    // 사용 중인 구성원 전체(공통 게시판 필독 읽음 확인·공지 알림 대상). 이름순.
    @Query("select membership from Membership membership join fetch membership.userAccount account "
            + "left join fetch membership.department where account.enabled = true order by account.displayName")
    List<Membership> findAllEnabled();

    // 사용 중인 관리자 전체(문의하기 새 글 알림 대상). 관리자는 모든 회사 문의를 봄.
    @Query("select membership from Membership membership join fetch membership.userAccount account "
            + "where membership.role = :role and account.enabled = true")
    List<Membership> findEnabledByRole(@Param("role") Role role);

    // 회사별 사용 중인 구성원 수를 쿼리 한 번으로 집계(조직도 표시용).
    @Query("select membership.company.id as companyId, count(membership) as memberCount from Membership membership "
            + "where membership.userAccount.enabled = true group by membership.company.id")
    List<CompanyMemberCount> countEnabledByCompany();

    interface CompanyMemberCount {
        Long getCompanyId();

        long getMemberCount();
    }
}
