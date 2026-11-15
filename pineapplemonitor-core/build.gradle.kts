dependencies {
    api("org.springframework.boot:spring-boot-starter")

    implementation(platform("io.opentelemetry:opentelemetry-bom:1.67.0"))
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")

    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
}
