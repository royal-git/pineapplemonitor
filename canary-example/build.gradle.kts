plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":canary-core"))
    // Only for the example API, which is the canary's target.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.assertj:assertj-core")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // Demo value matching the example API's default key. A real deployment supplies its own.
    environment("CANARY_ACCOUNT_EDIT_ACCOUNT_TEST_API_KEY", "demo-key")
    environment("CANARY_ACCOUNT_STATUS_TEST_API_KEY", "demo-key")
}
