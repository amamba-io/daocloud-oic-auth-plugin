package org.jenkinsci.plugins.oic;

import hudson.Extension;
import hudson.security.csrf.CrumbExclusion;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Excluding the escapeHatch login from CSRF protection as the crumb is calculated based on the authentication
 * mirroring behavior of the normal login page.
 *
 * @author Michael Bischoff
 */

// 用于排除某些URL在CSRF保护之外
@Extension
public class EscapeHatchCrumbExclusion extends CrumbExclusion {

    @Override
    public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String pathInfo = request.getPathInfo();
        if ("/securityRealm/escapeHatch".equals(pathInfo)) {
            chain.doFilter(request, response);
            // 表示请求已经处理，达到绕过此端点的CSRF
            return true;
        }
        return false;
    }
}
