import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

@Suppress("ConstPropertyName")
object Versions {

    // On change edit boost-ci.yml
    const val appVersion = "4.0.0"
    const val versionCode = 1500

    const val compileSdk = 37
    const val minSdk = 31
    const val targetSdk = 35
    const val wearMinSdk = 30
    const val wearTargetSdk = 30

    // Boost version, uploaded to Nightscout as apsConfiguration.boostVersion with the branch and
    // short hash appended at build time.
    const val boostVersion = "6.2.0"

    val javaVersion = JavaVersion.VERSION_21
    val jvmTarget = JvmTarget.JVM_21
    const val jacoco = "0.8.11"
}
