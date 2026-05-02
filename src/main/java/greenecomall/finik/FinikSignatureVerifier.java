package greenecomall.finik;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * Verifies RSA SHA256 signatures on incoming Finik webhooks.
 * Uses Finik's production public key.
 */
@Component
@Slf4j
public class FinikSignatureVerifier {

    // Finik production public key (from Averspay documentation)
    private static final String FINIK_PUBLIC_KEY =
            "-----BEGIN PUBLIC KEY-----\n" +
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuF/PUmhMPPidcMxhZBPb\n" +
            "BSGJoSphmCI+h6ru8fG8guAlcPMVlhs+ThTjw2LHABvciwtpj51ebJ4EqhlySPyT\n" +
            "hqSfXI6Jp5dPGJNDguxfocohaz98wvT+WAF86DEglZ8dEsfoumojFUy5sTOBdHEu\n" +
            "g94B4BbrJvjmBa1YIx9Azse4HFlWhzZoYPgyQpArhokeHOHIN2QFzJqeriANO+wV\n" +
            "aUMta2AhRVZHbfyJ36XPhGO6A5FYQWgjzkI65cxZs5LaNFmRx6pjnhjIeVKKgF99\n" +
            "4OoYCzhuR9QmWkPl7tL4Kd68qa/xHLz0Psnuhm0CStWOYUu3J7ZpzRK8GoEXRcr8\n" +
            "tQIDAQAB\n" +
            "-----END PUBLIC KEY-----";

    /**
     * Verifies the Finik webhook signature.
     * Canonical string format (same as BilimBulak production code):
     *   post\n
     *   /api/payment/webhook\n
     *   host:<host>&x-api-timestamp:<ts>\n
     *   <raw JSON body>
     */
    public boolean verifyWebhookSignature(String rawBody, Map<String, String> headers) {
        String signature = headers.getOrDefault("Signature", headers.get("signature"));
        if (signature == null) {
            log.error("Finik webhook: Signature header missing");
            return false;
        }

        String host      = headers.getOrDefault("Host", headers.getOrDefault("host", ""));
        String timestamp = headers.getOrDefault("X-Api-Timestamp",
                headers.getOrDefault("x-api-timestamp", ""));

        String canonicalString = "post\n"
                + "/api/payment/webhook\n"
                + "host:" + host + "&x-api-timestamp:" + timestamp + "\n"
                + rawBody;

        try {
            boolean result = verifySignature(canonicalString, signature);
            log.info("Finik webhook signature verification: {}", result ? "OK" : "FAILED");
            return result;
        } catch (Exception e) {
            log.error("Finik webhook signature verification error", e);
            return false;
        }
    }

    public boolean verifySignature(String data, String signatureBase64) throws Exception {
        PublicKey publicKey = loadPublicKey();
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(publicKey);
        sig.update(data.getBytes(StandardCharsets.UTF_8));
        return sig.verify(Base64.getDecoder().decode(signatureBase64));
    }

    private PublicKey loadPublicKey() throws Exception {
        String content = FINIK_PUBLIC_KEY
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(content);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
    }
}
