package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Which Java platform a build uses, decided without an IDE: the named platform first (Maven's and Gradle's
 * netbeans.hint.jdkPlatform, Ant's platform.active), then a unique bootstrap-classpath match, then the IDE's
 * default platform, and the inherited environment only when even that has no install folder.
 */
class JdkPlatformChooserTest {

    private static final JdkPlatformChooser.Platform JDK17 = platform("JDK_17", "/usr/lib/jvm/java-17-openjdk", "boot17");
    private static final JdkPlatformChooser.Platform JDK21 = platform("JDK_21", "/usr/lib/jvm/java-21-openjdk", "boot21");
    private static final JdkPlatformChooser.Platform DEFAULT = platform("default_platform", "/usr/lib/jvm/ide-jdk", "bootIde");
    private static final List<JdkPlatformChooser.Platform> INSTALLED = List.of(JDK17, JDK21, DEFAULT);

    private static JdkPlatformChooser.Platform platform(String antName, String home, String... bootRoots) {
        return new JdkPlatformChooser.Platform(antName, antName, home, Set.of(bootRoots));
    }

    @Test
    void aMavenHintNamingAnInstalledPlatformPicksIt() {
        JdkPlatformChooser.Choice choice = JdkPlatformChooser.choose(INSTALLED, DEFAULT, "JDK_17", Set.of());

        assertEquals(JDK17, choice.platform());
        assertEquals("named platform JDK_17", choice.source());
        assertNull(choice.namedButMissing());
    }

    @Test
    void anAntPlatformActiveNamingAnInstalledPlatformPicksItEvenWhenTheBootClasspathWouldMatchAnother() {
        JdkPlatformChooser.Choice choice = JdkPlatformChooser.choose(INSTALLED, DEFAULT, "JDK_21", Set.of("boot17"));

        assertEquals(JDK21, choice.platform(), "an explicit name wins over the bootstrap classpath");
    }

    @Test
    void aNamedPlatformThatIsNotInstalledFallsThroughToTheDefaultPlatformAndSaysSo() {
        JdkPlatformChooser.Choice choice = JdkPlatformChooser.choose(INSTALLED, DEFAULT, "JDK_11", Set.of("boot17"));

        assertEquals(DEFAULT, choice.platform(), "no guess from the bootstrap classpath when the project named a platform");
        assertEquals("default platform", choice.source());
        assertEquals("JDK_11", choice.namedButMissing());
    }

    @Test
    void aNamedPlatformWithoutAnInstallFolderIsNotChosen() {
        JdkPlatformChooser.Platform homeless = new JdkPlatformChooser.Platform("JDK_17", "JDK 17", null, Set.of());

        JdkPlatformChooser.Choice choice = JdkPlatformChooser.choose(List.of(homeless, DEFAULT), DEFAULT, "JDK_17", Set.of());

        assertEquals(DEFAULT, choice.platform());
        assertEquals("JDK_17", choice.namedButMissing());
    }

    @Test
    void defaultPlatformMeansTheDefaultPlatformAndIgnoresTheBootClasspath() {
        JdkPlatformChooser.Choice choice = JdkPlatformChooser.choose(INSTALLED, DEFAULT, "default_platform", Set.of("boot17"));

        assertEquals(DEFAULT, choice.platform());
        assertNull(choice.namedButMissing(), "default_platform is not a missing platform");
    }

    @Test
    void withNoNameAUniqueBootClasspathMatchPicksThatPlatform() {
        JdkPlatformChooser.Choice choice = JdkPlatformChooser.choose(INSTALLED, DEFAULT, null, Set.of("boot21"));

        assertEquals(JDK21, choice.platform());
        assertEquals("boot classpath", choice.source());
    }

    @Test
    void anAmbiguousBootClasspathMatchFallsThroughToTheDefaultPlatform() {
        JdkPlatformChooser.Platform sameRoots = platform("JDK_21B", "/opt/jdk-21b", "boot21");
        List<JdkPlatformChooser.Platform> installed = List.of(JDK17, JDK21, sameRoots, DEFAULT);

        JdkPlatformChooser.Choice choice = JdkPlatformChooser.choose(installed, DEFAULT, "", Set.of("boot21"));

        assertEquals(DEFAULT, choice.platform(), "two platforms with equal roots must not be told apart by guesswork");
    }

    @Test
    void withNoNameAndNoBootClasspathTheDefaultPlatformIsUsed() {
        JdkPlatformChooser.Choice choice = JdkPlatformChooser.choose(INSTALLED, DEFAULT, null, Set.of());

        assertEquals(DEFAULT, choice.platform());
        assertEquals("default platform", choice.source());
    }

    @Test
    void anUnmatchedBootClasspathFallsThroughToTheDefaultPlatform() {
        assertEquals(DEFAULT, JdkPlatformChooser.choose(INSTALLED, DEFAULT, null, Set.of("bootUnknown")).platform());
    }

    @Test
    void whenEvenTheDefaultPlatformHasNoInstallFolderNothingIsChosen() {
        JdkPlatformChooser.Platform homelessDefault = new JdkPlatformChooser.Platform("default_platform", "Default", " ",
                Set.of());

        assertNull(JdkPlatformChooser.choose(INSTALLED, homelessDefault, null, Set.of()));
        assertNull(JdkPlatformChooser.choose(INSTALLED, null, "JDK_11", Set.of()));
        assertNull(JdkPlatformChooser.choose(List.of(), null, null, Set.of()));
    }

    @Test
    void aNamePaddedWithWhitespaceStillMatches() {
        assertTrue(JdkPlatformChooser.choose(INSTALLED, DEFAULT, "  JDK_17\n", Set.of()).platform() == JDK17);
    }
}
