module.exports = {
    username: 'renovate-release',
    gitAuthor: 'Renovate Bot <bot@renovateapp.com>',
    onboarding: false,
    platform: 'github',
    repositories: ['Netcracker/qubership-core-infra'],
    packageRules: [
        {
            matchUpdateTypes: ["minor", "major"],
            enabled: false
        },
        {
            matchUpdateTypes: ["patch"],
            enabled: true
        }
    ]
};