package com.example;

import org.apache.maven.model.*;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class MavenProjectGenerator {
    private static final String DEFAULT_GROUP_ID = "com.decompiled";
    private static final String DEFAULT_VERSION = "1.0-SNAPSHOT";

    public void generateProject(Path projectPath, String jarPath, String artifactId) throws IOException {
        // 创建并配置 Maven Model
        Model model = createBasicModel(artifactId);
        
        // 添加项目属性
        addProjectProperties(model);
        
        // 分析并添加依赖
        analyzeDependencies(model, jarPath);
        
        // 添加构建插件
        addBuildPlugins(model);
        
        // 写入 pom.xml
        writePomXml(model, projectPath);
    }

    private Model createBasicModel(String artifactId) {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId(DEFAULT_GROUP_ID);
        model.setArtifactId(artifactId);
        model.setVersion(DEFAULT_VERSION);
        model.setPackaging("jar");
        return model;
    }

    private void addProjectProperties(Model model) {
        Properties properties = new Properties();
        properties.setProperty("project.build.sourceEncoding", "UTF-8");
        properties.setProperty("maven.compiler.source", "1.8");
        properties.setProperty("maven.compiler.target", "1.8");
        model.setProperties(properties);
    }

    private void analyzeDependencies(Model model, String jarPath) throws IOException {
        Set<Dependency> dependencies = new HashSet<>();
        Map<String, Set<String>> importedClasses = new HashMap<>();
        
        try (JarFile jarFile = new JarFile(new File(jarPath))) {
            // 1. 首先尝试从JAR包中的pom.xml获取依赖
            boolean foundPom = false;
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (isPomFile(entry.getName())) {
                    foundPom = true;
                    Model originalPom = readPomFromJar(jarFile, entry);
                    if (originalPom != null && originalPom.getDependencies() != null) {
                        dependencies.addAll(originalPom.getDependencies());
                        // 如果找到了pom.xml，也复制其他有用的配置
                        copyUsefulPomConfiguration(originalPom, model);
                    }
                    break;
                }
            }
            
            // 2. 如果没有找到pom.xml或pom.xml中没有依赖，则尝试其他方法
            if (!foundPom || dependencies.isEmpty()) {
                // 从 MANIFEST.MF 提取依赖信息
                Manifest manifest = jarFile.getManifest();
                if (manifest != null) {
                    String classpath = manifest.getMainAttributes().getValue("Class-Path");
                    if (classpath != null) {
                        for (String dependency : classpath.split(" ")) {
                            if (dependency.endsWith(".jar")) {
                                addDependencyFromFilename(dependencies, dependency);
                            }
                        }
                    }
                }
                
                // 分析所有类文件中的依赖
                entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class")) {
                        analyzeClassFile(jarFile, entry, importedClasses);
                    }
                }
                
                // 根据分析到的类推断依赖
                inferDependenciesFromImports(dependencies, importedClasses);
                
                // 添加一些常用的运行时依赖
                addCommonDependencies(dependencies);
            }
        }
        
        // 将收集到的依赖添加到模型中
        model.setDependencies(new ArrayList<>(dependencies));
    }
    
    private void analyzeClassFile(JarFile jarFile, JarEntry entry, Map<String, Set<String>> importedClasses) throws IOException {
        try (InputStream is = jarFile.getInputStream(entry)) {
            ClassDependencyVisitor visitor = new ClassDependencyVisitor();
            new org.objectweb.asm.ClassReader(is).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            importedClasses.put(visitor.getClassName(), visitor.getImportedClasses());
        }
    }
    
    private void inferDependenciesFromImports(Set<Dependency> dependencies, Map<String, Set<String>> importedClasses) {
        // 常见的包名到依赖映射
        Map<String, String[]> packageToDependency = new HashMap<>();
        packageToDependency.put("org.springframework", new String[]{"org.springframework", "spring-core", "5.3.9"});
        packageToDependency.put("javax.servlet", new String[]{"javax.servlet", "javax.servlet-api", "4.0.1"});
        packageToDependency.put("org.hibernate", new String[]{"org.hibernate", "hibernate-core", "5.5.7.Final"});
        packageToDependency.put("com.fasterxml.jackson", new String[]{"com.fasterxml.jackson.core", "jackson-core", "2.13.0"});
        packageToDependency.put("org.apache.commons", new String[]{"org.apache.commons", "commons-lang3", "3.12.0"});
        packageToDependency.put("org.slf4j", new String[]{"org.slf4j", "slf4j-api", "1.7.32"});
        packageToDependency.put("ch.qos.logback", new String[]{"ch.qos.logback", "logback-classic", "1.2.6"});
        
        // 分析所有导入的类
        for (Set<String> imports : importedClasses.values()) {
            for (String importedClass : imports) {
                // 获取包名
                String packageName = getPackageName(importedClass);
                if (packageName != null) {
                    // 检查是否匹配任何已知的依赖
                    for (Map.Entry<String, String[]> entry : packageToDependency.entrySet()) {
                        if (packageName.startsWith(entry.getKey())) {
                            String[] depInfo = entry.getValue();
                            addKnownDependency(dependencies, depInfo[0], depInfo[1], depInfo[2]);
                            break;
                        }
                    }
                }
            }
        }
    }
    
    private String getPackageName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot > 0 ? className.substring(0, lastDot) : null;
    }
    
    private void addKnownDependency(Set<Dependency> dependencies, String groupId, String artifactId, String version) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion(version);
        dependency.setScope("compile");
        dependencies.add(dependency);
    }
    
    private static class ClassDependencyVisitor extends ClassVisitor {
        private final Set<String> importedClasses = new HashSet<>();
        private String className;
        
        public ClassDependencyVisitor() {
            super(Opcodes.ASM9);
        }
        
        @Override
        public void visit(int version, int access, String name, String signature, 
                        String superName, String[] interfaces) {
            this.className = name.replace('/', '.');
            if (superName != null) {
                importedClasses.add(superName.replace('/', '.'));
            }
            if (interfaces != null) {
                for (String iface : interfaces) {
                    importedClasses.add(iface.replace('/', '.'));
                }
            }
        }
        
        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                     String signature, Object value) {
            addTypeFromDescriptor(descriptor);
            return null;
        }
        
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                       String signature, String[] exceptions) {
            addTypeFromDescriptor(descriptor);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    importedClasses.add(exception.replace('/', '.'));
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitTypeInsn(int opcode, String type) {
                    importedClasses.add(type.replace('/', '.'));
                }
                
                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    importedClasses.add(owner.replace('/', '.'));
                    addTypeFromDescriptor(descriptor);
                }
                
                @Override
                public void visitMethodInsn(int opcode, String owner, String name,
                                          String descriptor, boolean isInterface) {
                    importedClasses.add(owner.replace('/', '.'));
                    addTypeFromDescriptor(descriptor);
                }
            };
        }
        
        private void addTypeFromDescriptor(String descriptor) {
            // 解析类型描述符并添加到导入的类集合中
            try {
                Type type = Type.getType(descriptor);
                if (type.getSort() == Type.OBJECT) {
                    importedClasses.add(type.getClassName());
                } else if (type.getSort() == Type.ARRAY) {
                    Type elementType = type.getElementType();
                    if (elementType.getSort() == Type.OBJECT) {
                        importedClasses.add(elementType.getClassName());
                    }
                }
            } catch (Exception ignored) {
                // 忽略无效的描述符
            }
        }
        
        public String getClassName() {
            return className;
        }
        
        public Set<String> getImportedClasses() {
            return importedClasses;
        }
    }

    private void addDependencyFromFilename(Set<Dependency> dependencies, String filename) {
        // 这里的实现是一个简化版本
        // 实际项目中可能需要更复杂的依赖解析逻辑
        String artifactId = filename.substring(0, filename.lastIndexOf(".jar"));
        
        Dependency dependency = new Dependency();
        dependency.setGroupId("unknown"); // 需要更好的方法来推断groupId
        dependency.setArtifactId(artifactId);
        dependency.setVersion("unknown");
        dependency.setScope("compile");
        
        dependencies.add(dependency);
    }

    private void addCommonDependencies(Set<Dependency> dependencies) {
        // 添加一些常用的依赖
        String[][] commonDeps = {
            {"org.slf4j", "slf4j-api", "1.7.32"},
            {"junit", "junit", "4.13.2", "test"},
            {"org.mockito", "mockito-core", "3.12.4", "test"}
        };
        
        for (String[] dep : commonDeps) {
            Dependency dependency = new Dependency();
            dependency.setGroupId(dep[0]);
            dependency.setArtifactId(dep[1]);
            dependency.setVersion(dep[2]);
            if (dep.length > 3) {
                dependency.setScope(dep[3]);
            }
            dependencies.add(dependency);
        }
    }

    private void addBuildPlugins(Model model) {
        Build build = new Build();
        
        // 添加编译插件
        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setGroupId("org.apache.maven.plugins");
        compilerPlugin.setArtifactId("maven-compiler-plugin");
        compilerPlugin.setVersion("3.8.1");
        
        // 配置编译器选项
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        addXpp3DomChild(configuration, "source", "${maven.compiler.source}");
        addXpp3DomChild(configuration, "target", "${maven.compiler.target}");
        compilerPlugin.setConfiguration(configuration);
        
        // 添加源码插件
        Plugin sourcePlugin = new Plugin();
        sourcePlugin.setGroupId("org.apache.maven.plugins");
        sourcePlugin.setArtifactId("maven-source-plugin");
        sourcePlugin.setVersion("3.2.1");
        
        // 添加javadoc插件
        Plugin javadocPlugin = new Plugin();
        javadocPlugin.setGroupId("org.apache.maven.plugins");
        javadocPlugin.setArtifactId("maven-javadoc-plugin");
        javadocPlugin.setVersion("3.3.0");
        
        build.addPlugin(compilerPlugin);
        build.addPlugin(sourcePlugin);
        build.addPlugin(javadocPlugin);
        
        model.setBuild(build);
    }

    private void addXpp3DomChild(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        parent.addChild(child);
    }

    private boolean isPomFile(String entryName) {
        return entryName.endsWith("pom.xml") || 
               entryName.matches("META-INF/maven/.*/pom.xml");
    }

    private Model readPomFromJar(JarFile jarFile, JarEntry pomEntry) {
        try (InputStream is = jarFile.getInputStream(pomEntry)) {
            org.apache.maven.model.io.xpp3.MavenXpp3Reader reader = 
                new org.apache.maven.model.io.xpp3.MavenXpp3Reader();
            return reader.read(new InputStreamReader(is, "UTF-8"));
        } catch (Exception e) {
            System.err.println("Failed to read pom.xml from jar: " + e.getMessage());
            return null;
        }
    }

    private void copyUsefulPomConfiguration(Model source, Model target) {
        // 复制有用的属性，但不覆盖已设置的值
        if (target.getGroupId() == null && source.getGroupId() != null) {
            target.setGroupId(source.getGroupId());
        }
        if (target.getVersion() == null && source.getVersion() != null) {
            target.setVersion(source.getVersion());
        }
        
        // 复制属性
        if (source.getProperties() != null) {
            if (target.getProperties() == null) {
                target.setProperties(new Properties());
            }
            target.getProperties().putAll(source.getProperties());
        }
        
        // 复制仓库配置
        if (source.getRepositories() != null) {
            if (target.getRepositories() == null) {
                target.setRepositories(new ArrayList<>());
            }
            target.getRepositories().addAll(source.getRepositories());
        }
        
        // 复制插件管理配置
        if (source.getBuild() != null && source.getBuild().getPluginManagement() != null) {
            if (target.getBuild() == null) {
                target.setBuild(new Build());
            }
            if (target.getBuild().getPluginManagement() == null) {
                target.getBuild().setPluginManagement(new PluginManagement());
            }
            target.getBuild().getPluginManagement().getPlugins()
                .addAll(source.getBuild().getPluginManagement().getPlugins());
        }
    }

    private void writePomXml(Model model, Path projectPath) throws IOException {
        File pomFile = projectPath.resolve("pom.xml").toFile();
        try (FileWriter writer = new FileWriter(pomFile)) {
            new MavenXpp3Writer().write(writer, model);
        }
    }
}