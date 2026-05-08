package greenecomall.repository;

import greenecomall.entity.User;
import greenecomall.enums.AccountStatus;
import greenecomall.enums.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByPhone(String phone);

    Optional<User> findByReferralCode(String referralCode);

    boolean existsByPhone(String phone);

    boolean existsByPassportNumber(String passportNumber);

    long countByAccountStatus(AccountStatus status);

    long countByRole(Role role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT u.currentLevel, COUNT(u) FROM User u WHERE u.accountStatus = 'ACTIVE' GROUP BY u.currentLevel ORDER BY u.currentLevel")
    List<Object[]> countByCurrentLevel();

    boolean existsByFixedPartnerLeft(User user);
    boolean existsByFixedPartnerRight(User user);
}
