package greenecomall.repository;

import greenecomall.entity.User;
import greenecomall.entity.Withdrawal;
import greenecomall.enums.WithdrawalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, UUID> {

    Page<Withdrawal> findByUser(User user, Pageable pageable);

    Page<Withdrawal> findByStatus(WithdrawalStatus status, Pageable pageable);

    long countByStatus(WithdrawalStatus status);
}
