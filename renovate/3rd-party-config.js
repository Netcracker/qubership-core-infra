module.exports = {
    username: 'renovate-release',
    gitAuthor: 'Renovate Bot <bot@renovateapp.com>',
    onboarding: false,
    platform: 'github',
    repositories: ['Netcracker/qubership-core-infra'],
    labels: ['renovate'],
    packageRules: [
        {
            matchUpdateTypes: ["patch"],
            enabled: true
        }
    ]
};