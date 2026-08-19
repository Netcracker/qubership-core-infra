# frozen_string_literal: true

require "yaml"
require "open3"
require "tmpdir"

workflow_path = File.expand_path("../.github/workflows/generic-go-build.yaml", __dir__)
workflow = YAML.safe_load_file(workflow_path)
jobs = workflow.fetch("jobs")
docker_dry_run_workflow_path = File.expand_path("../.github/workflows/docker-build-dry-run.yaml", __dir__)
docker_dry_run_workflow = File.exist?(docker_dry_run_workflow_path) ? YAML.safe_load_file(docker_dry_run_workflow_path) : {}
docker_trusted_dry_run_workflow_path = File.expand_path("../.github/workflows/docker-build-trusted-dry-run.yaml", __dir__)
docker_publish_workflow_path = File.expand_path("../.github/workflows/docker-build.yaml", __dir__)
docker_publish_workflow = YAML.safe_load_file(docker_publish_workflow_path)
docker_core_workflow_path = File.expand_path("../.github/workflows/docker-build-core.yaml", __dir__)
docker_core_workflow = File.exist?(docker_core_workflow_path) ? YAML.safe_load_file(docker_core_workflow_path) : {}
contract_workflow_path = File.expand_path("../.github/workflows/generic-go-build-contract.yaml", __dir__)
contract_workflow = YAML.safe_load_file(contract_workflow_path)
actionlint_config_path = File.expand_path("../.github/actionlint.yaml", __dir__)
actionlint_config = YAML.safe_load_file(actionlint_config_path)
failures = []

check = lambda do |description, condition|
  failures << description unless condition
end

normalize_expression = lambda do |expression|
  expression
    .sub(/\A\$\{\{\s*/, "")
    .sub(/\s*\}\}\z/, "")
    .gsub(/\s+/, " ")
end

input_contract = lambda do |workflow_input|
  workflow_input.slice("required", "type", "default")
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
trusted_dry_run_docker = docker_jobs.find { |job| job.fetch("if", "").include?("inputs.dry-run == true") }
publish_docker = docker_jobs.find { |job| job.fetch("if", "").include?("inputs.dry-run == false") }

check.call(
  "Docker route predicates must be mutually exclusive and complete",
  pull_request_docker &&
    trusted_dry_run_docker &&
    publish_docker &&
    normalize_expression.call(pull_request_docker.fetch("if")) ==
      "!inputs.skip-docker && (github.event_name == 'pull_request' || github.event_name == 'pull_request_target')" &&
    normalize_expression.call(trusted_dry_run_docker.fetch("if")) ==
      "!inputs.skip-docker && inputs.dry-run == true && github.event_name != 'pull_request' && github.event_name != 'pull_request_target'" &&
    normalize_expression.call(publish_docker.fetch("if")) ==
      "!inputs.skip-docker && inputs.dry-run == false && github.event_name != 'pull_request' && github.event_name != 'pull_request_target'"
)
check.call(
  "pull requests must use the dedicated read-only Docker route",
  pull_request_docker && pull_request_docker["permissions"] == { "contents" => "read" }
)
check.call(
  "trusted explicit dry runs must use the public Docker workflow without package write permission",
  trusted_dry_run_docker &&
    trusted_dry_run_docker["permissions"] == { "contents" => "read" } &&
    trusted_dry_run_docker["uses"] == "$/.github/workflows/docker-build.yaml" &&
    trusted_dry_run_docker.dig("with", "dry-run") == true
)
check.call(
  "Docker publication must run only for trusted non-dry-run events",
  publish_docker &&
    publish_docker.dig("with", "dry-run") == "${{ inputs.dry-run }}" &&
    publish_docker.dig("permissions", "packages") == "write"
)
check.call(
  "pull requests must call the read-only Docker workflow from the shared workflow revision",
  pull_request_docker && pull_request_docker["uses"] == "$/.github/workflows/docker-build-dry-run.yaml"
)
check.call(
  "non-dry-run builds must call the publication-capable Docker workflow from the shared workflow revision",
  publish_docker && publish_docker["uses"] == "$/.github/workflows/docker-build.yaml"
)
docker_dry_run_jobs = docker_dry_run_workflow.fetch("jobs", {})
docker_publish_jobs = docker_publish_workflow.fetch("jobs", {})
docker_core_jobs = docker_core_workflow.fetch("jobs", {})
docker_publish_dry_run_job = docker_publish_jobs.values.find do |job|
  normalize_expression.call(job.fetch("if", "")) == "inputs.dry-run == true"
end || {}
docker_publish_job = docker_publish_jobs.values.find do |job|
  normalize_expression.call(job.fetch("if", "")) == "inputs.dry-run == false"
end || {}
docker_dry_run_action_steps = docker_dry_run_jobs.values
  .flat_map { |job| job.fetch("steps", []) }
  .select { |step| step.fetch("uses", "").include?("/actions/docker-action@") }
check.call(
  "the PR Docker workflow must remain read-only and force Docker actions into dry-run mode",
  !docker_dry_run_jobs.empty? &&
    docker_dry_run_workflow["permissions"] == {} &&
    docker_dry_run_jobs.values.all? { |job| job["permissions"] == { "contents" => "read" } } &&
    !docker_dry_run_action_steps.empty? &&
    docker_dry_run_action_steps.all? { |step| step.dig("with", "dry-run") == true }
)
check.call(
  "the public Docker workflow must isolate direct dry runs from package write permission",
  docker_publish_jobs.size == 2 &&
    !docker_publish_workflow.key?("permissions") &&
    docker_publish_dry_run_job["permissions"] == { "contents" => "read" } &&
    docker_publish_dry_run_job["uses"] == "$/.github/workflows/docker-build-core.yaml" &&
    docker_publish_dry_run_job["with"] == {
      "tags" => "${{ inputs.tags }}",
      "dry-run" => true,
      "config-filename" => "${{ inputs.config-filename }}",
      "ref" => "${{ inputs.ref }}",
      "build-args" => "${{ inputs.build-args }}"
    }
)
check.call(
  "the public Docker workflow must let publication inherit its caller permission ceiling",
  !docker_publish_job.key?("permissions") &&
    docker_publish_job["uses"] == "$/.github/workflows/docker-build-core.yaml" &&
    docker_publish_job["with"] == {
      "tags" => "${{ inputs.tags }}",
      "dry-run" => false,
      "config-filename" => "${{ inputs.config-filename }}",
      "ref" => "${{ inputs.ref }}",
      "build-args" => "${{ inputs.build-args }}"
    }
)
check.call(
  "the obsolete trusted dry-run wrapper must not remain as a divergent public path",
  !File.exist?(docker_trusted_dry_run_workflow_path)
)
expected_publish_input_contract = {
  "tags" => { "required" => false, "type" => "string", "default" => "" },
  "dry-run" => { "required" => true, "type" => "boolean" },
  "config-filename" => { "required" => false, "type" => "string", "default" => "docker-dev-config.json" },
  "ref" => { "required" => false, "type" => "string" },
  "build-args" => { "required" => false, "type" => "string", "default" => "" }
}
publish_inputs = docker_publish_workflow.fetch(true).fetch("workflow_call").fetch("inputs")
core_inputs = docker_core_workflow.fetch(true).fetch("workflow_call").fetch("inputs")
check.call(
  "the publication wrapper and shared core must preserve the public Docker input contract",
  publish_inputs.transform_values(&input_contract) == expected_publish_input_contract &&
    core_inputs.transform_values(&input_contract) == expected_publish_input_contract
)
check.call(
  "the shared Docker core must inherit permissions without requesting write access",
  !docker_core_jobs.empty? &&
    !docker_core_workflow.key?("permissions") &&
    docker_core_jobs.values.none? { |job| job.key?("permissions") }
)
docker_core_steps = docker_core_jobs.values.flat_map { |job| job.fetch("steps", []) }
docker_core_action_steps = docker_core_steps.select do |step|
  step.fetch("uses", "").include?("/actions/docker-action@")
end
check.call(
  "the shared Docker core must contain the only metadata and Docker action implementations",
  docker_core_steps.count { |step| step.fetch("uses", "").include?("/actions/metadata-action@") } == 1 &&
    docker_core_steps.count { |step| step.fetch("uses", "").include?("/actions/docker-action@") } == 1 &&
    docker_publish_jobs.values.all? { |job| !job.key?("steps") }
)
check.call(
  "the shared Docker core must forward the publication switch to the Docker action",
  docker_core_action_steps.size == 1 &&
    docker_core_action_steps.first.dig("with", "dry-run") == "${{ inputs.dry-run }}"
)

contract_triggers = contract_workflow.fetch(true)
docker_contract_paths = [
  ".github/workflows/docker-build.yaml",
  ".github/workflows/docker-build-core.yaml",
  ".github/workflows/docker-build-dry-run.yaml"
]
check.call(
  "the contract workflow must run when any Docker workflow changes",
  %w[push pull_request].all? do |event|
    paths = contract_triggers.fetch(event).fetch("paths")
    (docker_contract_paths - paths).empty?
  end
)
shared_revision_workflows = [
  ".github/workflows/generic-go-build.yaml",
  ".github/workflows/docker-build.yaml"
]
check.call(
  "actionlint must recognize every workflow that uses shared-revision nested calls",
  shared_revision_workflows.all? { |path| actionlint_config.fetch("paths", {}).key?(path) }
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
