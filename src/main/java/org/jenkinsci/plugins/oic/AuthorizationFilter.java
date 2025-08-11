package org.jenkinsci.plugins.oic;

import hudson.security.SecurityRealm;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;
import hudson.model.User;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AuthorizationFilter implements Filter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthorizationFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest) || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        SecurityRealm securityRealm = Jenkins.get().getSecurityRealm();
        if (!(securityRealm instanceof OicSecurityRealm)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            if (!shouldAuthorization(httpRequest)) {
                LOGGER.debug("Skipping authorization for request: {}", httpRequest.getRequestURI());
                chain.doFilter(request, response);
                return;
            }

            // 只针对workspace下的资源进行鉴权
            UrlPathUtils.FolderInfo folderInfo = UrlPathUtils.extractFolderInfo(httpRequest);
            if (StringUtils.isBlank(folderInfo.getFolderPath())) {
                LOGGER.warn("Could not extract folder info from request: {}, skipping authorization check", httpRequest.getRequestURI());
                chain.doFilter(request, response);
                return;
            }

            String permission = folderInfo.getPermission();

            // 这里分为两种请求，纯DCE API调用和OIDC登录的用户访问
            String token = httpRequest.getHeader("Authorization");
            if (StringUtils.isBlank(token)) {
               // OIDC 登录的用户
                token = getTokenFromUserCredentials();
            }

            if (StringUtils.isBlank(token)) {
                LOGGER.warn("No Authorization token found for request: {}", httpRequest.getRequestURI());
                httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing Authorization token");
                return;
            }

            // 有三种形式:
            // 注意，DCE的API 使用 admin账户调用时，虽然是user/password 这种形式，但是也是在header中传递的。
            // 1. jenkins user token
            // 2. jenkins username/password
            // 3. DCE token

            String[] split = StringUtils.split(token, ".");
            if (split.length != 3) {
                // 说明是使用用户名密码或者jenkins token的方式，不进行处理
                chain.doFilter(httpRequest, response);
                return;
            }

            JwtUtils.JwtTokenInfo tokenInfo = JwtUtils.parseJwtToken(token);
            if (tokenInfo == null) {
                LOGGER.warn("Invalid JWT token for request: {}", httpRequest.getRequestURI());
                httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid JWT token");
                return;
            }

            if (JwtUtils.isTokenExpired(tokenInfo)) {
                LOGGER.warn("Expired JWT token for request: {}", httpRequest.getRequestURI());
                httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Expired JWT token");
                return;
            }
            LOGGER.info("Checking authorization for user: {}, path: {}, method: {}, permission: {}",
                    tokenInfo.getSubject(), httpRequest.getRequestURI(), folderInfo.getHttpMethod(), permission);

          // Check with external auth service
          AuthorizationServiceClient client = new AuthorizationServiceClient(getAuthServiceUrl());
          AuthorizationServiceClient.AuthorizationResponse authResponse = client.checkAuthorization(token, folderInfo.getFolderPath(), permission);
          LOGGER.debug("Authorization response: {}", authResponse);

          if (authResponse.isAuthorized()) {
              // 必须设置用户已经被认证过， 否则其实用户是没有登录的状态，后续的filter会认为用户没有登录
              // TODO： 这里不太确定是不是会把所有请求的用户都给设置成同一个。也需要参考 sessionStore.renewSession
              setUserAuthenticationFromJwt(tokenInfo);

              chain.doFilter(request, response);
          } else {
              LOGGER.warn("Authorization denied for user: {}, path: {}, permission: {}", 
                  tokenInfo.getSubject(), httpRequest.getRequestURI(), permission);
              httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied" );
          }

        } catch (AuthorizationServiceClient.AuthorizationException e) {
            LOGGER.error("Authorization service error for request: {}", httpRequest.getRequestURI(), e);
            httpResponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Authorization service error: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Unexpected error during authorization for request: {}", httpRequest.getRequestURI(), e);
            httpResponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Internal server error during authorization");
        }
    }

  
    private boolean shouldAuthorization(HttpServletRequest request) {
        // 哪几种情况需要鉴权
        // job 相关api (包括blue ocean的)， 需要匹配正则，可以匹配出ws的
        // http 方法是 POST/PUT/DELETE 的api
        // header中需要有token的并且来源是DCE，避免与原本的jenkins token冲突
        // 已经通过OIDC登录的用户，访问pipeline相关的也需要鉴权

        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        LOGGER.debug("Checking if authorization should be skipped for: {} (context: {})", requestUri, contextPath);

  
        String requestMethod = request.getMethod().toLowerCase();
        LOGGER.debug("Request method: {}", requestMethod);
        boolean methodMatch = requestMethod.equals("post") || requestMethod.equals("put") || requestMethod.equals("delete") || requestMethod.equals("get");
        if (!methodMatch) {
            LOGGER.debug("Skipping authorization - request method does not require authorization: {}", requestMethod);
            return false;
        }

        // 可以匹配出 ws的, 并且是Job相关的路径才需要授权
        // 匹配 job 相关的路径（包括嵌套的 job 结构）
        java.util.regex.Pattern jobPattern = java.util.regex.Pattern.compile("^/job/([^/]+)(?:/job/([^/]+))*");

        // 匹配 blue ocean pipeline 相关的路径（包括嵌套的 pipeline 结构）
        java.util.regex.Pattern pipelinePattern = java.util.regex.Pattern.compile("^/blue/rest/organizations/jenkins/pipelines/([^/]+)(?:/pipelines/([^/]+))*");

        // Remove context path if present
        if (StringUtils.isNotBlank(contextPath) && requestUri.startsWith(contextPath)) {
            requestUri = requestUri.substring(contextPath.length());
            LOGGER.debug("Request URI after removing context: {}", requestUri);
        }

        boolean pathMatch = jobPattern.matcher(requestUri).find() || pipelinePattern.matcher(requestUri).find();
        if (!pathMatch) {
            LOGGER.debug("Skipping authorization - request path does not require authorization: {}", requestUri);
            return false;
        }

        // 处理OIDC已经登录的用户, 这里是不是可能设置成把用户给覆盖了？？？
        String tokenFromUserCredentials = getTokenFromUserCredentials();
        if (StringUtils.isNotBlank(tokenFromUserCredentials)) {
            // 同时还需要通过外部接口的鉴权，目前认为用户拥有所有权限。
            // 其实这里如果用的admin账户，一直都是true的。
            return true;
        }


        String authorization = request.getHeader("Authorization");
        if (StringUtils.isBlank(authorization)) {
            LOGGER.debug("Skipping authorization - no Authorization header found");
            return false;
        }

        // 为了处理jenkins原生的token，因为header头都是一样的，这里要求必须是DCE来源的请求才进行鉴权
        String from = request.getHeader(OicConstants.CUSTOM_SOURCE_HEADER);
        if (StringUtils.isBlank(from) || !from.equals(OicConstants.CUSTOM_SOURCE_HEADER_VALUE)) {
            LOGGER.debug("Skipping authorization - request not from DCE");
            return false;
        }

        LOGGER.debug("Authorization check required for: {}", requestUri);
        return true;
    }

    private String getAuthServiceUrl() {
        try {
            OicSecurityRealm securityRealm = (OicSecurityRealm) Jenkins.get().getSecurityRealm();
            if (securityRealm != null) {
                return securityRealm.getExternalAuthServiceUrl();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get authorization configuration", e);
        }
        return null;
    }

    /**
     * Set user authentication information from JWT token
     * This will create and set the authentication object in Spring security context
     */
    private void setUserAuthenticationFromJwt(JwtUtils.JwtTokenInfo tokenInfo) {
        try {
            // Create basic authorities for the authenticated user
            List<GrantedAuthority> authorities = new ArrayList<>();
            // 设置用户的角色为已认证，需要配合 安全矩阵 的功能来使用。
            // 具体有哪些权限，需要看 安全矩阵 对已认证用户给了哪些权限
            authorities.add(SecurityRealm.AUTHENTICATED_AUTHORITY2);

            String userName = tokenInfo.getClaims().getAsString("preferred_username");
            JwtAuthAuthenticationToken authentication  = new JwtAuthAuthenticationToken(userName, authorities);
            // 通知系统用户已认证和登录
            SecurityContextHolder.getContext().setAuthentication(authentication);
            LOGGER.info("Successfully set authentication for user: {}", tokenInfo.getSubject());
            
        } catch (Exception e) {
            LOGGER.error("Failed to set user authentication for user: {}", tokenInfo.getSubject(), e);
        }
    }


    // 如果用户是通过OIDC登录的，那么可以获取到用户存储的token
    private String getTokenFromUserCredentials() {
        try {
            // 获取当前认证的用户
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null) {
                LOGGER.debug("No authentication found in security context");
                return null;
            }

            // 获取用户对象
            User user = User.get2(authentication);
            if (user == null) {
                LOGGER.debug("No user found from authentication");
                return null;
            }

            // 获取存储的OIDC token
            OicCredentials credentials = user.getProperty(OicCredentials.class);
            if (credentials == null) {
                LOGGER.debug("No OicCredentials found for user: {}", user.getId());
                return null;
            }

            // 优先使用ID Token，如果没有则使用Access Token
            String idToken = credentials.getIdToken();
            String accessToken = credentials.getAccessToken();

            String token = !Objects.equals(idToken, "") ? idToken : accessToken;

            LOGGER.debug("Retrieved token from user credentials for user: {}", user.getId());

            return token;

        } catch (Exception e) {
            LOGGER.error("Error getting token from user credentials", e);
            return null;
        }
    }

}

