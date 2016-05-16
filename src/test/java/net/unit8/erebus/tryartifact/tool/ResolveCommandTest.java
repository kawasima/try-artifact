package net.unit8.erebus.tryartifact.tool;

import org.testng.annotations.Test;

/**
 *
 * @author bitter_fox
 */
@Test
public class ResolveCommandTest extends TryArtifactTesting {
    public void testBadArtifactCoordinate() {
        test(
                a -> assertCommand(a, "/resolve bad", "|  Bad artifact coordinates bad, expected format is <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>\n")
        );
    }

}
