# Jar to Maven App

## 项目简介
该项目旨在提供一个工具，用于将 JAR 文件反编译为完整的 Maven 项目结构。它包含两个主要类：`JarDecompiler` 和 `MavenProjectGenerator`，分别负责反编译 JAR 文件和生成 Maven 项目。

## 功能
- **JarDecompiler**: 提供反编译 JAR 文件的功能。
  - 方法: `decompileJar(String jarFilePath)`

- **MavenProjectGenerator**: 生成 Maven 项目结构。
  - 方法: `generateProject(String outputPath)`

## 配置
项目使用 `src/main/resources/application.properties` 文件来存储配置信息，包括反编译工具的设置和输出路径。

## 测试
项目包含单元测试，确保 `JarDecompiler` 类的功能正常。测试类位于 `src/test/java/com/example/JarDecompilerTest.java`。

## 使用方法
1. 将 JAR 文件放置在指定路径。
2. 调用 `JarDecompiler` 的 `decompileJar` 方法进行反编译。
3. 使用 `MavenProjectGenerator` 的 `generateProject` 方法生成 Maven 项目结构。

## 依赖
项目使用 Maven 进行构建，所有依赖和插件配置在 `pom.xml` 文件中定义。

## 贡献
欢迎任何形式的贡献！请提交问题或拉取请求。