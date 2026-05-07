import re, os, sys

suffix = os.environ['SUFFIX']
log_path = '/tmp/maven-deploy.log'

# "Uploaded to <id>: <url> (<size>)" — past tense means upload succeeded.
# Only .pom files: one per artifact, no need to deduplicate jars/sources separately.
upload_re = re.compile(r'^\[INFO\] Uploaded to [^:]+: (\S+\.pom) \(')

artifacts = set()
try:
    with open(log_path) as f:
        for line in f:
            m = upload_re.match(line.strip())
            if not m:
                continue
            url = m.group(1)
            parts = url.split('/')
            # URL: https://maven.pkg.github.com/owner/repo/group/path/artifactId/version/file.pom
            # parts: ['https:', '', 'host', 'owner', 'repo', ...groupId..., artifactId, version, filename]
            if len(parts) < 8:
                continue
            version = parts[-2]
            if f'-{suffix}-SNAPSHOT' not in version:
                continue
            artifact_id = parts[-3]
            group_id = '.'.join(parts[5:-3])
            artifacts.add(f'{group_id}:{artifact_id}:{version}')
except FileNotFoundError:
    print(f'Maven log not found: {log_path}', file=sys.stderr)
    sys.exit(1)

for a in sorted(artifacts):
    print(a)
