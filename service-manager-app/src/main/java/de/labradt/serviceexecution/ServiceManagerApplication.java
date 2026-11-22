package de.labradt.serviceexecution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Service Manager Application - Standalone Service
 * 
 *   - Starts on port 8082
 */
@Slf4j
@SpringBootApplication
public class ServiceManagerApplication {

    public static void main(String[] args) {
        PluginLauncher.run(ServiceManagerApplication.class, args);  // plus the JARs in plugins/
    }
}

