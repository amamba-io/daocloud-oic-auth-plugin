package org.jenkinsci.plugins.oic;

import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import net.minidev.json.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.util.Date;

public class JwtUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtUtils.class);

    public static JwtTokenInfo parseJwtToken(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }

        try {
            String jwtToken = extractBearerToken(token);
            if (jwtToken == null) {
                return null;
            }

            JWT jwt = JWTParser.parse(jwtToken);

            JwtTokenInfo tokenInfo = new JwtTokenInfo();
            tokenInfo.setToken(jwtToken);
            tokenInfo.setSubject(jwt.getJWTClaimsSet().getSubject());
            tokenInfo.setIssuer(jwt.getJWTClaimsSet().getIssuer());
            tokenInfo.setExpirationTime(jwt.getJWTClaimsSet().getExpirationTime());
            tokenInfo.setIssuedAtTime(jwt.getJWTClaimsSet().getIssueTime());
            JSONObject claims = new JSONObject();
            claims.putAll(jwt.getJWTClaimsSet().getClaims());
            tokenInfo.setClaims(claims);

            return tokenInfo;

        } catch (ParseException e) {
            LOGGER.error("Failed to parse JWT token: {}", e.getMessage(), e);
            return null;
        }
    }

    public static boolean isTokenExpired(JwtTokenInfo tokenInfo) {
        if (tokenInfo == null || tokenInfo.getExpirationTime() == null) {
            return true;
        }

        return tokenInfo.getExpirationTime().before(new Date());
    }

    public static String extractBearerToken(String authHeader) {
        if (StringUtils.isBlank(authHeader)) {
            return null;
        }

        if (authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        if (authHeader.startsWith("bearer ")) {
            return authHeader.substring(7);
        }

        return authHeader;
    }


    public static class JwtTokenInfo {
        @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
        private String token;
        private String subject;
        private String issuer;
        private Date expirationTime;
        private Date issuedAtTime;
        private JSONObject claims;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getSubject() {
            return subject;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public Date getExpirationTime() {
            return expirationTime;
        }

        public void setExpirationTime(Date expirationTime) {
            this.expirationTime = expirationTime;
        }

        public Date getIssuedAtTime() {
            return issuedAtTime;
        }

        public void setIssuedAtTime(Date issuedAtTime) {
            this.issuedAtTime = issuedAtTime;
        }

        public JSONObject getClaims() {
            return claims;
        }

        public void setClaims(JSONObject claims) {
            this.claims = claims;
        }
    }
}