package com.example;


import org.junit.Test;


public class JarDecompilerTest {

    @Test
    public void testDecompileJar() {
        String jarFilePath = "D:\\文档库\\Demo\\lib\\uds-api-1.0.0.jar";
        JarDecompiler decompiler = new JarDecompiler("D:\\文档库\\Demo",jarFilePath);
        decompiler.decompileJar(jarFilePath);
        
    }
}