Behaviour.specify("#logoutFromIDP", 'oic-security-realm', 0, function(logoutFromIDP) {

    var endSessionConfig = document.querySelector('.endSessionConfig');

    if (endSessionConfig && logoutFromIDP) {
        endSessionConfig.style.display = logoutFromIDP.checked ? "block" : "none";

        logoutFromIDP.addEventListener("change", function() {
            endSessionConfig.style.display = logoutFromIDP.checked ? "block" : "none";
        });
    }
});

Behaviour.specify("#enableExternalAuth", 'oic-security-realm', 0, function(enableExternalAuth) {

    var externalAuthConfig = document.querySelector('.externalAuthConfig');

    if (externalAuthConfig && enableExternalAuth) {
        externalAuthConfig.style.display = enableExternalAuth.checked ? "block" : "none";

        enableExternalAuth.addEventListener("change", function() {
            externalAuthConfig.style.display = enableExternalAuth.checked ? "block" : "none";
        });
    }
});
