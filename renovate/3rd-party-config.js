module.exports = {
    username: 'renovate-release',
    gitAuthor: 'Renovate Bot <bot@renovateapp.com>',
    onboarding: false,
    platform: 'github',
    repositories: ['Netcracker/qubership-core-infra'],
    labels: ['renovate'],
    hostRules: [{
        hostType: "maven",
        matchHost: "https://repo.maven.apache.org/maven2",
        username: process.env.RENOVATE_MAVEN_USER,
        password: process.env.RENOVATE_MAVEN_TOKEN
    }],
    packageRules: [{
        matchDatasources: ["maven"],
        registryUrls: ["https://repo.maven.apache.org/maven2"]
    }, {
        matchUpdateTypes: ["minor", "major"],
        enabled: false
    }, {
        matchUpdateTypes: ["patch"],
        enabled: true
    }]
};