package greenecomall.repository;

import greenecomall.entity.Product;
import greenecomall.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    List<Product> findByUser(User user);
}
