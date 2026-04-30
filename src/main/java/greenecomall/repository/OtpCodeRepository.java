package greenecomall.repository;

import greenecomall.entity.OtpCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {

    Optional<OtpCode> findFirstByPhoneAndIsUsedFalseOrderByCreatedAtDesc(String phone);

    long countByPhoneAndCreatedAtAfter(String phone, LocalDateTime after);

    boolean existsByPhoneAndIsUsedTrueAndCreatedAtAfter(String phone, LocalDateTime after);
}
