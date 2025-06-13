module.exports = {
    username: 'renovate-release',
    gitAuthor: 'Renovate Bot <bot@renovateapp.com>',
    onboarding: false,
    platform: 'github',
    repositories: ['Netcracker/qubership-core-infra'],
    packageRules: [
        // Spring
        {
            matchPackageNames: ["org.springframework.boot:spring-boot-dependencies"],
            allowedVersions: "~3.4.0",
            groupName: "Spring"
        },
        {
            matchPackageNames: ["org.springframework.cloud:spring-cloud-dependencies"],
            allowedVersions: "~2024.0.0",
            groupName: "Spring"
        },
        {
            matchPackageNames: ["org.springframework.data:spring-data-bom"],
            allowedVersions: "~2024.1.0",
            groupName: "Spring"
        },
        // Quarkus
        {
            matchPackageNames: ["io.quarkus:quarkus-bom"],
            allowedVersions: "~3.15.0",
            groupName: "Quarkus"
        },
    ],
};