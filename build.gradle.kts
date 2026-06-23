import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.diffsense"
version = "0.4.0"

repositories {
    mavenCentral()
}

dependencies {
    // Gson 用于 JSON 序列化（IntelliJ Platform 自带，但显式声明避免歧义）
    implementation("com.google.code.gson:gson:2.10.1")
}

// IntelliJ Platform 配置
intellij {
    version.set("2023.1.7")
    type.set("IC") // IDEA Community
    plugins.set(listOf("Git4Idea"))
}

tasks {
    // 关闭 patchPluginXml 的 until-build 限制（让 pluginSinceBuild/pluginUntilBuild 生效）
    patchPluginXml {
        sinceBuild.set("231")
        untilBuild.set("252.*")
    }

    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
        }
    }

    // 签名与发布配置：发布到 Marketplace 时再启用
    // signPlugin {
    //     certificationChain.set(System.getenv("CERTIFICATE_CHAIN"))
    //     privateKey.set(System.getenv("PRIVATE_KEY"))
    //     password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    // }
    // publishPlugin {
    //     token.set(System.getenv("PUBLISH_TOKEN"))
    // }

    runIde {
        // 调试时分配更大内存
        jvmArgs = listOf("-Xmx2g")
    }
}
