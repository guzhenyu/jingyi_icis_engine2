package com.jingyicare.jingyi_icis_engine.testutils;

import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import lombok.extern.slf4j.Slf4j;

@SpringBootTest
@ActiveProfiles("test")
@Slf4j
public class TestsBase {
    @BeforeAll
    public static void globalSetUp() {
    }

    @BeforeEach
    public void setUp() {
    }
}