package com.barberease;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.beans.factory.annotation.Value;

import java.awt.Desktop;
import java.net.URI;
import java.io.IOException;

@SpringBootApplication
@EnableScheduling
public class BarberEaseApplication {

    @Value("${server.port:3000}")
    private String serverPort;

    @Value("${app.auto-open-browser:false}")
    private boolean autoOpenBrowser;

    public static void main(String[] args) {
        SpringApplication.run(BarberEaseApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowser() {
        String url = "http://localhost:" + serverPort;
        System.out.println("🚀 BarberEase Application is ready at: " + url);
        if (!autoOpenBrowser) {
            return;
        }
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", "chrome", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", "-a", "Google Chrome", url).start();
            } else {
                new ProcessBuilder("google-chrome", url).start();
            }
        } catch (IOException e) {
            System.err.println("Failed to open Chrome: " + e.getMessage());
            // Fallback to default browser
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                }
            } catch (Exception ex) {
                System.err.println("Failed to open default browser: " + ex.getMessage());
            }
        }
    }
}
