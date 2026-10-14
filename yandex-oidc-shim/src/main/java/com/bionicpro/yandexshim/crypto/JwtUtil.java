package com.bionicpro.yandexshim.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class JwtUtil {

    private static final String KID = "yandex-shim-key-1";

    private final RSAPublicKey publicKey;
    private final PrivateKey privateKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtUtil() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            this.publicKey = (RSAPublicKey) kp.getPublic();
            this.privateKey = kp.getPrivate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA key pair", e);
        }
    }

    public String signIdToken(Map<String, Object> claims) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "RS256");
            header.put("typ", "JWT");
            header.put("kid", KID);

            String headerJson = objectMapper.writeValueAsString(header);
            String payloadJson = objectMapper.writeValueAsString(claims);
            String signingInput = base64Url(headerJson.getBytes(StandardCharsets.UTF_8))
                    + "." + base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            byte[] sig = signature.sign();

            return signingInput + "." + base64Url(sig);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign the JWT", e);
        }
    }

    public Map<String, Object> jwks() {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", KID);
        jwk.put("n", base64UrlUnsigned(publicKey.getModulus()));
        jwk.put("e", base64UrlUnsigned(publicKey.getPublicExponent()));
        return Map.of("keys", List.of(jwk));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String base64UrlUnsigned(BigInteger big) {
        byte[] bytes = big.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return base64Url(bytes);
    }
}