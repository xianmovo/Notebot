plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = properties["archives_base_name"] as String
    group = properties["maven_group"] as String
    version = "${libs.versions.minecraft.get()}-1.0.2"
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)

    // Fabric API modules we actually use
    modImplementation(fabricApi.module("fabric-api-base", libs.versions.fabric.api.get()))
    modImplementation(fabricApi.module("fabric-lifecycle-events-v1", libs.versions.fabric.api.get()))
    modImplementation(fabricApi.module("fabric-command-api-v2", libs.versions.fabric.api.get()))
    modImplementation(fabricApi.module("fabric-resource-loader-v0", libs.versions.fabric.api.get()))
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "minecraft_version" to libs.versions.minecraft.get(),
            "loader_version" to libs.versions.fabric.loader.get()
        )

        inputs.properties(propertyMap)
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }
}