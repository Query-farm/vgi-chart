plugins {
    java
    application
    // Fat/shaded JAR: `./gradlew shadowJar` -> build/libs/vgi-chart-<ver>-all.jar
    id("com.gradleup.shadow") version "9.4.2"
}

group = "farm.query"
version = "0.1.0-SNAPSHOT"

repositories {
    // The VGI Java SDK is published to Maven Central as farm.query:vgi /
    // farm.query:vgirpc, so the build is fully self-contained — no mavenLocal,
    // no sibling checkout, no composite build.
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:all,-serial,-processing",
            "-parameters",
        )
    )
    options.encoding = "UTF-8"
}

dependencies {
    // VGI Java SDK from Maven Central. `vgi` is the worker/catalog API and pulls
    // in farm.query:vgirpc transitively; vgirpc is declared explicitly because
    // the code imports farm.query.vgirpc.* directly.
    implementation("farm.query:vgi:0.5.0")
    implementation("farm.query:vgirpc:0.10.2")

    // JFreeChart — LGPL-2.1. Used UNMODIFIED as a standard Maven Central
    // dependency to render charts to PNG via java.awt (headless). See the README
    // licensing table + LGPL note. Pulls in org.jfree:jcommon transitively.
    implementation("org.jfree:jfreechart:1.5.5")

    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("farm.query.vgi.chart.Main")
    applicationDefaultJvmArgs = listOf(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "-Djava.awt.headless=true",
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED", "-Djava.awt.headless=true")
}

tasks.shadowJar {
    archiveBaseName.set("vgi-chart")
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest {
        attributes(
            "Main-Class" to "farm.query.vgi.chart.Main",
            "Multi-Release" to "true",
            // Arrow's off-heap MemoryUtil needs java.nio reflectively opened. Bake
            // it into the manifest so a bare `java -jar vgi-chart-all.jar` works as
            // a VGI LOCATION without the caller having to pass --add-opens.
            "Add-Opens" to "java.base/java.nio",
        )
    }
}

// Make `build` produce the fat jar.
tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
