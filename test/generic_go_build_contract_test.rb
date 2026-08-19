# frozen_string_literal: true

require "yaml"
require "open3"
require "tmpdir"

workflow_path = File.expand_path("../.github/workflows/generic-go-build.yaml", __dir__)
workflow = YAML.safe_load_file(workflow_path)
jobs = workflow.fetch("jobs")
docker_dry_run_workflow_path = File.expand_path("../.github/workflows/docker-build-dry-run.yaml", __dir__)
docker_dry_run_workflow = File.exist?(docker_dry_run_workflow_path) ? YAML.safe_load_file(docker_dry_run_workflow_path) : {}
failures = []

check = lambda do |description, condition|
  failures << description unless condition
end

sonar_secret = workflow.fetch(true).fetch("workflow_call").fetch("secrets").fetch("SONAR_TOKEN")
check.call("SONAR_TOKEN must be optional", sonar_secret["required"] == false)
check.call("workflow permissions must deny access by default", workflow["permissions"] == {})

discover = jobs.fetch("discover")
sonar = jobs.fetch("sonar")
check.call(
  "module discovery must expose whether Sonar credentials are available",
  discover.fetch("outputs", {}).key?("sonar-enabled")
)
check.call(
  "Sonar analysis must use the credential availability output",
  sonar.fetch("if", "").include?("needs.discover.outputs.sonar-enabled")
)

availability_step = discover.fetch("steps").find { |step| step["id"] == "sonar-availability" }
availability_cases = [
  ["push", "", "netcracker/example", "project", "token", "true"],
  ["pull_request", "netcracker/example", "netcracker/example", "project", "token", "true"],
  ["pull_request", "contributor/example", "netcracker/example", "project", "token", "false"],
  ["pull_request_target", "netcracker/example", "netcracker/example", "project", "token", "false"],
  ["push", "", "netcracker/example", "", "token", "false"],
  ["push", "", "netcracker/example", "project", "", "false"]
]
availability_cases.each do |event_name, head_repository, repository, project_key, token, expected|
  Dir.mktmpdir do |directory|
    output_path = File.join(directory, "output")
    environment = {
      "EVENT_NAME" => event_name,
      "HEAD_REPOSITORY" => head_repository,
      "REPOSITORY" => repository,
      "SONAR_PROJECT_KEY" => project_key,
      "SONAR_TOKEN" => token,
      "GITHUB_OUTPUT" => output_path
    }
    _stdout, stderr, status = Open3.capture3(environment, "bash", "-c", availability_step.fetch("run"))
    actual = File.exist?(output_path) ? File.read(output_path)[/^enabled=(.+)$/, 1] : nil
    check.call(
      "Sonar availability for #{event_name} with head #{head_repository.inspect} must be #{expected}: #{stderr}",
      status.success? && actual == expected
    )
  end
end

coverage_steps = jobs.fetch("test").fetch("steps").select { |step| step.fetch("name", "").include?("coverage artifact") }
check.call(
  "coverage artifacts must only be handled when Sonar is enabled",
  !coverage_steps.empty? && coverage_steps.all? { |step| step.fetch("if", "").include?("needs.discover.outputs.sonar-enabled") }
)

docker_jobs = jobs.values.select do |job|
  job.fetch("uses", "").include?("/.github/workflows/docker-build")
end
pull_request_docker = docker_jobs.find { |job| job.fetch("if", "").include?("github.event_name == 'pull_request'") }
publish_docker = docker_jobs.find { |job| job.fetch("if", "").include?("github.event_name != 'pull_request'") }

check.call(
  "only pull requests must run Docker builds through the read-only workflow",
  pull_request_docker &&
    pull_request_docker.fetch("if", "").include?("github.event_name == 'pull_request_target'") &&
    !pull_request_docker.fetch("if", "").include?("inputs.dry-run") &&
    pull_request_docker["permissions"] == { "contents" => "read" }
)
check.call(
  "trusted non-PR Docker builds must use the publication workflow even for explicit dry runs",
  publish_docker &&
    publish_docker.fetch("if", "").include?("github.event_name != 'pull_request_target'") &&
    !publish_docker.fetch("if", "").include?("inputs.dry-run == false") &&
    publish_docker.dig("with", "dry-run") == "${{ inputs.dry-run }}" &&
    publish_docker.dig("permissions", "packages") == "write"
)
check.call(
  "pull requests must call a read-only Docker workflow from the same revision",
  pull_request_docker && pull_request_docker["uses"] == "./.github/workflows/docker-build-dry-run.yaml"
)
check.call(
  "non-PR builds must call the publication-capable Docker workflow from the same revision",
  publish_docker && publish_docker["uses"] == "./.github/workflows/docker-build.yaml"
)
docker_dry_run_jobs = docker_dry_run_workflow.fetch("jobs", {})
check.call(
  "the nested Docker dry-run workflow must be read-only",
  !docker_dry_run_jobs.empty? &&
    docker_dry_run_workflow["permissions"] == {} &&
    docker_dry_run_jobs.values.all? { |job| job["permissions"] == { "contents" => "read" } }
)
docker_dry_run_steps = docker_dry_run_jobs.values.flat_map { |job| job.fetch("steps", []) }
docker_dry_run_action_steps = docker_dry_run_steps.select do |step|
  step.fetch("uses", "").include?("/actions/docker-action@")
end
check.call(
  "the nested Docker workflow must force every Docker action into dry-run mode",
  !docker_dry_run_action_steps.empty? && docker_dry_run_action_steps.all? { |step| step.dig("with", "dry-run") == true }
)

checkout_steps = jobs.values.flat_map { |job| job.fetch("steps", []) }.select do |step|
  step.fetch("uses", "").start_with?("actions/checkout@")
end
check.call(
  "read-only jobs must not persist checkout credentials",
  !checkout_steps.empty? && checkout_steps.all? { |step| step.dig("with", "persist-credentials") == false }
)

if failures.empty?
  puts "generic-go-build workflow contract: PASS"
else
  warn "generic-go-build workflow contract: FAIL"
  failures.each { |failure| warn "- #{failure}" }
  exit 1
end
