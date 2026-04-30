package greenecomall.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.servers.Server;
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
                        .description("MLM-платформа на эко-продуктах из Кыргызстана. " +
                                "Для защищённых эндпоинтов нажмите **Authorize** и вставьте: `Bearer <access_token>`")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Green Eco Mall")
                                .email("support@greenecomall.kg")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT access token (15 мин)")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
