package edu.pb.dcls;

import javafx.application.Application;

/** Non-Application entry point so the distribution can start through java -jar. */
public final class DclsLauncher {
    private DclsLauncher() { }

    public static void main(String[] args) {
        // Java has no Happy-Eyeballs fallback: a blackholed IPv6 route stalls every
        // connect for the full timeout (browsers fail over in ~250ms). Force IPv4 so
        // tile fetches fail fast, and bound DNS caching so one bad answer cannot
        // poison the session (JVM default is to cache successes forever).
        System.setProperty("java.net.preferIPv4Stack", "true");
        try {
            java.security.Security.setProperty("networkaddress.cache.ttl", "60");
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", "10");
        } catch (Exception ignored) { }
        Application.launch(DclsApp.class, args);
    }
}
