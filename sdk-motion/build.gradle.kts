plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    `maven-publish`
}

android {
    namespace = "com.tezeract.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Picks which variant the maven-publish plugin gets to publish. Including
    // sources makes the SDK navigable from consumers' IDEs.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)
}

// ─────────────────────────────────────────────
// GitHub Packages publication.
//
// Locally:
//   ./gradlew :sdk-motion:publishToMavenLocal
//
// Publish to GitHub Packages (CI runs this on tag push):
//   ./gradlew :sdk-motion:publish -PsdkVersion=0.1.0
//
// Auth: GITHUB_ACTOR + GITHUB_TOKEN in CI; for local you can put
// `gpr.user` + `gpr.key` (a personal access token with `write:packages`)
// in ~/.gradle/gradle.properties.
// ─────────────────────────────────────────────
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.tezeract"
                artifactId = "sdk-motion"
                version = (project.findProperty("sdkVersion") as String?) ?: "0.0.0-SNAPSHOT"
                pom {
                    name.set("Tezeract Motion SDK")
                    description.set(
                        "Kotlin SDK for building motion-controlled games for Tezeract devices."
                    )
                    url.set("https://github.com/tezeract-gaming/tezeract-platform")
                    licenses {
                        license {
                            name.set("The Apache Software License, Version 2.0")
                            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    scm {
                        url.set("https://github.com/tezeract-gaming/tezeract-platform")
                        connection.set("scm:git:git://github.com/tezeract-gaming/tezeract-platform.git")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/tezeract-gaming/tezeract-platform")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                        ?: project.findProperty("gpr.user") as String?
                    password = System.getenv("GITHUB_TOKEN")
                        ?: project.findProperty("gpr.key") as String?
                }
            }
        }
    }
}
