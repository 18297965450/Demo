import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JarDecompilerTest {

    @Test
    void testDecompileJar() {
        JarDecompiler decompiler = new JarDecompiler();
        String jarFilePath = "path/to/test.jar"; // Replace with an actual test JAR file path
        boolean result = decompiler.decompileJar(jarFilePath);
        
        assertTrue(result, "The JAR file should be decompiled successfully.");
        // Additional assertions can be added here to verify the output of the decompilation
    }
}