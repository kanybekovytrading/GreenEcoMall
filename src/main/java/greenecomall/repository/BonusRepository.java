package greenecomall.repository;

import greenecomall.entity.Bonus;
import greenecomall.entity.User;
import greenecomall.enums.BonusStatus;
import greenecomall.enums.BonusType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface BonusRepository extends JpaRepository<Bonus, UUID> {

    Page<Bonus> findByUser(User user, Pageable pageable);

    Page<Bonus> findByUserAndType(User user, BonusType type, Pageable pageable);

    Page<Bonus> findByUserAndStatus(User user, BonusStatus status, Pageable pageable);

    Page<Bonus> findByUserAndTypeAndStatus(User user, BonusType type, BonusStatus status, Pageable pageable);

    List<Bonus> findByFromUserAndStatus(User fromUser, BonusStatus status);

    @Query("SELECT COALESCE(SUM(b.amount), 0) FROM Bonus b WHERE b.user = :user AND b.status = :status")
    BigDecimal sumByUserAndStatus(@Param("user") User user, @Param("status") BonusStatus status);

    @Query("SELECT COALESCE(SUM(b.amount), 0) FROM Bonus b WHERE b.user = :user")
    BigDecimal sumAllByUser(@Param("user") User user);

    @Query("SELECT COALESCE(SUM(b.amount), 0) FROM Bonus b WHERE b.status = 'CONFIRMED'")
    BigDecimal sumTotalConfirmed();

}
