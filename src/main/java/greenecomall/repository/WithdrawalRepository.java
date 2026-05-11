package greenecomall.repository;

import greenecomall.entity.User;
import greenecomall.entity.Withdrawal;
import greenecomall.enums.WithdrawalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, UUID> {

    Page<Withdrawal> findByUser(User user, Pageable pageable);

    Page<Withdrawal> findByStatus(WithdrawalStatus status, Pageable pageable);

    Page<Withdrawal> findByStatusAndCreatedAtAfter(WithdrawalStatus status, LocalDateTime after, Pageable pageable);

    Page<Withdrawal> findByCreatedAtAfter(LocalDateTime after, Pageable pageable);

    long countByStatus(WithdrawalStatus status);

    @Query("SELECT COALESCE(SUM(w.amount), 0) FROM Withdrawal w WHERE w.status = :status")
    BigDecimal sumByStatus(@Param("status") WithdrawalStatus status);

    @Query("SELECT COUNT(w) FROM Withdrawal w WHERE w.status = :status AND w.reviewedAt >= :from")
    long countByStatusAndReviewedAtAfter(@Param("status") WithdrawalStatus status, @Param("from") LocalDateTime from);

    @Query("SELECT COALESCE(SUM(w.amount), 0) FROM Withdrawal w WHERE w.status = 'APPROVED'")
    BigDecimal sumTotalApproved();
}
