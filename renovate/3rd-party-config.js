module.exports = {
    username: 'renovate-release',
    gitAuthor: 'Renovate Bot <bot@renovateapp.com>',
    onboarding: false,
    platform: 'github',
    repositories: ['Netcracker/qubership-core-infra'],
    packageRules: [
        {
            matchUpdateTypes: ["patch"],
            enabled: true
        },
        {
            matchUpdateTypes: ["minor", "major"],
            enabled: false
        }
    ]
};