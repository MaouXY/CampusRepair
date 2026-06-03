package com.maou.apptemplateapi.module.rag.service;

import com.maou.apptemplateapi.common.config.ai.AgentToolProperties;
import dev.langchain4j.data.embedding.Embedding;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
@RequiredArgsConstructor
public class LocalHashEmbeddingService {

    private final AgentToolProperties agentToolProperties;

    public Embedding embed(String text) {
        int dimension = dimension();
        float[] vector = new float[dimension];
        if (!StringUtils.hasText(text)) {
            return Embedding.from(vector);
        }
        String normalized = text.toLowerCase();
        for (int i = 0; i < normalized.length(); i++) {
            int bucket = Math.floorMod(normalized.charAt(i) * 31 + i, dimension);
            vector[bucket] += 1.0f;
        }
        for (String token : normalized.split("[\\s,.;，。；、]+")) {
            if (StringUtils.hasText(token)) {
                int bucket = Math.floorMod(stableHash(token), dimension);
                vector[bucket] += 3.0f;
            }
        }
        normalize(vector);
        return Embedding.from(vector);
    }

    public int dimension() {
        Integer dimension = agentToolProperties.getRag().getMilvus().getDimension();
        return dimension == null || dimension <= 0 ? 384 : dimension;
    }

    public String provider() {
        return "LOCAL_HASH";
    }

    public String modelName() {
        return "local-hash";
    }

    private int stableHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return ((digest[0] & 0xff) << 24)
                    | ((digest[1] & 0xff) << 16)
                    | ((digest[2] & 0xff) << 8)
                    | (digest[3] & 0xff);
        } catch (NoSuchAlgorithmException exception) {
            return token.hashCode();
        }
    }

    private void normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }
}
