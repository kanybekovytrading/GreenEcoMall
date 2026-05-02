package greenecomall.finik;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class FinikSignatureUtil {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    public String generateSignature(
            String httpMethod,
            String path,
            Map<String, String> headers,
            Map<String, String> queryParams,
            Object body,
            String privateKeyPath
    ) throws Exception {
        String canonicalString = createCanonicalString(httpMethod, path, headers, queryParams, body);
        log.debug("Canonical string for signing: {}", canonicalString);
        return signWithPrivateKey(canonicalString, privateKeyPath);
    }

    private String createCanonicalString(
            String httpMethod,
            String path,
            Map<String, String> headers,
            Map<String, String> queryParams,
            Object body
    ) throws Exception {
        StringBuilder canonical = new StringBuilder();
        canonical.append(httpMethod.toLowerCase()).append("\n");
        canonical.append(path).append("\n");
        canonical.append(buildCanonicalHeaders(headers)).append("\n");

        if (queryParams != null && !queryParams.isEmpty()) {
            canonical.append(buildCanonicalQueryString(queryParams)).append("\n");
        }

        if (body != null) {
            canonical.append(objectMapper.writeValueAsString(body));
        }

        return canonical.toString();
    }

    private String buildCanonicalHeaders(Map<String, String> headers) {
        List<String> parts = new ArrayList<>();

        if (headers.containsKey("Host")) {
            parts.add("host:" + headers.get("Host"));
        }

        headers.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().startsWith("x-api-"))
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(e -> parts.add(e.getKey().toLowerCase() + ":" + e.getValue()));

        return String.join("&", parts);
    }

    private String buildCanonicalQueryString(Map<String, String> queryParams) {
        return queryParams.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private String signWithPrivateKey(String data, String privateKeyPath) throws Exception {
        PrivateKey privateKey = loadPrivateKey(privateKeyPath);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    private PrivateKey loadPrivateKey(String path) throws Exception {
        String pem;

        String fromEnv = System.getenv("FINIK_PRIVATE_KEY_CONTENT");
        if (fromEnv != null && !fromEnv.isBlank()) {
            pem = fromEnv;
        } else {
            Resource resource = resourceLoader.getResource(path);
            pem = new String(resource.getInputStream().readAllBytes());
        }

        boolean isPKCS1 = pem.contains("BEGIN RSA PRIVATE KEY");

        pem = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "").trim();

        byte[] encoded = Base64.getDecoder().decode(pem);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");

        if (isPKCS1) {
            return convertPKCS1ToPKCS8(encoded, keyFactory);
        } else {
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
        }
    }

    private PrivateKey convertPKCS1ToPKCS8(byte[] pkcs1Bytes, KeyFactory keyFactory) throws Exception {
        int pkcs1Length = pkcs1Bytes.length;
        int totalLength = pkcs1Length + 22;

        byte[] pkcs8Header = new byte[]{
                0x30, (byte) 0x82, (byte) ((totalLength >> 8) & 0xff), (byte) (totalLength & 0xff),
                0x2, 0x1, 0x0,
                0x30, 0xD, 0x6, 0x9, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0xD, 0x1, 0x1, 0x1, 0x5, 0x0,
                0x4, (byte) 0x82, (byte) ((pkcs1Length >> 8) & 0xff), (byte) (pkcs1Length & 0xff)
        };

        byte[] pkcs8bytes = new byte[pkcs8Header.length + pkcs1Length];
        System.arraycopy(pkcs8Header, 0, pkcs8bytes, 0, pkcs8Header.length);
        System.arraycopy(pkcs1Bytes, 0, pkcs8bytes, pkcs8Header.length, pkcs1Length);

        return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8bytes));
    }
}
