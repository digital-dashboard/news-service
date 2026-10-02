package com.j11a.argus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ObservabilityFilesTest {

    private static final Path PROMETHEUS = Path.of("observability/prometheus/argus-scrape.yml");
    private static final Path PROMTAIL = Path.of("observability/promtail/argus-job.yml");
    private static final Path LOCAL_PROMTAIL = Path.of("observability/local/promtail.yml");

    @SuppressWarnings("unchecked")
    private static <T> T load(Path path) throws IOException {
        return (T) new Yaml().load(Files.readString(path));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> prometheusJob() throws IOException {
        Map<String, Object> file = load(PROMETHEUS);
        return ((List<Map<String, Object>>) file.get("scrape_configs")).get(0);
    }

    private static Map<String, Object> promtailJob() throws IOException {
        List<Map<String, Object>> jobs = load(PROMTAIL);
        assertThat(jobs).hasSize(1);
        return jobs.get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> relabelRules(Map<String, Object> job) {
        return (List<Map<String, Object>>) job.get("relabel_configs");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stages(Map<String, Object> job) {
        return (List<Map<String, Object>>) job.get("pipeline_stages");
    }

    private static Map<String, Object> stage(Map<String, Object> job, String name) {
        return stages(job).stream().filter(s -> s.containsKey(name)).findFirst().map(s -> asMap(s.get(name))).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static boolean keepsOn(Map<String, Object> job, String sourceLabel, String regex) {
        return relabelRules(job).stream().anyMatch(rule ->
                "keep".equals(rule.get("action"))
                        && List.of(sourceLabel).equals(rule.get("source_labels"))
                        && regex.equals(rule.get("regex")));
    }

    @Test
    void prometheusJobScrapesTheActuatorEndpointOfTheArgusService() throws IOException {
        Map<String, Object> job = prometheusJob();

        assertThat(job.get("job_name")).isEqualTo("argus");
        assertThat(job.get("metrics_path")).isEqualTo("/actuator/prometheus");
        assertThat(keepsOn(job, "__meta_dockerswarm_service_name", "(?:.+_)?argus")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void prometheusJobDiscoversTasksThroughTheDockerProxy() throws IOException {
        List<Map<String, Object>> discovery = (List<Map<String, Object>>) prometheusJob().get("dockerswarm_sd_configs");

        assertThat(discovery).hasSize(1);
        assertThat(discovery.get(0)).containsEntry("host", "tcp://docker-proxy:2375").containsEntry("role", "tasks");
    }

    @Test
    void prometheusJobKeepsOnlyRunningTasksOnTheGrafanaNetwork() throws IOException {
        Map<String, Object> job = prometheusJob();

        assertThat(keepsOn(job, "__meta_dockerswarm_task_desired_state", "running")).isTrue();
        assertThat(keepsOn(job, "__meta_dockerswarm_network_name", "grafana-overlay-network")).isTrue();
    }

    @Test
    void prometheusJobSetsTheServiceLabel() throws IOException {
        assertThat(relabelRules(prometheusJob())).anySatisfy(rule -> {
            assertThat(rule).containsEntry("target_label", "service").containsEntry("replacement", "argus");
        });
    }

    @Test
    void promtailFragmentHasNoDollarSignBecauseTheStackExpandsEnvironmentVariables() throws IOException {
        assertThat(Files.readString(PROMTAIL)).doesNotContain("$");
    }

    @Test
    void promtailJobKeepsOnlyArgusAndDerivesTheServiceLabel() throws IOException {
        Map<String, Object> job = promtailJob();

        assertThat(job.get("job_name")).isEqualTo("argus");
        assertThat(keepsOn(job, "__meta_docker_container_label_com_docker_swarm_service_name", "(?:.+_)?argus")).isTrue();
        assertThat(relabelRules(job)).anySatisfy(rule -> assertThat(rule).containsEntry("target_label", "service"));
    }

    @Test
    void promtailExtractsTheTimestampExplicitlyAndUsesIt() throws IOException {
        Map<String, Object> job = promtailJob();

        assertThat(asMap(stage(job, "json").get("expressions"))).containsEntry("ts", "\"@timestamp\"");
        assertThat(stage(job, "timestamp")).containsEntry("source", "ts").containsEntry("format", "RFC3339Nano");
    }

    @Test
    void promtailPromotesOnlyTheLevelLabel() throws IOException {
        Map<String, Object> labels = stage(promtailJob(), "labels");

        assertThat(labels.keySet()).containsExactly("level");
    }

    @Test
    void localPromtailUsesTheSamePipelineAsTheDeployedFragment() throws IOException {
        Map<String, Object> local = load(LOCAL_PROMTAIL);
        List<Map<String, Object>> jobs = asJobs(local);

        assertThat(jobs).hasSize(1);
        assertThat(stages(jobs.get(0))).isEqualTo(stages(promtailJob()));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asJobs(Map<String, Object> config) {
        return (List<Map<String, Object>>) config.get("scrape_configs");
    }
}
