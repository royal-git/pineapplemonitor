import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    // Publishes RunLockContract, so that teams writing their own RunLock can test it.
    `java-test-fixtures`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter")

    implementation(platform("io.opentelemetry:opentelemetry-bom:1.68.0"))
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")

    testFixturesApi(platform(SpringBootPlugin.BOM_COORDINATES))
    testFixturesApi("org.junit.jupiter:junit-jupiter-api")
    testFixturesApi("org.assertj:assertj-core")

    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
}
