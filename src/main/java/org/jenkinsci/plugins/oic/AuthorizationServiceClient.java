package org.jenkinsci.plugins.oic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class AuthorizationServiceClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthorizationServiceClient.class);

    private final String externalAuthServiceUrl;
    private final ObjectMapper objectMapper;

    public AuthorizationServiceClient(String externalAuthServiceUrl) {
        this.externalAuthServiceUrl = externalAuthServiceUrl;
        this.objectMapper = new ObjectMapper();
    }

    public AuthorizationResponse checkAuthorization(String jwtToken, String workspaceID, String permission)
        throws AuthorizationException {
        LOGGER.info("Checking authorization for workspace: {}, permission: {}", workspaceID, permission);

        try {
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("workspaceID", workspaceID);
            requestBody.put("permission", permission);
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpPost httpPost = new HttpPost(externalAuthServiceUrl);
            httpPost.setHeader("Content-Type", "application/json; charset=UTF-8");
            httpPost.setHeader("Accept", "*/*");
            // trim jwtToken Bearer
            if (StringUtils.startsWithIgnoreCase(jwtToken, "Bearer ")) {
                jwtToken = jwtToken.substring(7).trim();
            }
            httpPost.setHeader("Authorization", "Bearer " + jwtToken);
            httpPost.setEntity(new StringEntity(jsonBody, "application/json", "UTF-8"));

            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                LOGGER.info("Sending authorization request to: {}", externalAuthServiceUrl);
                
                HttpResponse response = httpClient.execute(httpPost);
                String responseBody = EntityUtils.toString(response.getEntity());

                LOGGER.info("Authorization response status: {}", response.getStatusLine().getStatusCode());
                LOGGER.debug("Authorization response body: {}", responseBody);

                if (response.getStatusLine().getStatusCode() == 200) {
                    return objectMapper.readValue(responseBody, AuthorizationResponse.class);
                } else {
                    throw new AuthorizationException("External auth service returned status: " +
                        response.getStatusLine().getStatusCode() + ", body: " + responseBody);
                }
            }

        } catch (IOException e) {
            LOGGER.error("Failed to check authorization with external service", e);
            throw new AuthorizationException("Failed to check authorization: " + e.getMessage(), e);
        }
    }

    
    public static class AuthorizationResponse {
        private boolean authorized;

        public boolean isAuthorized() {
            return authorized;
        }

        public void setAuthorized(boolean authorized) {
            this.authorized = authorized;
        }
    }

    public static class AuthorizationException extends Exception {
        public AuthorizationException(String message) {
            super(message);
        }

        public AuthorizationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}