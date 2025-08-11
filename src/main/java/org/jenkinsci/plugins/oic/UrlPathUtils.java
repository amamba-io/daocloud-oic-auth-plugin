package org.jenkinsci.plugins.oic;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlPathUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(UrlPathUtils.class);

    private static final Pattern FOLDER_PATH_PATTERN = Pattern.compile("^/job/([^/]+)/job/");
    private static final Pattern BLUEOCEAN_PATH_PATTERN = Pattern.compile("^/blue/rest/organizations/jenkins/pipelines/([^/]+)/pipelines/");

    public static FolderInfo extractFolderInfo(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();

        LOGGER.debug("Extracting folder info from URI: {}, context: {}", requestUri, contextPath);

        if (StringUtils.isNotBlank(contextPath)) {
            requestUri = requestUri.substring(contextPath.length());
        }

        String httpMethod = request.getMethod();

        FolderInfo folderInfo = new FolderInfo();
        folderInfo.setRequestUri(requestUri);
        folderInfo.setHttpMethod(httpMethod);

        String folderPath = ExtractFolderPath(requestUri);
        if (StringUtils.isNotBlank(folderPath)) {
            folderInfo.setFolderPath(folderPath);
            LOGGER.debug("Extracted folder path: {}", folderPath);
        } else {
            LOGGER.debug("No folder path found in URI: {}", requestUri);
        }

        return folderInfo;
    }

    private static String ExtractFolderPath(String requestUri) {
        if (StringUtils.isBlank(requestUri)) {
            return null;
        }

        Matcher jobMatcher = FOLDER_PATH_PATTERN.matcher(requestUri);
        if (jobMatcher.find()) {
            return jobMatcher.group(1);
        }

        Matcher blueoceanMatcher = BLUEOCEAN_PATH_PATTERN.matcher(requestUri);
        if (blueoceanMatcher.find()) {
            return blueoceanMatcher.group(1);
        }

        return null;
    }

    public static class FolderInfo {
        private String requestUri;
        private String httpMethod;
        private String folderPath;

        public String getRequestUri() {
            return requestUri;
        }

        public void setRequestUri(String requestUri) {
            this.requestUri = requestUri;
        }

        public String getHttpMethod() {
            return httpMethod;
        }

        public void setHttpMethod(String httpMethod) {
            this.httpMethod = httpMethod;
        }

        public String getFolderPath() {
            return folderPath;
        }

        public void setFolderPath(String folderPath) {
            this.folderPath = folderPath;
        }

        /**
         * Maps HTTP method to permission string
         * GET -> read, POST -> create (or run for pipeline execution), PUT -> update, DELETE -> delete
         */
        public String getPermission() {
            String method = httpMethod.toUpperCase();
            
            boolean isRunAction = isRunAction();
            if (isRunAction) {
                return "amamba.pipeline.run";
            }

            return switch (method) {
                case "GET" -> "amamba.pipeline.get";
                case "POST" -> "amamba.pipeline.create";
                case "PUT" -> "amamba.pipeline.update";
                case "DELETE" -> "amamba.pipeline.delete";
                default -> "amamba.pipeline." + method.toLowerCase();
            };
        }
        
        /**
         * Checks if the current request is a pipeline execution request
         */
        private boolean isRunAction() {
            if (StringUtils.isBlank(requestUri)) {
                return false;
            }

            boolean methodMatch = "POST".equalsIgnoreCase(httpMethod);
            if (!methodMatch) {
                return false;
            }

            return (requestUri.matches("/job/[^/]+/job/[^/]+/build.*") || requestUri.matches("/blue/rest/organizations/jenkins/pipelines/[^/]+/pipelines/[^/]+/runs.*"));
        }

        @Override
        public String toString() {
            return "FolderInfo{" +
                   "requestUri='" + requestUri + '\'' +
                   ", httpMethod='" + httpMethod + '\'' +
                   ", folderPath='" + folderPath + '\'' +
                   '}';
        }
    }
}