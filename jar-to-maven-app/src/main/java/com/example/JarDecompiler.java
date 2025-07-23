package com.example;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.codehaus.plexus.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class JarDecompiler {

    private final String outputBaseDir;
    private final String jarFilePath;
    private final Set<String> decompiledFiles;

    public JarDecompiler(String outputBaseDir, String jarFilePath) {
        this.outputBaseDir = outputBaseDir;
        this.jarFilePath = jarFilePath;
        this.decompiledFiles = new HashSet<>();
    }

    public void decompileJar(String jarFilePath) {
        File jarFile = new File(jarFilePath);
        if (!jarFile.exists() || !jarFile.getName().endsWith(".jar")) {
            throw new IllegalArgumentException("Invalid JAR file path: " + jarFilePath);
        }

        try {
            // 创建输出目录
            String projectName = jarFile.getName().replace(".jar", "");
            Path outputDir = Paths.get(outputBaseDir, projectName);
            if (Files.exists(outputDir)){
                FileUtils.deleteDirectory(outputDir.toFile());
            }
            Files.createDirectories(outputDir);
            
            // 创建Maven项目结构
            createMavenProjectStructure(outputDir);
            
            // 提取和反编译JAR文件
            decompileJarContent(jarFile, outputDir);
            
            // 解析JAR依赖并更新pom.xml
            extractDependencies(outputDir);
            
        } catch (IOException e) {
            throw new RuntimeException("Failed to decompile JAR: " + jarFilePath, e);
        }
    }

    private void createMavenProjectStructure(Path outputDir) throws IOException {
        // 创建标准Maven目录结构
        Files.createDirectories(outputDir.resolve("src/main/java"));
        Files.createDirectories(outputDir.resolve("src/main/resources"));
        Files.createDirectories(outputDir.resolve("src/test/java"));
        Files.createDirectories(outputDir.resolve("src/test/resources"));
    }

    private void decompileJarContent(File jarFile, Path outputDir) throws IOException {
        Map<String, String> options = new HashMap<>();
        options.put("showversion", "false");
        options.put("decodestringswitch", "true");
        options.put("sugarenums", "true");
        options.put("decodelambdas", "true");
        options.put("hidebridgemethods", "true");
        
        try (JarFile jar = new JarFile(jarFile)) {
            // 创建源代码目录
            Path srcDir = outputDir.resolve("src/main/java");
            
            // 设置CFR输出工厂
            OutputSinkFactory outputSinkFactory = createOutputSinkFactory(srcDir);
            
            // 遍历JAR文件中的所有条目
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                if (entry.getName().contains("META-INF")
                        || entry.getName().contains("org/springframework/")){
                    continue;
                }

                if (entry.getName().endsWith(".class")) {
                    // 反编译类文件
                    decompileClass(jar, entry, outputSinkFactory, options);
                } else if (!entry.isDirectory() && !entry.getName().endsWith(".class")) {
                    // 复制资源文件
                    if (entry.getName().contains("BOOT-INF/classes")){
                        copyResource(jar, entry, outputDir);
                    }
                }
            }
        }
    }

    private OutputSinkFactory createOutputSinkFactory(Path srcDir) {
        return new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> collection) {
                return Collections.singletonList(SinkClass.STRING);
            }

            @Override
            public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                return new Sink<T>() {
                    @Override
                    public void write(T t) {
                        if (t instanceof String && sinkType == SinkType.JAVA) {
                            String result = (String) t;
                            try {
                                // 获取包名和类名
                                String packageName = extractPackageName(result);
                                String className = extractClassName(result);
                                
                                // 创建包目录
                                Path packageDir = srcDir;
                                if (!packageName.isEmpty()) {
                                    packageDir = srcDir.resolve(packageName.replace('.', '/'));
                                    Files.createDirectories(packageDir);
                                }
                                
                                // 写入反编译后的源代码
                                Path outputFile = packageDir.resolve(className + ".java");
                                Files.write(outputFile, Collections.singleton(result));
                                decompiledFiles.add(outputFile.toString());
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to write decompiled source", e);
                            }
                        }
                    }
                };
            }
        };
    }

    private void decompileClass(JarFile jar, JarEntry entry, OutputSinkFactory outputSinkFactory, Map<String, String> options) {
        try {
            // 准备反编译选项
            Map<String, String> cfg = new HashMap<>(options);
            cfg.put("filename", entry.getName());
            
            // 创建CFR驱动并执行反编译
            CfrDriver driver = new CfrDriver.Builder()
                    .withOptions(cfg)
                    .withOutputSink(outputSinkFactory)
                    .build();
            
            driver.analyse(Collections.singletonList(jarFilePath));
        } catch (Exception e) {
            System.err.println("Failed to decompile: " + jar.getName());
            e.printStackTrace();
        }
    }

    private void copyResource(JarFile jar, JarEntry entry, Path outputDir) throws IOException {
        Path resourcePath = outputDir.resolve("src/main/resources")
                .resolve(entry.getName().replace("BOOT-INF/classes/", ""));
        Files.createDirectories(resourcePath.getParent());
        
        try (InputStream is = jar.getInputStream(entry)) {
            Files.copy(is, resourcePath);
        }
    }

    private String extractPackageName(String source) {
        String[] lines = source.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("package ")) {
                return line.trim().substring(8).replace(";", "").trim();
            }
        }
        return "";
    }

    private String extractClassName(String source) {
        String[] lines = source.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.contains(" class ")) {
                String[] parts = line.split(" class ");
                return parts[1].split("[ {]")[0].trim();
            }
        }
        return "Unknown";
    }

    private void extractDependencies(Path outputDir) {
        MavenProjectGenerator mavenProjectGenerator = new MavenProjectGenerator();
        mavenProjectGenerator.generateProject(outputDir,jarFilePath);
    }
}