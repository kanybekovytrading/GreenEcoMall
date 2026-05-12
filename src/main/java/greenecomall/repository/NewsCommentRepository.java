package greenecomall.repository;

import greenecomall.entity.News;
import greenecomall.entity.NewsComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NewsCommentRepository extends JpaRepository<NewsComment, UUID> {
    Page<NewsComment> findByNewsOrderByCreatedAtAsc(News news, Pageable pageable);
    long countByNews(News news);
}
