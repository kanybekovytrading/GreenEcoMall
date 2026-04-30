package greenecomall.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_REFERRAL_CODE("INVALID_REFERRAL_CODE", "Реферальный код не найден", HttpStatus.BAD_REQUEST),
    PHONE_ALREADY_EXISTS("PHONE_ALREADY_EXISTS", "Телефон уже зарегистрирован", HttpStatus.CONFLICT),
    PASSPORT_ALREADY_EXISTS("PASSPORT_ALREADY_EXISTS", "Паспорт уже зарегистрирован", HttpStatus.CONFLICT),
    INVALID_OTP("INVALID_OTP", "Неверный или истёкший OTP", HttpStatus.BAD_REQUEST),
    TOO_MANY_OTP_REQUESTS("TOO_MANY_OTP_REQUESTS", "Превышен лимит OTP запросов (макс 5 в час)", HttpStatus.TOO_MANY_REQUESTS),
    ACCOUNT_NOT_ACTIVATED("ACCOUNT_NOT_ACTIVATED", "Аккаунт не активирован. Оплатите вступительный взнос.", HttpStatus.FORBIDDEN),
    ACCOUNT_BLOCKED("ACCOUNT_BLOCKED", "Аккаунт заблокирован", HttpStatus.FORBIDDEN),
    PAYMENT_NOT_FOUND("PAYMENT_NOT_FOUND", "Платёж не найден", HttpStatus.NOT_FOUND),
    QR_EXPIRED("QR_EXPIRED", "QR-код истёк", HttpStatus.BAD_REQUEST),
    INSUFFICIENT_BALANCE("INSUFFICIENT_BALANCE", "Недостаточно средств", HttpStatus.BAD_REQUEST),
    WITHDRAWAL_MIN_AMOUNT("WITHDRAWAL_MIN_AMOUNT", "Сумма меньше минимума 1 000 сом", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("UNAUTHORIZED", "Не авторизован", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("FORBIDDEN", "Нет доступа", HttpStatus.FORBIDDEN),
    NOT_FOUND("NOT_FOUND", "Ресурс не найден", HttpStatus.NOT_FOUND),
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", "Неверный телефон или пароль", HttpStatus.UNAUTHORIZED),
    PHONE_NOT_VERIFIED("PHONE_NOT_VERIFIED", "Телефон не верифицирован", HttpStatus.BAD_REQUEST),
    USER_NOT_FOUND("USER_NOT_FOUND", "Пользователь не найден", HttpStatus.NOT_FOUND),
    INVALID_TOKEN("INVALID_TOKEN", "Неверный или истёкший токен", HttpStatus.UNAUTHORIZED),
    WEBHOOK_SIGNATURE_INVALID("WEBHOOK_SIGNATURE_INVALID", "Неверная подпись webhook", HttpStatus.UNAUTHORIZED);

    private final String code;
    private final String message;
    private final HttpStatus status;
}
