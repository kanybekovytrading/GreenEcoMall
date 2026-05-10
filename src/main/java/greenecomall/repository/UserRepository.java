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

    // Level 0 (Fast Start) users waiting for their 1 person
    @Query("SELECT u FROM User u WHERE u.registrationPlan = 'FAST_START' AND u.currentLevel = 0 AND u.currentStage = 1 AND u.accountStatus = 'ACTIVE' ORDER BY u.activatedAt ASC")
    List<User> findWaitingLevel0Users();

    // Stage 2 users who still have at least 1 empty fixed partner slot
    @Query("SELECT u FROM User u WHERE u.currentLevel = 1 AND u.currentStage = 2 AND u.accountStatus = 'ACTIVE' AND (u.fixedPartnerLeft IS NULL OR u.fixedPartnerRight IS NULL) ORDER BY u.activatedAt ASC")
    List<User> findStage2UsersWithEmptySlots();

    // Next sequential number for Fast Start queue
    @Query("SELECT COALESCE(MAX(u.fastStartNumber), 0) + 1 FROM User u WHERE u.registrationPlan = 'FAST_START'")
    int getNextFastStartNumber();
}
