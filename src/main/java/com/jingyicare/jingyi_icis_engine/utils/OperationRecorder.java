package com.jingyicare.jingyi_icis_engine.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;

public class OperationRecorder {
    public static void logOperation(String operation, String username, String message) {
        logger.warn("\nOperation: " + operation + 
            "\nUser: " + username +
            "\nMessage: " + message);
    }
    public static void logOperation(String operation, String message) {
        logger.warn("\nOperation: " + operation + 
            "\nUser: " + SecurityContextHolder.getContext().getAuthentication().getName() +
            "\nMessage: " + message);
    }

    private static final Logger logger = LoggerFactory.getLogger(OperationRecorder.class);

}