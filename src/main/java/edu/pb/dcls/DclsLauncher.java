package edu.pb.dcls;

import javafx.application.Application;

/** Non-Application entry point so the distribution can start through java -jar. */
public final class DclsLauncher {
    private DclsLauncher() { }

    public static void main(String[] args) {
        Application.launch(DclsApp.class, args);
    }
}
