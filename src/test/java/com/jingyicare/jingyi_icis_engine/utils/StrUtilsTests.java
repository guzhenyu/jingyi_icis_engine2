package com.jingyicare.jingyi_icis_engine.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class StrUtilsTests {
    @Test
    public void testSnakeToCamel() {
        assertEquals("helloWorld", StrUtils.snakeToCamel("hello_world"));
        assertEquals("helloWorld", StrUtils.snakeToCamel("hello_World"));
        assertEquals("helloWorld", StrUtils.snakeToCamel("hello__world"));
        assertEquals("helloWorld", StrUtils.snakeToCamel("hello___world"));
        assertEquals("helloWorld", StrUtils.snakeToCamel("hello_world_"));
        assertEquals("helloWorld", StrUtils.snakeToCamel("_hello_world"));
    }
}
