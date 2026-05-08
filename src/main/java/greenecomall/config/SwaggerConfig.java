package greenecomall.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Green Eco Mall API")
                        .version("1.0.0")
                        .contact(new Contact().name("Green Eco Mall").email("support@greenecomall.kg"))
                        .description("""
                                ## MLM-платформа Green Eco Mall

                                ---

                                ## 🔐 Авторизация
                                Нажмите кнопку **Authorize** вверху и вставьте: `Bearer <ваш_access_token>`

                                ---

                                ## 📋 Порядок вызовов — полный флоу

                                ### Шаг 1 — Регистрация нового участника
                                ```
                                POST /api/auth/send-otp       ← отправить OTP на телефон
                                POST /api/auth/verify-otp     ← подтвердить OTP-код
                                POST /api/auth/register       ← создать аккаунт (нужен referralCode)
                                ```
                                После регистрации аккаунт имеет статус **PENDING** (не активирован).

                                ### Шаг 2 — Оплата вступительного взноса (10 000 сом)
                                ```
                                POST /api/payment/create-qr   ← получить QR-код Finik (действует 30 мин)
                                GET  /api/payment/status/{id} ← проверить статус оплаты
                                POST /api/payment/webhook     ← (вызывает Finik автоматически после оплаты)
                                ```
                                После успешной оплаты: аккаунт → **ACTIVE**, участник размещается в дереве.

                                ### Шаг 3 — Вход
                                ```
                                POST /api/auth/login           ← получить accessToken + refreshToken
                                POST /api/auth/refresh         ← обновить accessToken когда истечёт (15 мин)
                                ```

                                ### Шаг 4 — Работа в системе (требует Bearer токен)
                                ```
                                GET  /api/dashboard               ← главная страница ⭐ (всё в одном)
                                GET  /api/user/me                 ← мой профиль и баланс
                                GET  /api/user/referral-qr        ← QR-код реферальной ссылки
                                GET  /api/tree/my?level=1&stage=1 ← моё дерево (6 позиций)
                                GET  /api/tree/overview           ← прогресс всех 4 этапов ⭐
                                GET  /api/tree/branches           ← статистика левой и правой веток
                                GET  /api/tree/activity           ← лента событий команды
                                GET  /api/levels/overview         ← матрица 4 уровня × 4 этапа ⭐
                                GET  /api/bonuses/summary         ← сводка бонусов
                                GET  /api/bonuses                 ← история всех бонусов
                                GET  /api/user/notifications      ← уведомления о событиях
                                ```

                                ### Шаг 5 — Вывод средств
                                ```
                                POST /api/withdrawals          ← подать заявку (мин. 1 000 сом)
                                GET  /api/withdrawals          ← история заявок
                                ```

                                ### Шаг 6 — Администратор (роль ADMIN)
                                ```
                                GET   /api/admin/stats                      ← статистика платформы
                                GET   /api/admin/stats/distribution         ← распределение по уровням
                                GET   /api/admin/users                      ← список участников
                                POST  /api/admin/users                      ← добавить участника вручную
                                PATCH /api/admin/users/{id}/block           ← заблокировать
                                PATCH /api/admin/users/{id}/activate        ← активировать без оплаты
                                GET   /api/admin/withdrawals?status=PENDING ← заявки на вывод
                                PATCH /api/admin/withdrawals/{id}/approve   ← одобрить вывод
                                PATCH /api/admin/withdrawals/{id}/reject    ← отклонить вывод
                                ```

                                ---

                                ## 🌳 Как работает дерево

                                Каждый участник имеет дерево из **6 позиций** (2 яруса):
                                ```
                                        [ВЫ]
                                       /    \\
                                    [2]      [3]        ← Ярус 1 (прямые приглашённые)
                                   /  \\    /  \\
                                 [4] [5] [6] [7]       ← Ярус 2 (их команды)
                                ```
                                - Новый участник встаёт в дерево **того, кто выдал ему реферальную ссылку**
                                - Система автоматически находит свободное место (BFS, слева направо)
                                - Когда заполнены все 6 → автоматический переход на **Этап 2**

                                ## 📊 Этапы (для каждого из 4 уровней)
                                | Этап | Условие перехода | Что происходит |
                                |------|-----------------|----------------|
                                | 1 | 6 позиций заполнены | → Этап 2, бонусы подтверждаются |
                                | 2 | 2 фиксированных партнёра | → Этап 3, ставится ускоритель |
                                | 3 | вся команда на Этапе 3 | → Этап 4, бонус 25 000 сом |
                                | 4 | оба партнёра на Этапе 4 | → следующий уровень |
                                """))
                .servers(List.of(
                        new Server().url("https://greenecomall-dev.up.railway.app").description("Dev")))
                .tags(List.of(
                        new Tag().name("1. Auth").description("Регистрация и вход — начни здесь"),
                        new Tag().name("2. Payment").description("Оплата вступительного взноса"),
                        new Tag().name("3. Tree").description("Дерево участников и прогресс по этапам"),
                        new Tag().name("Dashboard").description("Главная страница — агрегированные данные"),
                        new Tag().name("Levels").description("Матрица 4 уровня × 4 этапа с бонусами"),
                        new Tag().name("Bonuses & Withdrawals").description("Бонусы и вывод средств"),
                        new Tag().name("User").description("Профиль и уведомления"),
                        new Tag().name("Admin").description("Панель администратора (только ADMIN)")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Вставьте accessToken из ответа /api/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
