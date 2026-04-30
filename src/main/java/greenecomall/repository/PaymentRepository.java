package greenecomall.repository;

import greenecomall.entity.Payment;
import greenecomall.entity.User;
import greenecomall.enums.PaymentStatus;
import greenecomall.enums.PaymentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findFirstByUserAndTypeAndStatusOrderByCreatedAtDesc(
            User user, PaymentType type, PaymentStatus status);

    Optional<Payment> findByFinikTransactionId(String transactionId);

    Optional<Payment> findFirstByUserAndTypeOrderByCreatedAtDesc(User user, PaymentType type);
}
