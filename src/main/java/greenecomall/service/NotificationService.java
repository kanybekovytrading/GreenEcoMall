package greenecomall.service;

import greenecomall.entity.Notification;
import greenecomall.entity.User;
import greenecomall.enums.NotificationType;
import greenecomall.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    @Transactional
    public void send(User user, NotificationType type, String title, String body) {
        notificationRepository.save(Notification.builder()
                .user(user)
                .type(type)
                .title(title)
                .body(body)
                .build());
    }

    public java.util.List<Notification> getLatest(User user) {
        return notificationRepository.findByUserOrderByCreatedAtDesc(user, PageRequest.of(0, 50));
    }

    @Transactional
    public void markRead(User user, java.util.UUID notificationId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            if (n.getUser().getId().equals(user.getId())) {
                n.setIsRead(true);
            }
        });
    }

    @Transactional
    public void markAllRead(User user) {
        notificationRepository.markAllAsRead(user);
    }
}
