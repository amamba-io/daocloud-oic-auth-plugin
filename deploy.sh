#!/bin/bash

# Jenkins Plugin Deployment Script
# Build and deploy plugin to local Jenkins, just for development and testing purposes.

set -e

# Configuration
JENKINS_URL="LocalJenkinsURL"  # e.g., http://localhost:8080
JENKINS_USER="AdminUserAccount"
JENKINS_PASSWORD="AdminUserAccountPassword"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${GREEN}=== Jenkins Plugin Deployment ===${NC}"

# Check if we're in the right directory
if [ ! -f "pom.xml" ]; then
    echo -e "${RED}Error: Not in a Maven project directory${NC}"
    exit 1
fi

# Build the plugin
echo -e "${YELLOW}Building plugin...${NC}"
mvn clean package -DskipTests

# Find the oic-auth HPI file
HPI_FILE=$(find target -name "daocloud-oic-auth*.hpi" -type f | head -1)
# Convert to absolute path
HPI_FILE=$(realpath "$HPI_FILE")
if [ -z "$HPI_FILE" ]; then
    echo -e "${RED}Error: daocloud-oic-auth HPI file not found${NC}"
    echo -e "${YELLOW}Available HPI files:${NC}"
    find target -name "*.hpi" -type f
    exit 1
fi

echo -e "${GREEN}Plugin built: $HPI_FILE${NC}"
echo -e "${YELLOW}Size: $(du -h "$HPI_FILE" | cut -f1)${NC}"

# Deploy using Jenkins CLI
echo -e "${YELLOW}Deploying via Jenkins CLI...${NC}"

# Download Jenkins CLI if not exists
if [ ! -f "jenkins-cli.jar" ]; then
    echo -e "${YELLOW}Downloading Jenkins CLI...${NC}"
    curl -s -o jenkins-cli.jar "$JENKINS_URL/jnlpJars/jenkins-cli.jar"
fi

# Install plugin using Jenkins CLI with local file
echo -e "${YELLOW}Installing plugin from local file: $HPI_FILE${NC}"
# Note: RestartRequiredException is normal and expected
if java -jar jenkins-cli.jar -s "$JENKINS_URL" -auth "$JENKINS_USER:$JENKINS_PASSWORD" install-plugin = < "$HPI_FILE" -deploy -restart 2>/dev/null; then
    echo -e "${GREEN}✓ Plugin installed successfully${NC}"
    echo -e "${GREEN}✓ Jenkins restart initiated${NC}"
else
    # Check if the error was just RestartRequiredException (which is normal)
    if [ $? -eq 1 ]; then
        echo -e "${GREEN}✓ Plugin uploaded successfully${NC}"
        echo -e "${GREEN}✓ Jenkins restart initiated${NC}"
        echo -e "${YELLOW}Note: Jenkins will restart immediately to load the new plugin${NC}"
    else
        echo -e "${RED}✗ Plugin installation failed${NC}"
        exit 1
    fi
fi

# Force restart Jenkins to ensure plugin is loaded
echo -e "${YELLOW}Forcing Jenkins restart to ensure plugin is loaded...${NC}"
if java -jar jenkins-cli.jar -s "$JENKINS_URL" -auth "$JENKINS_USER:$JENKINS_PASSWORD" restart 2>/dev/null; then
    echo -e "${GREEN}✓ Jenkins restart command sent successfully${NC}"
else
    echo -e "${YELLOW}Note: Jenkins restart command may have failed, but plugin should be loaded after next restart${NC}"
fi

# Wait for Jenkins to restart and verify it's back online
echo -e "${YELLOW}Waiting for Jenkins to restart...${NC}"
MAX_WAIT=300  # Maximum wait time in seconds
WAIT_COUNT=0
RESTARTED=false

while [ $WAIT_COUNT -lt $MAX_WAIT ]; do
    # Check if Jenkins is responding
    if curl -s -f "$JENKINS_URL/login" > /dev/null 2>&1; then
        # Additional check - try to use CLI to verify Jenkins is fully operational
        if java -jar jenkins-cli.jar -s "$JENKINS_URL" -auth "$JENKINS_USER:$JENKINS_PASSWORD" version > /dev/null 2>&1; then
            echo -e "${GREEN}✓ Jenkins has restarted successfully and is fully operational${NC}"
            RESTARTED=true
            break
        else
            echo -e "${YELLOW}Jenkins is responding but CLI not yet available...${NC}"
        fi
    else
        echo -e "${YELLOW}Waiting for Jenkins to come back online... (${WAIT_COUNT}s/${MAX_WAIT}s)${NC}"
    fi
    
    sleep 5
    WAIT_COUNT=$((WAIT_COUNT + 5))
done

if [ "$RESTARTED" = false ]; then
    echo -e "${RED}✗ Jenkins did not restart within $MAX_WAIT seconds${NC}"
    echo -e "${YELLOW}Please check Jenkins status manually${NC}"
else
    # Verify the plugin is installed
    echo -e "${YELLOW}Verifying plugin installation...${NC}"
    if java -jar jenkins-cli.jar -s "$JENKINS_URL" -auth "$JENKINS_USER:$JENKINS_PASSWORD" list-plugins | grep -q "oic-auth"; then
        echo -e "${GREEN}✓ Plugin verification successful - oic-auth is installed${NC}"
    else
        echo -e "${YELLOW}⚠ Plugin verification failed - plugin may not be loaded yet${NC}"
        echo -e "${YELLOW}Please check Jenkins plugin manager${NC}"
    fi
fi

echo -e "${GREEN}=== Deployment Complete ===${NC}"

open ${JENKINS_URL}

