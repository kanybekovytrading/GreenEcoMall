package greenecomall.repository;

import greenecomall.entity.News;
import greenecomall.entity.NewsMedia;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NewsMediaRepository extends JpaRepository<NewsMedia, UUID> {
    List<NewsMedia> findByNewsOrderBySortOrderAsc(News news);
    void deleteByNews(News news);
}
