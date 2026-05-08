package greenecomall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.finik")
@Data
public class FinikConfig {

    private String apiKey;
    private String accountId;
    private String baseUrl;
    private String privateKeyPath;
    private String webhookUrl;
    private String redirectUrl;
    private String merchantCategoryCode = "0742";
    private String qrNameEn = "GreenEcoMall";
}
