package com.j11a.argus.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class BuildContractTest {

    private static final Path POM = Path.of("pom.xml");
    private static final Path JENKINSFILE = Path.of("Jenkinsfile");
    private static final Path DOCKERFILE = Path.of("Dockerfile");
    private static final Pattern LEGACY = Pattern.compile("newscatcher|newsservice|RestTemplate|ApiResponse", Pattern.CASE_INSENSITIVE);

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    private static List<String> dockerfileInstructions(String keyword) throws IOException {
        return Files.readAllLines(DOCKERFILE).stream()
                .map(String::strip)
                .filter(line -> line.toUpperCase().startsWith(keyword + " "))
                .toList();
    }

    @Test
    void pomIdentifiesArgusAsASnapshot() throws IOException {
        String pom = read(POM).replaceFirst("(?s)<parent>.*?</parent>", "");

        assertThat(pom).contains("<groupId>com.j11a</groupId>", "<artifactId>argus</artifactId>")
                .containsPattern("<version>[^<]+-SNAPSHOT</version>");
    }

    @Test
    void pomPointsSonarAtTheMergedJacocoReport() throws IOException {
        assertThat(read(POM)).contains(
                "<sonar.projectKey>${project.artifactId}</sonar.projectKey>",
                "<sonar.coverage.jacoco.xmlReportPaths>${project.build.directory}/site/jacoco/jacoco.xml</sonar.coverage.jacoco.xmlReportPaths>");
    }

    @Test
    void jenkinsfileOnlyDelegatesToTheSharedPipelineWithAnEmptyRepositoryPath() throws IOException {
        List<String> code = read(JENKINSFILE).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("//"))
                .toList();

        assertThat(code).containsExactly(
                "@Library('jenkins-shared-lib') _",
                "standardSpringSnapshotPipeline(dockerRepoPath: '')");
    }

    @Test
    void dockerfileHasASingleFromWithoutFlags() throws IOException {
        assertThat(dockerfileInstructions("FROM")).hasSize(1).allSatisfy(from ->
                assertThat(from.split("\\s+")).hasSize(2));
    }

    @Test
    void dockerfileUsesExecFormForEntrypointAndHealthcheck() throws IOException {
        assertThat(dockerfileInstructions("ENTRYPOINT")).hasSize(1).allMatch(line -> line.startsWith("ENTRYPOINT [\""));
        assertThat(read(DOCKERFILE)).containsPattern("(?s)HEALTHCHECK [^\\n]*\\\\\\s*\\n\\s*CMD \\[\"");
    }

    @Test
    void dockerfileRunsAsANumericNonRootUser() throws IOException {
        assertThat(dockerfileInstructions("USER")).hasSize(1).allSatisfy(user -> {
            String uid = user.split("\\s+")[1].split(":")[0];
            assertThat(uid).matches("[1-9]\\d*");
        });
    }

    @Test
    void dockerfileHasNoAddAndNoLatestTag() throws IOException {
        assertThat(dockerfileInstructions("ADD")).isEmpty();
        assertThat(read(DOCKERFILE)).doesNotContain(":latest");
    }

    @Test
    void nothingFromTheOldNewsCatcherServiceRemains() throws IOException {
        Path self = Path.of("src/test/java/com/j11a/argus/build/BuildContractTest.java");
        try (Stream<Path> files = Files.walk(Path.of("src"))) {
            List<String> offenders = Stream.concat(files, Stream.of(POM))
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.equals(self))
                    .filter(path -> LEGACY.matcher(contentOf(path)).find())
                    .map(Path::toString)
                    .toList();

            assertThat(offenders).isEmpty();
        }
    }

    private static String contentOf(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }
}
