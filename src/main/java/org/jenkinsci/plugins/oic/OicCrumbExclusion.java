package org.jenkinsci.plugins.oic;

import hudson.Extension;
import hudson.security.SecurityRealm;
import hudson.security.csrf.CrumbExclusion;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;

/**
 * Crumb exclusion to allow POSTing to {@link OicSecurityRealm#doFinishLogin(org.kohsuke.stapler.StaplerRequest2, org.kohsuke.stapler.StaplerResponse2)}
 * and requests that have passed AuthorizationFilter
 */
// 跟EscapeHatchCrumbExclusion一样的效果，跳过CSRF的判断
@Extension
public class OicCrumbExclusion extends CrumbExclusion {

    @Override
    public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j != null) {
            SecurityRealm sr = j.getSecurityRealm();
            if (sr instanceof OicSecurityRealm) {
                if ("/securityRealm/finishLogin".equals(request.getPathInfo())) {
                    chain.doFilter(request, response);
                    return true;
                }
                
                // Exclude requests that have DCE authorization header 
                // (these have been validated by AuthorizationFilter)
                String authorization = request.getHeader("Authorization");
                String from = request.getHeader(OicConstants.CUSTOM_SOURCE_HEADER);
                if (StringUtils.isNotBlank(authorization) && OicConstants.CUSTOM_SOURCE_HEADER_VALUE.equals(from)) {
                    chain.doFilter(request, response);
                    // 如果是DCE授权的请求，直接放行，不进行CSRF的校验
                    return true;
                }
            }
        }
        return false;
    }
}
